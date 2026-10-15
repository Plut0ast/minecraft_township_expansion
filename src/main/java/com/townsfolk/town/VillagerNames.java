package com.townsfolk.town;

import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.npc.villager.AbstractVillager;

public final class VillagerNames {
	private static final String[] NAMES = {
		"Ada", "Aldric", "Alma", "Ansel", "Astrid", "Aubrey", "Barnaby", "Beatrix", "Bertram", "Bram",
		"Brigid", "Cedric", "Clara", "Corwin", "Dagny", "Darius", "Delphine", "Dorian", "Edda", "Edmund",
		"Elara", "Elias", "Elsie", "Emeric", "Esme", "Fenwick", "Fiona", "Florian", "Freya", "Gareth",
		"Greta", "Gideon", "Gwen", "Hamish", "Hazel", "Henrik", "Hilda", "Ida", "Ingrid", "Isolde",
		"Ivo", "Jasper", "Joan", "Jorah", "Juniper", "Kaspar", "Kestrel", "Lars", "Leif", "Lena",
		"Linus", "Lorna", "Lucan", "Mabel", "Magnus", "Marigold", "Matthias", "Maude", "Milo", "Mira",
		"Nell", "Nils", "Odette", "Olaf", "Orla", "Osric", "Otto", "Percival", "Petra", "Pip",
		"Quinn", "Rafe", "Rhea", "Roland", "Rosalind", "Rowan", "Sabine", "Silas", "Sigrid", "Soren",
		"Tamsin", "Tess", "Theo", "Thora", "Tobias", "Ulric", "Una", "Vera", "Viggo", "Wendel",
		"Willa", "Wren", "Xavier", "Yara", "Yorick", "Ysolde", "Zelda", "Ambrose", "Bridget", "Conrad",
		"Dulcie", "Ewan", "Fern", "Godfrey", "Harriet", "Ignatius", "Jemima", "Konrad", "Lettie", "Merrick",
		"Nora", "Oswin", "Prudence", "Reuben", "Saffron", "Tristan", "Ursula", "Vaughn", "Winifred", "Edric"
	};

	private VillagerNames() {
	}

	/** Gives the villager a random name if it does not already have one. */
	public static void ensureNamed(AbstractVillager villager) {
		if (!villager.hasCustomName()) {
			RandomSource random = villager.getRandom();
			villager.setCustomName(Component.literal(NAMES[random.nextInt(NAMES.length)]));
		}
	}

	public static String nameOf(AbstractVillager villager) {
		Component name = villager.getCustomName();
		return name != null ? name.getString() : villager.getType().getDescription().getString();
	}
}
