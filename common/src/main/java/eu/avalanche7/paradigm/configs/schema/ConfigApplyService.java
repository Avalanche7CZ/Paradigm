package eu.avalanche7.paradigm.configs.schema;

import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import eu.avalanche7.paradigm.ParadigmAPI;
import eu.avalanche7.paradigm.configs.BaseConfigHandler;
import eu.avalanche7.paradigm.configs.ConfigSection;
import eu.avalanche7.paradigm.configs.CooldownConfigHandler;
import eu.avalanche7.paradigm.configs.DiscordConfigHandler;
import eu.avalanche7.paradigm.configs.MainConfigHandler;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.Announcements;
import eu.avalanche7.paradigm.modules.CommandManager;
import eu.avalanche7.paradigm.modules.Restart;
import eu.avalanche7.paradigm.modules.commands.Reload;
import eu.avalanche7.paradigm.modules.dashboard.LocalDashboardModule;
import eu.avalanche7.paradigm.modules.menus.Menus;
import eu.avalanche7.paradigm.modules.tab.Tablist;
import eu.avalanche7.paradigm.modules.tickets.TicketsModule;
import eu.avalanche7.paradigm.utils.ServerStatusIconCache;

public final class ConfigApplyService {
    private final Services services;
    private final Set<ConfigSection> failedEffects = EnumSet.noneOf(ConfigSection.class);

    public ConfigApplyService(Services services) {
        this.services = services;
    }

    public CompletableFuture<ConfigValidationResult> reload(Collection<ConfigSection> sections) {
        return reload(sections, () -> {});
    }

    public CompletableFuture<ConfigValidationResult> reload(Collection<ConfigSection> sections, Runnable authorize) {
        return reload(sections, authorize, java.util.concurrent.ForkJoinPool.commonPool());
    }

    public CompletableFuture<ConfigValidationResult> reload(Collection<ConfigSection> sections, Runnable authorize,
            java.util.concurrent.Executor executor) {
        return CompletableFuture.supplyAsync(() -> ConfigPatchService.serialized(() -> {
            requireRunning();
            authorize.run();
            return buildReload(sections);
        }), executor);
    }

    public ConfigValidationResult reloadNow(Collection<ConfigSection> sections) {
        return ConfigPatchService.serialized(() -> {
            requireRunning();
            return buildReload(sections);
        });
    }

    private ConfigValidationResult buildReload(Collection<ConfigSection> sections) {
        var result = new ConfigValidationResult();
        var publications = new EnumMap<ConfigSection, Runnable>(ConfigSection.class);
        var effects = EnumSet.noneOf(ConfigSection.class);
        for (ConfigSection section : sections) {
            attempt(section, result, () -> {
                switch (section) {
                    case COMMANDS -> {
                        var store = services.getCommandToggleStore();
                        var candidate = store.readReload();
                        publications.put(section, () -> store.publish(candidate));
                    }
                    case COOLDOWNS -> {
                        var candidate = CooldownConfigHandler.handler().read();
                        publications.put(section, () -> CooldownConfigHandler.publishTimings(candidate));
                    }
                    case CUSTOMCOMMANDS -> prepareCustomCommands(publications, result);
                    case MENUS -> {
                        Menus menus = Menus.current();
                        if (menus == null) throw new IllegalStateException("Menus are unavailable.");
                        var candidate = menus.readSettings();
                        publications.put(section, () -> {
                            var problems = menus.applySettings(candidate);
                            problems.forEach(problem -> result.reject(section.key(), problem));
                        });
                    }
                    case HOLOGRAMS -> publications.put(section, services.getHologramService().prepareReload());
                    case DASHBOARD -> {
                        LocalDashboardModule dashboard = LocalDashboardModule.current();
                        if (dashboard == null) throw new IllegalStateException("Dashboard is unavailable.");
                        dashboard.reloadSettings();
                    }
                    default -> {
                        var candidate = section.handler().loadCandidate();
                        ConfigPatchService.validateFile(section, candidate.value());
                        if (stage(section, candidate, publications)) {
                            result.pending(section.key(), ConfigReloadBehavior.RESTART_REQUIRED);
                            result.warn(section.key() + " contains changes that require a server restart.");
                        }
                    }
                }
                effects.add(section);
                result.accept(section.key());
            });
        }
        apply(publications, effects, result, true);
        return result;
    }

