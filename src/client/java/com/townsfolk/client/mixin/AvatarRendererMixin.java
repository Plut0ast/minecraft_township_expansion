package com.townsfolk.client.mixin;

import com.townsfolk.client.BannerCapes;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A town member's banner cape replaces their own cape, and follows the "Cape" skin setting. */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {
	@Inject(
		method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
		at = @At("TAIL")
	)
	private void townsfolk$extractBannerCape(Avatar avatar, AvatarRenderState state, float partialTicks, CallbackInfo ci) {
		BannerCapes.Cape cape = state.showCape ? BannerCapes.capeOf(avatar) : null;
		state.setData(BannerCapes.CAPE, cape);
		if (cape != null) {
			state.showCape = false;
		}
	}
}
