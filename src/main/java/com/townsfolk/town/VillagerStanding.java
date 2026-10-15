package com.townsfolk.town;

import com.townsfolk.ModRegistry;
import com.townsfolk.mixin.AbstractVillagerInvoker;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.item.trading.TradeSet;

/**
 * Trades that reroll every morning, and each player's own standing with each villager.
 * <p>
 * Every Minecraft day a villager with a job rolls a fresh set of trades for all five levels, each
 * tagged with its level. Each player has their own standing (xp) with each villager, earned by
 * trading with it at vanilla's rate and thresholds; a player only sees the trades their standing has
 * unlocked, and the trade screen shows their own level and progress. Standing is kept across rerolls.
 * <p>
 * While a player trades, the villager's offers, level and xp are temporarily swapped for that player's
 * view (a {@link Session}) and restored when trading stops (and around saving).
 */
public final class VillagerStanding {
	/** Standing needed for each level: vanilla's villager xp thresholds (Novice 0 ... Master 250). */
	private static final int[] THRESHOLDS = {0, 10, 70, 150, 250};
	private static final int MAX_LEVEL = 5;

	/** A trade in progress: the villager's real offers, level and xp, and the trading player's view. */
	private static final class Session {
		final MerchantOffers allOffers;
		final List<Integer> tiers;
		final int ownLevel;
		final int ownXp;
		final UUID player;
		int playerLevel;
		int gainedXp;

		Session(MerchantOffers allOffers, List<Integer> tiers, int ownLevel, int ownXp, UUID player, int playerLevel) {
			this.allOffers = allOffers;
			this.tiers = tiers;
			this.ownLevel = ownLevel;
			this.ownXp = ownXp;
			this.player = player;
			this.playerLevel = playerLevel;
		}
	}

	private static final Map<Villager, Session> SESSIONS = new WeakHashMap<>();

	private VillagerStanding() {
	}

	public static int levelFor(int standing) {
		int level = 1;
		for (int i = 0; i < THRESHOLDS.length; i++) {
			if (standing >= THRESHOLDS[i]) {
				level = i + 1;
			}
		}
		return level;
	}

	public static int standing(Villager villager, UUID player) {
		return villager.getAttachedOrElse(ModRegistry.STANDING, Map.of()).getOrDefault(player, 0);
	}

	private static void addStanding(Villager villager, UUID player, int amount) {
		Map<UUID, Integer> standing = new HashMap<>(villager.getAttachedOrElse(ModRegistry.STANDING, Map.of()));
		standing.merge(player, amount, Integer::sum);
		villager.setAttached(ModRegistry.STANDING, standing);
	}

	private static boolean hasTrades(Villager villager) {
		return !villager.isBaby() && villager.getVillagerData().profession().value().getTrades(1) != null;
	}

	// ---- Daily reroll ----

	/** Runs each tick: rerolls the villager's trades once per Minecraft day (never mid-trade). */
	public static void tick(Villager villager, ServerLevel level) {
		if (SESSIONS.containsKey(villager) || !hasTrades(villager) || (level.getGameTime() + villager.getId()) % 20 != 0) {
			return;
		}
		long day = level.getOverworldClockTime() / 24000L; // a Minecraft day; sleeping skips ahead
		boolean rolled = villager.getAttached(ModRegistry.OFFER_TIERS) != null;
		if (!rolled || villager.getAttachedOrElse(ModRegistry.LAST_ROLL_DAY, -1L) != day) {
			reroll(villager, level);
			villager.setAttached(ModRegistry.LAST_ROLL_DAY, day);
		}
	}

	/** A fresh set of trades for every level, each tagged with the level it unlocks at. */
	private static void reroll(Villager villager, ServerLevel level) {
		VillagerProfession profession = villager.getVillagerData().profession().value();
		MerchantOffers offers = new MerchantOffers();
		List<Integer> tiers = new ArrayList<>();
		for (int tier = 1; tier <= MAX_LEVEL; tier++) {
			ResourceKey<TradeSet> set = profession.getTrades(tier);
			if (set == null) {
				continue;
			}
			int before = offers.size();
			((AbstractVillagerInvoker) villager).townsfolk$addOffersFromTradeSet(level, offers, set);
			for (int i = before; i < offers.size(); i++) {
				tiers.add(tier);
			}
		}
		villager.setOffers(offers);
		villager.setAttached(ModRegistry.OFFER_TIERS, tiers);
	}

