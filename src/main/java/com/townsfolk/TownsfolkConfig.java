package com.townsfolk;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Settings in {@code config/townsfolk.properties}. The file is written with every setting, its
 * default and a comment the first time the game runs, and topped up when new settings are added.
 * Changes apply on restart, or at once with {@code /town reload}.
 */
public final class TownsfolkConfig {
	private record Setting(String key, String defaultValue, String comment) {
	}

	private static final List<Setting> SETTINGS = new ArrayList<>();

	// Trades
	public static boolean dailyTradeRerolls;
	public static boolean perPlayerStanding;
	// Jobs
	public static boolean villagerJobs;
	public static boolean fishermen;
	public static boolean shepherds;
	public static boolean butchers;
	public static boolean clerics;
	public static boolean fletchers;
	public static boolean smiths;
	public static boolean smithGifts;
	public static boolean repairVisits;
	public static boolean playerRepairs;
	// Villagers
	public static boolean villagerFood;
	public static boolean arrivalWalk;
	public static boolean nitwitBabies;
	public static boolean nitwitSpawnEggs;
	// Sentries
	public static boolean sentries;
	public static boolean swordSentries;
	public static boolean axeSentries;
	public static boolean spearSentries;
	public static boolean archerSentries;
	public static boolean crossbowSentries;
	public static boolean sentryBodyguards;
	public static boolean sentriesAttackEnemies;
	public static boolean sentriesNeedArrows;
	public static boolean sentryWeaponsWearOut;
	public static double sentryGuardRadius;
	public static double sentryBodyguardRadius;
	public static double sentryBowRange;
	public static double sentryDamageMultiplier;
	public static int sentrySwordCooldown;
	public static int sentryBowCooldown;
	// Towns
	public static boolean villagerCapes;
	public static boolean playerCapes;
	public static boolean generateTownCenters;
	public static boolean farmChests;
	public static boolean golemsAttackEnemies;
	// Reputation
	public static int reputationPerTrade;
	public static int reputationLostPerHit;
	public static int reputationLostPerKill;

