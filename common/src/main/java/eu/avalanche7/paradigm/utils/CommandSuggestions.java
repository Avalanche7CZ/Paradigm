package eu.avalanche7.paradigm.utils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class CommandSuggestions {
    private static final int MAX_CHOICE_LENGTH = 100;
    private static final int MAX_ROOT_LENGTH = 24;
    private static final long SUGGESTION_TIMEOUT_MILLIS = 800L;

    private static final ConcurrentHashMap<Class<?>, Method> PARSE_METHODS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Method> SUGGEST_METHODS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Method> GET_LIST_METHODS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Method> APPLY_METHODS = new ConcurrentHashMap<>();

    private CommandSuggestions() {
    }

    public static CompletableFuture<List<String>> suggestAsync(Object dispatcher, Object source,
                                                                String partialInput, int maxResults,
                                                                DebugLogger debugLogger) {
        if (dispatcher == null || source == null || partialInput == null) {
            debug(debugLogger, "called with dispatcher=" + dispatcher + " source=" + source
                    + " partialInput=" + (partialInput == null ? "null" : "present"));
            return CompletableFuture.completedFuture(List.of());
        }

        CompletableFuture<List<String>> first = obtainSuggestionsFuture(dispatcher, source, partialInput, debugLogger)
                .thenApply(raw -> extract(raw, partialInput, maxResults, debugLogger))
                .exceptionally(ex -> List.of());

        boolean canDescend = !partialInput.isBlank() && !partialInput.endsWith(" ");
        CompletableFuture<List<String>> combined = canDescend
                ? descendProbe(dispatcher, source, partialInput, maxResults, debugLogger, first)
                : first;

        return combined.orTimeout(SUGGESTION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                .exceptionally(ex -> List.of());
    }

    private static CompletableFuture<List<String>> descendProbe(Object dispatcher, Object source,
                                                                String partialInput, int maxResults,
                                                                DebugLogger debugLogger,
                                                                CompletableFuture<List<String>> first) {
        String descendInput = partialInput + " ";
        return first.thenCombine(
                obtainSuggestionsFuture(dispatcher, source, descendInput, debugLogger)
                        .thenApply(raw -> extract(raw, descendInput, maxResults, debugLogger))
                        .exceptionally(ex -> List.of()),
                (list1, list2) -> merge(list1, list2, maxResults));
    }

    private static CompletableFuture<Object> obtainSuggestionsFuture(Object dispatcher, Object source,
                                                                      String input, DebugLogger debugLogger) {
        try {
            Method parseMethod = cachedMethod(PARSE_METHODS, dispatcher.getClass(), "parse", String.class, Object.class);
            Object parseResults = parseMethod.invoke(dispatcher, input, source);
            if (parseResults == null) {
                debug(debugLogger, "parse() returned null");
                return CompletableFuture.completedFuture(null);
            }

            Method suggestMethod = cachedMethod(SUGGEST_METHODS, dispatcher.getClass(),
                    "getCompletionSuggestions", parseResults.getClass());
            Object futureObj = suggestMethod.invoke(dispatcher, parseResults);
            if (!(futureObj instanceof CompletableFuture<?> future)) {
                debug(debugLogger, "getCompletionSuggestions() did not return a CompletableFuture");
                return CompletableFuture.completedFuture(null);
            }
            @SuppressWarnings("unchecked")
            CompletableFuture<Object> casted = (CompletableFuture<Object>) future;
            return casted;
        } catch (Exception failure) {
            debug(debugLogger, "failed to obtain suggestions future: " + failure.getClass().getSimpleName());
            return CompletableFuture.completedFuture(null);
        }
    }

    private static List<String> extract(Object suggestions, String input, int maxResults, DebugLogger debugLogger) {
        if (suggestions == null) {
            debug(debugLogger, "the suggestions future resolved to null");
            return List.of();
        }
        try {
            Method getListMethod = cachedMethod(GET_LIST_METHODS, suggestions.getClass(), "getList");
            Object listObj = getListMethod.invoke(suggestions);
            if (!(listObj instanceof List<?> rawList)) {
                debug(debugLogger, "getList() did not return a List");
                return List.of();
            }
            debug(debugLogger, "input inputLen=" + input.length() + " root=\"" + safeRoot(input)
                    + "\" raw Brigadier suggestions=" + rawList.size());

            List<String> results = new ArrayList<>();
            for (Object suggestion : rawList) {
                if (results.size() >= maxResults) {
                    break;
                }
                if (suggestion == null) {
                    continue;
                }
                Method applyMethod = cachedMethod(APPLY_METHODS, suggestion.getClass(), "apply", String.class);
                Object appliedObj = applyMethod.invoke(suggestion, input);
                if (appliedObj instanceof String applied) {
                    if (applied.length() <= MAX_CHOICE_LENGTH) {
                        results.add(applied);
                    } else {
                        debug(debugLogger, "dropped a suggestion over " + MAX_CHOICE_LENGTH
                                + " chars, length=" + applied.length());
                    }
                }
            }
            return results;
        } catch (Exception failure) {
            debug(debugLogger, "extraction failed: " + failure.getClass().getSimpleName());
            return List.of();
        }
    }

    private static List<String> merge(List<String> first, List<String> second, int maxResults) {
        Set<String> merged = new LinkedHashSet<>(first);
        merged.addAll(second);
        List<String> ordered = new ArrayList<>(merged);
        if (ordered.size() > maxResults) {
            ordered = ordered.subList(0, maxResults);
        }
        return ordered;
    }

    private static Method cachedMethod(ConcurrentHashMap<Class<?>, Method> cache, Class<?> type,
                                       String name, Class<?>... paramTypes) throws NoSuchMethodException {
        Method cached = cache.get(type);
        if (cached != null) {
            return cached;
        }
        Method resolved = type.getMethod(name, paramTypes);
        cache.putIfAbsent(type, resolved);
        return resolved;
    }

    public static String safeRoot(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        int space = input.indexOf(' ');
        String root = space >= 0 ? input.substring(0, space) : input;
        return root.length() > MAX_ROOT_LENGTH ? root.substring(0, MAX_ROOT_LENGTH) : root;
    }

    private static void debug(DebugLogger debugLogger, String message) {
        if (debugLogger != null) {
            debugLogger.debugLog("[CommandSuggestions] " + message);
        }
    }
}
