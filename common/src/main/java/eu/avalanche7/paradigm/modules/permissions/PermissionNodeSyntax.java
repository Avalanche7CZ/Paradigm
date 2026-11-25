package eu.avalanche7.paradigm.modules.permissions;

import java.util.regex.Pattern;

public final class PermissionNodeSyntax {
    private static final Pattern NODE = Pattern.compile("[A-Za-z0-9_.*:$-]+");

    private PermissionNodeSyntax() {}

    public static boolean validAssignment(String node) {
        return node != null && node.length() <= 255 && NODE.matcher(node).matches();
    }

    public static boolean validDiscoveredLiteral(String node) {
        return node != null && node.indexOf('*') < 0 && NODE.matcher(node).matches();
    }

    public static boolean validConfigured(String node) {
        return node != null && node.length() <= 128 && node.indexOf(':') < 0 && NODE.matcher(node).matches();
    }
}
