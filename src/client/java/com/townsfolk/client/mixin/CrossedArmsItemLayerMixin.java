package com.townsfolk.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.townsfolk.client.HeldWeapons;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.VillagerLikeModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.entity.layers.CrossedArmsItemLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.HoldingEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Weapons stand upright in the middle of a villager's folded arms instead of lying across them. */
@Mixin(CrossedArmsItemLayer.class)
public abstract class CrossedArmsItemLayerMixin {
	/** Bows are held out in front, upright, instead of in the folded arms like other weapons. */
	@Inject(
		method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/HoldingEntityRenderState;FF)V",
		at = @At("HEAD"),
		cancellable = true
	)
	private void townsfolk$holdBow(PoseStack poseStack, SubmitNodeCollector collector, int lightCoords, HoldingEntityRenderState state,
		float yRot, float xRot, CallbackInfo ci) {
		ItemStackRenderState bow = state.getData(HeldWeapons.BOW);
		if (bow != null && !bow.isEmpty() && ((RenderLayer<?, ?>) (Object) this).getParentModel() instanceof EntityModel<?> model) {
			HeldWeapons.submitBow(model.root(), bow, poseStack, collector, lightCoords, state.outlineColor);
			ci.cancel();
		}
	}

	@Inject(method = "applyTranslation", at = @At("HEAD"), cancellable = true)
	private void townsfolk$holdWeapon(HoldingEntityRenderState state, PoseStack poseStack, CallbackInfo ci) {
		if (Boolean.TRUE.equals(state.getData(HeldWeapons.WEAPON))
			&& ((RenderLayer<?, ?>) (Object) this).getParentModel() instanceof VillagerLikeModel<?> model) {
			HeldWeapons.applyGrip(model, state, poseStack);
			ci.cancel();
		}
	}
}
