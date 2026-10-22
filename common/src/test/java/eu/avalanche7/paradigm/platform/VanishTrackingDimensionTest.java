package eu.avalanche7.paradigm.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;

import javax.tools.ToolProvider;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class VanishTrackingDimensionTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"NeoForge-1.21.1", "NeoForge-26.1.2", "Forge-1.18.2", "Forge-1.19.2", "Forge-1.20.1", "Forge-1.21.1", "Forge-1.21.11", "Fabric-1.19.2", "Fabric-1.20.1", "Fabric-1.21.1", "Fabric-1.21.11"})
    void refreshNeverPairsPlayersAcrossWorlds(String loader) throws Exception {
        Path root = Path.of(System.getProperty("vanish.source.root", ".."));
        String mixin = Files.readString(root.resolve(loader + "/src/main/java/eu/avalanche7/paradigm/mixin/VanishMixin.java"));
        int start = mixin.indexOf("public void paradigm$refreshVisibility(Object viewer)");
        int end = mixin.indexOf("\n    }", start);
        String method = mixin.substring(start, end).replace("ServerPlayerEntity", "ServerPlayer");
        Path source = directory.resolve("Regression.java");
        Files.writeString(source, """
                public class Regression {
                    static class Entity {
                        Object level;
                        Entity(Object world) { level = world; }
                        Object level() { return level; }
                        Object getWorld() { return level; }
                        Object getEntityWorld() { return level; }
                    }
                    static class ServerPlayer extends Entity {
                        ServerPlayer(Object world) { super(world); }
                    }
                    static class VanishTracking {
                        static boolean visible = true;
                        static boolean canSee(Object viewer, Object target) { return visible; }
                    }
                    Entity entity;
                    boolean paired;
                    void updatePlayer(ServerPlayer viewer) { paired = viewer != entity; }
                    void removePlayer(ServerPlayer viewer) { paired = false; }
                    void updateTrackedStatus(ServerPlayer viewer) { updatePlayer(viewer); }
                    void stopTracking(ServerPlayer viewer) { removePlayer(viewer); }
                    METHOD
                    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
                    public static void main(String[] args) {
                        Object world = new Object(), other = new Object();
                        Regression tracker = new Regression();
                        tracker.entity = new ServerPlayer(world);
                        ServerPlayer viewer = new ServerPlayer(other);
                        tracker.paradigm$refreshVisibility(viewer);
                        check(!tracker.paired, "cross-world spawn");
                        tracker.paired = true;
                        tracker.paradigm$refreshVisibility(viewer);
                        check(!tracker.paired, "stale cross-world pairing retained");
                        viewer.level = world;
                        tracker.paradigm$refreshVisibility(viewer);
                        check(tracker.paired, "same-world reveal lost");
                        VanishTracking.visible = false;
                        tracker.paradigm$refreshVisibility(viewer);
                        check(!tracker.paired, "hidden player exposed");
                        VanishTracking.visible = true;
                        tracker.paradigm$refreshVisibility(viewer);
                        check(tracker.paired, "permission grant failed");
                        viewer.level = other;
                        tracker.paradigm$refreshVisibility(viewer);
                        check(!tracker.paired, "dimension transfer retained pairing");
                        tracker.paradigm$refreshVisibility(null);
                        tracker.paradigm$refreshVisibility(new Object());
                        check(!tracker.paired, "invalid viewer paired");
                    }
                }
                """.replace("METHOD", method));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-d", directory.toString(), source.toString()));
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-cp", directory.toString(), "Regression").redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), loader + ": " + output);
    }
}
