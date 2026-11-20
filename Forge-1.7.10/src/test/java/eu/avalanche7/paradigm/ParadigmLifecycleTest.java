package eu.avalanche7.paradigm;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.List;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppedEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;
import cpw.mods.fml.common.eventhandler.EventBus;
import cpw.mods.fml.relauncher.Side;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import eu.avalanche7.paradigm.configs.CooldownConfigHandler;
import eu.avalanche7.paradigm.core.CommonRuntime;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.MinecraftEventSystem;
import eu.avalanche7.paradigm.utils.TaskScheduler;
import eu.avalanche7.paradigm.utils.TelemetryReporter;
import eu.avalanche7.paradigm.utils.UpdateChecker;

class ParadigmLifecycleTest {
    @TempDir Path config;

    @Test void fullRuntimeCompositionUsesForgeLifecycleOrderingAndOneReporter() {
        var enabled = mock(ParadigmModule.class);
        var disabled = mock(ParadigmModule.class);
        var services = mock(Services.class);
        when(enabled.isEnabled(services)).thenReturn(true);
        when(services.isModuleActive(enabled)).thenReturn(true);
        doAnswer(call -> {
            var module = call.getArgument(0, ParadigmModule.class);
            if (module.isEnabled(services)) module.onEnable(services);
            return null;
        }).when(services).enableModule(any());
        doAnswer(call -> {
            ((ParadigmModule) call.getArgument(0)).onDisable(services);
            return null;
        }).when(services).disableModule(any());
        doAnswer(call -> {
            var module = (ParadigmModule) call.getArgument(0);
            module.onServerStopping(call.getArgument(1), services);
            module.onDisable(services);
            return null;
        }).when(services).stopModule(any(), any());
        when(services.getTaskScheduler()).thenReturn(mock(TaskScheduler.class));
        var runtime = new CommonRuntime.Runtime(List.of(enabled, disabled), services, null);
        var loader = mock(Loader.class);
        when(loader.getCallableCrashInformation()).thenReturn(mock(cpw.mods.fml.common.ICrashCallable.class));
        try (var loading = mockStatic(Loader.class)) {
            loading.when(Loader::instance).thenReturn(loader);
            var fml = mock(FMLCommonHandler.class);
            when(fml.bus()).thenReturn(mock(EventBus.class));
            var pre = mock(FMLPreInitializationEvent.class);
            when(pre.getSide()).thenReturn(Side.SERVER);
            when(pre.getModConfigurationDirectory()).thenReturn(config.toFile());
            try (var bootstrap = mockStatic(CommonRuntime.class);
                 var handler = mockStatic(FMLCommonHandler.class);
                 var updates = mockStatic(UpdateChecker.class);
                 var cooldowns = mockStatic(CooldownConfigHandler.class);
                 var events = mockConstruction(MinecraftEventSystem.class);
                 var reporters = mockConstruction(TelemetryReporter.class);
                 var imports = mockConstruction(eu.avalanche7.paradigm.modules.playtime.LegacyPlaytimeCommand.class)) {
                handler.when(FMLCommonHandler::instance).thenReturn(fml);
                bootstrap.when(() -> CommonRuntime.bootstrap(any(), any(), any())).thenAnswer(call -> {
                    when(services.getPlatformAdapter()).thenReturn(call.getArgument(2));
                    return runtime;
                });
                var mod = new Paradigm();
                mod.preInit(pre);
                assertEquals(runtime.modules(), Paradigm.getModules());
                assertSame(services, Paradigm.getServices());
                bootstrap.verify(() -> CommonRuntime.attachToApi(runtime, ModVersion.VALUE));
                var ordering = inOrder(enabled, disabled);
                ordering.verify(enabled).onLoad(eq(pre), eq(services), any());
                ordering.verify(disabled).onLoad(eq(pre), eq(services), any());
                ordering.verify(enabled).registerEventListeners(any(), eq(services));
                ordering.verify(disabled).registerEventListeners(any(), eq(services));
                mod.init(mock(FMLInitializationEvent.class));
                verify(services).enableModule(enabled);
                verify(enabled).onEnable(services);
                verify(disabled, never()).onEnable(any());
                updates.verify(() -> UpdateChecker.checkForUpdates(
                        eq(new UpdateChecker.UpdateConfig("s4i32SJd", "paradigm",
                                "https://raw.githubusercontent.com/Avalanche7CZ/Paradigm/main/version.txt")),
                        eq(ModVersion.VALUE), eq("1.7.10"), eq("forge"), any()));
                var starting = mock(FMLServerStartingEvent.class);
                mod.starting(starting);
                assertTrue(imports.constructed().isEmpty()); // Importer belongs to the shared Playtime module.
                assertEquals(1, reporters.constructed().size());
                verify(reporters.constructed().get(0)).start();
                verify(enabled).onServerStarting(starting, services);
                verify(enabled).registerCommands(isNull(), isNull(), eq(services));
                verify(disabled, never()).onServerStarting(any(), any());
                verify(disabled, never()).registerCommands(any(), any(), any());
                mod.started(mock(FMLServerStartedEvent.class));
                verify(services).refreshDiscoveredCommandPermissions();
                var stopping = mock(FMLServerStoppingEvent.class);
                mod.stopping(stopping);
                var shutdown = inOrder(enabled, reporters.constructed().get(0), services);
                shutdown.verify(enabled).onServerStopping(stopping, services);
                shutdown.verify(enabled).onDisable(services);
                shutdown.verify(reporters.constructed().get(0)).stop();
                shutdown.verify(services).shutdown();
                var stopped = mock(FMLServerStoppedEvent.class);
                mod.stopped(stopped);
                verify(enabled).onServerStopped(stopped, services);
                verify(disabled, never()).onServerStopped(any(), any());
                verify(events.constructed().get(0)).register();
                verify(events.constructed().get(0)).unregister();
            }
        }
    }

