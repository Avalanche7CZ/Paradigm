package eu.avalanche7.paradigm.storage.runtime;

import java.util.Locale;

public record RuntimeLibrary(
        String id,
        String group,
        String artifact,
        String version,
        String primaryClass,
        String sha256
) {
    public static final RuntimeLibrary SQLITE = new RuntimeLibrary(
            "sqlite",
            "org.xerial",
            "sqlite-jdbc",
            "3.46.1.3",
            "org.sqlite.JDBC",
            "4a4832720a65eaf7f4d6fd7ede52087b994dc5633c076f9e994dc0c8b4b0b4fa"
    );

    public static final RuntimeLibrary MARIADB = new RuntimeLibrary(
            "mysql",
            "org.mariadb.jdbc",
            "mariadb-java-client",
            "3.4.1",
            "org.mariadb.jdbc.Driver",
            "f60e4b282f1f4bdb74f0a26436ba7078a5e480b6f6702f6a7b45d9ba5e604a24"
    );

    public String fileName() {
        return artifact + "-" + version + ".jar";
    }

    public String mavenPath() {
        return group.replace('.', '/') + "/" + artifact + "/" + version + "/" + fileName();
    }

    public static RuntimeLibrary forDialect(String dialectName) {
        String normalized = dialectName != null ? dialectName.trim().toLowerCase(Locale.ROOT) : "";
        if ("sqlite".equals(normalized)) {
            return SQLITE;
        }
        if ("mysql".equals(normalized) || "mariadb".equals(normalized)) {
            return MARIADB;
        }
        throw new RuntimeLibraryException("No runtime JDBC library is known for SQL dialect: " + dialectName);
    }
}
