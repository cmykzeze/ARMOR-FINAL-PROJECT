package com.ascendant.client;

import com.ascendant.AscendantMod;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * Hotkeys. Each key just sends "/ascendant use <slot>" to the server, so no custom networking is needed.
 *   Z = helmet ability, X = chestplate ability, C = leggings ability, V = boots ability
 * Rebind them in Options > Controls > Key Binds > Ascendant Armor.
 */
public class AscendantClient implements ClientModInitializer {
    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath(AscendantMod.MOD_ID, "keys"));

    private static KeyMapping helmet;
    private static KeyMapping chest;
    private static KeyMapping legs;
    private static KeyMapping boots;

    @Override
    public void onInitializeClient() {
        helmet = register("helmet", GLFW.GLFW_KEY_Z);
        chest = register("chest", GLFW.GLFW_KEY_X);
        legs = register("legs", GLFW.GLFW_KEY_C);
        boots = register("boots", GLFW.GLFW_KEY_V);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;
            while (helmet.consumeClick()) client.player.connection.sendCommand("ascendant use helmet");
            while (chest.consumeClick()) client.player.connection.sendCommand("ascendant use chest");
            while (legs.consumeClick()) client.player.connection.sendCommand("ascendant use legs");
            while (boots.consumeClick()) client.player.connection.sendCommand("ascendant use boots");
        });
    }

    private static KeyMapping register(String name, int glfwKey) {
        return KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key." + AscendantMod.MOD_ID + "." + name,
                InputConstants.Type.KEYSYM,
                glfwKey,
                CATEGORY));
    }
}
