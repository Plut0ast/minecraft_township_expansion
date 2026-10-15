package com.townsfolk.town;

import net.minecraft.ChatFormatting;
import net.minecraft.util.RandomSource;

/** Default town names, and cleaning up names players type in. */
public final class TownNames {
	public static final int MAX_LENGTH = 32;

	private static final String[] PREFIXES = {
		"Oak", "Ash", "Elm", "Stone", "River", "Mill", "Brook", "Iron", "Wheat", "Thorn",
		"Fox", "Raven", "Amber", "Copper", "Willow", "Maple", "Frost", "Sun", "Moss", "Clay",
		"Birch", "Hazel", "Pine", "Red", "Gold", "Honey", "Deep", "High", "Wind", "Barley"
	};
	private static final String[] SUFFIXES = {
		"hollow", "ford", "field", "brook", "vale", "wick", "stead", "haven", "bury", "ton",
		"more", "dale", "crest", "moor", "wood", "gate", "bridge", "well", "ridge", "mere"
	};

	private TownNames() {
	}

	public static String random(RandomSource random) {
		return PREFIXES[random.nextInt(PREFIXES.length)] + SUFFIXES[random.nextInt(SUFFIXES.length)];
	}

	/** Strips formatting codes and control characters and trims to length; empty means "invalid". */
	public static String clean(String name) {
		String stripped = ChatFormatting.stripFormatting(name);
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < stripped.length(); i++) {
			char c = stripped.charAt(i);
			if (!Character.isISOControl(c) && c != '§') {
				out.append(c);
			}
		}
		String cleaned = out.toString().trim().replaceAll("\\s+", " ");
		return cleaned.length() > MAX_LENGTH ? cleaned.substring(0, MAX_LENGTH).trim() : cleaned;
	}
}
