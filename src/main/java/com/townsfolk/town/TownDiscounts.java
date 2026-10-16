package com.townsfolk.town;

import com.townsfolk.ModRegistry;
import com.townsfolk.TownsfolkConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.trading.MerchantOffer;

/**
 * Heroes of a town get cheaper trades from every one of its villagers, like the Hero of the Village
 * effect after a raid (which already gives its own discount, so the two do not stack).
 */
public final class TownDiscounts {
	private TownDiscounts() {
	}

	/** Runs after vanilla works out a trading player's special prices. */
	public static void apply(Villager villager, Player player) {
		if (TownsfolkConfig.heroTradeDiscount <= 0.0 || !(villager.level() instanceof ServerLevel level)
			|| player.hasEffect(MobEffects.HERO_OF_THE_VILLAGE)) {
			return;
		}
		BlockPos town = villager.getAttached(ModRegistry.TOWN);
		if (town == null || TownCenters.isDestroyed(level, town)
			|| TownReputation.Rank.of(TownReputation.get(level).get(town, player.getUUID())) != TownReputation.Rank.HERO) {
			return;
		}
		for (MerchantOffer offer : villager.getOffers()) {
			int reduction = (int) Math.floor(TownsfolkConfig.heroTradeDiscount * offer.getBaseCostA().getCount());
			offer.addToSpecialPriceDiff(-Math.max(reduction, 1));
		}
		if (villager.getTradingPlayer() != null && villager.getTradingPlayer().containerMenu instanceof MerchantMenu menu) {
			menu.updateSellItem();
		}
	}
}
