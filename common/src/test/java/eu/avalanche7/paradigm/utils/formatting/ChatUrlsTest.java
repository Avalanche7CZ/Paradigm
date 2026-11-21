package eu.avalanche7.paradigm.utils.formatting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ChatUrlsTest {
    @Test
    void signedUrlsKeepTheirOriginalEscapingAndQueryParameters() {
        String url = "HTTPS://cdn.example/file%20name.png?expires=123&signature=a%2Bb%3D";
        assertEquals(url, ChatUrls.httpUri(url).toString());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"https:relative", "https:///file", "https://[broken/file", "https://host/file space",
            "https://host/%ZZ", "https://host:65536/file", "https://user@host/file", "file:///etc/passwd",
            "javascript:alert(1)", "//host/file"})
    void invalidOrNonWebTargetsAreRejected(String value) {
        assertNull(ChatUrls.httpUri(value));
    }
}