    @Test void telemetryHonorsDisabledConfigurationAndCancelsItsSingleTask() {
        var logger = mock(eu.avalanche7.paradigm.utils.DebugLogger.class);
        var services = mock(Services.class);
        var scheduler = mock(TaskScheduler.class);
        when(services.getDebugLogger()).thenReturn(logger);
        doAnswer(call -> {
            var module = (ParadigmModule) call.getArgument(0);
            module.onServerStopping(call.getArgument(1), services);
            module.onDisable(services);
            return null;
        }).when(services).stopModule(any(), any());
        when(services.getTaskScheduler()).thenReturn(scheduler);
        eu.avalanche7.paradigm.configs.MainConfigHandler.init(
                new eu.avalanche7.paradigm.platform.ForgeConfig(config), logger);
        var settings = eu.avalanche7.paradigm.configs.MainConfigHandler.getConfig();
        settings.telemetryEnable.value = false;
        var reporter = new TelemetryReporter(services);
        reporter.start();
        verifyNoInteractions(scheduler);
        settings.telemetryEnable.value = true;
        settings.telemetryServerId.value = "fixture-id";
        var task = mock(java.util.concurrent.ScheduledFuture.class);
        when(scheduler.scheduleAtFixedRateRaw(any(), anyLong(), anyLong(), any())).thenReturn(task);
        reporter.start();
        reporter.start();
        verify(scheduler).scheduleAtFixedRateRaw(any(), eq(10L), eq(300L), eq(java.util.concurrent.TimeUnit.SECONDS));
        reporter.stop();
        verify(task).cancel(false);
    }

    @Test void clientSideDoesNotBootstrapOrRunServerServices() {
        try (var bootstrap = mockStatic(CommonRuntime.class);
             var updates = mockStatic(UpdateChecker.class);
             var reporters = mockConstruction(TelemetryReporter.class)) {
            var event = mock(FMLPreInitializationEvent.class);
            when(event.getSide()).thenReturn(Side.CLIENT);
            var mod = new Paradigm();
            mod.preInit(event);
            mod.init(mock(FMLInitializationEvent.class));
            mod.starting(mock(FMLServerStartingEvent.class));
            mod.started(mock(FMLServerStartedEvent.class));
            mod.stopping(mock(FMLServerStoppingEvent.class));
            mod.stopped(mock(FMLServerStoppedEvent.class));
            bootstrap.verifyNoInteractions();
            updates.verifyNoInteractions();
            assertTrue(reporters.constructed().isEmpty());
        }
    }
}
