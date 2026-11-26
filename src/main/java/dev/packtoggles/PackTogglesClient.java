package dev.packtoggles;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PackTogglesClient implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("packtoggles");
    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("packtoggles", "main"));
    private static KeyMapping openKey;

    @Override
    public void onInitializeClient() {
        PackBuilder.finishPendingSwap();
        Config.load();
        openKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.packtoggles.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, CATEGORY));
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (openKey.consumeClick()) mc.setScreen(new ToggleScreen(mc.screen));
        });
    }
}
