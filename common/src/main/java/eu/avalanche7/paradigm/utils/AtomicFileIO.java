package eu.avalanche7.paradigm.utils;

import java.io.IOException;
import java.io.Writer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

public final class AtomicFileIO {
    private AtomicFileIO() {
    }

    @FunctionalInterface
    public interface WriterAction {
        void write(Writer writer) throws IOException;
    }

    public static void writeUtf8Atomic(Path path, WriterAction action) throws IOException {
        if (path == null) {
            throw new IOException("Target path is null.");
        }
        if (action == null) {
            throw new IOException("Writer action is null.");
        }

        Path parent = path.toAbsolutePath().getParent();
        if (parent == null) {
            throw new IOException("Target path has no parent: " + path);
        }
        Files.createDirectories(parent);

        Path temporary = Files.createTempFile(parent, "." + path.getFileName() + ".", ".tmp");
        boolean moved = false;
        try {
            try (Writer writer = Files.newBufferedWriter(
                    temporary,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                action.write(writer);
                writer.flush();
            }

            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            } catch (IOException ignored) {
            }

            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    public static Path quarantine(Path path, String label) {
        return archive(path, label == null || label.isBlank() ? "corrupt" : label.trim());
    }

    public static Path archive(Path path, String label) {
        if (path == null || !Files.exists(path)) {
            return null;
        }
        String safeLabel = label == null || label.isBlank() ? "archived" : label.replaceAll("[^A-Za-z0-9._-]", "_");
        Path target = path.resolveSibling(path.getFileName() + "." + safeLabel + "-" + System.currentTimeMillis());
        try {
            try {
                return Files.move(path, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                return Files.move(path, target);
            }
        } catch (IOException ignored) {
            return null;
        }
    }
}
