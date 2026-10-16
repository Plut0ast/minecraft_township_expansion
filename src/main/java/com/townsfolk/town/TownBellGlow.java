package com.townsfolk.town;

import com.mojang.math.Transformation;
import com.townsfolk.block.TownCenterBlockEntity;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import org.joml.Vector3f;

/**
 * Ringing a bell makes the town's Town Center glow for a few seconds, seen through walls. The block
 * itself is left alone: a glowing block display copy of it is placed exactly over it and removed again.
 */
public final class TownBellGlow {
	private static final int GLOW_TICKS = 100;
	/** Marks the displays, so any left behind (the game closed mid-glow) are removed when loaded again. */
	private static final String TAG = "townsfolk_bell_glow";
	/** A hair bigger than the block, so the copy does not flicker against it. */
	private static final float GROW = 0.004F;

	private record Glow(Display.BlockDisplay display, long until) {
	}

	private static final Map<GlobalPos, Glow> GLOWS = new HashMap<>();

	private TownBellGlow() {
	}

	public static void register() {
		ServerTickEvents.END_LEVEL_TICK.register(TownBellGlow::tick);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> GLOWS.clear());
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (entity instanceof Display.BlockDisplay display && display.entityTags().contains(TAG)
				&& GLOWS.values().stream().noneMatch(glow -> glow.display() == display)) {
				display.discard();
			}
		});
	}

	/** Called when a player rings a bell: lights up the Town Center of the town the bell is in. */
	public static void onBellRung(ServerLevel level, BlockPos bell) {
		TownCenterBlockEntity town = TownCenters.townsNear(level, bell, null).stream()
			.min(Comparator.comparingDouble(t -> t.getBlockPos().distSqr(bell)))
			.orElse(null);
		if (town == null) {
			return;
		}
		BlockPos pos = town.getBlockPos();
		GlobalPos key = GlobalPos.of(level.dimension(), pos);
		long until = level.getGameTime() + GLOW_TICKS;
		Glow existing = GLOWS.get(key);
		if (existing != null && existing.display().isAlive()) {
			GLOWS.put(key, new Glow(existing.display(), until));
			return;
		}
		Display.BlockDisplay display = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.TRIGGERED);
		if (display == null) {
			return;
		}
		display.setPos(pos.getX(), pos.getY(), pos.getZ());
		display.setBlockState(level.getBlockState(pos));
		display.setTransformation(new Transformation(new Vector3f(-GROW / 2), null, new Vector3f(1.0F + GROW), null));
		display.setGlowingTag(true);
		display.addTag(TAG);
		level.addFreshEntity(display);
		GLOWS.put(key, new Glow(display, until));
	}

	private static void tick(ServerLevel level) {
		long now = level.getGameTime();
		Iterator<Map.Entry<GlobalPos, Glow>> it = GLOWS.entrySet().iterator();
		while (it.hasNext()) {
			Glow glow = it.next().getValue();
			if (glow.display().level() != level) {
				continue;
			}
			if (now >= glow.until() || !glow.display().isAlive()) {
				glow.display().discard();
				it.remove();
			}
		}
	}
}
