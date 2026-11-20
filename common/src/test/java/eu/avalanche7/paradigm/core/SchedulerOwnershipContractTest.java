package eu.avalanche7.paradigm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class SchedulerOwnershipContractTest {

    private static final List<String> TARGETS = List.of(
            "Fabric-1.19.2", "Fabric-1.20.1", "Fabric-1.21.1", "Fabric-1.21.11",
            "Forge-1.18.2", "Forge-1.19.2", "Forge-1.20.1", "Forge-1.21.1", "Forge-1.21.11",
            "NeoForge-1.21.1");

    @Test
    void everyLoaderCreatesExactlyOneSchedulerAndHandsItToItsPlatformAdapter() throws Exception {
        Path root = repositoryRoot();
        for (String target : TARGETS) {
            String entryPoint = Files.readString(root.resolve(target + "/src/main/java/eu/avalanche7/paradigm/Paradigm.java"));
            assertEquals(1, countOccurrences(entryPoint, "new eu.avalanche7.paradigm.utils.TaskScheduler(")
                            + countOccurrences(entryPoint, "new TaskScheduler("),
                    target + " must construct exactly one TaskScheduler");
            assertTrue(entryPoint.contains("taskScheduler"), target);
            assertTrue(entryPoint.contains("new PlatformAdapterImpl("), target);
        }
    }

    @Test
    void everyPlatformAdapterExposesItsSchedulerSoTheRuntimeCanAdoptIt() throws Exception {
        Path root = repositoryRoot();
        for (String target : TARGETS) {
            String adapter = Files.readString(root.resolve(target + "/src/main/java/eu/avalanche7/paradigm/platform/PlatformAdapterImpl.java"));
            assertTrue(adapter.contains("public TaskScheduler getTaskScheduler()"), target + " must expose its TaskScheduler");
            String body = adapter.substring(adapter.indexOf("public TaskScheduler getTaskScheduler()"));
            body = body.substring(0, Math.min(body.length(), 120));
            assertTrue(body.contains("return taskScheduler;"), target + " must return the injected scheduler");
        }
    }

    @Test
    void everyLoaderShutsTheRuntimeDownThroughServices() throws Exception {
        Path root = repositoryRoot();
        for (String target : TARGETS) {
            String entryPoint = Files.readString(root.resolve(target + "/src/main/java/eu/avalanche7/paradigm/Paradigm.java"));
            assertTrue(entryPoint.contains("runtime.serverStopping("), target + " must signal the runtime on server stop");
            assertTrue(entryPoint.contains("runtime.serverStopped("), target + " must let the runtime close storage");
            assertFalse(entryPoint.contains("getTaskScheduler().onServerStopping()"),
                    target + " must not stop the scheduler behind Services' back");
        }
    }

    @Test
    void theRuntimeAdoptsThePlatformSchedulerInsteadOfCreatingASecondOne() throws Exception {
        Path root = repositoryRoot();
        String runtime = Files.readString(root.resolve("common/src/main/java/eu/avalanche7/paradigm/core/CommonRuntime.java"));
        assertTrue(runtime.contains("adoptPlatformScheduler("), "CommonRuntime must adopt the platform adapter's scheduler");
        assertTrue(runtime.contains("platformAdapter.getTaskScheduler()"), "CommonRuntime must read the adapter's scheduler");
        assertEquals(1, countOccurrences(runtime, "new TaskScheduler("),
                "CommonRuntime may only construct a TaskScheduler as the documented fallback");
    }

    private static Path repositoryRoot() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("settings.gradle"))) root = root.getParent();
        if (root == null) throw new IllegalStateException("Could not locate repository root");
        return root;
    }

    private static int countOccurrences(String source, String needle) {
        int count = 0;
        int index = source.indexOf(needle);
        while (index >= 0) {
            count++;
            index = source.indexOf(needle, index + needle.length());
        }
        return count;
    }
}
