package com.townsfolk.town;

import com.townsfolk.ModRegistry;
import com.townsfolk.TownsfolkConfig;
import java.util.Map;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.BannerItem;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * A town's villagers (Sentries included) and its players wear the banner from its Town Center as a
 * cape. The server keeps each one's {@link ModRegistry#CAPE} up to date; clients draw it.
 */
public final class TownCapes {
	private static final int UPDATE_INTERVAL_TICKS = 20;

	private TownCapes() {
	}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(TownCapes::tickPlayers);
	}

	/** Called from the villager's server tick. */
	public static void tickVillager(Villager villager, ServerLevel level) {
		if ((level.getGameTime() + villager.getId()) % UPDATE_INTERVAL_TICKS != 0) {
			return;
		}
		boolean wears = TownsfolkConfig.villagerCapes && !villager.isBaby();
		apply(villager, wears ? bannerOf(level, villager.getAttached(ModRegistry.TOWN)) : ItemStack.EMPTY);
	}

	private static void tickPlayers(MinecraftServer server) {
		if (server.getTickCount() % UPDATE_INTERVAL_TICKS != 0) {
			return;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			apply(player, TownsfolkConfig.playerCapes ? playerBanner(server, player) : ItemStack.EMPTY);
		}
	}

	/** The banner of the town the player has joined, in any dimension. */
	private static ItemStack playerBanner(MinecraftServer server, ServerPlayer player) {
		ItemStack best = ItemStack.EMPTY;
		double bestDistance = Double.MAX_VALUE;
		for (ServerLevel level : server.getAllLevels()) {
			for (Map.Entry<BlockPos, TownRegistry.Town> entry : TownRegistry.get(level).all().entrySet()) {
				TownRegistry.Town town = entry.getValue();
				if (!town.players().containsKey(player.getUUID()) || !isBanner(town.banner())) {
					continue;
				}
				// Towns in another dimension only count when there is none in this one.
				double distance = level == player.level() ? entry.getKey().distSqr(player.blockPosition()) : Double.MAX_VALUE / 2;
				if (distance < bestDistance) {
					bestDistance = distance;
					best = town.banner();
				}
			}
		}
		return best;
	}

	private static ItemStack bannerOf(ServerLevel level, @Nullable BlockPos townPos) {
		TownRegistry.Town town = townPos == null ? null : TownRegistry.get(level).town(townPos);
		return town != null && isBanner(town.banner()) ? town.banner() : ItemStack.EMPTY;
	}

	private static boolean isBanner(ItemStack stack) {
		return stack.getItem() instanceof BannerItem;
	}

	/** Only touches the attachment when the cape changes, since every change is sent to players. */
	private static void apply(Entity entity, ItemStack banner) {
		ItemStack current = entity.getAttachedOrElse(ModRegistry.CAPE, ItemStack.EMPTY);
		if (ItemStack.matches(current, banner)) {
			return;
		}
		if (banner.isEmpty()) {
			entity.removeAttached(ModRegistry.CAPE);
		} else {
			entity.setAttached(ModRegistry.CAPE, banner.copyWithCount(1));
		}
	}
}
