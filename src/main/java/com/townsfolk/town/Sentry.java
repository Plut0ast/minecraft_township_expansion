package com.townsfolk.town;

import com.townsfolk.TownsfolkConfig;
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
import net.minecraft.world.item.Items;
import net.minecraft.world.SimpleContainer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * A town's nitwit armed with a sword becomes a Sentry: like an iron golem it fights hostile mobs in its
 * town and the town's Enemies. Following a Hero of its town, it guards that player instead. Only
 * nitwits can be Sentries; it is their job, and disarmed they are plain nitwits again.
 */
public final class Sentry {
	// Guard and bodyguard radius, bow range, damage and cooldowns come from TownsfolkConfig.
	/** Staying Sentries only hit what comes right up to them. */
	private static final double STAY_RADIUS = 3.0;
	private static final double REACH = 2.2;
	/** Spears are long, so spear Sentries strike from further away. */
	private static final double SPEAR_REACH = 3.2;
	/** A sword's attack speed; slower weapons (axes) wait longer between hits than the sword cooldown. */
	private static final double SWORD_SPEED = 1.6;
	/** How long a Sentry's melee swing animation lasts. */
	private static final int ATTACK_SWING_TICKS = 10;
	private static final int TARGET_SEARCH_INTERVAL_TICKS = 10;
	private static final float CHASE_SPEED = 0.75F;
	/** Archers draw for this long before loosing, like a skeleton (shorter if the bow cooldown is shorter). */
	private static final int BOW_DRAW_TICKS = 20;

	private static final Map<Villager, LivingEntity> TARGETS = new WeakHashMap<>();
	private static final Map<Villager, Long> NEXT_ATTACK = new WeakHashMap<>();

	private Sentry() {
	}

	/** Only nitwits can be armed as Sentries. */
	public static boolean canBecomeSentry(Villager villager) {
		return !villager.isBaby() && villager.getVillagerData().profession().is(VillagerProfession.NITWIT);
	}

	/** A nitwit of a town holding a sword, axe, spear or bow. */
	public static boolean isSentry(Villager villager) {
		// A Sentry stays one while it eats, with its weapon put away.
		ItemStack weapon = VillagerFood.heldOutsideMeals(villager);
		boolean allowed = weapon.is(Items.BOW) ? TownsfolkConfig.archerSentries
			: weapon.is(ItemTags.AXES) ? TownsfolkConfig.axeSentries
			: weapon.is(ItemTags.SPEARS) ? TownsfolkConfig.spearSentries
			: TownsfolkConfig.swordSentries;
		return TownsfolkConfig.sentries && allowed && canBecomeSentry(villager) && isSentryWeapon(weapon)
			&& villager.getAttached(ModRegistry.TOWN) != null;
	}

