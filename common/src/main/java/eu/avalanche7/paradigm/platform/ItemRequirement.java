package eu.avalanche7.paradigm.platform;

import java.util.Locale;

public record ItemRequirement(String itemId, Integer metadata, int amount) {
    public ItemRequirement {
        if (itemId == null || !itemId.matches("[a-z0-9_.-]+(:[a-z0-9_/.-]+)?") || amount <= 0
                || metadata != null && (metadata < 0 || metadata > 32767))
            throw new IllegalArgumentException("Invalid item requirement");
    }

    public boolean numeric() { return itemId.matches("[0-9]+"); }

    public static ItemRequirement parse(String text) { return parse(text, 1); }

    public static ItemRequirement parse(String text, int defaultAmount) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("Item specification is required");
        String[] parts = text.trim().toLowerCase(Locale.ROOT).split("\\s+");
        if (parts.length > 2) throw new IllegalArgumentException("Expected ITEM[:METADATA] [AMOUNT]");
        int amount = parts.length == 2 ? positive(parts[1]) : defaultAmount;
        String id = parts[0];
        Integer metadata = null;
        if (id.matches("[0-9]+(:[0-9]+)?")) {
            String[] legacy = id.split(":");
            id = Integer.toString(positive(legacy[0]));
            metadata = legacy.length == 2 ? Integer.parseInt(legacy[1]) : 0;
        } else {
            if (id.matches("[0-9]+:.*") || id.matches("[+-][0-9]+.*")) throw new IllegalArgumentException("Invalid numeric item specification");
            if (!id.contains(":")) id = "minecraft:" + id;
        }
        return new ItemRequirement(id, metadata, amount);
    }

    private static int positive(String value) {
        if (!value.matches("[0-9]+")) throw new IllegalArgumentException("Expected a positive integer");
        int number = Integer.parseInt(value);
        if (number <= 0) throw new IllegalArgumentException("Quantity and numeric item IDs must be positive");
        return number;
    }
}
