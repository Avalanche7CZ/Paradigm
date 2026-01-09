package eu.avalanche7.paradigm.modules.commands;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import eu.avalanche7.paradigm.configs.MainConfigHandler;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.webeditor.EditorApplier;
import eu.avalanche7.paradigm.webeditor.WebEditorSession;
import eu.avalanche7.paradigm.webeditor.socket.WebEditorSocket;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;

import java.security.PublicKey;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class editor implements ParadigmModule {
    private static final String NAME = "Editor";
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    @Override
    public String getName() { return NAME; }

    @Override
    public boolean isEnabled(Services services) { return true; }

    @Override
    public void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess, Services services) {
        IPlatformAdapter platform = services.getPlatformAdapter();

        dispatcher.register(
            CommandManager.literal("paradigm")
                .then(CommandManager.literal("editor")
                    .requires(src -> src.hasPermissionLevel(2))
                    .executes(ctx -> {
                        ICommandSource source = platform.wrapCommandSource(ctx.getSource());
                        return openEditor(source, services);
                    })
                    .then(CommandManager.literal("trust")
                        .then(CommandManager.argument("nonce", StringArgumentType.word())
                            .executes(ctx -> {
                                ICommandSource source = platform.wrapCommandSource(ctx.getSource());
                                String nonce = StringArgumentType.getString(ctx, "nonce");
                                return trustEditor(source, services, nonce);
                            })
                        )
                    )
                    .then(CommandManager.literal("untrust")
                        .executes(ctx -> {
                            ICommandSource source = platform.wrapCommandSource(ctx.getSource());
                            return untrustEditor(source, services, null);
                        })
                        .then(CommandManager.argument("hash", StringArgumentType.greedyString())
                            .executes(ctx -> {
                                ICommandSource source = platform.wrapCommandSource(ctx.getSource());
                                String hash = StringArgumentType.getString(ctx, "hash");
                                return untrustEditor(source, services, hash);
                            })
                        )
                    )
                    .then(CommandManager.literal("trusted")
                        .executes(ctx -> {
                            ICommandSource source = platform.wrapCommandSource(ctx.getSource());
                            return listTrusted(source, services);
                        })
                    )
                )
                .then(CommandManager.literal("apply")
                    .then(CommandManager.argument("code", StringArgumentType.word())
                        .executes(ctx -> {
                            ICommandSource source = platform.wrapCommandSource(ctx.getSource());
                            String code = StringArgumentType.getString(ctx, "code");
                            return applyChanges(source, services, code);
                        })
                    )
                )
        );
    }

    private int openEditor(ICommandSource source, Services services) {
        IPlatformAdapter platform = services.getPlatformAdapter();
        Object srvObj = platform.getMinecraftServer();
        final MinecraftServer server = srvObj instanceof MinecraftServer ? (MinecraftServer) srvObj : null;

        try {
            services.getWebEditorStore().keyPair();
        } catch (Throwable ignored) {}

        platform.sendSuccess((ServerCommandSource) source.getOriginalSource(), platform.createLiteralComponent("Creating web editor session..."), false);

        try { services.getLogger().info("Paradigm WebEditor: openEditor invoked by {}", source.getSourceName()); } catch (Throwable ignored) {}

        CompletableFuture.runAsync(() -> {
            try {
                try { services.getLogger().info("Paradigm WebEditor: async task starting for {}", source.getSourceName()); } catch (Throwable ignored) {}
                JsonObject payload = WebEditorSession.buildPayload(services);
                try { services.getLogger().info("Paradigm WebEditor: built payload, size={}", payload.toString().length()); } catch (Throwable ignored) {}
                WebEditorSession session = WebEditorSession.of(services, payload, source);
                String id = session.open();
                if (id == null || id.isEmpty()) {
                    try { services.getLogger().warn("Paradigm WebEditor: session.open() returned null or empty id for {}", source.getSourceName()); } catch (Throwable ignored) {}
                    if (server != null) server.execute(() -> platform.sendFailure((ServerCommandSource) source.getOriginalSource(), platform.createLiteralComponent("Failed to create web editor session.")));
                    try { services.getLogger().warn("Paradigm WebEditor: Failed to create web editor session for {}", source.getSourceName()); } catch (Throwable ignored) {}
                    return;
                }
                String baseUrl = getEditorBaseUrl(services);
                String url = baseUrl + id;
                if (server != null) {
                    server.execute(() -> {
                        IComponent link = platform.createComponentFromLiteral(url)
                            .onClickOpenUrl(url)
                            .onHoverText("Click to open the Web Editor in your browser");
                        IComponent message = platform.createComponentFromLiteral("Web Editor: ").append(link);
                        platform.sendSuccess((ServerCommandSource) source.getOriginalSource(), message, false);
                    });
                }
            } catch (Exception e) {
                try { services.getLogger().warn("Paradigm WebEditor: Error opening editor for {}: {}", source.getSourceName(), e.toString()); } catch (Throwable ignored) {}
                if (server != null) server.execute(() -> platform.sendFailure((ServerCommandSource) source.getOriginalSource(), platform.createLiteralComponent("Error opening editor: " + e.getMessage())));
            }
        });
        return 1;
    }

    private String getEditorBaseUrl(Services services) {
        try {
            boolean useTestUrl = false;
            try {
                useTestUrl = MainConfigHandler.CONFIG != null && MainConfigHandler.CONFIG.webEditorTestUrl != null && MainConfigHandler.CONFIG.webEditorTestUrl.get();
            } catch (Throwable ignored) {}
            if (useTestUrl) {
                return "http://localhost:8083/editor/";
            }
        } catch (Throwable ignored) {}
        return "https://paradigm.avalanche7.eu/editor/";
    }

    private int trustEditor(ICommandSource source, Services services, String nonce) {
        IPlatformAdapter platform = services.getPlatformAdapter();
        if (nonce == null || nonce.isEmpty()) {
            platform.sendFailure((ServerCommandSource) source.getOriginalSource(), platform.createLiteralComponent("You must specify the trust code (nonce)."));
            return 0;
        }
        Collection<WebEditorSocket> sockets = services.getWebEditorStore().sockets().getSockets();
        boolean foundSocket = false;
        for (WebEditorSocket s : sockets) {
            if (s.isOwnedBy(source)) {
                foundSocket = true;
                try {
                    java.util.Set<String> pendingNonces = s.getPendingNonces();
                    services.getLogger().info("Paradigm WebEditor: Pending nonces for {}: {}", source.getSourceName(), pendingNonces);
                } catch (Throwable ignored) {}
                PublicKey pk = s.getAttemptedPublicKey(nonce);
                if (pk != null) {
                    boolean changed = services.getWebEditorStore().keystore().trust(source, pk.getEncoded());

                    try {
                        s.setRemotePublicKey(pk);
                        JsonObject reply = new JsonObject();
                        reply.addProperty("type", "hello_reply");
                        reply.addProperty("nonce", nonce);
                        reply.addProperty("state", "trusted");
                        s.send(reply);
                    } catch (Throwable ignored) {}

                    s.clearAttempt(nonce);
                    String hash = eu.avalanche7.paradigm.webeditor.store.WebEditorKeystore.hash(pk.getEncoded());
                    if (changed) {
                        platform.sendSuccess((ServerCommandSource) source.getOriginalSource(), platform.createLiteralComponent("Trusted web editor (" + hash + "). The editor should now be connected."), false);
                    } else {
                        platform.sendSuccess((ServerCommandSource) source.getOriginalSource(), platform.createLiteralComponent("This web editor is already trusted (" + hash + "). The editor should now be connected."), false);
                    }
                    try { services.getLogger().info("Paradigm WebEditor: {} trusted editor key {}", source.getSourceName(), hash); } catch (Throwable ignored) {}
                    return 1;
                }
            }
        }
        if (!foundSocket) {
            try { services.getLogger().warn("Paradigm WebEditor: No WebEditorSocket found for user {} when trusting nonce {}", source.getSourceName(), nonce); } catch (Throwable ignored) {}
        }
        platform.sendFailure((ServerCommandSource) source.getOriginalSource(), platform.createLiteralComponent("No pending editor connection for that code. Make sure the editor page is open and shows 'Awaiting trust'."));
        try { services.getLogger().warn("Paradigm WebEditor: No pending untrusted editor for nonce={} by {}", nonce, source.getSourceName()); } catch (Throwable ignored) {}
        return 0;
    }

    private int untrustEditor(ICommandSource source, Services services, String hashOrAll) {
        IPlatformAdapter platform = services.getPlatformAdapter();
        String arg = hashOrAll;
        if (!source.isConsole()) {
            if (arg == null || arg.isEmpty() || "all".equalsIgnoreCase(arg)) {
                boolean ok = services.getWebEditorStore().keystore().untrust(source, null);
                if (ok) {
                    platform.sendSuccess((ServerCommandSource) source.getOriginalSource(), platform.createLiteralComponent("Cleared your trusted editor key."), false);
                    try { services.getLogger().info("Paradigm WebEditor: {} cleared their trusted editor key", source.getSourceName()); } catch (Throwable ignored) {}
                    return 1;
                } else {
                    platform.sendFailure((ServerCommandSource) source.getOriginalSource(), platform.createLiteralComponent("You do not have a trusted editor key set."));
                    return 0;
                }
            }
        }
        boolean ok = services.getWebEditorStore().keystore().untrust(source, arg);
        if (ok) {
            platform.sendSuccess((ServerCommandSource) source.getOriginalSource(), platform.createLiteralComponent("Untrusted key: " + arg), false);
            try { services.getLogger().info("Paradigm WebEditor: {} untrusted key {}", source.getSourceName(), arg); } catch (Throwable ignored) {}
            return 1;
        } else {
            platform.sendFailure((ServerCommandSource) source.getOriginalSource(), platform.createLiteralComponent("No matching trusted key to remove."));
            return 0;
        }
    }

    private int listTrusted(ICommandSource source, Services services) {
        IPlatformAdapter platform = services.getPlatformAdapter();
        List<String> hashes = services.getWebEditorStore().keystore().listTrusted(source);
        if (hashes.isEmpty()) {
            platform.sendSuccess((ServerCommandSource) source.getOriginalSource(), platform.createLiteralComponent("No trusted editor keys set."), false);
            return 1;
        }
        platform.sendSuccess((ServerCommandSource) source.getOriginalSource(), platform.createLiteralComponent("Trusted key(s):"), false);
        for (String h : hashes) {
            IComponent line = platform.createComponentFromLiteral(" - " + h)
                .onClickSuggestCommand("/paradigm editor untrust " + h)
                .onHoverText("Click to prepare untrust of this key");
            platform.sendSuccess((ServerCommandSource) source.getOriginalSource(), line, false);
        }
        try { services.getLogger().info("Paradigm WebEditor: {} listed {} trusted key(s)", source.getSourceName(), hashes.size()); } catch (Throwable ignored) {}
        return 1;
    }

    private int applyChanges(ICommandSource source, Services services, String code) {
        IPlatformAdapter platform = services.getPlatformAdapter();
        try {
            EditorApplier.ApplyResult result = EditorApplier.applyFromBytebinWithReport(services, code);
            if (result.applied <= 0) {
                if (result.unchanged > 0) {
                    platform.sendSuccess((ServerCommandSource) source.getOriginalSource(), platform.createLiteralComponent(result.message), false);
                } else {
                    platform.sendFailure((ServerCommandSource) source.getOriginalSource(), platform.createLiteralComponent(result.message));
                }
                return result.applied > 0 || result.unchanged > 0 ? 1 : 0;
            }
            platform.sendSuccess((ServerCommandSource) source.getOriginalSource(), platform.createLiteralComponent(result.message), false);
            return 1;
        } catch (Exception e) {
            platform.sendFailure((ServerCommandSource) source.getOriginalSource(), platform.createLiteralComponent("Apply failed: " + e.getMessage()));
            try { services.getLogger().warn("Paradigm Apply: Failed for code {}", code, e); } catch (Throwable ignored) {}
            return 0;
        }
    }

    @Override public void onLoad(Object event, Services services, Object modEventBus) {}
    @Override public void onServerStarting(Object event, Services services) {}
    @Override public void onEnable(Services services) {}
    @Override public void onDisable(Services services) {}
    @Override public void onServerStopping(Object event, Services services) {}
    @Override public void registerEventListeners(Object eventBus, Services services) {}
}
