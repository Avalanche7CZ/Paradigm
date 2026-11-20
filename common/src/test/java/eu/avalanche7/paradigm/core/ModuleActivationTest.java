package eu.avalanche7.paradigm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import eu.avalanche7.paradigm.ParadigmAPI;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;

class ModuleActivationTest {
    @Test
    void disabledModulesStayInactiveAndEachActivationEnablesOnce() {
        Services services = services();
        CountingModule module = new CountingModule();
        services.enableModule(module);
        assertEquals(List.of(), module.calls);
        module.enabled = true;
        services.enableModule(module);
        module.onServerStarting(null, services);
        services.enableModule(module);
        assertEquals(List.of("enable", "starting"), module.calls);
        services.disableModule(module);
        services.enableModule(module);
        assertEquals(List.of("enable", "starting", "disable", "enable"), module.calls);
    }

    @Test
    void queuedBootstrapRefreshDoesNotEnableAnAlreadyActivatedModuleAgain() throws Exception {
        Services services = services();
        CountingModule module = new CountingModule();
        var field = ParadigmAPI.class.getDeclaredField("instance");
        field.setAccessible(true);
        var previous = (ParadigmAPI.ParadigmAccessor) field.get(null);
        ParadigmAPI.setInstance(new ParadigmAPI.ParadigmAccessor() {
            @Override public List<ParadigmModule> getModules() { return List.of(module); }
            @Override public Services getServices() { return services; }
            @Override public String getModVersion() { return "test"; }
        });
        try {
            module.enabled = true;
            services.enableModule(module);
            module.onServerStarting(null, services);
            services.reconcileModule(module);
            assertEquals(List.of("enable", "starting"), module.calls);

            module.enabled = false;
            services.reconcileModule(module);
            module.enabled = true;
            services.reconcileModule(module);
            assertEquals(List.of("enable", "starting", "disable", "enable"), module.calls);
        } finally {
            ParadigmAPI.setInstance(previous);
        }
    }

