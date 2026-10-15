package com.townsfolk.client.mixin;

import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Entity previews in screens (like the villager screen) should not draw a floating name tag. */
@Mixin(InventoryScreen.class)
public abstract class InventoryScreenMixin {
	@Inject(
		method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;",
		at = @At("RETURN")
	)
	private static void townsfolk$hidePreviewNameTag(LivingEntity entity, CallbackInfoReturnable<EntityRenderState> cir) {
		cir.getReturnValue().nameTag = null;
	}
}
