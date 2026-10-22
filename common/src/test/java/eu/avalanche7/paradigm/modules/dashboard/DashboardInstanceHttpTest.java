package eu.avalanche7.paradigm.modules.dashboard;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.dashboard.auth.DashboardPrincipal;
import eu.avalanche7.paradigm.modules.permissions.PermissionDefinition;
import eu.avalanche7.paradigm.modules.permissions.PermissionsHandler;
import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.storage.StorageService;
import eu.avalanche7.paradigm.storage.identity.ServerIdentity;
import eu.avalanche7.paradigm.storage.identity.StorageContext;
import eu.avalanche7.paradigm.utils.DebugLogger;

class DashboardInstanceHttpTest {
    @TempDir Path directory;
    final DashboardPrincipal player = new DashboardPrincipal(UUID.randomUUID().toString(), "Admin", false);

    Services services(String server, AtomicBoolean allowed) {
        IConfig config = new IConfig() {
            @Override public Path getConfigDirectory() { return directory.resolve(server); }
            @Override public String getModId() { return "paradigm"; }
        };
        var platform = (IPlatformAdapter) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{IPlatformAdapter.class}, (proxy, method, args) -> {
            if (method.getName().equals("getConfig")) return config;
            if (method.getReturnType() == boolean.class) return false;
            if (method.getReturnType() == int.class) return 0;
            if (method.getReturnType() == long.class) return 0L;
            return null;
        });
        var storage = new StorageService(null, null, config, null, null, null, null) {
            @Override public boolean isMysqlActive() { return false; }
            @Override public StorageContext context() { return new StorageContext(new ServerIdentity("network", server, server)); }
        };
        var permissions = new PermissionsHandler(null, null, new DebugLogger(null), platform, null, null) {
            @Override public boolean hasDashboardLoginPermission(String uuid, PermissionDefinition permission) { return allowed.get() && player.uuid().equals(uuid); }
            @Override public boolean hasPermission(String uuid, String permission, int fallback) { return allowed.get() && player.uuid().equals(uuid); }
        };
        return new Services(null, null, null, null, null, null, null, null, null, null, null,
                null, permissions, null, null, null, null, null, storage, null, platform);
    }

    DashboardConfig config(String server) throws Exception {
        var config = new DashboardConfig();
        try (var socket = new ServerSocket(0)) { config.port = socket.getLocalPort(); }
        config.publicBaseUrl = "https://dashboard.example.test/" + server;
        return config;
    }

    HttpResponse<String> login(HttpClient client, DashboardConfig config, String token) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + config.port + "/api/auth/login"))
                .header("Origin", "https://dashboard.example.test")
                .POST(HttpRequest.BodyPublishers.ofString("{\"token\":\"" + token + "\"}")).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void independentListenersConsumeTheirOwnTokensAndKeepSessionsAcrossConfigReload() throws Exception {
        var allowed = new AtomicBoolean(true);
        var aServices = services("gtnh", allowed); var bServices = services("tfg-modern", allowed);
        var aConfig = config("gtnh"); var bConfig = config("tfg-modern");
        try (var a = new DashboardService(aServices, aConfig); var b = new DashboardService(bServices, bConfig)) {
            assertTrue(a.start()); assertTrue(b.start());
            var tokenA = a.issueLoginToken(player); var tokenB = b.issueLoginToken(player);
            var client = HttpClient.newHttpClient();
            var shell = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + bConfig.port + "/")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, shell.statusCode());
            assertTrue(shell.body().contains("src=\"./assets/"), shell.body());
            assertEquals(401, login(client, aConfig, tokenB.token()).statusCode());
            assertEquals(401, login(client, bConfig, tokenA.token()).statusCode());
            var responseA = login(client, aConfig, tokenA.token());
            var responseB = login(client, bConfig, tokenB.token());
            assertEquals(200, responseA.statusCode(), responseA.body());
            assertEquals(200, responseB.statusCode(), responseB.body());
            assertTrue(responseA.headers().firstValue("Set-Cookie").orElseThrow().contains("Path=/gtnh/; HttpOnly; SameSite=Lax"));
            assertTrue(responseB.headers().firstValue("Set-Cookie").orElseThrow().contains("Path=/tfg-modern/; HttpOnly; SameSite=Lax"));
            assertEquals(401, login(client, aConfig, tokenA.token()).statusCode());
            String cookie = responseB.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];
            var tokenBeforeReload = b.issueLoginToken(player);
            b.reload(bConfig);
            assertEquals(200, login(client, bConfig, tokenBeforeReload.token()).statusCode());
            var status = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + bConfig.port + "/api/auth/status")).header("Cookie", cookie).build();
            var statusResponse = client.send(status, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, statusResponse.statusCode());
            assertTrue(com.google.gson.JsonParser.parseString(statusResponse.body()).getAsJsonObject()
                    .getAsJsonObject("data").get("authenticated").getAsBoolean());
            var denied = b.issueLoginToken(player);
            allowed.set(false);
            assertEquals(401, login(client, bConfig, denied.token()).statusCode());
            allowed.set(true);
            assertEquals(401, login(client, bConfig, denied.token()).statusCode());
        } finally {
            aServices.shutdown(); bServices.shutdown();
            aServices.getStorageService().close(); bServices.getStorageService().close();
        }
    }
}