    @Test
    void concurrentFirstUseAndReactivationEachInvokeEnableOnce() throws Exception {
        Services services = services();
        CountingModule module = new CountingModule();
        module.enabled = true;
        var executor = Executors.newFixedThreadPool(6);
        try {
            for (int activation = 0; activation < 2; activation++) {
                CountDownLatch start = new CountDownLatch(1);
                List<Future<?>> futures = new ArrayList<>();
                for (int request = 0; request < 12; request++) {
                    futures.add(executor.submit(() -> {
                        start.await();
                        services.enableModule(module);
                        return null;
                    }));
                }
                start.countDown();
                for (Future<?> future : futures) future.get(10, TimeUnit.SECONDS);
                assertEquals(activation + 1, module.calls.stream().filter("enable"::equals).count());
                services.disableModule(module);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void failedPostEnableValidationCleansUpAndAllowsRetry() {
        Services services = services();
        var module = new CountingModule() {
            int checks;
            boolean failValidation = true;
            @Override public boolean isEnabled(Services ignored) {
                if (++checks == 2 && failValidation) throw new IllegalStateException("validation failed");
                return true;
            }
        };
        assertThrows(IllegalStateException.class, () -> services.enableModule(module));
        org.junit.jupiter.api.Assertions.assertFalse(services.isModuleActive(module));
        org.junit.jupiter.api.Assertions.assertFalse(ModuleEventListeners.isActive(services, module));
        module.failValidation = false;
        services.enableModule(module);
        assertEquals(List.of("enable", "disable", "enable"), ((CountingModule) module).calls);
    }

    @Test
    void repeatedFailureObjectDuringCleanupPreservesOriginalFailure() {
        Services services = services();
        var failure = new IllegalStateException("shared failure");
        CountingModule module = new CountingModule() {
            @Override public void onEnable(Services ignored) { throw failure; }
            @Override public void onDisable(Services ignored) { throw failure; }
        };
        module.enabled = true;
        org.junit.jupiter.api.Assertions.assertSame(failure,
                assertThrows(IllegalStateException.class, () -> services.enableModule(module)));
        org.junit.jupiter.api.Assertions.assertFalse(services.isModuleActive(module));
    }

    @Test
    void failedEnablePreservesTheFailureAndCanBeRetried() {
        Services services = services();
        CountingModule module = new CountingModule();
        module.enabled = true;
        module.fail = true;
        assertThrows(IllegalStateException.class, () -> services.enableModule(module));
        org.junit.jupiter.api.Assertions.assertFalse(ModuleEventListeners.isActive(services, module));
        module.fail = false;
        services.enableModule(module);
        services.enableModule(module);
        assertEquals(List.of("enable", "disable", "enable"), module.calls);
    }


    @Test
    void failedConfiguredEnabledModuleRetriesOnReloadAndStopsOnce() throws Exception {
        Services services = services();
        CountingModule module = new CountingModule();
        module.enabled = true;
        module.fail = true;
        var field = ParadigmAPI.class.getDeclaredField("instance");
        field.setAccessible(true);
        var previous = (ParadigmAPI.ParadigmAccessor) field.get(null);
        ParadigmAPI.setInstance(new ParadigmAPI.ParadigmAccessor() {
            public List<ParadigmModule> getModules() { return List.of(module); }
            public Services getServices() { return services; }
            public String getModVersion() { return "test"; }
        });
        try {
            assertThrows(IllegalStateException.class, () -> services.enableModule(module));
            module.fail = false;
            services.reconcileModule(module);
            services.reconcileModule(module);
            assertEquals(List.of("enable", "disable", "enable"), module.calls);
            services.beginStopping();
            services.disableModule(module);
            services.disableModule(module);
            services.reconcileModule(module);
            assertEquals(List.of("enable", "disable", "enable", "disable"), module.calls);
        } finally {
            ParadigmAPI.setInstance(previous);
        }
    }

    @Test
    void aThrowingStopHookStillDisablesOnceAndDoesNotPreventOtherCleanup() {
        Services services = services();
        CountingModule broken = new CountingModule() {
            @Override public void onServerStopping(Object e, Services s) {
                super.onServerStopping(e, s);
                throw new IllegalStateException("stop failure");
            }
        };
        CountingModule healthy = new CountingModule();
        broken.enabled = true;
        healthy.enabled = true;
        services.enableModule(broken);
        services.enableModule(healthy);
        services.beginStopping();
        services.stopModule(broken, null);
        services.stopModule(healthy, null);
        services.stopModule(broken, null);
        assertEquals(List.of("enable", "stopping", "disable"), broken.calls);
        assertEquals(List.of("enable", "stopping", "disable"), healthy.calls);
        org.junit.jupiter.api.Assertions.assertFalse(services.isModuleActive(broken));
    }

    @Test
    void stoppingDuringActivationCleansUpInsteadOfPublishingAnActiveModule() {
        Services services = services();
        CountingModule module = new CountingModule() {
            @Override public void onEnable(Services s) {
                super.onEnable(s);
                s.beginStopping();
            }
        };
        module.enabled = true;
        services.enableModule(module);
        services.enableModule(module);
        assertEquals(List.of("enable", "disable"), module.calls);
        org.junit.jupiter.api.Assertions.assertFalse(services.isModuleActive(module));
    }

    private static Services services() {
        IPlatformAdapter platform = (IPlatformAdapter) Proxy.newProxyInstance(ModuleActivationTest.class.getClassLoader(),
                new Class<?>[]{IPlatformAdapter.class}, (proxy, method, arguments) -> method.getReturnType() == boolean.class ? false : null);
        return new Services(null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, platform);
    }

    private static class CountingModule implements ParadigmModule {
        private boolean enabled;
        private boolean fail;
        private final List<String> calls = new ArrayList<>();
        @Override public String getName() { return "probe"; }
        @Override public boolean isEnabled(Services services) { return enabled; }
        @Override public void onLoad(Object e, Services s, Object b) { }
        @Override public void onEnable(Services services) {
            calls.add("enable");
            if (fail) throw new IllegalStateException("enable failure");
        }
        @Override public void onDisable(Services services) { calls.add("disable"); }
        @Override public void onServerStarting(Object e, Services s) { calls.add("starting"); }
        @Override public void onServerStopping(Object e, Services s) { calls.add("stopping"); }
        @Override public void registerCommands(Object d, Object r, Services s) { }
        @Override public void registerEventListeners(Object b, Services s) { }
    }
}
