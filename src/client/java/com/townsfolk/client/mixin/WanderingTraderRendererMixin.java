package com.townsfolk.client.mixin;

import com.townsfolk.client.VillagerArmorLayer;
import net.minecraft.client.renderer.entity.WanderingTraderRenderer;
import net.minecraft.client.renderer.entity.state.VillagerRenderState;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WanderingTraderRenderer.class)
public abstract class WanderingTraderRendererMixin {
	@Inject(
		method = "extractRenderState(Lnet/minecraft/world/entity/npc/wanderingtrader/WanderingTrader;Lnet/minecraft/client/renderer/entity/state/VillagerRenderState;F)V",
		at = @At("TAIL")
	)
	private void townsfolk$extractArmor(WanderingTrader trader, VillagerRenderState state, float partialTicks, CallbackInfo ci) {
		VillagerArmorLayer.extract(trader, state);
	}
}
