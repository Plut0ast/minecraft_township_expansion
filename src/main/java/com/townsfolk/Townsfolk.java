package com.townsfolk;

import com.townsfolk.town.RenameTownPayload;
import com.townsfolk.town.RenameVillagerPayload;
import com.townsfolk.town.RepairPayloads;
import com.townsfolk.town.TownCommand;
import com.townsfolk.town.TownEvents;
import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Townsfolk implements ModInitializer {
	public static final String MOD_ID = "townsfolk";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		TownsfolkConfig.load();
		ModRegistry.init();
		TownEvents.init();
		RenameTownPayload.register();
		RenameVillagerPayload.register();
		RepairPayloads.register();
		TownCommand.register();
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
