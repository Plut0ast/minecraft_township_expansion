package com.townsfolk.town;

import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Mob;

/** Random names for villagers, wandering traders and (robotic) iron golems. */
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

	private static final String[] GOLEM_NAMES = {
		"Bolt", "Rivet", "Cog", "Clank", "Anvil", "Gearhart", "Ironside", "Piston", "Sprocket", "Tinker",
		"Ingot", "Forge", "Ferrum", "Magnet", "Titan", "Brass", "Dynamo", "Servo", "Gizmo", "Crank",
		"Lugnut", "Spanner", "Cinder", "Bulwark", "Sentinel", "Warden", "Plate", "Hinge", "Bellows", "Tungsten",
		"Cobalt", "Ratchet", "Chassis", "Boiler", "Steelheart", "Cogsworth", "Quarry", "Ballast", "Gauge", "Valve"
	};

	private VillagerNames() {
	}

	/** Gives the mob a random name if it does not already have one. */
	public static void ensureNamed(Mob mob) {
		if (!mob.hasCustomName()) {
			RandomSource random = mob.getRandom();
			mob.setCustomName(Component.literal(NAMES[random.nextInt(NAMES.length)]));
		}
	}

	/** Gives an iron golem a robotic name with a serial number, e.g. "Rivet-42". */
	public static void ensureGolemNamed(Mob golem) {
		if (!golem.hasCustomName()) {
			RandomSource random = golem.getRandom();
			String name = GOLEM_NAMES[random.nextInt(GOLEM_NAMES.length)] + "-" + (1 + random.nextInt(99));
			golem.setCustomName(Component.literal(name));
		}
	}

	public static String nameOf(Mob mob) {
		Component name = mob.getCustomName();
		return name != null ? name.getString() : mob.getType().getDescription().getString();
	}
}