    public CompletableFuture<ConfigValidationResult> setCommandToggle(String command, boolean enabled) {
        return CompletableFuture.supplyAsync(() -> ConfigPatchService.serialized(() -> {
            requireRunning();
            var result = new ConfigValidationResult();
            var publications = new EnumMap<ConfigSection, Runnable>(ConfigSection.class);
            var effects = EnumSet.noneOf(ConfigSection.class);
            attempt(ConfigSection.COMMANDS, result, () -> {
                if (saveCommandToggles(Map.of(command, enabled), publications)) effects.add(ConfigSection.COMMANDS);
                result.accept("commands." + command);
            });
            apply(publications, effects, result);
            return result;
        }));
    }

    public ConfigValidationResult saveDiscordToken(String token) {
        return ConfigPatchService.serialized(() -> {
            requireRunning();
            var result = new ConfigValidationResult();
            var publications = new EnumMap<ConfigSection, Runnable>(ConfigSection.class);
            var effects = EnumSet.noneOf(ConfigSection.class);
            attempt(ConfigSection.DISCORD, result, () -> {
                var candidate = DiscordConfigHandler.handler().edit();
                candidate.value().botToken.value = token;
                if (save(ConfigSection.DISCORD, candidate, publications)) effects.add(ConfigSection.DISCORD);
                result.accept("discord.botToken");
            });
            apply(publications, effects, result);
            return result;
        });
    }

    boolean save(ConfigSection section, BaseConfigHandler.Candidate<?> candidate, Map<ConfigSection, Runnable> publications) {
        if (section == ConfigSection.COOLDOWNS) {
            var timings = (CooldownConfigHandler.Config) candidate.value();
            return CooldownConfigHandler.saveTimings(timings);
        }
        boolean changed = candidate.changed();
        if (section == ConfigSection.MAIN) {
            var main = MainConfigHandler.handler();
            var value = (MainConfigHandler.Config) candidate.value();
            if (!main.settings(value).equals(main.settings(main.reviewed()))) {
                candidate.save();
                stage(section, candidate, publications);
            }
            return !main.settings(main.active()).equals(main.settings(main.forRuntime(value)));
        }
        if (changed) {
            candidate.save();
            stage(section, candidate, publications);
        }
        return changed;
    }

    private boolean stage(ConfigSection section, BaseConfigHandler.Candidate<?> candidate, Map<ConfigSection, Runnable> publications) {
        var publish = candidate.publication();
        if (section != ConfigSection.MAIN) {
            publications.put(section, publish);
            return false;
        }
        var main = MainConfigHandler.handler();
        var saved = (MainConfigHandler.Config) candidate.value();
        publications.put(section, () -> {
            publish.run();
            main.recordSaved(saved);
        });
        return main.hasPendingRestart(saved);
    }

    boolean saveCommandToggles(Map<String, Boolean> changes, Map<ConfigSection, Runnable> publications) {
        var store = services.getCommandToggleStore();
        var before = store.listStates();
        var candidate = new java.util.LinkedHashMap<>(before);
        candidate.putAll(changes);
        if (candidate.equals(before)) return false;
        store.save(candidate);
        publications.put(ConfigSection.COMMANDS, () -> store.publish(candidate));
        return true;
    }

    void apply(Map<ConfigSection, Runnable> publications, Set<ConfigSection> effects, ConfigValidationResult result) {
        apply(publications, effects, result, false);
    }

