package eu.avalanche7.paradigm.platform;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import eu.avalanche7.paradigm.platform.Interfaces.IMenuPlatform.ClickKind;

class NativePortTest {
    @Test
    void nativePermissionLevelsPreserveOperatorFallback() {
        for (int actual = 0; actual <= 4; actual++) {
            var source = new CommandSourceStack(CommandSource.NULL, Vec3.ZERO, Vec2.ZERO, null,
                    LevelBasedPermissionSet.forLevel(PermissionLevel.byId(actual)), "test", Component.literal("test"), null, null);
            var wrapped = new MinecraftCommandSource(source);
            for (int required = 0; required <= 4; required++) {
                assertEquals(actual >= required, wrapped.hasPermissionLevel(required));
            }
        }
    }

    @Test
    void nativeContainerInputsPreserveClickDistinctions() {
        assertEquals(ClickKind.LEFT, MinecraftMenuPlatform.mapClick(ContainerInput.PICKUP, 0));
        assertEquals(ClickKind.RIGHT, MinecraftMenuPlatform.mapClick(ContainerInput.PICKUP, 1));
        assertEquals(ClickKind.SHIFT_LEFT, MinecraftMenuPlatform.mapClick(ContainerInput.QUICK_MOVE, 0));
        assertEquals(ClickKind.SHIFT_RIGHT, MinecraftMenuPlatform.mapClick(ContainerInput.QUICK_MOVE, 1));
        assertEquals(ClickKind.SWAP_OFFHAND, MinecraftMenuPlatform.mapClick(ContainerInput.SWAP, 40));
        assertEquals(ClickKind.NUMBER_KEY, MinecraftMenuPlatform.mapClick(ContainerInput.SWAP, 0));
        assertEquals(ClickKind.DROP, MinecraftMenuPlatform.mapClick(ContainerInput.THROW, 0));
        assertEquals(ClickKind.CONTROL_DROP, MinecraftMenuPlatform.mapClick(ContainerInput.THROW, 1));
        assertEquals(ClickKind.MIDDLE, MinecraftMenuPlatform.mapClick(ContainerInput.CLONE, 0));
        assertEquals(ClickKind.DRAG, MinecraftMenuPlatform.mapClick(ContainerInput.QUICK_CRAFT, 0));
        assertEquals(ClickKind.DOUBLE_CLICK, MinecraftMenuPlatform.mapClick(ContainerInput.PICKUP_ALL, 0));
    }

    @Test
    void interactiveComponentsRetainPayloads() {
        var component = new MinecraftComponent(Component.literal("link"));
        assertEquals(new ClickEvent.RunCommand("/home"), ((net.minecraft.network.chat.Style) component.onClickRunCommand("/home").getStyle()).getClickEvent());
        assertEquals(new ClickEvent.SuggestCommand("/msg "), ((net.minecraft.network.chat.Style) component.onClickSuggestCommand("/msg ").getStyle()).getClickEvent());
        assertEquals(new ClickEvent.CopyToClipboard("text"), ((net.minecraft.network.chat.Style) component.onClickCopyToClipboard("text").getStyle()).getClickEvent());
        assertEquals(new ClickEvent.OpenUrl(URI.create("https://example.org")), ((net.minecraft.network.chat.Style) component.onClickOpenUrl("example.org").getStyle()).getClickEvent());
        assertEquals(new HoverEvent.ShowText(Component.literal("hint")), ((net.minecraft.network.chat.Style) component.onHoverText("hint").getStyle()).getHoverEvent());
        assertEquals("link", component.getRawText());
    }
    @Test
    void exhaustionAccessorAppliesAndPreservesOtherFoodState() {
        var food = new net.minecraft.world.food.FoodData();
        food.setFoodLevel(12);
        food.setSaturation(3.0f);
        food.addExhaustion(40.0f);
        ((eu.avalanche7.paradigm.mixin.FoodDataAccessor) food).paradigm$setExhaustion(0.0f);
        var output = net.minecraft.world.level.storage.TagValueOutput.createWithoutContext(net.minecraft.util.ProblemReporter.DISCARDING);
        food.addAdditionalSaveData(output);
        assertEquals(0.0f, output.buildResult().getFloatOr("foodExhaustionLevel", -1.0f));
        assertEquals(12, food.getFoodLevel());
        assertEquals(3.0f, food.getSaturationLevel());
    }

    @Test
    void nativePermissionElevationIsScopedAndPreservesOtherPermissions() {
        var original = LevelBasedPermissionSet.ALL.union(permission -> permission.equals(net.minecraft.server.permissions.Permissions.CHAT_SEND_MESSAGES));
        var source = new CommandSourceStack(CommandSource.NULL, Vec3.ZERO, Vec2.ZERO, null,
                original, "test", Component.literal("test"), null, null);
        var required = net.minecraft.server.permissions.Permissions.COMMANDS_OWNER;
        assertFalse(source.permissions().hasPermission(required));
        eu.avalanche7.paradigm.modules.permissions.CommandPermissionElevation.begin(source, true);
        try {
            assertTrue(source.permissions().hasPermission(required));
            assertTrue(source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_ENTITY_SELECTORS));
            assertTrue(source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.CHAT_SEND_MESSAGES));
            assertFalse(source.permissions().hasPermission(net.minecraft.server.permissions.Permission.Atom.create("portprobe/privileged")));
        } finally {
            eu.avalanche7.paradigm.modules.permissions.CommandPermissionElevation.clear();
        }
        assertFalse(source.permissions().hasPermission(required));
        eu.avalanche7.paradigm.modules.permissions.CommandPermissionElevation.begin(source, false);
        try {
            assertFalse(source.permissions().hasPermission(required));
        } finally {
            eu.avalanche7.paradigm.modules.permissions.CommandPermissionElevation.clear();
        }
    }

}
