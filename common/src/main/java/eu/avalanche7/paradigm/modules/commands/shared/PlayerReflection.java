package eu.avalanche7.paradigm.modules.commands.shared;

import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

public final class PlayerReflection {
    private PlayerReflection() {
    }

    public static Object invokeNoArg(Object target, String... methodNames) {
        if (target == null) {
            return null;
        }
        for (String methodName : methodNames) {
            try {
                Method method = target.getClass().getMethod(methodName);
                method.setAccessible(true);
                return method.invoke(target);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    public static Object readField(Object target, String... fieldNames) {
        if (target == null) {
            return null;
        }
        for (String fieldName : fieldNames) {
            for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
                try {
                    Field field = type.getDeclaredField(fieldName);
                    field.setAccessible(true);
                    return field.get(target);
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    public static boolean invokeBooleanMethod(Object target, boolean value, String... methodNames) {
        if (target == null) {
            return false;
        }
        for (String methodName : methodNames) {
            try {
                Method method = target.getClass().getMethod(methodName, boolean.class);
                method.setAccessible(true);
                method.invoke(target, value);
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    public static boolean writeBooleanField(Object target, boolean value, String... fieldNames) {
        if (target == null) {
            return false;
        }
        boolean changed = false;
        for (String fieldName : fieldNames) {
            for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
                try {
                    Field field = type.getDeclaredField(fieldName);
                    field.setAccessible(true);
                    field.setBoolean(target, value);
                    changed = true;
                    break;
                } catch (Throwable ignored) {
                }
            }
        }
        return changed;
    }

    public static boolean writeIntField(Object target, int value, String... fieldNames) {
        if (target == null) {
            return false;
        }
        boolean changed = false;
        for (String fieldName : fieldNames) {
            for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
                try {
                    Field field = type.getDeclaredField(fieldName);
                    field.setAccessible(true);
                    field.setInt(target, value);
                    changed = true;
                    break;
                } catch (Throwable ignored) {
                }
            }
        }
        return changed;
    }

    public static Object inventory(IPlayer player) {
        Object handle = player != null ? player.getOriginalPlayer() : null;
        Object inventory = invokeNoArg(handle, "getInventory");
        if (inventory != null) {
            return inventory;
        }
        return readField(handle, "inventory");
    }

    public static Object enderInventory(IPlayer player) {
        Object handle = player != null ? player.getOriginalPlayer() : null;
        Object inventory = invokeNoArg(handle, "getEnderChestInventory", "getEnderChest", "getEnderChestContainer");
        if (inventory != null) {
            return inventory;
        }
        return readField(handle, "enderChestInventory", "enderChest", "enderChestContainer");
    }

    public static Object mainHandItem(IPlayer player) {
        Object handle = player != null ? player.getOriginalPlayer() : null;
        Object stack = invokeNoArg(handle, "getMainHandItem", "getMainHandStack");
        if (stack != null) {
            return stack;
        }
        Object inventory = inventory(player);
        stack = invokeNoArg(inventory, "getSelected", "getMainHandStack");
        if (stack != null) {
            return stack;
        }
        Object selected = readField(inventory, "selected", "selectedSlot");
        int slot = selected instanceof Number n ? n.intValue() : 0;
        return stackAt(inventory, slot);
    }

    public static List<Object> inventoryStacks(IPlayer player, boolean includeArmorAndOffhand) {
        Object inventory = inventory(player);
        List<Object> stacks = new ArrayList<>();
        addStacksFrom(inventory, stacks, "items", "main", "mainInventory");
        if (includeArmorAndOffhand) {
            addStacksFrom(inventory, stacks, "armor", "armorInventory");
            addStacksFrom(inventory, stacks, "offhand", "offHand", "offHandInventory");
        }
        if (stacks.isEmpty() && inventory instanceof Iterable<?> iterable) {
            for (Object stack : iterable) {
                stacks.add(stack);
            }
        }
        return stacks;
    }

    public static List<Object> enderStacks(IPlayer player) {
        Object inventory = enderInventory(player);
        List<Object> stacks = new ArrayList<>();
        addStacksFrom(inventory, stacks, "items", "stacks");
        if (stacks.isEmpty() && inventory instanceof Iterable<?> iterable) {
            for (Object stack : iterable) {
                stacks.add(stack);
            }
        }
        if (stacks.isEmpty()) {
            int size = sizeOf(inventory);
            for (int i = 0; i < size; i++) {
                Object stack = stackAt(inventory, i);
                if (stack != null) {
                    stacks.add(stack);
                }
            }
        }
        return stacks;
    }

    public static boolean isEmptyStack(Object stack) {
        if (stack == null) {
            return true;
        }
        Object empty = invokeNoArg(stack, "isEmpty");
        if (empty instanceof Boolean b) {
            return b;
        }
        return stack.toString().toLowerCase(Locale.ROOT).contains("empty");
    }

    public static int stackCount(Object stack) {
        Object count = invokeNoArg(stack, "getCount", "getAmount");
        return count instanceof Number n ? n.intValue() : 1;
    }

    public static String stackName(Object stack) {
        Object name = invokeNoArg(stack, "getHoverName", "getDisplayName", "getName");
        Object raw = invokeNoArg(name, "getString", "getContents");
        if (raw != null) {
            return raw.toString();
        }
        Object item = invokeNoArg(stack, "getItem");
        return item != null ? item.toString() : stack.toString();
    }

    public static boolean repairStack(Object stack) {
        if (stack == null || isEmptyStack(stack)) {
            return false;
        }
        boolean changed = false;
        for (String name : List.of("setDamageValue", "setDamage", "setDamageAmount")) {
            try {
                Method method = stack.getClass().getMethod(name, int.class);
                method.setAccessible(true);
                method.invoke(stack, 0);
                changed = true;
            } catch (Throwable ignored) {
            }
        }
        changed |= writeIntField(stack, 0, "damage", "damageValue");
        return changed;
    }

    public static double distanceSquared(IPlayer a, IPlayer b) {
        if (a == null || b == null || a.getX() == null || a.getY() == null || a.getZ() == null || b.getX() == null || b.getY() == null || b.getZ() == null) {
            return Double.MAX_VALUE;
        }
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private static void addStacksFrom(Object inventory, List<Object> result, String... fieldNames) {
        Object value = readField(inventory, fieldNames);
        if (value instanceof Collection<?> collection) {
            result.addAll(collection);
        } else if (value instanceof Iterable<?> iterable) {
            for (Object stack : iterable) {
                result.add(stack);
            }
        }
    }

    private static int sizeOf(Object inventory) {
        Object size = invokeNoArg(inventory, "getContainerSize", "size", "getSize");
        return size instanceof Number n ? Math.max(0, n.intValue()) : 0;
    }

    private static Object stackAt(Object inventory, int slot) {
        if (inventory == null || slot < 0) {
            return null;
        }
        for (String name : List.of("getItem", "getStack", "get")) {
            try {
                Method method = inventory.getClass().getMethod(name, int.class);
                method.setAccessible(true);
                return method.invoke(inventory, slot);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }
}
