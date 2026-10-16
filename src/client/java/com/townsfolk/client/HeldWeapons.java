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
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

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
	/**
	 * A held spear, seen as if held in a right hand (vanilla's long spear-in-hand model). It is carried
	 * in a hand hanging at the villager's right side, point forward and a little up, like a guard.
	 */
	public static final RenderStateDataKey<ItemStackRenderState> SPEAR = RenderStateDataKey.create(() -> "townsfolk:held_spear");
	/** Set when the held weapon is an axe, which is turned over around its handle so its blade faces forward. */
	public static final RenderStateDataKey<Boolean> AXE = RenderStateDataKey.create(() -> "townsfolk:held_axe");
	/** Set while the villager is eating what it holds: its folded arms come up to its mouth. */
	public static final RenderStateDataKey<Boolean> EATING = RenderStateDataKey.create(() -> "townsfolk:eating");

	/**
	 * A melee hit: the folded arms (and the weapon in them) wind up, strike down and forward past where
	 * they rest, then come back. Radians for the swing, pixels for how far forward they reach.
	 */
	private static final float WIND_UP = 1.3F;
	private static final float FOLLOW_THROUGH = 0.7F;
	private static final float STRIKE_REACH = 3.0F;
	/** Parts of the swing (0 to 1): winding up until this point, then striking until the next. */
	private static final float WIND_UP_END = 0.35F;
	private static final float STRIKE_END = 0.6F;

	/** A little bigger than the small "on the ground" model villagers use for held items. */
	private static final float SCALE = 1.5F;
	/** Raise it slightly so the grip sits at the folded arms. */
	private static final float RAISE = 0.1F;
	/** Turns the held weapon a quarter turn so it points out in front instead of facing the viewer. */
	private static final float TURN_FORWARD = -90.0F;
	/** Pushes it a little further out so the handle sits at the folded arms, not inside them. */
	private static final float OUT_FROM_ARMS = 0.08F;
	/** How far an axe is pulled back in towards the villager from where a sword sits. */
	private static final float AXE_PULL_IN = 0.1F;
	/** The handle's direction in an axe's icon: bottom-left to top-right. */
	private static final Vector3f AXE_HANDLE = new Vector3f(1.0F, 1.0F, 0.0F).normalize();
	/** Spears: the shoulder of an imaginary right arm at the villager's side (model pixels; right is negative x). */
	private static final float SPEAR_SHOULDER_X = -5.5F;
	private static final float SPEAR_SHOULDER_Y = -1.0F;
	/** That arm leans forward this far (degrees), tilting the spear's point up. */
	private static final float SPEAR_ARM_LEAN = -40.0F;
	/** How much less the arm leans while pulling the spear back (degrees); it leans a bit further on the thrust. */
	private static final float SPEAR_PULL = 10.0F;
	/** How far (pixels) the spear is pulled back and then driven forward in a thrust. */
	private static final float SPEAR_DRIVE = 4.0F;
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
		state.setData(AXE, held.is(ItemTags.AXES) ? Boolean.TRUE : null);
		ItemStackRenderState spearState = null;
		if (held.is(ItemTags.SPEARS)) {
			spearState = new ItemStackRenderState();
			Minecraft.getInstance().getItemModelResolver().updateForLiving(spearState, held, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, villager);
		}
		state.setData(SPEAR, spearState);

		// Bows and crossbows are both held out in front, using their in-hand models.
		boolean bow = held.is(Items.BOW) || held.is(Items.CROSSBOW);
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

	/** Wind up, strike through, recover: see {@link #WIND_UP}. */
	public static void animateJab(ModelPart arms, HoldingEntityRenderState state) {
		Float swing = state.getData(SWING);
		if (swing == null) {
			return;
		}
		arms.xRot += strikeAngle(swing);
		arms.z -= Mth.sin(swing * (float) Math.PI) * STRIKE_REACH;
	}

	/** The swing's angle at this point: lifting back (negative), then down through to past rest, then back to rest. */
	static float strikeAngle(float swing) {
		if (swing < WIND_UP_END) {
			return -WIND_UP * ease(swing / WIND_UP_END);
		}
		if (swing < STRIKE_END) {
			return Mth.lerp(ease((swing - WIND_UP_END) / (STRIKE_END - WIND_UP_END)), -WIND_UP, FOLLOW_THROUGH);
		}
		return FOLLOW_THROUGH * (1.0F - ease((swing - STRIKE_END) / (1.0F - STRIKE_END)));
	}

	private static float ease(float t) {
		return t * t * (3.0F - 2.0F * t);
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

	/**
	 * A spear carried at the villager's right side: as if in the hand of an arm hanging from the right
	 * shoulder and leaning a little forward, then vanilla's hand-item steps, so the point faces forward
	 * and slightly up and the butt sits low. A hit thrusts it forward.
	 */
	public static void submitSpear(ModelPart root, HoldingEntityRenderState state, ItemStackRenderState spear, PoseStack poseStack,
		SubmitNodeCollector collector, int lightCoords, int outlineColor) {
		Float swing = state.getData(SWING);
		// Pulled back, then driven forward and a little down: a thrust rather than a chop.
		float thrust = swing == null ? 0.0F : -strikeAngle(swing) / WIND_UP * SPEAR_PULL;
		float drive = swing == null ? 0.0F : -strikeAngle(swing) / WIND_UP * SPEAR_DRIVE;
		poseStack.pushPose();
		root.translateAndRotate(poseStack);
		poseStack.translate(SPEAR_SHOULDER_X / 16.0F, SPEAR_SHOULDER_Y / 16.0F, drive / 16.0F);
		poseStack.rotateDegrees(Axis.XP, SPEAR_ARM_LEAN + thrust);
		// Exactly what vanilla's hand item layer does for a 12 pixel arm.
		poseStack.rotateDegrees(Axis.XP, -90.0F);
		poseStack.rotateDegrees(Axis.YP, 180.0F);
		poseStack.translate(1.0F / 16.0F, 2.0F / 16.0F, -10.0F / 16.0F);
		spear.submit(poseStack, collector, lightCoords, OverlayTexture.NO_OVERLAY, outlineColor);
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
		if (state.getData(AXE) != null) {
			// Back in towards the villager, so the handle sits in the folded arms.
			poseStack.translate(-AXE_PULL_IN, 0.0F, 0.0F);
			// Turned over around its own handle (the icon's bottom-left to top-right diagonal): the head
			// stays at the top, but its blade faces the other way, out in front like a sword's edge.
			poseStack.mulPose(new Matrix4f().rotation(new Quaternionf().rotateAxis((float) Math.PI, AXE_HANDLE)));
		}
	}
}
