package eu.avalanche7.paradigm.modules.tab;

import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A scoreboard journal attached to the transport, independent of the player entity and TAB module. */
@io.netty.channel.ChannelHandler.Sharable
public final class ScoreboardConnection extends ChannelDuplexHandler {
    public static final int CREATE = 0, REMOVE = 1, UPDATE = 2, ADD = 3, LEAVE = 4;
    private static final Logger LOGGER = LoggerFactory.getLogger(ScoreboardConnection.class);
    private static final String HANDLER = "paradigm_scoreboard";

    public interface Access {
        ScoreboardConnection paradigm$scoreboard();
    }

    public interface Codec {
        Change team(Object packet);
        boolean opensScoreboard(Object packet);
        Object remove(String name);
        default Listing listing(Object packet) { return null; }
    }

    public record Change(String name, int mode, Collection<String> members) {
        public Change { members = List.copyOf(members); }
    }

    public record Listing(String name, boolean online) { }

    public record OwnedTeam(String subject, Object appearance, boolean containsSubject) { }

    public record View(boolean ready, Map<String, OwnedTeam> owned, Set<String> foreignNames, Set<String> foreignMembers, List<String> listed) {
        private static final View EMPTY = new View(false, Map.of(), Set.of(), Set.of(), List.of());

        public boolean allows(String name, String subject) {
            return ready && !foreignNames.contains(name) && !foreignMembers.contains(subject);
        }
    }

    private record Owner(String subject, Object appearance, long epoch) { }
    private record Team(Owner owner, Set<String> members) { }
    private record Write(Object packet, ChannelPromise promise, Change change, Owner owner, boolean reset, Listing listing) { }

    private record Cleanup(long epoch, String subject) { }
    private record Pending(Write write, Cleanup cleanup) { }

    private final Codec codec;
    private final AtomicLong epoch = new AtomicLong();
    // All supported team packet classes use object identity. Weak keys also cover packets cancelled above Netty.
    private final Map<Object, Owner> marked = Collections.synchronizedMap(new WeakHashMap<>());
    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private final Map<String, Team> teams = new HashMap<>();
    private final Set<String> listed = new LinkedHashSet<>();
    private final Set<String> hiddenSubjects = ConcurrentHashMap.newKeySet();
    private volatile View view = View.EMPTY;
    private volatile ChannelHandlerContext context;
    private boolean ready;
    private boolean degraded;
    private boolean draining;
    private boolean writing;
    private boolean flushPending;

    public ScoreboardConnection(Codec codec) {
        this.codec = codec;
    }

    public static ScoreboardConnection install(Channel channel, Codec codec) {
        if (channel.pipeline().get(HANDLER) instanceof ScoreboardConnection current) return current;
        String encoder = channel.pipeline().get("encoder") != null ? "encoder" : "outbound_config";
        // Some local connections have no encoder. For remote connections this placement also sees unpacked bundles.
        if (channel.pipeline().get(encoder) == null) return null;
        var scoreboard = new ScoreboardConnection(codec);
        channel.pipeline().addAfter(encoder, HANDLER, scoreboard);
        return scoreboard;
    }

    public static ScoreboardConnection of(Object connection) {
        return connection instanceof Access access ? access.paradigm$scoreboard() : null;
    }

    public View view() { return view; }

    public <P> void sendOwned(P packet, String subject, Object appearance, Consumer<P> send) {
        if (!view.ready()) return;
        marked.put(packet, new Owner(subject, appearance, epoch.get()));
        try {
            send.accept(packet);
        } catch (RuntimeException rejected) {
            marked.remove(packet);
            LOGGER.debug("Paradigm optional scoreboard packet was rejected before enqueue", rejected);
        }
    }

    public void clearOwned() {
        enqueueCleanup(new Cleanup(epoch.getAndIncrement(), null));
    }

    public void visibility(String subject, boolean visible) {
        if (subject == null) return;
        if (visible) hiddenSubjects.remove(subject);
        else if (hiddenSubjects.add(subject)) enqueueCleanup(new Cleanup(Long.MAX_VALUE, subject));
    }