	/** Swords, axes and spears for close fighting, bows for shooting arrows from the Sentry's pockets. */
	public static boolean isSentryWeapon(ItemStack stack) {
		return stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES) || stack.is(ItemTags.SPEARS) || stack.is(Items.BOW);
	}

	private static double reachOf(ItemStack weapon) {
		return weapon.is(ItemTags.SPEARS) ? SPEAR_REACH : REACH;
	}

	/**
	 * Ticks between melee hits: the sword cooldown setting, made longer for weapons slower than a sword
	 * (axes) and shorter for faster ones, using the weapon's own attack speed like a player.
	 */
	private static int cooldownOf(ItemStack weapon) {
		ItemAttributeModifiers modifiers = weapon.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
		double speed = modifiers.compute(Attributes.ATTACK_SPEED, 4.0, EquipmentSlot.MAINHAND);
		if (speed <= 0.0) {
			return TownsfolkConfig.sentrySwordCooldown;
		}
		return Math.max(1, (int) Math.round(TownsfolkConfig.sentrySwordCooldown * SWORD_SPEED / speed));
	}

	public static boolean isArcher(Villager villager) {
		return villager.getMainHandItem().is(Items.BOW);
	}

	/**
	 * Runs right after the villager's brain each tick. Returns true while the Sentry is fighting, so
	 * other movement (following, walking to town) waits until the fight is over.
	 */
	public static boolean afterBrainTick(Villager villager, ServerLevel level) {
		if (!isSentry(villager) || villager.isSleeping() || VillagerFood.isEating(villager)) {
			TARGETS.remove(villager);
			lowerBow(villager);
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
			lowerBow(villager);
			return false;
		}

		villager.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, new EntityTracker(target, true));
		if (isArcher(villager)) {
			return shootAt(level, villager, target, command);
		}
		if (villager.distanceTo(target) > reachOf(villager.getMainHandItem())) {
			if (command != VillagerCommands.Command.STAY) {
				villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(target, CHASE_SPEED, 1));
			}
			return true;
		}

		villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
		long now = level.getGameTime();
		if (now >= NEXT_ATTACK.getOrDefault(villager, 0L) && villager.hasLineOfSight(target)) {
			NEXT_ATTACK.put(villager, now + cooldownOf(villager.getMainHandItem()));
			attack(level, villager, target);
		}
		return true;
	}

	/** The player this Sentry follows, if that player is a Hero of its town. */
	private static @Nullable ServerPlayer heroOwner(Villager villager, ServerLevel level, BlockPos town) {
		UUID ownerId = villager.getAttached(ModRegistry.COMMAND_OWNER);
		ServerPlayer owner = ownerId == null ? null : level.getServer().getPlayerList().getPlayer(ownerId);
		if (!TownsfolkConfig.sentryBodyguards || owner == null || owner.level() != level || owner.isSpectator()) {
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
			return TownsfolkConfig.sentryBodyguardRadius;
		}
		return command == VillagerCommands.Command.STAY ? STAY_RADIUS : TownsfolkConfig.sentryGuardRadius;
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
		if (TownsfolkConfig.sentriesAttackEnemies && entity instanceof ServerPlayer player && !player.isCreative() && !player.isSpectator()) {
			int reputation = TownReputation.get(level).get(town, player.getUUID());
			return TownReputation.Rank.of(reputation) == TownReputation.Rank.ENEMY;
		}
		return false;
	}

	/**
	 * Archers close in to bow range and shoot arrows from their pockets, like a skeleton. Without
	 * arrows they cannot fight (a fletcher brings more).
	 */
	private static boolean shootAt(ServerLevel level, Villager villager, LivingEntity target, VillagerCommands.Command command) {
		if (TownsfolkConfig.sentriesNeedArrows && !hasArrows(villager)) {
			lowerBow(villager);
			return false;
		}
		double bowRange = TownsfolkConfig.sentryBowRange;
		double distance = villager.distanceTo(target);
		boolean inSight = villager.hasLineOfSight(target);
		if (distance > bowRange || !inSight) {
			lowerBow(villager);
		}
		if ((distance > bowRange || !inSight) && command != VillagerCommands.Command.STAY) {
			villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(target, CHASE_SPEED, (int) (bowRange / 2)));
			return true;
		}
		villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
		villager.getNavigation().stop();
		long now = level.getGameTime();
		int drawTicks = Math.min(BOW_DRAW_TICKS, TownsfolkConfig.sentryBowCooldown);
		if (inSight && distance <= bowRange && !villager.isUsingItem() && now >= NEXT_ATTACK.getOrDefault(villager, 0L)) {
			// Start drawing; the arrow flies once the bow is fully drawn.
			villager.startUsingItem(InteractionHand.MAIN_HAND);
		}
		if (inSight && distance <= bowRange && villager.isUsingItem() && villager.getTicksUsingItem() >= drawTicks) {
			villager.stopUsingItem();
			NEXT_ATTACK.put(villager, now + Math.max(0, TownsfolkConfig.sentryBowCooldown - drawTicks));
			ItemStack bow = villager.getMainHandItem();
			ItemStack arrowItem = TownsfolkConfig.sentriesNeedArrows ? takeArrow(villager) : new ItemStack(Items.ARROW);
			AbstractArrow arrow = ProjectileUtil.getMobArrow(villager, arrowItem, 1.0F, bow);
			if (TownsfolkConfig.sentryDamageMultiplier != 1.0) {
				// An arrow's base damage is 2 (Power and other enchantments still add on hit).
				arrow.setBaseDamage(2.0 * TownsfolkConfig.sentryDamageMultiplier);
			}
			double xd = target.getX() - villager.getX();
			double yd = target.getY(0.3333333333333333) - arrow.getY();
			double zd = target.getZ() - villager.getZ();
			double flat = Math.sqrt(xd * xd + zd * zd);
			Projectile.spawnProjectileUsingShoot(arrow, level, arrowItem, xd, yd + flat * 0.2F, zd, 1.6F, 6.0F);
			level.playSound(null, villager.getX(), villager.getY(), villager.getZ(), SoundEvents.ARROW_SHOOT, SoundSource.NEUTRAL,
				1.0F, 1.0F / (villager.getRandom().nextFloat() * 0.4F + 0.8F));
			// A quiet villager "hrmm" with the shot, so archers sound like villagers rather than skeletons.
			level.playSound(null, villager.getX(), villager.getY(), villager.getZ(), SoundEvents.VILLAGER_AMBIENT, SoundSource.NEUTRAL,
				0.35F, 0.9F + villager.getRandom().nextFloat() * 0.2F);
			if (TownsfolkConfig.sentryWeaponsWearOut) {
				bow.hurtAndBreak(1, villager, EquipmentSlot.MAINHAND);
			}
		}
		return true;
	}

	/** Stops drawing the bow, if it was. */
	private static void lowerBow(Villager villager) {
		if (villager.isUsingItem() && villager.getUseItem().is(Items.BOW)) {
			villager.stopUsingItem();
		}
	}

	public static boolean hasArrows(Villager villager) {
		SimpleContainer pockets = villager.getInventory();
		for (int i = 0; i < pockets.getContainerSize(); i++) {
			if (pockets.getItem(i).is(ItemTags.ARROWS)) {
				return true;
			}
		}
		return false;
	}

	public static int arrowCount(Villager villager) {
		int count = 0;
		SimpleContainer pockets = villager.getInventory();
		for (int i = 0; i < pockets.getContainerSize(); i++) {
			if (pockets.getItem(i).is(ItemTags.ARROWS)) {
				count += pockets.getItem(i).getCount();
			}
		}
		return count;
	}

	private static ItemStack takeArrow(Villager villager) {
		SimpleContainer pockets = villager.getInventory();
		for (int i = 0; i < pockets.getContainerSize(); i++) {
			ItemStack stack = pockets.getItem(i);
			if (stack.is(ItemTags.ARROWS)) {
				ItemStack one = stack.split(1);
				pockets.setChanged();
				return one;
			}
		}
		return new ItemStack(Items.ARROW);
	}

	private static void attack(ServerLevel level, Villager villager, LivingEntity target) {
		ItemStack weapon = villager.getMainHandItem();
		// A slower swing than a player's (6 ticks), so the wind-up and strike can be seen.
		villager.swing(InteractionHand.MAIN_HAND, new SwingAnimation(weapon.getAttackAnimation().type(), ATTACK_SWING_TICKS));
		float damage = (float) (damageOf(weapon) * TownsfolkConfig.sentryDamageMultiplier);
		if (target.hurtServer(level, level.damageSources().mobAttack(villager), damage) && TownsfolkConfig.sentryWeaponsWearOut) {
			weapon.hurtAndBreak(1, villager, EquipmentSlot.MAINHAND);
		}
	}

	/** The weapon's own attack damage (a netherite sword 8, a diamond axe 9), as if a player swung it. */
	private static float damageOf(ItemStack weapon) {
		ItemAttributeModifiers modifiers = weapon.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
		return (float) modifiers.compute(Attributes.ATTACK_DAMAGE, 1.0, EquipmentSlot.MAINHAND);
	}
}
