package eu.avalanche7.paradigm.storage.runtime;

import java.nio.file.Path;

public record RuntimeLibraryDownloadResult(
        RuntimeLibrary library,
        State state,
        Path path,
        String message
) {
    public enum State {
        NOT_NEEDED("not_needed"),
        CACHED("cached"),
        DOWNLOADED("downloaded"),
        MISSING("missing"),
        CHECKSUM_FAILED("checksum_failed"),
        FAILED("failed"),
        LOADED("loaded");

        private final String key;

        State(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }
    }
}