    private void enqueueCleanup(Cleanup cleanup) {
        var ctx = context;
        if (ctx == null || !ctx.channel().isActive()) return;
        Runnable clear = () -> {
            if (context == null) return;
            pending.add(new Pending(null, cleanup));
            drain(context);
        };
        if (ctx.executor().inEventLoop()) clear.run();
        else ctx.executor().execute(clear);
    }

    @Override public void handlerAdded(ChannelHandlerContext ctx) {
        context = ctx;
    }

    @Override public void write(ChannelHandlerContext ctx, Object packet, ChannelPromise promise) {
        Change change = codec.team(packet);
        Listing listing = codec.listing(packet);
        boolean reset = codec.opensScoreboard(packet);
        if (change == null && listing == null && !reset && pending.isEmpty() && !writing && !draining) {
            ctx.write(packet, promise);
            return;
        }
        Owner owner = change == null ? null : marked.remove(packet);
        pending.add(new Pending(new Write(packet, promise, change, owner, reset, listing), null));
        drain(ctx);
    }

    @Override public void flush(ChannelHandlerContext ctx) {
        flushPending |= !pending.isEmpty();
        ctx.flush();
    }

    private void drain(ChannelHandlerContext ctx) {
        if (ctx == null || draining || writing) return;
        draining = true;
        try {
            while (!writing && !pending.isEmpty()) {
                ChannelHandlerContext current = context;
                Pending next = pending.removeFirst();
                if (next.cleanup() != null) {
                    queueCleanup(current, next.cleanup());
                    continue;
                }
                Write write = next.write();
                if (write.promise().isCancelled()) {
                    ReferenceCountUtil.release(write.packet());
                    continue;
                }
                if (write.reset()) {
                    acknowledged(current, write, () -> {
                        epoch.incrementAndGet();
                        teams.clear();
                        listed.clear();
                        degraded = false;
                        ready = true;
                        followBundleUnpacker(current);
                        publish();
                    });
                    continue;
                }
                if (write.listing() != null) {
                    acknowledged(current, write, () -> {
                        boolean changed = write.listing().online() ? listed.add(write.listing().name())
                                : listed.remove(write.listing().name());
                        if (changed) publish();
                    });
                    continue;
                }
                Change change = write.change();
                if (change == null) {
                    current.write(write.packet(), write.promise());
                    continue;
                }
                Team existing = teams.get(change.name());
                if (write.owner() != null) {
                    if (!allowOwn(write, existing)) {
                        skip(write);
                        continue;
                    }
                } else if (change.mode() == CREATE && existing != null && existing.owner() != null) {
                    handOver(current, write, change.name(), existing.owner());
                    continue;
                }
                acknowledged(current, write, () -> remember(write));
            }
            if (!writing && flushPending) {
                flushPending = false;
                context.flush();
            }
        } finally {
            draining = false;
        }
    }

    private void queueCleanup(ChannelHandlerContext ctx, Cleanup cleanup) {
        if (cleanup.subject() != null && !hiddenSubjects.contains(cleanup.subject())) return;
        for (var entry : teams.entrySet()) {
            var owner = entry.getValue().owner();
            if (owner != null && owner.epoch() <= cleanup.epoch()
                    && (cleanup.subject() == null || cleanup.subject().equals(owner.subject()))) {
                String name = entry.getKey();
                pending.addFirst(new Pending(new Write(codec.remove(name), ctx.newPromise(),
                        new Change(name, REMOVE, List.of()), owner, false, null), null));
            }
        }
    }

    private void followBundleUnpacker(ChannelHandlerContext ctx) {
        var pipeline = ctx.pipeline();
        var names = pipeline.names();
        int unpacker = names.indexOf("unbundler");
        if (unpacker >= 0 && unpacker < names.indexOf(HANDLER)) {
            // A PLAY encoder and its unpacker can be installed after channelActive.
            pipeline.remove(this);
            pipeline.addBefore("unbundler", HANDLER, this);
        }
    }

