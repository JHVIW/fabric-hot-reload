package com.example.hello.mixin;

import com.example.hello.HelloHotReload;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftMixin {
	@Inject(method = "tick", at = @At("HEAD"))
	private void hello_hotreload$countTicks(CallbackInfo ci) {
		HelloHotReload.ticks++;
	}
}
