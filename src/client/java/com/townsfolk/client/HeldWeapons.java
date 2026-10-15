package com.townsfolk.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.client.model.VillagerLikeModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
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
	/** How far through a melee hit the villager is (0 to 1); absent when it is not hitting. */
	public static final RenderStateDataKey<Float> SWING = RenderStateDataKey.create(() -> "townsfolk:weapon_swing");
	/**
	 * A held bow, seen as if held in a right hand (vanilla's bow-in-hand model), which stands it upright
	 * with the string towards the villager. Resolved against the villager so it shows how far it is drawn.
	 */
	public static final RenderStateDataKey<ItemStackRenderState> BOW = RenderStateDataKey.create(() -> "townsfolk:held_bow");
	/** Set while the villager is eating what it holds: its folded arms come up to its mouth. */
	public static final RenderStateDataKey<Boolean> EATING = RenderStateDataKey.create(() -> "townsfolk:eating");

	/** How far the folded arms lift and push forward with a melee hit (radians / pixels). */
	private static final float JAB_LIFT = 0.9F;
	private static final float JAB_REACH = 2.0F;

	/** A little bigger than the small "on the ground" model villagers use for held items. */
	private static final float SCALE = 1.5F;
	/** Raise it slightly so the grip sits at the folded arms. */
	private static final float RAISE = 0.1F;
	/** Turns the held weapon a quarter turn so it points out in front instead of facing the viewer. */
	private static final float TURN_FORWARD = -90.0F;
	/** Pushes it a little further out so the handle sits at the folded arms, not inside them. */
	private static final float OUT_FROM_ARMS = 0.08F;
	/** Where the bow is gripped: in front of the folded arms, in model pixels (x, y down, z forward is negative). */
	private static final float BOW_GRIP_X = 0.0F;
	private static final float BOW_GRIP_Y = 6.0F;
	private static final float BOW_GRIP_Z = -6.5F;

	private HeldWeapons() {
	}

	public static boolean isWeapon(ItemStack stack) {
		return stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES) || stack.is(ItemTags.SPEARS)
			|| stack.is(Items.TRIDENT) || stack.is(Items.MACE) || stack.is(Items.BOW) || stack.is(Items.CROSSBOW);
	}

	/** Called while the villager's render state is extracted: marks weapons for the held pose and attacks. */
	public static void extract(AbstractVillager villager, HoldingEntityRenderState state, float partialTicks) {
		ItemStack held = villager.getMainHandItem();
		boolean weapon = isWeapon(held);
		state.setData(WEAPON, weapon ? Boolean.TRUE : null);

		boolean bow = held.is(Items.BOW);
		float swing = weapon && !bow && villager.isSwinging() ? villager.getSwingAnimation(partialTicks) : 0.0F;
		state.setData(SWING, swing > 0.0F ? swing : null);
		ItemStackRenderState bowState = null;
		if (bow) {
			bowState = new ItemStackRenderState();
			Minecraft.getInstance().getItemModelResolver().updateForLiving(bowState, held, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, villager);
		}
		state.setData(BOW, bowState);
		state.setData(EATING, !bow && villager.isUsingItem() && held.has(DataComponents.FOOD) ? Boolean.TRUE : null);
	}

	/** Folded arms lifted towards the mouth, bobbing with each bite. */
	public static void animateEating(ModelPart arms, HoldingEntityRenderState state) {
		if (state.getData(EATING) == null) {
			return;
		}
		arms.xRot -= 0.55F + 0.12F * Mth.sin(state.ageInTicks * 1.6F);
		arms.y -= 1.5F;
	}

	/** A quick jab forward and up of the folded arms (and the weapon in them), eased in and out. */
	public static void animateJab(ModelPart arms, HoldingEntityRenderState state) {
		Float swing = state.getData(SWING);
		if (swing == null) {
			return;
		}
		float amount = Mth.sin(swing * (float) Math.PI);
		arms.xRot -= amount * JAB_LIFT;
		arms.z -= amount * JAB_REACH;
	}

	/**
	 * A bow held out in front of the folded arms the way a skeleton aims: as if gripped by a hand at the
	 * end of an arm pointing forward, then vanilla's hand-item steps, so the bow-in-hand model stands it
	 * upright with the string towards the villager and the arrow pointing out.
	 */
	public static void submitBow(ModelPart root, ItemStackRenderState bow, PoseStack poseStack, SubmitNodeCollector collector,
		int lightCoords, int outlineColor) {
		poseStack.pushPose();
		root.translateAndRotate(poseStack);
		poseStack.translate(BOW_GRIP_X / 16.0F, BOW_GRIP_Y / 16.0F, BOW_GRIP_Z / 16.0F);
		// An arm pointing straight forward...
		poseStack.rotateDegrees(Axis.XP, -90.0F);
		// ...then exactly what vanilla's hand item layer does at the hand.
		poseStack.rotateDegrees(Axis.XP, -90.0F);
		poseStack.rotateDegrees(Axis.YP, 180.0F);
		poseStack.translate(1.0F / 16.0F, 2.0F / 16.0F, 0.0F);
		bow.submit(poseStack, collector, lightCoords, OverlayTexture.NO_OVERLAY, outlineColor);
		poseStack.popPose();
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