    private void apply(Map<ConfigSection, Runnable> publications, Set<ConfigSection> effects,
            ConfigValidationResult result, boolean explicitReload) {
        for (ConfigSection section : failedEffects) {
            String prefix = section.key() + ".";
            if (result.accepted().stream().anyMatch(key -> key.equals(section.key()) || key.startsWith(prefix))) {
                effects.add(section);
            }
        }
        if (publications.isEmpty() && effects.isEmpty()) return;

        Set<ConfigSection> failed = EnumSet.noneOf(ConfigSection.class);
        var previousMain = effects.contains(ConfigSection.MAIN) ? MainConfigHandler.getConfig() : null;
        var previousCommands = effects.contains(ConfigSection.COMMANDS) ? services.getCommandToggleStore().listStates() : Map.<String, Boolean>of();
        ConfigSection commandTree;
        try {
            commandTree = onServer(() -> {
                for (var publication : publications.entrySet()) {
                    if (!attempt(publication.getKey(), result, publication.getValue())) failed.add(publication.getKey());
                }
                effects.removeAll(failed);
                return applyMinecraftEffects(effects, result, failed, previousMain, previousCommands, explicitReload);
            }).join();
        } catch (CompletionException failure) {
            result.reject("<runtime>", message(failure));
            failedEffects.addAll(effects);
            return;
        }

        failedEffects.addAll(failed);
        for (ConfigSection section : effects) {
            int errors = result.rejected().size();
            attempt(section, result, () -> {
                requireRunning();
                switch (section) {
                    case MAIN -> {
                        var main = MainConfigHandler.getConfig();
                        if (services.getLang() != null && (explicitReload || failedEffects.contains(section)
                                || !java.util.Objects.equals(main.defaultLanguage.value, previousMain.defaultLanguage.value))) {
                            services.getLang().initializeLanguage();
                        }
                        if (!effects.contains(ConfigSection.CUSTOMCOMMANDS)
                                && (explicitReload || failedEffects.contains(section)
                                || !java.util.Objects.equals(main.internalPermissionsEnable.value, previousMain.internalPermissionsEnable.value))) {
                            services.getPermissionsHandler().refreshInternalPermissions();
                        }
                    }
                    case CUSTOMCOMMANDS -> services.getPermissionsHandler().refreshInternalPermissions();
                    case MOTD -> ServerStatusIconCache.reload(services.getPlatformAdapter().getConfig(), services.getLogger());
                    case MODERATION -> services.getPunishmentService().refreshForConfig();
                    case DISCORD -> {
                        services.getDiscordService().applyConfiguration();
                        result.warn("Discord settings applied locally. See Discord status for the gateway connection.");
                    }
                    default -> {}
                }
            });
            if (result.rejected().size() > errors) failed.add(section);
        }
        if (commandTree != null && !attempt(commandTree, result, () -> onServer(() -> {
            if (effects.contains(ConfigSection.CUSTOMCOMMANDS)) services.getPermissionsHandler().registerLuckPermsPermissions();
            services.getPlatformAdapter().refreshAllPlayerCommandTrees();
            return null;
        }).join())) {
            failed.add(commandTree);
            if (effects.contains(ConfigSection.COMMANDS)) failed.add(ConfigSection.COMMANDS);
            if (effects.contains(ConfigSection.CUSTOMCOMMANDS)) failed.add(ConfigSection.CUSTOMCOMMANDS);
        }
        for (ConfigSection section : effects) {
            if (failed.contains(section)) {
                failedEffects.add(section);
            } else {
                failedEffects.remove(section);
                result.applied(section.key());
            }
        }
    }

