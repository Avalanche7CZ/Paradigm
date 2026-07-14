package eu.avalanche7.paradigm.utils;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LiteralPlaceholdersTest {
    @Test
    void callerValuesCannotBecomeFormattingInstructions() {
        assertEquals("Hello \\<bold>Admin\\&c", LiteralPlaceholders.apply(
                "Hello {name}", Map.of("name", "<bold>Admin&c")));
    }
}
