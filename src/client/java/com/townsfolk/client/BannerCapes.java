package com.townsfolk.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.townsfolk.ModRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BannerRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.sprite.SpriteGetter;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.BannerItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import org.jspecify.annotations.Nullable;

/**
 * Town banners worn as capes. The cape is the banner's own flag (20 x 40 pixels, so the banner
 * pattern textures line up) shrunk to cape size, 10 x 16.
 */
public final class BannerCapes {
	/** The banner an entity wears this frame; absent when it wears none. */
	public static final RenderStateDataKey<Cape> CAPE = RenderStateDataKey.create(() -> "townsfolk:banner_cape");

	public record Cape(DyeColor base, BannerPatternLayers patterns) {
	}

	private BannerCapes() {
	}

	public static @Nullable Cape capeOf(Entity entity) {
		ItemStack banner = entity.getAttachedOrElse(ModRegistry.CAPE, ItemStack.EMPTY);
		if (!(banner.getItem() instanceof BannerItem item)) {
			return null;
		}
		return new Cape(item.getColor(), banner.getOrDefault(DataComponents.BANNER_PATTERNS, BannerPatternLayers.EMPTY));
	}

	/** Adds the cape part: a banner flag hanging from the top of the back, at {@code backZ}. */
	static void addCape(PartDefinition parent, float backZ) {
		parent.addOrReplaceChild(
			"cape",
			CubeListBuilder.create().texOffs(0, 0).addBox(-10.0F, 0.0F, -1.0F, 20.0F, 40.0F, 1.0F),
			PartPose.offsetAndRotation(0.0F, 0.0F, backZ, 0.0F, (float) Math.PI, 0.0F).scaled(0.5F, 0.4F, 1.0F)
		);
	}

	static ModelPart cape(ModelPart parent) {
		return parent.getChild("cape");
	}

	/** Draws the banner cloth, then its base colour and patterns, like a placed banner. */
	static <S> void submit(Model<S> model, S state, PoseStack poseStack, SubmitNodeCollector collector, SpriteGetter sprites,
		int lightCoords, int outlineColor, Cape cape) {
		// Solid like a placed banner: a translucent cloth is drawn after the patterns and covers them.
		collector.submitModel(model, state, poseStack, Sheets.BANNER_BASE.renderType(RenderTypes::entitySolid), lightCoords,
			OverlayTexture.NO_OVERLAY, -1, sprites.get(Sheets.BANNER_BASE), outlineColor);
		BannerRenderer.submitPatterns(sprites, poseStack, collector, lightCoords, OverlayTexture.NO_OVERLAY, model, state, true,
			cape.base(), cape.patterns());
	}
}
