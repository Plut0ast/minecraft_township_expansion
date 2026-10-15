package com.townsfolk.town;

import com.mojang.serialization.Codec;
import com.townsfolk.ModRegistry;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;

/**
 * Orders a Trusted player can give a town's villager, like sitting a dog: wander (normal AI),
 * stay (stand still) or follow (walk after the player who gave the order).
 */
public final class VillagerCommands {
	public enum Command {
		WANDER, STAY, FOLLOW;

		public static final Codec<Command> CODEC = Codec.STRING.xmap(Command::valueOf, Command::name);

		public Command next() {
			return values()[(this.ordinal() + 1) % values().length];
		}
	}

	private static final float FOLLOW_SPEED = 0.7F;
	private static final double FOLLOW_START_DISTANCE = 3.0;
	private static final double TELEPORT_DISTANCE = 24.0;

	private VillagerCommands() {
	}

	public static Command commandOf(AbstractVillager villager) {
		return villager.getAttachedOrElse(ModRegistry.COMMAND, Command.WANDER);
	}

	/** Only a town's villagers take orders, and only from players that town trusts. */
	public static boolean canCommand(Player player, AbstractVillager villager) {
		if (!(villager instanceof Villager) || villager.isBaby() || !(villager.level() instanceof ServerLevel level)) {
			return false;
		}
		BlockPos town = villager.getAttached(ModRegistry.TOWN);
		if (town == null || TownCenters.isDestroyed(level, town)) {
			return false;
		}
		int reputation = TownReputation.get(level).get(town, player.getUUID());
		return TownReputation.Rank.of(reputation).ordinal() >= TownReputation.Rank.TRUSTED.ordinal();
	}

	/** Moves the villager to its next order; following always follows whoever clicked. */
	public static void cycle(Player player, AbstractVillager villager) {
		Command next = commandOf(villager).next();
		if (next == Command.WANDER) {
			villager.removeAttached(ModRegistry.COMMAND);
			villager.removeAttached(ModRegistry.COMMAND_OWNER);
		} else {
			villager.setAttached(ModRegistry.COMMAND, next);
			villager.setAttached(ModRegistry.COMMAND_OWNER, player.getUUID());
		}
		villager.getNavigation().stop();
		villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
	}

	/** Runs right after the villager's brain each tick, overriding where it wants to walk. */
	public static void afterBrainTick(Villager villager, ServerLevel level) {
		Command command = commandOf(villager);
		if (command == Command.WANDER || villager.isSleeping()) {
			return;
		}

		Brain<Villager> brain = villager.getBrain();
		UUID ownerId = villager.getAttached(ModRegistry.COMMAND_OWNER);
		ServerPlayer owner = ownerId == null ? null : level.getServer().getPlayerList().getPlayer(ownerId);
		boolean ownerHere = owner != null && owner.level() == level && !owner.isSpectator();

		if (command == Command.FOLLOW && ownerHere) {
			brain.setMemory(MemoryModuleType.LOOK_TARGET, new EntityTracker(owner, true));
			double distance = villager.distanceTo(owner);
			if (distance > TELEPORT_DISTANCE) {
				villager.getNavigation().stop();
				brain.eraseMemory(MemoryModuleType.WALK_TARGET);
				villager.teleportTo(owner.getX(), owner.getY(), owner.getZ());
			} else if (distance > FOLLOW_START_DISTANCE) {
				brain.setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(owner, FOLLOW_SPEED, 2));
			} else {
				brain.eraseMemory(MemoryModuleType.WALK_TARGET);
				villager.getNavigation().stop();
			}
			return;
		}

		// Staying, or following an owner who is offline or elsewhere: stand still.
		brain.eraseMemory(MemoryModuleType.WALK_TARGET);
		villager.getNavigation().stop();
	}
}