	// ---- A player trading ----

	/** Before the trade screen opens: show this player only their unlocked trades, at their own level. */
	public static void beginTrading(Villager villager, Player player, int[] xpField) {
		if (!(villager.level() instanceof ServerLevel level) || !hasTrades(villager)) {
			return;
		}
		endTrading(villager, xpField);
		if (villager.getAttached(ModRegistry.OFFER_TIERS) == null) {
			reroll(villager, level);
		}
		List<Integer> tiers = villager.getAttachedOrElse(ModRegistry.OFFER_TIERS, List.of());
		MerchantOffers all = villager.getOffers();
		if (tiers.size() != all.size()) {
			reroll(villager, level); // trades changed under us (e.g. a profession change): start fresh
			tiers = villager.getAttachedOrElse(ModRegistry.OFFER_TIERS, List.of());
			all = villager.getOffers();
		}
		int standing = standing(villager, player.getUUID());
		int playerLevel = levelFor(standing);
		Session session = new Session(all, tiers, villager.getVillagerData().level(), xpField[0], player.getUUID(), playerLevel);
		SESSIONS.put(villager, session);
		villager.setOffers(visible(session));
		villager.setVillagerData(villager.getVillagerData().withLevel(playerLevel));
		xpField[0] = standing;
	}

	private static MerchantOffers visible(Session session) {
		MerchantOffers shown = new MerchantOffers();
		for (int i = 0; i < session.allOffers.size(); i++) {
			if (session.tiers.get(i) <= session.playerLevel) {
				shown.add(session.allOffers.get(i)); // same offers, so uses and stock carry over
			}
		}
		return shown;
	}

	/**
	 * A trade completed: the trading player's standing grows instead of the villager's own xp. Returns
	 * false when no player session is active (vanilla handles it).
	 */
	public static boolean rewardTrade(Villager villager, MerchantOffer offer, int[] xpField) {
		Session session = SESSIONS.get(villager);
		if (session == null || !(villager.level() instanceof ServerLevel level)) {
			return false;
		}
		addStanding(villager, session.player, offer.getXp());
		session.gainedXp += offer.getXp();
		int standing = standing(villager, session.player);
		xpField[0] = standing;
		int popXp = 3 + villager.getRandom().nextInt(4);
		int newLevel = levelFor(standing);
		if (newLevel > session.playerLevel) {
			session.playerLevel = newLevel;
			villager.setOffers(visible(session));
			villager.setVillagerData(villager.getVillagerData().withLevel(newLevel));
			villager.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 200, 0));
			popXp += 5;
			if (villager.getTradingPlayer() instanceof ServerPlayer player) {
				player.sendMerchantOffers(player.containerMenu.containerId, villager.getOffers(), newLevel, standing, true, true);
			}
		}
		if (offer.shouldRewardExp()) {
			level.addFreshEntity(new ExperienceOrb(level, villager.getX(), villager.getY() + 0.5, villager.getZ(), popXp));
		}
		return true;
	}

	/** Trading stopped: put the villager's real offers back; its own level is the highest anyone reached. */
	public static void endTrading(Villager villager, int[] xpField) {
		Session session = SESSIONS.remove(villager);
		if (session == null) {
			return;
		}
		villager.setOffers(session.allOffers);
		villager.setVillagerData(villager.getVillagerData().withLevel(Math.max(session.ownLevel, session.playerLevel)));
		xpField[0] = session.ownXp + session.gainedXp;
	}

	/** Around saving: write the villager's real offers, level and xp, not a player's view. */
	public static void beforeSave(Villager villager, int[] xpField) {
		Session session = SESSIONS.get(villager);
		if (session != null) {
			villager.setOffers(session.allOffers);
			villager.setVillagerData(villager.getVillagerData().withLevel(Math.max(session.ownLevel, session.playerLevel)));
			xpField[0] = session.ownXp + session.gainedXp;
		}
	}

	public static void afterSave(Villager villager, int[] xpField) {
		Session session = SESSIONS.get(villager);
		if (session != null) {
			villager.setOffers(visible(session));
			villager.setVillagerData(villager.getVillagerData().withLevel(session.playerLevel));
			xpField[0] = standing(villager, session.player);
		}
	}
}
