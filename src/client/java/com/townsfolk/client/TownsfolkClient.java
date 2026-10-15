package com.townsfolk.client;

import com.townsfolk.ModRegistry;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.gui.screens.MenuScreens;

public class TownsfolkClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		MenuScreens.register(ModRegistry.TOWN_CENTER_MENU, TownCenterScreen::new);
		MenuScreens.register(ModRegistry.VILLAGER_MENU, VillagerScreen::new);
	}
}
