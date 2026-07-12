package eu.avalanche7.paradigm.modules.dashboard;

import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.dashboard.auth.DashboardPermission;
import eu.avalanche7.paradigm.modules.dashboard.auth.DashboardPrincipal;
import eu.avalanche7.paradigm.modules.audit.AuditEntry;
import eu.avalanche7.paradigm.modules.audit.AuditService;
import eu.avalanche7.paradigm.modules.audit.AuditActionType;
import eu.avalanche7.paradigm.modules.audit.AuditResult;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class LocalDashboardModule implements ParadigmModule {
    private static volatile LocalDashboardModule INSTANCE;

    private Services services;
    private DashboardConfig config;
    private DashboardService dashboard;

    public LocalDashboardModule() {
        INSTANCE = this;
    }

    public static LocalDashboardModule current() {
        return INSTANCE;
    }

    public AuditService audit() {
        return dashboard != null ? dashboard.audit() : null;
    }

    @Override
    public String getName() {
        return "LocalDashboard";
    }

    @Override
    public boolean isEnabled(Services services) {
        return true;
    }

    @Override
    public void onLoad(Object event, Services services, Object modEventBus) {
        this.services = services;
        this.config = DashboardConfig.load(services.getPlatformAdapter().getConfig(), services.getLogger());
        this.dashboard = new DashboardService(services, config);
    }

    @Override
    public void onServerStarting(Object event, Services services) {
        if (dashboard != null && config != null && config.enabled) {
            dashboard.start();
        }
    }

    @Override public void onEnable(Services services) {}
    @Override public void onDisable(Services services) {}

    @Override
    public void onServerStopping(Object event, Services services) {
        if (dashboard != null) {
            dashboard.close();
        }
    }

    @Override public void registerEventListeners(Object eventBus, Services services) {}
    @Override public void registerCommands(Object dispatcher, Object registryAccess, Services services) {}

    public ICommandBuilder buildCommandBranch(IPlatformAdapter platform, Services services) {
        ICommandBuilder root = platform.createCommandBuilder()
                .literal("dashboard")
                .requires(src -> hasPermission(src, services))
                .executes(ctx -> status(ctx.getSource()));

        return root
                .then(platform.createCommandBuilder().literal("open").executes(ctx -> open(ctx.getSource())))
                .then(platform.createCommandBuilder().literal("status").executes(ctx -> status(ctx.getSource())))
                .then(platform.createCommandBuilder().literal("token").executes(ctx -> token(ctx.getSource())))
                .then(platform.createCommandBuilder().literal("start").executes(ctx -> start(ctx.getSource())))
                .then(platform.createCommandBuilder().literal("stop").executes(ctx -> stop(ctx.getSource())))
                .then(platform.createCommandBuilder().literal("reload").executes(ctx -> reload(ctx.getSource())));
    }

    public ICommandBuilder buildAuditCommandBranch(IPlatformAdapter platform, Services services) {
        ICommandBuilder root = platform.createCommandBuilder()
                .literal("audit")
                .requires(src -> hasPermission(src, services))
                .executes(ctx -> auditRecent(ctx.getSource()));

        return root
                .then(platform.createCommandBuilder().literal("recent").executes(ctx -> auditRecent(ctx.getSource())))
                .then(platform.createCommandBuilder().literal("player")
                        .then(platform.createCommandBuilder().argument("player", ICommandBuilder.ArgumentType.WORD)
                                .executes(ctx -> auditPlayer(ctx.getSource(), ctx.getStringArgument("player")))))
                .then(platform.createCommandBuilder().literal("type")
                        .then(platform.createCommandBuilder().argument("type", ICommandBuilder.ArgumentType.WORD)
                                .executes(ctx -> auditType(ctx.getSource(), ctx.getStringArgument("type")))));
    }

    private int status(ICommandSource source) {
        if (dashboard == null) {
            send(source, "dashboard.error.unavailable", "Dashboard is unavailable.");
            return 0;
        }
        send(source, dashboard.running() ? "dashboard.status.enabled" : "dashboard.status.disabled",
                dashboard.running() ? "Dashboard is running." : "Dashboard is stopped.");
        send(source, "dashboard.status.url", "Dashboard URL: {url}", "{url}", dashboard.baseUrl());
        send(source, "dashboard.status.bind", "Bind: {host}:{port}", "{host}", dashboard.config().host, "{port}", String.valueOf(dashboard.config().port));
        if (dashboard.config().publicBaseUrl != null && !dashboard.config().publicBaseUrl.isBlank()) {
            send(source, "dashboard.status.public_url", "Public URL: {url}", "{url}", dashboard.config().publicBaseUrl);
        }
        send(source, "dashboard.status.sessions", "Sessions: {sessions}, login tokens: {tokens}",
                "{sessions}", String.valueOf(dashboard.auth().activeSessionCount()),
                "{tokens}", String.valueOf(dashboard.auth().activeLoginTokenCount()));
        send(source, "dashboard.status.security", "Security: login={login}, csrf=enabled, rateLimit={rate}/min",
                "{login}", dashboard.config().requireLogin ? "enabled" : "disabled",
                "{rate}", String.valueOf(dashboard.config().rateLimitPerMinute));
        send(source, "dashboard.status.executor", "API executor: active", "{state}", "active");
        if (config != null && !config.remoteAccessRequested()) {
            send(source, "dashboard.status.bound_local", "Dashboard is bound locally by default.");
        } else {
            send(source, "dashboard.status.remote_warning", "Warning: dashboard remote access is enabled or bound outside localhost.");
        }
        return 1;
    }

    private int auditRecent(ICommandSource source) {
        if (dashboard == null) {
            send(source, "dashboard.error.unavailable", "Dashboard is unavailable.");
            return 0;
        }
        sendAuditEntries(source, dashboard.audit().recent(8));
        return 1;
    }

    private int auditPlayer(ICommandSource source, String player) {
        if (dashboard == null) {
            send(source, "dashboard.error.unavailable", "Dashboard is unavailable.");
            return 0;
        }
        sendAuditEntries(source, dashboard.audit().actor(player, 8));
        return 1;
    }

    private int auditType(ICommandSource source, String type) {
        if (dashboard == null) {
            send(source, "dashboard.error.unavailable", "Dashboard is unavailable.");
            return 0;
        }
        sendAuditEntries(source, dashboard.audit().type(type, 8));
        return 1;
    }

    private void sendAuditEntries(ICommandSource source, java.util.List<AuditEntry> entries) {
        send(source, "audit.recent.header", "Recent audit entries:");
        if (entries == null || entries.isEmpty()) {
            send(source, "audit.recent.empty", "No audit entries found.");
            return;
        }
        for (AuditEntry entry : entries) {
            String line = tr("audit.recent.line", "{time} {actor} {action} {result} {message}")
                    .replace("{time}", java.time.Instant.ofEpochMilli(entry.timestampMs()).toString())
                    .replace("{actor}", entry.actorName() != null && !entry.actorName().isBlank() ? entry.actorName() : "-")
                    .replace("{action}", entry.actionType() != null ? entry.actionType().name() : "-")
                    .replace("{result}", entry.result() != null ? entry.result().name() : "-")
                    .replace("{message}", entry.message() != null ? entry.message() : "");
            services.getPlatformAdapter().sendSuccess(source, services.getPlatformAdapter().createLiteralComponent(line), false);
        }
    }

    private int token(ICommandSource source) {
        if (dashboard == null) {
            send(source, "dashboard.error.unavailable", "Dashboard is unavailable.");
            return 0;
        }
        DashboardPrincipal principal = principal(source);
        if (principal == null) {
            send(source, "dashboard.token.failed", "Could not create dashboard login token.");
            return 0;
        }
        var issued = dashboard.auth().createLoginToken(principal, dashboard.config());
        String url = loginUrl(issued.token());
        if (services.getLogger() != null) {
            services.getLogger().info("Paradigm Dashboard: one-time login token created for {}.", principal.name());
        }
        sendLoginLink(source, url);
        return 1;
    }

    private int open(ICommandSource source) {
        if (dashboard == null) {
            send(source, "dashboard.error.unavailable", "Dashboard is unavailable.");
            return 0;
        }
        if (!dashboard.running() && !dashboard.start()) {
            dashboard.audit().command(source, AuditActionType.DASHBOARD_STATUS, AuditResult.FAILED,
                    "Dashboard open failed.", java.util.Map.of("action", "open"));
            send(source, "dashboard.token.failed", "Dashboard could not be started.");
            return 0;
        }
        DashboardPrincipal principal = principal(source);
        if (principal == null) {
            send(source, "dashboard.token.failed", "Could not create dashboard login token.");
            return 0;
        }
        var issued = dashboard.auth().createLoginToken(principal, dashboard.config());
        sendLoginLink(source, loginUrl(issued.token()));
        dashboard.audit().command(source, AuditActionType.DASHBOARD_STATUS, AuditResult.SUCCESS,
                "Dashboard login link created.", java.util.Map.of("action", "open"));
        return 1;
    }

    private String loginUrl(String token) {
        return dashboard.baseUrl() + "/?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
    }

    private int start(ICommandSource source) {
        if (dashboard == null) {
            send(source, "dashboard.error.unavailable", "Dashboard is unavailable.");
            return 0;
        }
        if (dashboard.start()) {
            dashboard.audit().command(source, AuditActionType.DASHBOARD_STATUS, AuditResult.SUCCESS, "Dashboard started.", java.util.Map.of("action", "start"));
            send(source, "dashboard.server.started", "Dashboard started at {url}", "{url}", dashboard.baseUrl());
            return 1;
        }
        dashboard.audit().command(source, AuditActionType.DASHBOARD_STATUS, AuditResult.FAILED, "Dashboard start failed.", java.util.Map.of("action", "start"));
        send(source, "dashboard.token.failed", "Dashboard could not be started.");
        return 0;
    }

    private int stop(ICommandSource source) {
        if (dashboard != null) {
            dashboard.stop();
            dashboard.audit().command(source, AuditActionType.DASHBOARD_STATUS, AuditResult.SUCCESS, "Dashboard stopped.", java.util.Map.of("action", "stop"));
        }
        send(source, "dashboard.server.stopped", "Dashboard stopped.");
        return 1;
    }

    private int reload(ICommandSource source) {
        this.config = DashboardConfig.load(services.getPlatformAdapter().getConfig(), services.getLogger());
        if (dashboard == null) {
            dashboard = new DashboardService(services, config);
        } else {
            dashboard.reload(config);
        }
        dashboard.audit().command(source, AuditActionType.DASHBOARD_STATUS, AuditResult.SUCCESS, "Dashboard reloaded.", java.util.Map.of("action", "reload"));
        send(source, "dashboard.server.reload", "Dashboard config reloaded.");
        if (dashboard.running()) {
            send(source, "dashboard.status.url", "Dashboard URL: {url}", "{url}", dashboard.baseUrl());
        }
        return 1;
    }

    private DashboardPrincipal principal(ICommandSource source) {
        if (source == null) {
            return null;
        }
        if (source.isConsole()) {
            return new DashboardPrincipal("console", "Console", true);
        }
        IPlayer player = source.getPlayer();
        if (player == null || player.getUUID() == null || player.getUUID().isBlank()) {
            return null;
        }
        return new DashboardPrincipal(player.getUUID(), player.getName(), false);
    }

    private boolean hasPermission(ICommandSource source, Services services) {
        if (source == null) {
            return false;
        }
        if (source.isConsole()) {
            return true;
        }
        IPlayer player = source.getPlayer();
        return player != null
                && player.getUUID() != null
                && services.getPermissionsHandler().hasPermission(player.getUUID(), DashboardPermission.MANAGE, 4);
    }

    private void send(ICommandSource source, String key, String fallback, String... placeholders) {
        String raw = services.getLang().getTranslation(key);
        if (raw == null || raw.equals(key)) {
            raw = fallback;
        }
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            raw = raw.replace(placeholders[i], placeholders[i + 1]);
        }
        services.getPlatformAdapter().sendSuccess(source, services.getPlatformAdapter().createLiteralComponent(raw), false);
    }

    private void sendLoginLink(ICommandSource source, String url) {
        IPlatformAdapter platform = services.getPlatformAdapter();
        IComponent message = platform.createEmptyComponent()
                .append(platform.createComponentFromLiteral("[Paradigm]").withColorHex("38BDF8").withFormatting("bold"))
                .append(platform.createComponentFromLiteral(" " + tr("dashboard.token.created", "Dashboard ready:") + " "))
                .append(platform.createComponentFromLiteral("[" + tr("dashboard.token.open", "Open Dashboard") + "]")
                        .withColorHex("22C55E")
                        .withFormatting("bold")
                        .onClickOpenUrl(url)
                        .onHoverText(tr("dashboard.token.open_hover", "Open the local dashboard and log in with this one-time token.")))
                .append(platform.createComponentFromLiteral(" "))
                .append(platform.createComponentFromLiteral("[" + tr("dashboard.token.copy_link", "Copy Link") + "]")
                        .withColorHex("FBBF24")
                        .withFormatting("bold")
                        .onClickCopyToClipboard(url)
                        .onHoverText(tr("dashboard.token.copy_link_hover", "Copy the one-time dashboard login link.")));
        platform.sendSuccess(source, message, false);
    }

    private String tr(String key, String fallback) {
        String raw = services.getLang().getTranslation(key);
        return raw == null || raw.equals(key) ? fallback : raw;
    }
}
