package com.townsfolk.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.npc.VillagerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.VillagerRenderState;
import net.minecraft.client.renderer.entity.state.ZombieVillagerRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.resources.model.sprite.SpriteGetter;
import org.joml.Quaternionf;

/**
 * A town villager's banner cape, hanging behind its robe. Villagers have no cape physics, so it
 * swings out a little while they walk.
 */
public class VillagerBannerCapeLayer extends RenderLayer<VillagerRenderState, VillagerModel> {
	/** The back of the villager's robe. */
	private static final float BACK_Z = 3.5F;
	/** How far back the cape is pushed over a chestplate. */
	private static final float ARMOR_OFFSET = 0.6F / 16.0F;

	private final CapeModel model = new CapeModel();
	private final SpriteGetter sprites;

	public VillagerBannerCapeLayer(RenderLayerParent<VillagerRenderState, VillagerModel> parent, EntityRendererProvider.Context context) {
		super(parent);
		this.sprites = context.getSprites();
	}

	@Override
	public void submit(PoseStack poseStack, SubmitNodeCollector collector, int lightCoords, VillagerRenderState state, float yRot, float xRot) {
		BannerCapes.Cape cape = state.getData(BannerCapes.CAPE);
		if (cape == null || state.isInvisible || state.isBaby) {
			return;
		}
		poseStack.pushPose();
		ZombieVillagerRenderState armor = state.getData(VillagerArmorLayer.ARMOR);
		if (armor != null && !armor.chestEquipment.isEmpty()) {
			poseStack.translate(0.0F, 0.0F, ARMOR_OFFSET);
		}
		BannerCapes.submit(this.model, state, poseStack, collector, this.sprites, lightCoords, state.outlineColor, cape);
		poseStack.popPose();
	}

	private static class CapeModel extends Model<VillagerRenderState> {
		private final ModelPart cape;

		CapeModel() {
			super(createLayer().bakeRoot(), RenderTypes::entitySolid);
			this.cape = BannerCapes.cape(this.root());
		}

		static LayerDefinition createLayer() {
			MeshDefinition mesh = new MeshDefinition();
			BannerCapes.addCape(mesh.getRoot(), BACK_Z);
			return LayerDefinition.create(mesh, 64, 64);
		}

		@Override
		public void setupAnim(VillagerRenderState state) {
			super.setupAnim(state);
			float lean = 6.0F + Math.min(state.walkAnimationSpeed, 1.0F) * 25.0F;
			this.cape.rotateBy(new Quaternionf()
				.rotateY((float) -Math.PI)
				.rotateX(lean * (float) (Math.PI / 180.0))
				.rotateY((float) Math.PI));
		}
	}
}
