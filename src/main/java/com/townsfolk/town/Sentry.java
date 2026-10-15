package com.townsfolk.town;

import com.townsfolk.ModRegistry;
import com.townsfolk.block.TownCenterBlockEntity;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * A town's nitwit armed with a sword becomes a Sentry: like an iron golem it fights hostile mobs in its
 * town and the town's Enemies. Following a Hero of its town, it guards that player instead. Only
 * nitwits can be Sentries; it is their job, and disarmed they are plain nitwits again.
 */
public final class Sentry {
	/** How far a Sentry looks for trouble around itself (wandering) or its Hero (following). */
	private static final double GUARD_RADIUS = 16.0;
	private static final double BODYGUARD_RADIUS = 12.0;
	/** Staying Sentries only hit what comes right up to them. */
	private static final double STAY_RADIUS = 3.0;
	private static final double REACH = 2.2;
	private static final int ATTACK_COOLDOWN_TICKS = 20;
	private static final int TARGET_SEARCH_INTERVAL_TICKS = 10;
	private static final float CHASE_SPEED = 0.75F;

	private static final Map<Villager, LivingEntity> TARGETS = new WeakHashMap<>();
	private static final Map<Villager, Long> NEXT_ATTACK = new WeakHashMap<>();

	private Sentry() {
	}

	/** Only nitwits can be armed as Sentries. */
	public static boolean canBecomeSentry(Villager villager) {
		return !villager.isBaby() && villager.getVillagerData().profession().is(VillagerProfession.NITWIT);
	}

	/** A nitwit of a town holding a sword. */
	public static boolean isSentry(Villager villager) {
		return canBecomeSentry(villager) && villager.getMainHandItem().is(ItemTags.SWORDS) && villager.getAttached(ModRegistry.TOWN) != null;
	}

	/**
	 * Runs right after the villager's brain each tick. Returns true while the Sentry is fighting, so
	 * other movement (following, walking to town) waits until the fight is over.
	 */
	public static boolean afterBrainTick(Villager villager, ServerLevel level) {
		if (!isSentry(villager)) {
			TARGETS.remove(villager);
			return false;
		}
		if (villager.isSleeping()) {
			TARGETS.remove(villager);
			return false;
		}

		BlockPos town = villager.getAttached(ModRegistry.TOWN);
		VillagerCommands.Command command = VillagerCommands.commandOf(villager);
		ServerPlayer hero = command == VillagerCommands.Command.FOLLOW ? heroOwner(villager, level, town) : null;

		LivingEntity target = TARGETS.get(villager);
		if (!isValidTarget(level, villager, town, target, hero, command)) {
			target = null;
			TARGETS.remove(villager);
			if ((level.getGameTime() + villager.getId()) % TARGET_SEARCH_INTERVAL_TICKS == 0) {
				target = findTarget(level, villager, town, hero, command);
				if (target != null) {
					TARGETS.put(villager, target);
				}
			}
		}
		if (target == null) {
			return false;
		}

		villager.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, new EntityTracker(target, true));
		if (villager.distanceTo(target) > REACH) {
			if (command != VillagerCommands.Command.STAY) {
				villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(target, CHASE_SPEED, 1));
			}
			return true;
		}

		villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
		long now = level.getGameTime();
		if (now >= NEXT_ATTACK.getOrDefault(villager, 0L) && villager.hasLineOfSight(target)) {
			NEXT_ATTACK.put(villager, now + ATTACK_COOLDOWN_TICKS);
			attack(level, villager, target);
		}
		return true;
	}

	/** The player this Sentry follows, if that player is a Hero of its town. */
	private static @Nullable ServerPlayer heroOwner(Villager villager, ServerLevel level, BlockPos town) {
		UUID ownerId = villager.getAttached(ModRegistry.COMMAND_OWNER);
		ServerPlayer owner = ownerId == null ? null : level.getServer().getPlayerList().getPlayer(ownerId);
		if (owner == null || owner.level() != level || owner.isSpectator()) {
			return null;
		}
		int reputation = TownReputation.get(level).get(town, owner.getUUID());
		return TownReputation.Rank.of(reputation) == TownReputation.Rank.HERO ? owner : null;
	}

	/** Where the Sentry is guarding and how far around it. */
	private static Vec3 guardCenter(Villager villager, @Nullable ServerPlayer hero) {
		return hero != null ? hero.position() : villager.position();
	}

	private static double guardRadius(VillagerCommands.Command command, @Nullable ServerPlayer hero) {
		if (hero != null) {
			return BODYGUARD_RADIUS;
		}
		return command == VillagerCommands.Command.STAY ? STAY_RADIUS : GUARD_RADIUS;
	}

	private static @Nullable LivingEntity findTarget(ServerLevel level, Villager villager, BlockPos town, @Nullable ServerPlayer hero,
		VillagerCommands.Command command) {
		// A follower who is not guarding a Hero just follows; other orders (and farming) leave fighting to others.
		if (command == VillagerCommands.Command.FARM || (command == VillagerCommands.Command.FOLLOW && hero == null)) {
			return null;
		}
		Vec3 center = guardCenter(villager, hero);
		double radius = guardRadius(command, hero);
		AABB area = new AABB(center, center).inflate(radius);
		return level.getEntitiesOfClass(LivingEntity.class, area, e -> isValidTarget(level, villager, town, e, hero, command)).stream()
			.min(Comparator.comparingDouble(villager::distanceToSqr))
			.orElse(null);
	}

	private static boolean isValidTarget(ServerLevel level, Villager villager, BlockPos town, @Nullable LivingEntity target,
		@Nullable ServerPlayer hero, VillagerCommands.Command command) {
		if (target == null || !target.isAlive() || target.level() != level || !isHostile(level, town, target)) {
			return false;
		}
		Vec3 center = guardCenter(villager, hero);
		// A little slack so the Sentry does not give up the moment a mob steps out of range.
		if (target.position().distanceTo(center) > guardRadius(command, hero) * 1.5) {
			return false;
		}
		// Guarding the town (not a Hero): only fight inside the town's area.
		return hero != null || target.position().distanceTo(Vec3.atCenterOf(town)) <= TownCenterBlockEntity.CLAIM_RADIUS;
	}

	/** Hostile mobs (but not creepers or endermen, like iron golems) and Enemies of the town. */
	private static boolean isHostile(ServerLevel level, BlockPos town, LivingEntity entity) {
		if (entity instanceof Monster) {
			return !(entity instanceof Creeper) && !(entity instanceof Enderman);
		}
		if (entity instanceof ServerPlayer player && !player.isCreative() && !player.isSpectator()) {
			int reputation = TownReputation.get(level).get(town, player.getUUID());
			return TownReputation.Rank.of(reputation) == TownReputation.Rank.ENEMY;
		}
		return false;
	}

	private static void attack(ServerLevel level, Villager villager, LivingEntity target) {
		ItemStack sword = villager.getMainHandItem();
		villager.swingForAttack(InteractionHand.MAIN_HAND);
		if (target.hurtServer(level, level.damageSources().mobAttack(villager), damageOf(sword))) {
			sword.hurtAndBreak(1, villager, EquipmentSlot.MAINHAND);
		}
	}

	/** The sword's own attack damage (wood 4 up to netherite 8), as if a player swung it. */
	private static float damageOf(ItemStack sword) {
		ItemAttributeModifiers modifiers = sword.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
		return (float) modifiers.compute(Attributes.ATTACK_DAMAGE, 1.0, EquipmentSlot.MAINHAND);
	}
}
