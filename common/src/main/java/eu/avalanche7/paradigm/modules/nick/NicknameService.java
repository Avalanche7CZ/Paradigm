package eu.avalanche7.paradigm.modules.nick;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.storage.repository.PlayerRepository;

public final class NicknameService {
    private static final Set<String> RESERVED = Set.of("off", "set", "reset", "list", "auto");
    private final Supplier<PlayerRepository> repository;
    private final Supplier<java.util.List<IPlayer>> online;

    public NicknameService(Supplier<PlayerRepository> repository, Supplier<java.util.List<IPlayer>> online) {
        this.repository = repository;
        this.online = online;
    }

    public static boolean valid(String nickname) {
        return nickname != null && nickname.matches("[A-Za-z0-9_]{3,16}") && !RESERVED.contains(nickname.toLowerCase(Locale.ROOT));
    }

    public Map<String, String> active() {
        var repo = repository.get();
        Set<String> names = new java.util.HashSet<>();
        repo.listProfiles().forEach(p -> { if (p.name() != null) names.add(p.name().toLowerCase(Locale.ROOT)); });
        online.get().forEach(p -> names.add(p.getName().toLowerCase(Locale.ROOT)));
        Map<String, String> result = new LinkedHashMap<>();
        repo.nicknames().forEach((uuid, nick) -> {
            if (valid(nick) && !names.contains(nick.toLowerCase(Locale.ROOT))) result.put(uuid, nick);
        });
        return result;
    }

    public String nickname(String uuid) {
        try {
            String nickname = repository.get().nickname(uuid);
            if (!valid(nickname)) return "";
            for (IPlayer player : online.get()) if (nickname.equalsIgnoreCase(player.getName())) return "";
            return nickname;
        }
        catch (eu.avalanche7.paradigm.storage.StorageException failure) {
            org.slf4j.LoggerFactory.getLogger(NicknameService.class).warn("Nickname lookup failed; displaying account name: {}", failure.getMessage());
            return "";
        }
    }
    public String displayName(IPlayer player) {
        String nickname = nickname(player.getUUID());
        return nickname.isEmpty() ? player.getName() : nickname;
    }

    public void set(UUID uuid, String nickname) {
        if (nickname != null) {
            if (!valid(nickname)) throw new IllegalArgumentException("Use 3–16 letters, digits or underscores; command keywords are reserved.");
            for (IPlayer player : online.get()) if (nickname.equalsIgnoreCase(player.getName()))
                throw new IllegalArgumentException("That nickname is a real account name.");
        }
        repository.get().setNickname(uuid.toString(), nickname);
    }
}
