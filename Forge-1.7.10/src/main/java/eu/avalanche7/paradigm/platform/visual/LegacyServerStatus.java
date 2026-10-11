package eu.avalanche7.paradigm.platform.visual;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.ServerStatusResponse;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.MinecraftComponent;
import eu.avalanche7.paradigm.utils.ServerStatusDiagnostics;
import eu.avalanche7.paradigm.utils.ServerStatusIconCache;

public final class LegacyServerStatus {
    private LegacyServerStatus() {}

    public static ServerStatusResponse publicStatus(ServerStatusResponse original, Services services) {
        if (original == null || services == null || services.getVanishService().hiddenCount() == 0) return original;
        var filtered = new ServerStatusResponse();
        filtered.func_151315_a(original.func_151317_a());
        filtered.func_151321_a(original.func_151322_c());
        filtered.func_151320_a(original.func_151316_d());
        var players = original.func_151318_b();
        if (players != null) {
            var replacement = new ServerStatusResponse.PlayerCountData(players.func_151332_a(), Math.max(0, players.func_151333_b() - services.getVanishService().hiddenCount()));
            if (players.func_151331_c() != null) replacement.func_151330_a(java.util.Arrays.stream(players.func_151331_c())
                    .filter(profile -> !services.getVanishService().isHidden(profile.getId().toString())).toArray(GameProfile[]::new));
            filtered.func_151319_a(replacement);
        }
        return filtered;
    }

    public static ServerStatusResponse customize(ServerStatusResponse original, Services services) {
        if (original == null || services == null) return original;
        var config = services.getMotdConfig();
        if (config == null || !Boolean.TRUE.equals(config.serverlistMotdEnabled.value)
                || config.motds.value == null || config.motds.value.isEmpty()) return original;
        try {
            var selected = config.motds.value.get(ThreadLocalRandom.current().nextInt(config.motds.value.size()));
            if (selected == null) return original;
            var parser = services.getMessageParser();
            var first = (MinecraftComponent) parser.parseMessage(selected.line1 == null ? "" : selected.line1, null);
            var second = (MinecraftComponent) parser.parseMessage(selected.line2 == null ? "" : selected.line2, null);
            var result = new ServerStatusResponse();
            result.func_151315_a(first.getHandle().createCopy().appendText("\n").appendSibling(second.getHandle()));
            result.func_151321_a(original.func_151322_c());
            result.func_151320_a(Boolean.TRUE.equals(config.iconEnabled.value)
                    ? ServerStatusIconCache.resolveDataUri(selected.icon).orElse(original.func_151316_d()) : original.func_151316_d());
            var players = original.func_151318_b();
            var display = selected.playerCount;
            if (display != null) {
                var count = new ServerStatusResponse.PlayerCountData(display.maxPlayers != null ? display.maxPlayers : players != null ? players.func_151332_a() : 20, players != null ? players.func_151333_b() : 0);
                if (display.hoverText != null && !display.hoverText.isBlank()) {
                    var sample = new ArrayList<GameProfile>();
                    for (String line : display.hoverText.split("\n")) {
                        if (!line.isEmpty()) sample.add(new GameProfile(UUID.randomUUID(), ((MinecraftComponent) parser.parseMessage(line, null)).toLegacyText()));
                    }
                    count.func_151330_a(sample.toArray(GameProfile[]::new));
                } else if (players != null) count.func_151330_a(players.func_151331_c());
                result.func_151319_a(count);
            } else result.func_151319_a(players);
            return result;
        } catch (RuntimeException failure) {
            ServerStatusDiagnostics.customizationFailed(services, "Forge-1.7.10", "status request", failure);
            return original;
        }
    }
}
