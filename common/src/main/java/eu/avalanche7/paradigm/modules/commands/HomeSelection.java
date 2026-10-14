package eu.avalanche7.paradigm.modules.commands;

import java.util.List;
import java.util.Optional;

import eu.avalanche7.paradigm.storage.model.StoredHome;

public final class HomeSelection {
    private HomeSelection() {}

    public static Optional<StoredHome> defaultHome(List<StoredHome> homes) {
        if (homes == null || homes.stream().anyMatch(home -> home == null || home.location() == null)) return Optional.empty();
        List<StoredHome> available = List.copyOf(homes);
        List<StoredHome> named = available.stream().filter(home -> "home".equalsIgnoreCase(home.name())).toList();
        if (named.size() == 1) return Optional.of(named.get(0));
        if (named.isEmpty() && available.size() == 1) return Optional.of(available.get(0));
        return Optional.empty();
    }
}
