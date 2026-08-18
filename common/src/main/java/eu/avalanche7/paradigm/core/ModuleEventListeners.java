package eu.avalanche7.paradigm.core;

import eu.avalanche7.paradigm.platform.Interfaces.IEventSystem;

public final class ModuleEventListeners {

    private ModuleEventListeners() {
    }

    public static IEventSystem raw(Services services) {
        return services != null && services.getPlatformAdapter() != null
                ? services.getPlatformAdapter().getEventSystem()
                : null;
    }

    public static IEventSystem gated(Services services, ParadigmModule module) {
        IEventSystem delegate = raw(services);
        if (delegate == null || module == null) {
            return delegate;
        }
        return new GatedEventSystem(delegate, services, module);
    }

    public static boolean isActive(Services services, ParadigmModule module) {
        if (module == null) {
            return false;
        }
        try {
            return module.isEnabled(services);
        } catch (RuntimeException | LinkageError unavailable) {
            return false;
        }
    }

    private record GatedEventSystem(IEventSystem delegate, Services services, ParadigmModule module)
            implements IEventSystem {

        @Override
        public void onPlayerChat(ChatEventListener listener) {
            delegate.onPlayerChat(event -> {
                if (active()) listener.onPlayerChat(event);
            });
        }

        @Override
        public void onPlayerJoin(PlayerJoinEventListener listener) {
            delegate.onPlayerJoin(event -> {
                if (active()) listener.onPlayerJoin(event);
            });
        }

        @Override
        public void onPlayerLeave(PlayerLeaveEventListener listener) {
            delegate.onPlayerLeave(event -> {
                if (active()) listener.onPlayerLeave(event);
            });
        }

        @Override
        public void onPlayerDeath(PlayerDeathEventListener listener) {
            delegate.onPlayerDeath(event -> {
                if (active()) listener.onPlayerDeath(event);
            });
        }

        @Override
        public void onPlayerCommand(PlayerCommandEventListener listener) {
            delegate.onPlayerCommand(event -> {
                if (active()) listener.onPlayerCommand(event);
            });
        }

        @Override
        public void onPlayerAdvancement(PlayerAdvancementEventListener listener) {
            delegate.onPlayerAdvancement(event -> {
                if (active()) listener.onPlayerAdvancement(event);
            });
        }

        private boolean active() {
            return isActive(services, module);
        }
    }
}
