package com.townsfolk.client;

import com.townsfolk.ModRegistry;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityRenderLayerRegistrationCallback;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.model.npc.VillagerModel;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.VillagerRenderState;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.world.entity.EntityTypes;

public class TownsfolkClient implements ClientModInitializer {
	@Override
	@SuppressWarnings("unchecked")
	public void onInitializeClient() {
		MenuScreens.register(ModRegistry.TOWN_CENTER_MENU, TownCenterScreen::new);
		MenuScreens.register(ModRegistry.VILLAGER_MENU, VillagerScreen::new);
		MenuScreens.register(ModRegistry.REPAIR_MENU, RepairScreen::new);
		RepairScreen.register();

		LivingEntityRenderLayerRegistrationCallback.EVENT.register((entityType, renderer, helper, context) -> {
			if (entityType == EntityTypes.VILLAGER || entityType == EntityTypes.WANDERING_TRADER) {
				helper.register(new VillagerArmorLayer((RenderLayerParent<VillagerRenderState, VillagerModel>) renderer, context));
			}
			if (entityType == EntityTypes.VILLAGER) {
				helper.register(new VillagerBannerCapeLayer((RenderLayerParent<VillagerRenderState, VillagerModel>) renderer, context));
			}
			if (entityType == EntityTypes.PLAYER) {
				helper.register(new PlayerBannerCapeLayer((RenderLayerParent<AvatarRenderState, PlayerModel>) renderer, context));
			}
		});
	}
}
