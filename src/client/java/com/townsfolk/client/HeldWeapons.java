package com.townsfolk.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.client.model.VillagerLikeModel;
import net.minecraft.client.renderer.entity.state.HoldingEntityRenderState;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Villagers normally lay whatever they hold across their folded arms, which looks wrong for a sword.
 * Weapons are instead held diagonally in the middle of the folded arms, turned a quarter so they point forward.
 */
public final class HeldWeapons {
	/** Set on a villager's render state when it holds a weapon this frame. */
	public static final RenderStateDataKey<Boolean> WEAPON = RenderStateDataKey.create(() -> "townsfolk:held_weapon");

	/** A little bigger than the small "on the ground" model villagers use for held items. */
	private static final float SCALE = 1.5F;
	/** Raise it slightly so the grip sits at the folded arms. */
	private static final float RAISE = 0.1F;
	/** Turns the held weapon a quarter turn so it points out in front instead of facing the viewer. */
	private static final float TURN_FORWARD = -90.0F;
	/** Pushes it a little further out so the handle sits at the folded arms, not inside them. */
	private static final float OUT_FROM_ARMS = 0.08F;

	private HeldWeapons() {
	}

	public static boolean isWeapon(ItemStack stack) {
		return stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES) || stack.is(ItemTags.SPEARS)
			|| stack.is(Items.TRIDENT) || stack.is(Items.MACE);
	}

	/** Called while the villager's render state is extracted: marks weapons for the held pose. */
	public static void extract(AbstractVillager villager, HoldingEntityRenderState state) {
		state.setData(WEAPON, isWeapon(villager.getMainHandItem()) ? Boolean.TRUE : null);
	}

	/** Vanilla's centred spot in front of the folded arms, held diagonally, turned to point forward. */
	@SuppressWarnings("unchecked")
	public static void applyGrip(VillagerLikeModel<?> model, HoldingEntityRenderState state, PoseStack poseStack) {
		((VillagerLikeModel<HoldingEntityRenderState>) model).translateToArms(state, poseStack);
		// The same steps vanilla uses for anything a villager holds: centred, facing out.
		poseStack.rotate(Axis.XP, 0.75F);
		poseStack.scale(1.07F, 1.07F, 1.07F);
		poseStack.translate(0.0F, 0.13F, -0.34F);
		poseStack.rotate(Axis.XP, (float) Math.PI);
		// Now facing out with up being up: held diagonally like its icon, turned a quarter so the blade
		// points forward out of the folded arms.
		poseStack.translate(0.0F, RAISE, 0.0F);
		poseStack.rotateDegrees(Axis.YP, TURN_FORWARD);
		poseStack.scale(SCALE, SCALE, SCALE);
		poseStack.translate(OUT_FROM_ARMS, 0.0F, 0.0F);
	}
}
