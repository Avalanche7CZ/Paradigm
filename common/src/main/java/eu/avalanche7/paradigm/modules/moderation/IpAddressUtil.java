package eu.avalanche7.paradigm.modules.moderation;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class IpAddressUtil {
    private IpAddressUtil() {
    }

    public static String canonicalize(String input) {
        String value = input == null ? "" : input.trim();
        if (value.startsWith("/")) value = value.substring(1);
        if (value.startsWith("[")) {
            int close = value.indexOf(']');
            if (close < 0) throw new IllegalArgumentException("Invalid IP address.");
            value = value.substring(1, close);
        } else if (value.matches("(?:\\d{1,3}\\.){3}\\d{1,3}:\\d+")) {
            value = value.substring(0, value.lastIndexOf(':'));
        }
        int zone = value.indexOf('%');
        if (zone >= 0) value = value.substring(0, zone);
        if (!value.matches("[0-9A-Fa-f:.]+")) throw new IllegalArgumentException("Only literal IPv4 or IPv6 addresses are supported.");
        try {
            InetAddress address = InetAddress.getByName(value);
            byte[] bytes = address.getAddress();
            if (bytes.length == 16 && isIpv4Mapped(bytes)) {
                return (bytes[12] & 255) + "." + (bytes[13] & 255) + "." + (bytes[14] & 255) + "." + (bytes[15] & 255);
            }
            String result = address.getHostAddress();
            int resultZone = result.indexOf('%');
            return resultZone >= 0 ? result.substring(0, resultZone) : result;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid IP address.");
        }
    }

    public static String hash(String canonicalAddress) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonicalAddress.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable.", e);
        }
    }

    public static String mask(String canonicalAddress) {
        if (canonicalAddress == null) return null;
        if (canonicalAddress.contains(".")) {
            String[] parts = canonicalAddress.split("\\.");
            return parts.length == 4 ? parts[0] + "." + parts[1] + ".x.x" : "masked";
        }
        int separator = canonicalAddress.indexOf(':');
        return separator > 0 ? canonicalAddress.substring(0, separator) + ":…" : "masked";
    }

    public static String maskHash(String hash) {
        return hash == null || hash.length() < 12 ? "masked" : hash.substring(0, 12) + "…";
    }

    private static boolean isIpv4Mapped(byte[] bytes) {
        for (int i = 0; i < 10; i++) if (bytes[i] != 0) return false;
        return bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
    }
}
