package com.townsfolk.client.mixin;

import com.townsfolk.client.VillagerArmorLayer;
import net.minecraft.client.renderer.entity.VillagerRenderer;
import net.minecraft.client.renderer.entity.state.VillagerRenderState;
import net.minecraft.world.entity.npc.villager.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VillagerRenderer.class)
public abstract class VillagerRendererMixin {
	@Inject(
		method = "extractRenderState(Lnet/minecraft/world/entity/npc/villager/Villager;Lnet/minecraft/client/renderer/entity/state/VillagerRenderState;F)V",
		at = @At("TAIL")
	)
	private void townsfolk$extractArmor(Villager villager, VillagerRenderState state, float partialTicks, CallbackInfo ci) {
		VillagerArmorLayer.extract(villager, state);
	}
}