    private ConfigSection applyMinecraftEffects(Set<ConfigSection> effects, ConfigValidationResult result, Set<ConfigSection> failed,
            MainConfigHandler.Config previousMain, Map<String, Boolean> previousCommands, boolean explicitReload) {
        var modules = ParadigmAPI.getModules();
        var transitioned = new HashSet<ParadigmModule>();
        var commandModules = new HashSet<ParadigmModule>();
        boolean mainChanged = effects.contains(ConfigSection.MAIN);
        boolean togglesChanged = effects.contains(ConfigSection.COMMANDS);
        if (mainChanged) {
            var current = MainConfigHandler.getConfig();
            if (explicitReload || failedEffects.contains(ConfigSection.MAIN)
                    || !java.util.Objects.equals(previousMain.forceCommandPriorityEnable.value, current.forceCommandPriorityEnable.value)) {
                commandModules.addAll(modules);
            }
        }
        if (togglesChanged) {
            var commands = services.getCommandToggleStore().listStates();
            boolean allCommands = explicitReload || failedEffects.contains(ConfigSection.COMMANDS);
            for (var module : modules) {
                if (eu.avalanche7.paradigm.modules.commands.shared.CommandCatalog.entriesForModule(module).stream()
                        .anyMatch(entry -> allCommands || !java.util.Objects.equals(commands.get(entry.id()), previousCommands.get(entry.id())))) {
                    commandModules.add(module);
                }
            }
        }
        if (mainChanged || effects.contains(ConfigSection.TABLIST)
                || effects.contains(ConfigSection.AFK) || effects.contains(ConfigSection.TICKETS)) {
            for (ParadigmModule module : ParadigmAPI.getModules()) {
                ConfigSection section = switch (module.getName()) {
                    case "Tablist" -> ConfigSection.TABLIST;
                    case "Afk" -> ConfigSection.AFK;
                    case "Tickets" -> ConfigSection.TICKETS;
                    default -> ConfigSection.MAIN;
                };
                if (!effects.contains(section)) continue;
                int errors = result.rejected().size();
                attempt(section, result, () -> {
                    if (services.reconcileModule(module)) transitioned.add(module);
                });
                if (result.rejected().size() > errors) failed.add(section);
            }
        }
        for (ConfigSection section : effects) {
            int errors = result.rejected().size();
            attempt(section, result, () -> {
                for (ParadigmModule module : ParadigmAPI.getModules()) {
                    if (transitioned.contains(module) || !services.isModuleActive(module)) continue;
                    if (section == ConfigSection.ANNOUNCEMENTS && module instanceof Announcements announcements) announcements.rescheduleAnnouncements();
                    if (section == ConfigSection.RESTART && module instanceof Restart restart) restart.rescheduleNextRestart(services);
                    if (section == ConfigSection.TABLIST && module instanceof Tablist tablist) tablist.applySettings();
                    if (section == ConfigSection.TICKETS && module instanceof TicketsModule tickets) tickets.reloadTasks();
                    if (section == ConfigSection.AFK && module instanceof eu.avalanche7.paradigm.modules.afk.AfkModule) services.getAfkService().restart();
                }
            });
            if (result.rejected().size() > errors) failed.add(section);
        }
        for (var module : transitioned) {
            if (!eu.avalanche7.paradigm.modules.commands.shared.CommandCatalog.entriesForModule(module).isEmpty()) commandModules.add(module);
        }
        boolean customReload = effects.contains(ConfigSection.CUSTOMCOMMANDS);
        boolean permissionsChanged = mainChanged && (explicitReload
                || !java.util.Objects.equals(previousMain.internalPermissionsEnable.value, MainConfigHandler.getConfig().internalPermissionsEnable.value)
                || !java.util.Objects.equals(previousMain.externalCommandPermissionsEnable.value, MainConfigHandler.getConfig().externalCommandPermissionsEnable.value)
                || !java.util.Objects.equals(previousMain.externalCommandPermissionMode.value, MainConfigHandler.getConfig().externalCommandPermissionMode.value));
        if (!commandModules.isEmpty() || customReload || permissionsChanged) {
            ConfigSection section = mainChanged ? ConfigSection.MAIN
                    : togglesChanged ? ConfigSection.COMMANDS
                    : customReload ? ConfigSection.CUSTOMCOMMANDS : effects.iterator().next();
            int errors = result.rejected().size();
            attempt(section, result, () -> {
                boolean rebuilt = !commandModules.isEmpty() && Reload.rebuildCommands(services, commandModules);
                if (customReload && !rebuilt && commandModules.stream().noneMatch(CommandManager.class::isInstance)) {
                    CommandManager.registerReloadedCommands(services);
                }
            });
            if (result.rejected().size() > errors) {
                failed.add(section);
                if (effects.contains(ConfigSection.COMMANDS)) failed.add(ConfigSection.COMMANDS);
                if (effects.contains(ConfigSection.CUSTOMCOMMANDS)) failed.add(ConfigSection.CUSTOMCOMMANDS);
            }
            return section;
        }
        return null;
    }

    private void prepareCustomCommands(Map<ConfigSection, Runnable> publications, ConfigValidationResult result) {
        var candidate = services.getCmConfig().readCommands();
        candidate.errors().forEach(error -> result.reject(ConfigSection.CUSTOMCOMMANDS.key(), error));
        publications.put(ConfigSection.CUSTOMCOMMANDS, () -> services.getCmConfig().publishCommands(candidate));
    }

    public <T> CompletableFuture<T> onServer(Supplier<T> action) {
        var result = new CompletableFuture<T>();
        var pending = new AtomicBoolean(true);
        CompletableFuture.delayedExecutor(15, TimeUnit.SECONDS).execute(() -> {
            if (pending.compareAndSet(true, false)) {
                result.completeExceptionally(new IllegalStateException("Minecraft server did not accept the settings update."));
            }
        });
        try {
            services.getPlatformAdapter().executeOnServerThread(() -> {
                if (!pending.compareAndSet(true, false)) return;
                try {
                    requireRunning();
                    result.complete(action.get());
                } catch (RuntimeException | Error failure) {
                    result.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException failure) {
            pending.set(false);
            result.completeExceptionally(failure);
        }
        return result;
    }

    public void requireRunning() {
        if (services.isStopping()) throw new IllegalStateException("Server is stopping.");
    }

    private boolean attempt(ConfigSection section, ConfigValidationResult result, Runnable step) {
        try {
            step.run();
            return true;
        } catch (RuntimeException failure) {
            result.reject(section.key(), message(failure));
            if (services.getLogger() != null) services.getLogger().warn("Failed to apply settings for {}", section.key(), failure);
            return false;
        }
    }

    public static String message(Throwable failure) {
        while (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause();
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }
}
