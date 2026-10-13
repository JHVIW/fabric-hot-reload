package com.example.hello;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * A tiny mod to try Fabric Hot Reload with: run the game, then `./gradlew hotReload` in this folder.
 * Change the text or color below, run it again, and watch the HUD change without restarting.
 */
public class HelloHotReload implements ClientModInitializer {
	/** Counted by {@link com.example.hello.mixin.MinecraftMixin}, to show that mixins hot-load too. */
	public static int ticks;
	private static KeyMapping greetKey;

	@Override
	public void onInitializeClient() {
		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("hello_hotreload", "main"));
		greetKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.hello_hotreload.greet", InputConstants.KEY_J, category));
		ClientTickEvents.END_CLIENT_TICK.register(HelloHotReload::tick);
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("hello_hotreload", "hud"), (graphics, deltaTracker) ->
			graphics.text(Minecraft.getInstance().font, "Hello from a hot-loaded mod! ticks: " + ticks, 4, 4, 0xFFFFD540));
	}

	private static void tick(Minecraft mc) {
		while (greetKey.consumeClick()) {
			if (mc.player != null) {
				mc.player.sendOverlayMessage(Component.translatable("message.hello_hotreload.greet"));
			}
		}
	}
}