    private boolean allowOwn(Write write, Team existing) {
        Change change = write.change();
        Owner owner = write.owner();
        if (!ready || degraded) return false;
        if (change.mode() == REMOVE) {
            return existing != null && existing.owner() != null
                    && existing.owner().subject().equals(owner.subject())
                    && existing.owner().epoch() <= owner.epoch();
        }
        if (owner.epoch() != epoch.get() || hiddenSubjects.contains(owner.subject()) || !view.allows(change.name(), owner.subject())) return false;
        boolean onlySubject = change.members().equals(List.of(owner.subject()));
        if (change.mode() == CREATE) return existing == null && onlySubject;
        if (existing == null || existing.owner() == null || !existing.owner().subject().equals(owner.subject())) return false;
        return switch (change.mode()) {
            case UPDATE -> !existing.owner().appearance().equals(owner.appearance());
            case ADD -> onlySubject && !existing.members().contains(owner.subject());
            case LEAVE -> onlySubject && existing.members().contains(owner.subject());
            default -> false;
        };
    }

    private void handOver(ChannelHandlerContext ctx, Write foreign, String name, Owner owner) {
        if (degraded) {
            skip(foreign);
            return;
        }
        var removal = new Write(codec.remove(name), ctx.newPromise(), new Change(name, REMOVE, List.of()), owner, false, null);
        pending.addFirst(new Pending(foreign, null));
        acknowledged(ctx, removal, () -> remember(removal));
    }

    private void acknowledged(ChannelHandlerContext ctx, Write write, Runnable commit) {
        writing = true;
        ChannelPromise actual = ctx.newPromise();
        actual.addListener((ChannelFutureListener) result -> {
            if (result.isSuccess()) {
                if (context != null && ctx.channel().isActive()) commit.run();
                write.promise().trySuccess();
            } else {
                if (!degraded) LOGGER.warn("Paradigm scoreboard state could not be confirmed on this connection. Optional teams are suspended until the next login.", result.cause());
                degraded = true;
                publish();
                // A failed optional write must never trigger Minecraft's disconnect-on-send-failure listener.
                if (write.owner() != null) write.promise().trySuccess();
                else write.promise().tryFailure(result.cause());
            }
            writing = false;
            drain(context);
        });
        ctx.writeAndFlush(write.packet(), actual);
    }

    private static void skip(Write write) {
        ReferenceCountUtil.release(write.packet());
        write.promise().trySuccess();
    }

    private void remember(Write write) {
        Change change = write.change();
        String name = change.name();
        Team existing = teams.get(name);
        switch (change.mode()) {
            case CREATE -> {
                removeMemberships(change.members());
                teams.put(name, new Team(write.owner(), new HashSet<>(change.members())));
            }
            case REMOVE -> teams.remove(name);
            case UPDATE -> {
                if (existing != null) teams.put(name, new Team(write.owner(), existing.members()));
            }
            case ADD -> {
                if (existing != null) {
                    removeMemberships(change.members());
                    existing.members().addAll(change.members());
                    teams.put(name, new Team(write.owner() == null ? null : existing.owner(), existing.members()));
                }
            }
            case LEAVE -> {
                if (existing != null) existing.members().removeAll(change.members());
            }
            default -> { }
        }
        publish();
    }

    private void removeMemberships(Collection<String> members) {
        if (!members.isEmpty()) teams.values().forEach(team -> team.members().removeAll(members));
    }

    private void publish() {
        Map<String, OwnedTeam> owned = new HashMap<>();
        Set<String> foreignNames = new HashSet<>();
        Set<String> foreignMembers = new HashSet<>();
        teams.forEach((name, team) -> {
            if (team.owner() == null) {
                foreignNames.add(name);
                foreignMembers.addAll(team.members());
            } else {
                Owner owner = team.owner();
                owned.put(name, new OwnedTeam(owner.subject(), owner.appearance(), team.members().contains(owner.subject())));
            }
        });
        view = new View(ready && !degraded, Map.copyOf(owned), Set.copyOf(foreignNames), Set.copyOf(foreignMembers), List.copyOf(listed));
    }

    @Override public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        ready = false;
        degraded = true;
        teams.clear();
        listed.clear();
        context = null;
        view = View.EMPTY;
        marked.clear();
        hiddenSubjects.clear();
        while (!pending.isEmpty()) {
            var write = pending.removeFirst().write();
            if (write == null) continue;
            ReferenceCountUtil.release(write.packet());
            if (write.owner() != null) write.promise().trySuccess();
            else write.promise().tryFailure(new ClosedChannelException());
        }
        context = null;
        super.channelInactive(ctx);
    }
}
