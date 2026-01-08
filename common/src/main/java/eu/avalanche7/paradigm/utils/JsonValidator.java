package eu.avalanche7.paradigm.utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class JsonValidator {

    private static final Logger LOGGER = LoggerFactory.getLogger(JsonValidator.class);
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final DebugLogger debugLogger;

    public JsonValidator(DebugLogger debugLogger) {
        this.debugLogger = debugLogger;
    }

    public ValidationResult validateAndFix(String jsonString) {
        if (jsonString == null || jsonString.trim().isEmpty()) {
            return new ValidationResult(false, "JSON string is null or empty", jsonString != null ? jsonString : "", List.of("Input is null or empty"));
        }

        List<String> issues = new ArrayList<>();
        String originalJson = jsonString.trim();

        try {
            JsonElement element = JsonParser.parseString(originalJson);
            return new ValidationResult(true, "JSON is valid", originalJson, issues);
        } catch (JsonParseException e) {
            LOGGER.warn("[Paradigm] JSON syntax error detected: {}", e.getMessage());
            issues.add("Syntax error: " + e.getMessage());

            String fixedJson = attemptJsonFixes(originalJson, issues);

            try {
                JsonParser.parseString(fixedJson);
                LOGGER.info("[Paradigm] JSON syntax has been automatically fixed");
                return new ValidationResult(true, "JSON syntax fixed", fixedJson, issues);
            } catch (JsonParseException fixError) {
                LOGGER.error("[Paradigm] Unable to automatically fix JSON syntax errors: {}", fixError.getMessage());
                issues.add("Failed to fix: " + fixError.getMessage());
                return new ValidationResult(false, "Unable to fix JSON syntax errors", originalJson, issues);
            }
        }
    }

    private String attemptJsonFixes(String json, List<String> issues) {
        String result = json;
        result = removeComments(result, issues);
        result = fixQuotes(result, issues);
        result = fixUnquotedProperties(result, issues);
        result = fixTrailingCommas(result, issues);
        result = fixMissingCommas(result, issues);
        result = fixMissingBrackets(result, issues);

        return result;
    }
    /**
     * Remove comments from JSON (both single-line and multi-line)
     */
    private String removeComments(String json, List<String> issues) {
        if (!json.contains("//") && !json.contains("/*")) {
            return json;
        }

        StringBuilder result = new StringBuilder();
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            char next = (i + 1 < json.length()) ? json.charAt(i + 1) : 0;

            if (escaped) {
                result.append(c);
                escaped = false;
                continue;
            }

            if (c == '\\' && inString) {
                result.append(c);
                escaped = true;
                continue;
            }

            if (c == '"') {
                inString = !inString;
                result.append(c);
                continue;
            }

            if (!inString) {
                if (c == '/' && next == '/') {
                    while (i < json.length() && json.charAt(i) != '\n') {
                        i++;
                    }
                    if (i < json.length()) result.append('\n');
                    issues.add("Removed single-line comments");
                    continue;
                }
                if (c == '/' && next == '*') {
                    i += 2; // Skip /*
                    while (i + 1 < json.length() && !(json.charAt(i) == '*' && json.charAt(i + 1) == '/')) {
                        i++;
                    }
                    if (i + 1 < json.length()) i += 2; // Skip */
                    issues.add("Removed multi-line comments");
                    continue;
                }
            }

            result.append(c);
        }

        return result.toString();
    }

    /**
     * Fix single quotes to double quotes (but only for JSON strings, not content)
     */
    private String fixQuotes(String json, List<String> issues) {
        StringBuilder result = new StringBuilder();
        boolean inDoubleString = false;
        boolean escaped = false;
        boolean foundSingleQuotes = false;

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);

            if (escaped) {
                result.append(c);
                escaped = false;
                continue;
            }

            if (c == '\\') {
                result.append(c);
                escaped = true;
                continue;
            }

            if (c == '"') {
                inDoubleString = !inDoubleString;
                result.append(c);
            } else if (c == '\'' && !inDoubleString) {
                result.append('"');
                foundSingleQuotes = true;
            } else {
                result.append(c);
            }
        }

        if (foundSingleQuotes) {
            issues.add("Fixed single quotes to double quotes");
        }

        return result.toString();
    }

    /**
     * Add quotes around unquoted property names
     */
    private String fixUnquotedProperties(String json, List<String> issues) {
        StringBuilder result = new StringBuilder();
        String[] lines = json.split("\n");
        boolean foundIssues = false;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.matches("^\\s*[a-zA-Z_][a-zA-Z0-9_]*\\s*:.*") &&
                    !trimmed.matches("^\\s*\".*\"\\s*:.*")) {
                int colonIndex = trimmed.indexOf(':');
                if (colonIndex > 0) {
                    String propertyName = trimmed.substring(0, colonIndex).trim();
                    String rest = trimmed.substring(colonIndex);
                    String indent = line.substring(0, line.indexOf(line.trim()));
                    result.append(indent).append('"').append(propertyName).append('"').append(rest).append('\n');
                    foundIssues = true;
                } else {
                    result.append(line).append('\n');
                }
            } else {
                result.append(line).append('\n');
            }
        }

        if (foundIssues) {
            issues.add("Added quotes around property names");
        }

        return result.toString().trim();
    }

    /**
     * Remove trailing commas
     */
    private String fixTrailingCommas(String json, List<String> issues) {
        String[] lines = json.split("\n");
        StringBuilder result = new StringBuilder();
        boolean foundIssues = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            String nextLine = (i + 1 < lines.length) ? lines[i + 1].trim() : "";
            if (trimmed.endsWith(",") && (nextLine.startsWith("}") || nextLine.startsWith("]"))) {
                int commaIndex = line.lastIndexOf(',');
                result.append(line.substring(0, commaIndex)).append('\n');
                foundIssues = true;
            } else {
                result.append(line).append('\n');
            }
        }

        if (foundIssues) {
            issues.add("Removed trailing commas");
        }

        return result.toString().trim();
    }

    /**
     * Add missing commas between array elements and object properties
     */
    private String fixMissingCommas(String json, List<String> issues) {
        String[] lines = json.split("\n");
        StringBuilder result = new StringBuilder();
        boolean foundIssues = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            String nextLine = (i + 1 < lines.length) ? lines[i + 1].trim() : "";
            if (trimmed.endsWith("\"") && !trimmed.contains(":") &&
                    nextLine.startsWith("\"") && !nextLine.isEmpty() && !trimmed.endsWith("\",")) {
                result.append(line).append(',').append('\n');
                foundIssues = true;
            }
            else if (trimmed.endsWith("}") && nextLine.startsWith("\"") && !trimmed.endsWith("},")) {
                result.append(line).append(',').append('\n');
                foundIssues = true;
            } else {
                result.append(line).append('\n');
            }
        }

        if (foundIssues) {
            issues.add("Added missing commas");
        }

        return result.toString().trim();
    }

    /**
     * Fix missing brackets/braces
     */
    private String fixMissingBrackets(String json, List<String> issues) {
        int openBraces = 0, closeBraces = 0;
        int openBrackets = 0, closeBrackets = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);

            if (escaped) {
                escaped = false;
                continue;
            }

            if (c == '\\') {
                escaped = true;
                continue;
            }

            if (c == '"') {
                inString = !inString;
                continue;
            }

            if (!inString) {
                switch (c) {
                    case '{': openBraces++; break;
                    case '}': closeBraces++; break;
                    case '[': openBrackets++; break;
                    case ']': closeBrackets++; break;
                }
            }
        }

        StringBuilder result = new StringBuilder(json);
        boolean foundIssues = false;
        if (openBraces > closeBraces) {
            int missing = openBraces - closeBraces;
            for (int i = 0; i < missing; i++) {
                result.append('\n').append('}');
            }
            issues.add("Added " + missing + " missing closing brace(s)");
            foundIssues = true;
        }
        if (openBrackets > closeBrackets) {
            int missing = openBrackets - closeBrackets;
            for (int i = 0; i < missing; i++) {
                result.append('\n').append(']');
            }
            issues.add("Added " + missing + " missing closing bracket(s)");
            foundIssues = true;
        }

        return result.toString();
    }

    public boolean isValidJson(String jsonString) {
        try {
            JsonParser.parseString(jsonString);
            return true;
        } catch (JsonParseException e) {
            return false;
        }
    }

    public String formatJson(String jsonString) {
        try {
            JsonElement element = JsonParser.parseString(jsonString);
            return gson.toJson(element);
        } catch (JsonParseException e) {
            debugLogger.debugLog("Failed to format JSON: " + e.getMessage());
            return jsonString;
        }
    }

    public static class ValidationResult {
        private final boolean valid;
        private final String message;
        private final String fixedJson;
        private final List<String> issues;

        public ValidationResult(boolean valid, String message, String fixedJson, List<String> issues) {
            this.valid = valid;
            this.message = message;
            this.fixedJson = fixedJson;
            this.issues = new ArrayList<>(issues);
        }

        public boolean isValid() {
            return valid;
        }

        public String getMessage() {
            return message;
        }

        public String getFixedJson() {
            return fixedJson;
        }

        public List<String> getIssues() {
            return new ArrayList<>(issues);
        }

        public boolean hasIssues() {
            return !issues.isEmpty();
        }

        public String getIssuesSummary() {
            if (issues.isEmpty()) {
                return "No issues found";
            }
            return String.join(", ", issues);
        }
    }
}
