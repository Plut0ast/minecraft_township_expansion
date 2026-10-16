package com.townsfolk.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.resources.model.EquipmentAssetManager;
import net.minecraft.client.resources.model.EquipmentClientInfo;
import net.minecraft.client.resources.model.sprite.SpriteGetter;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.Equippable;
import org.joml.Quaternionf;

/**
 * A town member's banner cape on players, in place of their own cape. It hangs and flaps exactly like
 * the vanilla cape, and is hidden by an elytra.
 */
public class PlayerBannerCapeLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
	private final CapeModel model;
	private final EquipmentAssetManager equipmentAssets;
	private final SpriteGetter sprites;

	public PlayerBannerCapeLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent, EntityRendererProvider.Context context) {
		super(parent);
		this.model = new CapeModel(CapeModel.createLayer().bakeRoot());
		this.equipmentAssets = context.getEquipmentAssets();
		this.sprites = context.getSprites();
	}

	private boolean hasLayer(ItemStack stack, EquipmentClientInfo.LayerType layerType) {
		Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
		return equippable != null && equippable.assetId().isPresent()
			&& !this.equipmentAssets.get(equippable.assetId().get()).getLayers(layerType).isEmpty();
	}

	@Override
	public void submit(PoseStack poseStack, SubmitNodeCollector collector, int lightCoords, AvatarRenderState state, float yRot, float xRot) {
		BannerCapes.Cape cape = state.getData(BannerCapes.CAPE);
		if (cape == null || state.isInvisible || this.hasLayer(state.chestEquipment, EquipmentClientInfo.LayerType.WINGS)) {
			return;
		}
		poseStack.pushPose();
		if (this.hasLayer(state.chestEquipment, EquipmentClientInfo.LayerType.HUMANOID)) {
			poseStack.translate(0.0F, -0.053125F, 0.06875F);
		}
		BannerCapes.submit(this.model, state, poseStack, collector, this.sprites, lightCoords, state.outlineColor, cape);
		poseStack.popPose();
	}

	/**
	 * Only a body pivot with the banner cape on it, posed like {@code PlayerCapeModel}. It holds no other
	 * parts of the player, so nothing but the cape can ever be drawn with the banner's texture.
	 */
	private static class CapeModel extends Model<AvatarRenderState> {
		private final ModelPart body;
		private final ModelPart cape;

		CapeModel(ModelPart root) {
			super(root, RenderTypes::entitySolid);
			this.body = root.getChild("body");
			this.cape = BannerCapes.cape(this.body);
		}

		static LayerDefinition createLayer() {
			MeshDefinition mesh = new MeshDefinition();
			PartDefinition body = mesh.getRoot().addOrReplaceChild("body", CubeListBuilder.create(), PartPose.ZERO);
			BannerCapes.addCape(body, 2.0F);
			return LayerDefinition.create(mesh, 64, 64);
		}

		@Override
		public void setupAnim(AvatarRenderState state) {
			super.setupAnim(state);
			// The body leans and drops when crouching, as in HumanoidModel.
			if (state.isCrouching) {
				this.body.xRot = 0.5F;
				this.body.y += 3.2F;
			}
			this.cape.rotateBy(new Quaternionf()
				.rotateY((float) -Math.PI)
				.rotateX((6.0F + state.capeLean / 2.0F + state.capeFlap) * (float) (Math.PI / 180.0))
				.rotateZ(state.capeLean2 / 2.0F * (float) (Math.PI / 180.0))
				.rotateY((180.0F - state.capeLean2 / 2.0F) * (float) (Math.PI / 180.0)));
		}
	}
}
