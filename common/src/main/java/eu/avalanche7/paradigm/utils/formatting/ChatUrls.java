package eu.avalanche7.paradigm.utils.formatting;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;

public final class ChatUrls {
    public static final Pattern IN_TEXT = Pattern.compile("https?://[^\\s<>\"']+", Pattern.CASE_INSENSITIVE);

    private ChatUrls() {}

    public static URI httpUri(String value) {
        if (value == null) return null;
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            return ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                    && uri.getHost() != null && uri.getRawUserInfo() == null
                    && uri.getPort() <= 65535 ? uri : null;
        } catch (URISyntaxException invalid) {
            return null;
        }
    }
}
