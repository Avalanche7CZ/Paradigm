package eu.avalanche7.paradigm.modules.commands.admin;

final class EnchantmentIds {
    private static final String VANILLA_NAMESPACE = "minecraft:";

    private EnchantmentIds() {
    }

    static String normalize(String enchantmentId) {
        if (enchantmentId == null) {
            return null;
        }
        String normalized = enchantmentId.trim();
        if (normalized.isEmpty()) {
            return normalized;
        }
        return normalized.indexOf(':') >= 0 ? normalized : VANILLA_NAMESPACE + normalized;
    }
}