	static {
		section("Trades");
		add("dailyTradeRerolls", "true", "Villagers reroll their trades every Minecraft morning.");
		add("perPlayerStanding", "true", "Each villager remembers each player's own standing (Novice to Master), which decides which of its trades that player sees. Off = vanilla villager levels.");
		section("Jobs");
		add("villagerJobs", "true", "Master switch for all automatic villager jobs below (farmers' Farming order is separate).");
		add("fishermen", "true", "Fishermen fish and keep the catch in their barrel.");
		add("shepherds", "true", "Shepherds get a pen with sheep, shear them and store the wool.");
		add("butchers", "true", "Butchers smoke raw meat and feed hungry villagers.");
		add("clerics", "true", "Clerics heal hurt villagers.");
		add("fletchers", "true", "Fletchers craft arrows and supply archer Sentries.");
		add("smiths", "true", "Armourers, leatherworkers, weaponsmiths and toolsmiths do their work.");
		add("smithGifts", "true", "Smiths give villagers armour and tools for free.");
		add("repairVisits", "true", "Villagers take worn gear to a smith for a free repair.");
		add("playerRepairs", "true", "The Repair button on smiths' trade screens.");
		section("Villagers");
		add("villagerFood", "false", "Villagers get a food slot, start with bread and fetch food from farmers and butchers. (Either way, villagers below half health eat food from their pockets.)");
		add("arrivalWalk", "true", "Villagers walk to the Town Center and celebrate when they join a town.");
		add("nitwitBabies", "true", "Babies can be born nitwits if a parent is one (50% with one nitwit parent, 100% with two).");
		add("nitwitSpawnEggs", "true", "Villagers from spawn eggs have a 20% chance to be nitwits.");
		section("Sentries");
		add("sentries", "true", "Master switch: nitwits armed with a sword or bow become Sentries.");
		add("swordSentries", "true", "Nitwits holding a sword become Sentries.");
		add("axeSentries", "true", "Nitwits holding an axe become Sentries (slower, harder hits, like a player's axe).");
		add("spearSentries", "true", "Nitwits holding a spear become Sentries (a little more reach).");
		add("archerSentries", "true", "Nitwits holding a bow become archer Sentries.");
		add("crossbowSentries", "true", "Nitwits holding a crossbow become Sentries that load and fire it like a pillager.");
		add("sentryBodyguards", "true", "A Sentry following a Hero of its town guards that player.");
		add("sentriesAttackEnemies", "true", "Sentries attack players who are Enemies of their town (off = mobs only).");
		add("sentriesNeedArrows", "true", "Archers use arrows from their pockets. Off = they never run out.");
		add("sentryWeaponsWearOut", "true", "Sentries' swords and bows lose durability when used.");
		add("sentryGuardRadius", "16", "How far (in blocks) a Sentry looks for trouble while guarding its town.");
		add("sentryBodyguardRadius", "12", "How far (in blocks) around its Hero a bodyguard Sentry looks for trouble.");
		add("sentryBowRange", "14", "How far (in blocks) archer Sentries shoot from.");
		add("sentryDamageMultiplier", "1.0", "Multiplies Sentries' sword and arrow damage (1.0 = same as a player).");
		add("sentrySwordCooldown", "20", "Ticks between sword hits (20 ticks = 1 second). Axes and spears scale this by their attack speed.");
		add("sentryBowCooldown", "30", "Ticks between arrows (20 ticks = 1 second).");
		section("Towns");
		add("villagerCapes", "true", "A town's villagers and Sentries wear the banner from its Town Center as a cape.");
		add("playerCapes", "true", "Players who have joined a town wear its banner as a cape (in place of their own cape).");
		add("generateTownCenters", "true", "Villages get a Town Center automatically when a player first visits.");
		add("farmChests", "true", "Village farms get a chest with starter seeds.");
		add("golemsAttackEnemies", "true", "A town's iron golems attack players who are its Enemies.");
		section("Reputation");
		add("reputationPerTrade", "1", "Reputation gained with a town for each trade with one of its villagers.");
		add("reputationLostPerHit", "5", "Reputation lost for each hit on one of a town's villagers.");
		add("reputationLostPerKill", "25", "Extra reputation lost for killing one of a town's villagers.");
	}

	private TownsfolkConfig() {
	}

	private static void section(String name) {
		SETTINGS.add(new Setting(null, null, name));
	}

	private static void add(String key, String defaultValue, String comment) {
		SETTINGS.add(new Setting(key, defaultValue, comment));
	}

