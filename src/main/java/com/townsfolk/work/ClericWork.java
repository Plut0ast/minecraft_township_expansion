package com.townsfolk.work;

import com.townsfolk.ModRegistry;
import java.util.Comparator;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SpellParticleOption;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.phys.AABB;

/** Clerics walk to hurt villagers of their town nearby and heal them, like a splash of healing. */
public final class ClericWork {
	private static final double SEARCH_RADIUS = 16.0;
	private static final float HEAL = 6.0F;
	private static final int COOLDOWN_TICKS = 200;
	/** The red of a potion of healing. */
	private static final int HEALING_COLOR = 0xF82423;

	private static final Map<Villager, Long> NEXT_HEAL = new WeakHashMap<>();

	private ClericWork() {
	}

	public static boolean isCleric(Villager villager) {
		return WorkHelper.is(villager, VillagerProfession.CLERIC);
	}

	public static void tick(Villager cleric, ServerLevel level, BlockPos brewingStand) {
		if (level.getGameTime() < NEXT_HEAL.getOrDefault(cleric, 0L)) {
			return;
		}
		BlockPos town = cleric.getAttached(ModRegistry.TOWN);
		Villager hurt = level.getEntitiesOfClass(Villager.class, new AABB(cleric.blockPosition()).inflate(SEARCH_RADIUS),
				v -> v.isAlive() && v.getHealth() < v.getMaxHealth() && (town == null || town.equals(v.getAttached(ModRegistry.TOWN))))
			.stream().min(Comparator.comparingDouble(cleric::distanceToSqr)).orElse(null);
		if (hurt == null || !WorkHelper.walkTo(cleric, hurt, WorkHelper.REACH)) {
			return;
		}
		NEXT_HEAL.put(cleric, level.getGameTime() + COOLDOWN_TICKS);
		hurt.heal(HEAL);
		level.sendParticles(SpellParticleOption.create(ParticleTypes.INSTANT_EFFECT, HEALING_COLOR, 1.0F),
			hurt.getX(), hurt.getY() + hurt.getBbHeight() * 0.6, hurt.getZ(), 16, 0.3, 0.4, 0.3, 0.0);
		WorkHelper.workSound(cleric, level);
	}
}
