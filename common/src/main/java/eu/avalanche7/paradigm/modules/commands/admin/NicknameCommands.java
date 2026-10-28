package eu.avalanche7.paradigm.modules.commands.admin;

import java.util.UUID;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.permissions.ParadigmPermissions;
import eu.avalanche7.paradigm.modules.permissions.PermissionDefinition;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;

public final class NicknameCommands extends AbstractAdminCommand {
    @Override public String getName() { return "Nickname"; }
    @Override public boolean isEnabled(Services services) { return true; }

    @Override public void registerCommands(Object dispatcher, Object registry, Services services) {
        this.services = services;
        var nick = builder().literal("nick")
                .then(builder().literal("off").requires(s -> allowed(s, "nick", ParadigmPermissions.NICK))
                        .executes(c -> change(c.getSource(), null, null)))
                .then(builder().literal("list").requires(s -> allowed(s, "nick", ParadigmPermissions.NICK_LOOKUP))
                        .executes(c -> lookup(c.getSource(), null)))
                .then(builder().literal("reset").requires(s -> allowed(s, "nick", ParadigmPermissions.NICK_OTHERS))
                        .then(builder().argument("player", ICommandBuilder.ArgumentType.WORD)
                                .executes(c -> change(c.getSource(), c.getStringArgument("player"), null))))
                .then(builder().literal("set").requires(s -> allowed(s, "nick", ParadigmPermissions.NICK_OTHERS))
                        .then(builder().argument("player", ICommandBuilder.ArgumentType.WORD)
                                .then(builder().argument("nickname", ICommandBuilder.ArgumentType.WORD)
                                        .executes(c -> change(c.getSource(), c.getStringArgument("player"), c.getStringArgument("nickname"))))))
                .then(builder().argument("nickname", ICommandBuilder.ArgumentType.WORD)
                        .requires(s -> allowed(s, "nick", ParadigmPermissions.NICK))
                        .executes(c -> change(c.getSource(), null, c.getStringArgument("nickname"))));
        services.getPlatformAdapter().registerCommand(nick);
        services.getPlatformAdapter().registerCommand(builder().literal("realname")
                .requires(s -> allowed(s, "realname", ParadigmPermissions.NICK_LOOKUP))
                .then(builder().argument("nickname", ICommandBuilder.ArgumentType.WORD)
                        .executes(c -> lookup(c.getSource(), c.getStringArgument("nickname")))));
    }

    private int change(ICommandSource source, String target, String nickname) {
        PermissionDefinition permission = target == null ? ParadigmPermissions.NICK : ParadigmPermissions.NICK_OTHERS;
        if (!allowed(source, "nick", permission)) return 0;
        try {
            String uuid;
            if (target == null) {
                if (!source.isPlayer()) throw new IllegalArgumentException("Players only; use /nick set or /nick reset for another player.");
                uuid = source.getPlayer().getUUID();
            } else {
                var online = services.getPlatformAdapter().getPlayerByName(target);
                uuid = online != null ? online.getUUID() : services.getStorageService().players().listProfiles().stream()
                        .filter(p -> target.equalsIgnoreCase(p.name()) || target.equalsIgnoreCase(p.uuid()))
                        .map(p -> p.uuid()).findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown account name or UUID."));
            }
            services.getNicknameService().set(UUID.fromString(uuid), nickname);
            services.getPlatformAdapter().sendSuccess(source, services.getPlatformAdapter().createLiteralComponent(
                    nickname == null ? "Nickname removed." : "Nickname set to " + nickname + "."), false);
            return 1;
        } catch (IllegalArgumentException | eu.avalanche7.paradigm.storage.StorageException failure) {
            services.getPlatformAdapter().sendFailure(source, services.getPlatformAdapter().createLiteralComponent("Nickname change failed: " + failure.getMessage()));
            return 0;
        }
    }

    private int lookup(ICommandSource source, String nickname) {
        if (!allowed(source, nickname == null ? "nick" : "realname", ParadigmPermissions.NICK_LOOKUP)) return 0;
        try {
            var names = services.getNicknameService().active();
            var profiles = services.getStorageService().players().listProfiles();
            var matches = names.entrySet().stream().filter(e -> nickname == null || nickname.equalsIgnoreCase(e.getValue()))
                    .sorted(java.util.Map.Entry.comparingByValue(String.CASE_INSENSITIVE_ORDER)).toList();
            if (matches.isEmpty()) services.getPlatformAdapter().sendSuccess(source,
                    services.getPlatformAdapter().createLiteralComponent("No active nickname mappings found."), false);
            for (var entry : matches) {
                String real = profiles.stream().filter(p -> p.uuid().equals(entry.getKey())).map(p -> p.name() != null ? p.name() : p.uuid())
                        .findFirst().orElse(entry.getKey());
                services.getPlatformAdapter().sendSuccess(source, services.getPlatformAdapter().createLiteralComponent(
                        entry.getValue() + " → " + real + " (" + entry.getKey() + ")"), false);
            }
            return 1;
        } catch (eu.avalanche7.paradigm.storage.StorageException failure) {
            services.getPlatformAdapter().sendFailure(source, services.getPlatformAdapter().createLiteralComponent("Nickname lookup failed: " + failure.getMessage()));
            return 0;
        }
    }
}
