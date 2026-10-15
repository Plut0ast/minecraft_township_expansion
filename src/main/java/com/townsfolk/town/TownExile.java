package com.townsfolk.town;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.townsfolk.ModRegistry;
import com.townsfolk.block.TownCenterBlockEntity;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Removing a villager from its town. A Trusted member of the town can do it; the villager then
 * heads for the nearest other town and joins it. The old town will not take it back for a while,
 * but after that it may rejoin, for example if no other town was reachable.
 */
public final class TownExile {
	/** {@code from}: the town it was removed from; {@code until}: game time the old town may take it back; {@code heading}: town it is walking to. */
	public record Exile(BlockPos from, long until, Optional<BlockPos> heading) {
		public static final Codec<Exile> CODEC = RecordCodecBuilder.create(i -> i.group(
			BlockPos.CODEC.fieldOf("from").forGetter(Exile::from),
			Codec.LONG.fieldOf("until").forGetter(Exile::until),
			BlockPos.CODEC.optionalFieldOf("heading").forGetter(Exile::heading)
		).apply(i, Exile::new));
	}

	/** How long (5 minutes) the old town refuses the villager. */
	private static final long COOLDOWN = 6000;
	/** How far a removed villager will travel to find a new town. */
	private static final int SEARCH_RADIUS = 512;
	/** Walk in steps this long, so far-off towns stay within pathfinding range. */
	private static final double STEP = 24.0;
	private static final float WALK_SPEED = 0.6F;

	private TownExile() {
	}

	/** Players who are both members of the villager's town and Trusted by it may remove villagers. */
	public static boolean canRemove(Player player, AbstractVillager villager) {
		if (!(villager instanceof Villager) || !(villager.level() instanceof ServerLevel level)) {
			return false;
		}
		BlockPos town = villager.getAttached(ModRegistry.TOWN);
		if (town == null || TownCenters.isDestroyed(level, town)) {
			return false;
		}
		boolean trusted = TownReputation.Rank.of(TownReputation.get(level).get(town, player.getUUID())).ordinal()
			>= TownReputation.Rank.TRUSTED.ordinal();
		return trusted && TownRegistry.get(level).isMember(town, player.getUUID());
	}

	public static void remove(Villager villager, ServerLevel level) {
		BlockPos from = villager.getAttached(ModRegistry.TOWN);
		if (from == null) {
			return;
		}
		villager.removeAttached(ModRegistry.TOWN);
		villager.removeAttached(ModRegistry.COMMAND);
		villager.removeAttached(ModRegistry.COMMAND_OWNER);
		villager.removeAttached(ModRegistry.ARRIVAL_DEADLINE);
		if (level.isLoaded(from) && level.getBlockEntity(from) instanceof TownCenterBlockEntity town) {
			town.removeResident(villager.getUUID());
		}
		villager.setAttached(ModRegistry.EXILE,
			new Exile(from, level.getGameTime() + COOLDOWN, Optional.ofNullable(nearestOtherTown(level, villager.blockPosition(), from))));
	}

	/** The town a removed villager may not rejoin yet, or {@code null}. Clears the exile once it has expired. */
	public static @Nullable BlockPos barredTown(AbstractVillager villager, ServerLevel level) {
		Exile exile = villager.getAttached(ModRegistry.EXILE);
		if (exile == null) {
			return null;
		}
		if (level.getGameTime() > exile.until()) {
			villager.removeAttached(ModRegistry.EXILE);
			return null;
		}
		return exile.from();
	}

	private static @Nullable BlockPos nearestOtherTown(ServerLevel level, BlockPos pos, BlockPos exclude) {
		BlockPos best = null;
		double bestDist = (double) SEARCH_RADIUS * SEARCH_RADIUS;
		for (BlockPos town : TownRegistry.get(level).all().keySet()) {
			double dist = town.distSqr(pos);
			if (!town.equals(exclude) && dist < bestDist) {
				best = town;
				bestDist = dist;
			}
		}
		return best;
	}

	/** Runs right after the villager's brain each tick: walks a removed villager towards its new town. */
	public static void afterBrainTick(Villager villager, ServerLevel level) {
		Exile exile = villager.getAttached(ModRegistry.EXILE);
		if (exile == null || exile.heading().isEmpty() || villager.getAttached(ModRegistry.TOWN) != null) {
			return;
		}
		if (villager.isSleeping() || VillagerCommands.commandOf(villager) != VillagerCommands.Command.WANDER) {
			return;
		}

		// Once inside the new town's area its Town Center will claim the villager.
		Vec3 target = Vec3.atBottomCenterOf(exile.heading().get());
		Vec3 here = villager.position();
		double distance = here.distanceTo(target);
		if (distance < TownCenterBlockEntity.CLAIM_RADIUS - 8) {
			return;
		}
		Vec3 step = distance <= STEP ? target : here.add(target.subtract(here).normalize().scale(STEP));
		// Re-aim every couple of seconds so ordinary wandering does not keep sidetracking the trip.
		if (!villager.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET) || level.getGameTime() % 40 == 0) {
			villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(step, WALK_SPEED, 3));
		}
	}
}
