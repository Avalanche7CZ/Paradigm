package eu.avalanche7.paradigm.modules.dashboard;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class DashboardDiscordPreviewContractTest {

    @Test
    void discordMessageFormatsUseLiveDestinationAwarePreviews() throws Exception {
        String script = resource("dashboard/app.js");
        String css = resource("dashboard/style.css");

        assertTrue(script.contains("function renderDiscord()"));
        assertTrue(script.contains("function isDiscordMessageField(field)"));
        assertTrue(script.contains("function renderDiscordMessagePreview(panel, raw, samples)"));
        assertTrue(script.contains("function renderDiscordFieldPreview(panel, previewKey)"));
        assertTrue(script.contains("isMinecraftDiscordFormat(key)"));
        assertTrue(script.contains("renderMinecraftPreview(panel, raw, samples)"));
        assertTrue(script.contains("appendDiscordMarkdown(content, line)"));
        assertTrue(script.contains("panel.replaceChildren()"));
        assertTrue(css.contains(".minecraft-preview.discord-message-preview"));
    }

    private static String resource(String name) throws Exception {
        try (InputStream stream = DashboardDiscordPreviewContractTest.class.getClassLoader().getResourceAsStream(name)) {
            if (stream == null) throw new IllegalStateException("Missing resource " + name);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