	public static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("townsfolk.properties");
	}

	/** Reads the file (writing it first if missing, or adding any new settings), then applies it. */
	public static void load() {
		Properties values = new Properties();
		Path file = file();
		if (Files.exists(file)) {
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				values.load(reader);
			} catch (IOException e) {
				Townsfolk.LOGGER.error("Could not read {}, using defaults", file, e);
			}
		}
		boolean missing = SETTINGS.stream().anyMatch(s -> s.key() != null && !values.containsKey(s.key()));
		if (missing) {
			write(file, values);
		}

		dailyTradeRerolls = bool(values, "dailyTradeRerolls");
		perPlayerStanding = bool(values, "perPlayerStanding");
		villagerJobs = bool(values, "villagerJobs");
		fishermen = bool(values, "fishermen");
		shepherds = bool(values, "shepherds");
		butchers = bool(values, "butchers");
		clerics = bool(values, "clerics");
		fletchers = bool(values, "fletchers");
		smiths = bool(values, "smiths");
		smithGifts = bool(values, "smithGifts");
		repairVisits = bool(values, "repairVisits");
		playerRepairs = bool(values, "playerRepairs");
		villagerFood = bool(values, "villagerFood");
		arrivalWalk = bool(values, "arrivalWalk");
		sentries = bool(values, "sentries");
		swordSentries = bool(values, "swordSentries");
		axeSentries = bool(values, "axeSentries");
		spearSentries = bool(values, "spearSentries");
		archerSentries = bool(values, "archerSentries");
		crossbowSentries = bool(values, "crossbowSentries");
		sentryBodyguards = bool(values, "sentryBodyguards");
		sentriesAttackEnemies = bool(values, "sentriesAttackEnemies");
		sentriesNeedArrows = bool(values, "sentriesNeedArrows");
		sentryWeaponsWearOut = bool(values, "sentryWeaponsWearOut");
		sentryGuardRadius = Math.max(1.0, decimal(values, "sentryGuardRadius"));
		sentryBodyguardRadius = Math.max(1.0, decimal(values, "sentryBodyguardRadius"));
		sentryBowRange = Math.max(2.0, decimal(values, "sentryBowRange"));
		sentryDamageMultiplier = Math.max(0.0, decimal(values, "sentryDamageMultiplier"));
		sentrySwordCooldown = Math.max(1, integer(values, "sentrySwordCooldown"));
		sentryBowCooldown = Math.max(1, integer(values, "sentryBowCooldown"));
		nitwitBabies = bool(values, "nitwitBabies");
		nitwitSpawnEggs = bool(values, "nitwitSpawnEggs");
		villagerCapes = bool(values, "villagerCapes");
		playerCapes = bool(values, "playerCapes");
		generateTownCenters = bool(values, "generateTownCenters");
		farmChests = bool(values, "farmChests");
		golemsAttackEnemies = bool(values, "golemsAttackEnemies");
		reputationPerTrade = integer(values, "reputationPerTrade");
		reputationLostPerHit = integer(values, "reputationLostPerHit");
		reputationLostPerKill = integer(values, "reputationLostPerKill");
	}

	private static String raw(Properties values, String key) {
		String value = values.getProperty(key);
		if (value == null) {
			value = SETTINGS.stream().filter(s -> key.equals(s.key())).findFirst().map(Setting::defaultValue).orElse("");
		}
		return value.trim();
	}

	private static boolean bool(Properties values, String key) {
		return Boolean.parseBoolean(raw(values, key));
	}

	private static int integer(Properties values, String key) {
		try {
			return Integer.parseInt(raw(values, key));
		} catch (NumberFormatException e) {
			Townsfolk.LOGGER.warn("Setting {} in townsfolk.properties is not a whole number; using its default", key);
			return Integer.parseInt(SETTINGS.stream().filter(s -> key.equals(s.key())).findFirst().orElseThrow().defaultValue());
		}
	}

	private static double decimal(Properties values, String key) {
		try {
			return Double.parseDouble(raw(values, key));
		} catch (NumberFormatException e) {
			Townsfolk.LOGGER.warn("Setting {} in townsfolk.properties is not a number; using its default", key);
			return Double.parseDouble(SETTINGS.stream().filter(s -> key.equals(s.key())).findFirst().orElseThrow().defaultValue());
		}
	}

	/** Writes every setting with its comment, keeping values already in the file. */
	private static void write(Path file, Properties existing) {
		StringBuilder out = new StringBuilder();
		out.append("# Township Expansion settings.\n");
		out.append("# true = on, false = off. Restart the game, or use /town reload, after changing this file.\n");
		for (Setting setting : SETTINGS) {
			if (setting.key() == null) {
				out.append("\n# ---- ").append(setting.comment()).append(" ----\n");
				continue;
			}
			out.append("# ").append(setting.comment()).append(" (default: ").append(setting.defaultValue()).append(")\n");
			out.append(setting.key()).append('=').append(existing.getProperty(setting.key(), setting.defaultValue())).append('\n');
		}
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			Townsfolk.LOGGER.error("Could not write {}", file, e);
		}
	}
}
