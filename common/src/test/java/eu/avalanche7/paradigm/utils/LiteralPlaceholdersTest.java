package eu.avalanche7.paradigm.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.Test;

class LiteralPlaceholdersTest {
    @Test
    void callerValuesCannotBecomeFormattingInstructions() {
        assertEquals("Hello \\<bold>Admin\\&c", LiteralPlaceholders.apply(
                "Hello {name}", Map.of("name", "<bold>Admin&c")));
    }
}
