package com.townsfolk.town;

import com.townsfolk.ModRegistry;
import com.townsfolk.TownsfolkConfig;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

/**
 * Keeps followers with their player. A following villager keeps its own chunk loaded (like a thrown
 * ender pearl), so it never freezes behind a player who flies, pearls or teleports away; and when its
 * player changes dimension, it goes too.
 */
public final class FollowerTravel {
	private static final int TICKET_INTERVAL_TICKS = 20;
	/** Followers waiting to be moved to their player's dimension (done after the tick, not mid-tick). */
	private static final Map<Villager, ServerPlayer> MOVING = new WeakHashMap<>();

	private FollowerTravel() {
	}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(FollowerTravel::moveAcrossDimensions);
	}

	/** Runs every tick for every villager, before anything else steers it. */
	public static void tick(Villager villager, ServerLevel level) {
		if (!TownsfolkConfig.followersTravel || VillagerCommands.commandOf(villager) != VillagerCommands.Command.FOLLOW) {
			return;
		}
		UUID ownerId = villager.getAttached(ModRegistry.COMMAND_OWNER);
		ServerPlayer owner = ownerId == null ? null : level.getServer().getPlayerList().getPlayer(ownerId);
		if (owner == null || owner.isSpectator()) {
			return;
		}
		if ((level.getGameTime() + villager.getId()) % TICKET_INTERVAL_TICKS == 0) {
			level.getChunkSource().addTicketWithRadius(TicketType.ENDER_PEARL, villager.chunkPosition(), 2);
		}
		if (owner.level() != level) {
			MOVING.put(villager, owner);
		}
	}

	private static void moveAcrossDimensions(MinecraftServer server) {
		if (MOVING.isEmpty()) {
			return;
		}
		Map<Villager, ServerPlayer> moving = Map.copyOf(MOVING);
		MOVING.clear();
		moving.forEach((villager, owner) -> {
			if (villager.isRemoved() || owner.isRemoved() || owner.level() == villager.level()) {
				return;
			}
			villager.teleport(new TeleportTransition((ServerLevel) owner.level(), owner.position(), Vec3.ZERO, owner.getYRot(), 0.0F,
				TeleportTransition.DO_NOTHING));
		});
	}
}
