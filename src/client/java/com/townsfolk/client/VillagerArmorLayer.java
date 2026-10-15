package com.townsfolk.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.MeshTransformer;
import net.minecraft.client.model.monster.zombie.BabyZombieVillagerModel;
import net.minecraft.client.model.monster.zombie.ZombieVillagerModel;
import net.minecraft.client.model.npc.VillagerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.ArmorModelSet;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.EquipmentLayerRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.VillagerRenderState;
import net.minecraft.client.renderer.entity.state.ZombieVillagerRenderState;
import net.minecraft.client.resources.model.EquipmentClientInfo;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.Equippable;

/**
 * Draws armour on villagers. Villagers share their body shape with zombie villagers, so this reuses
 * the zombie villager armour models, scaled to villager size and posed from the villager's own render
 * state. The armour's sleeves are hidden because villagers keep their arms crossed; the robe and hat
 * underneath are hidden by {@code VillagerModelMixin}.
 */
public class VillagerArmorLayer extends RenderLayer<VillagerRenderState, VillagerModel> {
	/** Armour pose and items for one villager this frame; absent when it wears no armour. */
	public static final RenderStateDataKey<ZombieVillagerRenderState> ARMOR = RenderStateDataKey.create(() -> "townsfolk:villager_armor");

	private static final MeshTransformer VILLAGER_SCALE = MeshTransformer.scaling(0.9375F);
	private static final CubeDeformation INNER_DEFORMATION = new CubeDeformation(0.5F);
	private static final CubeDeformation OUTER_DEFORMATION = new CubeDeformation(1.0F);

	private static final EquipmentSlot[] ARMOR_SLOTS = {EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.HEAD};

	private final ArmorModelSet<ZombieVillagerModel<ZombieVillagerRenderState>> models;
	private final ArmorModelSet<ZombieVillagerModel<ZombieVillagerRenderState>> babyModels;
	private final EquipmentLayerRenderer equipmentRenderer;

	public VillagerArmorLayer(RenderLayerParent<VillagerRenderState, VillagerModel> parent, EntityRendererProvider.Context context) {
		super(parent);
		// Adult villagers are drawn at 93.75% of the zombie villager size, so the armour is shrunk to match.
		this.models = hideSleeves(ZombieVillagerModel.createArmorLayerSet(INNER_DEFORMATION, OUTER_DEFORMATION)
			.map(layer -> new ZombieVillagerModel<ZombieVillagerRenderState>(layer.apply(VILLAGER_SCALE).bakeRoot())));
		this.babyModels = hideSleeves(ArmorModelSet.bake(ModelLayers.ZOMBIE_VILLAGER_BABY_ARMOR, context.getModelSet(), BabyZombieVillagerModel::new));
		this.equipmentRenderer = context.getEquipmentRenderer();
	}

	private static <M extends HumanoidModel<?>> ArmorModelSet<M> hideSleeves(ArmorModelSet<M> set) {
		set.chest().rightArm.visible = false;
		set.chest().leftArm.visible = false;
		return set;
	}

	/** Called while the villager's render state is extracted; copies the armour it is wearing. */
	public static void extract(AbstractVillager villager, VillagerRenderState state) {
		ItemStack head = renderable(villager, EquipmentSlot.HEAD);
		ItemStack chest = renderable(villager, EquipmentSlot.CHEST);
		ItemStack legs = renderable(villager, EquipmentSlot.LEGS);
		ItemStack feet = renderable(villager, EquipmentSlot.FEET);
		if (head.isEmpty() && chest.isEmpty() && legs.isEmpty() && feet.isEmpty()) {
			state.setData(ARMOR, null);
			return;
		}

		ZombieVillagerRenderState armor = new ZombieVillagerRenderState();
		armor.headEquipment = head;
		armor.chestEquipment = chest;
		armor.legsEquipment = legs;
		armor.feetEquipment = feet;
		state.setData(ARMOR, armor);
	}

	private static ItemStack renderable(AbstractVillager villager, EquipmentSlot slot) {
		ItemStack stack = villager.getItemBySlot(slot);
		return HumanoidArmorLayer.shouldRender(stack, slot) ? stack.copy() : ItemStack.EMPTY;
	}

	@Override
	public void submit(PoseStack poseStack, SubmitNodeCollector collector, int lightCoords, VillagerRenderState state, float yRot, float xRot) {
		ZombieVillagerRenderState armor = state.getData(ARMOR);
		if (armor == null || state.isInvisible) {
			return;
		}
		// Copy the pose now rather than at extraction: screens like the villager menu turn the
		// villager towards the mouse after extraction, and the armour has to follow.
		armor.ageInTicks = state.ageInTicks;
		armor.walkAnimationPos = state.walkAnimationPos;
		armor.walkAnimationSpeed = state.walkAnimationSpeed;
		armor.yRot = state.yRot;
		armor.xRot = state.xRot;
		armor.bodyRot = state.bodyRot;
		armor.isBaby = state.isBaby;
		armor.ageScale = state.ageScale;
		armor.pose = state.pose;
		armor.entityType = state.entityType;

		for (EquipmentSlot slot : ARMOR_SLOTS) {
			ItemStack stack = switch (slot) {
				case HEAD -> armor.headEquipment;
				case CHEST -> armor.chestEquipment;
				case LEGS -> armor.legsEquipment;
				default -> armor.feetEquipment;
			};
			Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
			if (equippable == null || equippable.assetId().isEmpty()) {
				continue;
			}

			EquipmentClientInfo.LayerType layerType = armor.isBaby
				? EquipmentClientInfo.LayerType.HUMANOID_BABY
				: slot == EquipmentSlot.LEGS ? EquipmentClientInfo.LayerType.HUMANOID_LEGGINGS : EquipmentClientInfo.LayerType.HUMANOID;
			ZombieVillagerModel<ZombieVillagerRenderState> model = (armor.isBaby ? this.babyModels : this.models).get(slot);
			this.equipmentRenderer.renderLayers(
				layerType, equippable.assetId().get(), model, armor, stack, poseStack, collector, lightCoords, state.outlineColor
			);
		}
	}
}
