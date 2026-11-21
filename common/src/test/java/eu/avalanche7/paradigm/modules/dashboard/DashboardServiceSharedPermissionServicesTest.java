package eu.avalanche7.paradigm.modules.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.audit.AuditService;
import eu.avalanche7.paradigm.modules.permissions.PermissionAPI;
import eu.avalanche7.paradigm.modules.permissions.PermissionAdminService;
import eu.avalanche7.paradigm.modules.permissions.PermissionAssignment;
import eu.avalanche7.paradigm.modules.permissions.context.PermissionContextSet;
import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;

class DashboardServiceSharedPermissionServicesTest {
    @TempDir
    Path tempDir;

    @Test
    void dashboardReusesServicesAuditAndPermissionMutationServices() throws Exception {
        Services services = services();

        AuditService audit = services.getAuditService();
        PermissionAdminService permissions = services.getPermissionAdminService();
        assertSame(audit, services.getAuditService());
        assertSame(permissions, services.getPermissionAdminService());

        try (DashboardService dashboard = new DashboardService(services, new DashboardConfig())) {
            assertSame(audit, dashboard.audit());
            assertSame(permissions, dashboardPermissionAdminService(dashboard));
        }

        // Closing dashboard-owned executors must not replace the shared audit service.
        assertSame(audit, services.getAuditService());
        assertSame(permissions, services.getPermissionAdminService());
    }

    @Test
    void permissionUserPresentationSeparatesEffectiveAncestorsFromDirectAssignments() {
        PermissionAPI.PermissionMeta metadata = new PermissionAPI.PermissionMeta(
                "member", "[Member] ", "", List.of("default", "vip", "world-vip", "member"));
        var contextual = new PermissionAssignment("contextual", PermissionAssignment.Kind.USER_GROUP,
                "player", "player", "world-vip", false, PermissionContextSet.of(Map.of("world", "minecraft:overworld")), null, false, "");
        PermissionAPI.UserInfo info = new PermissionAPI.UserInfo(
                List.of(), List.of(contextual), List.of("vip"), List.of(), metadata);

        Map<String, Object> presented = DashboardService.permissionUserInfo(info);

        assertEquals(List.of("default", "member"), presented.get("implicitGroups"));
        assertEquals(List.of(contextual), presented.get("groupAssignments"));
        assertEquals(List.of("vip"), presented.get("permanentGroups"));
        assertSame(metadata, presented.get("meta"));
    }

    private Services services() {
        IConfig config = new IConfig() {
            @Override public Path getConfigDirectory() { return tempDir; }
            @Override public String getModId() { return "paradigm"; }
        };
        IPlatformAdapter platform = (IPlatformAdapter) Proxy.newProxyInstance(
                IPlatformAdapter.class.getClassLoader(),
                new Class<?>[]{IPlatformAdapter.class},
                (proxy, method, arguments) -> {
                    if ("getConfig".equals(method.getName())) {
                        return config;
                    }
                    if ("toString".equals(method.getName())) {
                        return "test-platform";
                    }
                    return defaultValue(method.getReturnType());
                }
        );

        return new Services(
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, platform
        );
    }

    private static PermissionAdminService dashboardPermissionAdminService(DashboardService dashboard) throws Exception {
        Field field = DashboardService.class.getDeclaredField("permissionAdminService");
        field.setAccessible(true);
        return (PermissionAdminService) field.get(dashboard);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0F;
        }
        if (type == double.class) {
            return 0D;
        }
        if (type == char.class) {
            return '\0';
        }
        return null;
    }
}
