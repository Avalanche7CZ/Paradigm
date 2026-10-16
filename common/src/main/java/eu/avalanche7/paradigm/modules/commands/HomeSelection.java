package eu.avalanche7.paradigm.modules.commands;

import java.util.List;
import java.util.Optional;

import eu.avalanche7.paradigm.storage.model.StoredHome;

public final class HomeSelection {
    private HomeSelection() {}

    public static Optional<StoredHome> defaultHome(List<StoredHome> homes) {
        if (homes == null || homes.size() != 1) return Optional.empty();
        StoredHome home = homes.get(0);
        return home != null && home.location() != null ? Optional.of(home) : Optional.empty();
    }
}
