package com.townsfolk.block;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.townsfolk.ModRegistry;
import com.townsfolk.menu.TownCenterMenu;
import com.townsfolk.town.TownArrival;
import com.townsfolk.town.TownCenters;
import com.townsfolk.town.TownExile;
import com.townsfolk.town.TownNames;
import com.townsfolk.town.TownRegistry;
import com.townsfolk.town.TownReputation;
import com.townsfolk.town.VillagerNames;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;

public class TownCenterBlockEntity extends BlockEntity implements ExtendedMenuProvider<TownCenterMenu.Data> {
	/** Villagers and iron golems within this many blocks that have no town yet join this one. */
	public static final int CLAIM_RADIUS = 64;
	private static final int CLAIM_INTERVAL_TICKS = 100;

	private record Resident(UUID uuid, String name, boolean golem) {
		static final Codec<Resident> CODEC = RecordCodecBuilder.create(i -> i.group(
			UUIDUtil.CODEC.fieldOf("uuid").forGetter(Resident::uuid),
			Codec.STRING.fieldOf("name").forGetter(Resident::name),
			Codec.BOOL.optionalFieldOf("golem", false).forGetter(Resident::golem)
		).apply(i, Resident::new));
	}

	private final Map<UUID, Resident> residents = new LinkedHashMap<>();
	private String townName = "";
	/** Game time this town was founded; newer towns win members that have no town yet. -1 until first tick. */
	private long foundedAt = -1;

	public TownCenterBlockEntity(BlockPos pos, BlockState state) {
		super(ModRegistry.TOWN_CENTER_BLOCK_ENTITY, pos, state);
	}

	public static void serverTick(Level level, BlockPos pos, BlockState state, TownCenterBlockEntity townCenter) {
		if (townCenter.foundedAt < 0) {
			townCenter.foundedAt = level.getGameTime();
			townCenter.setChanged();
		}
		townCenter.ensureTownName();
		if ((level.getGameTime() + pos.asLong()) % CLAIM_INTERVAL_TICKS == 0) {
			townCenter.refreshResidents();
		}
	}

	/** Gives a new town a random default name such as "Oakhollow". */
	public void ensureTownName() {
		if (this.townName.isEmpty() && this.level != null) {
			this.townName = TownNames.random(this.level.getRandom());
			this.setChanged();
		}
	}

	public String townName() {
		this.ensureTownName();
		return this.townName;
	}

	public void setTownName(String townName) {
		this.townName = townName;
		this.setChanged();
		this.syncRegistry();
	}

	/** Keeps this town's entry in the dimension's {@link TownRegistry} current, for browsing from other towns. */
	private void syncRegistry() {
		if (this.level instanceof ServerLevel serverLevel) {
			TownRegistry.get(serverLevel).update(this.worldPosition, this.townName(), this.names(false), this.names(true));
		}
	}

	private TownReputation.Rank rankOf(Player player) {
		return this.level instanceof ServerLevel serverLevel
			? TownReputation.Rank.of(TownReputation.get(serverLevel).get(this.worldPosition, player.getUUID()))
			: TownReputation.Rank.STRANGER;
	}

	private boolean isMember(Player player) {
		return this.level instanceof ServerLevel serverLevel && TownRegistry.get(serverLevel).isMember(this.worldPosition, player.getUUID());
	}

	/** Whoever places a Town Center founds the town and becomes its first player. */
	public void found(Player founder) {
		if (this.level instanceof ServerLevel serverLevel) {
			this.syncRegistry();
			TownRegistry.get(serverLevel).addMember(this.worldPosition, founder.getUUID(), founder.getName().getString());
		}
	}

	/** Heroes of this town may join it as players. Returns whether the player joined. */
	public boolean join(Player player) {
		if (!(this.level instanceof ServerLevel serverLevel) || this.rankOf(player) != TownReputation.Rank.HERO || this.isMember(player)) {
			return false;
		}
		this.syncRegistry();
		TownRegistry.get(serverLevel).addMember(this.worldPosition, player.getUUID(), player.getName().getString());
		return true;
	}

	/** Trusted (or better) players may rename the town and give its villagers orders. */
	public boolean isTrustedBy(Player player) {
		return this.rankOf(player).ordinal() >= TownReputation.Rank.TRUSTED.ordinal();
	}

	/** Newer towns sort higher; a town that has not ticked yet was placed just now, so it is the newest. */
	public long foundedAt() {
		return this.foundedAt < 0 ? Long.MAX_VALUE : this.foundedAt;
	}

	/**
	 * Claims nearby villagers and iron golems without a town and refreshes the names of loaded residents.
	 * A member without a town joins the newest town whose area it is in, so it may be left for another town.
	 */
	public void refreshResidents() {
		if (!(this.level instanceof ServerLevel serverLevel)) {
			return;
		}

		boolean changed = false;
		AABB area = new AABB(this.worldPosition).inflate(CLAIM_RADIUS);
		for (Mob member : serverLevel.getEntitiesOfClass(Mob.class, area, m -> m.isAlive() && TownCenters.canJoinTown(m))) {
			BlockPos town = member.getAttached(ModRegistry.TOWN);
			if (town != null && TownCenters.isDestroyed(serverLevel, town)) {
				member.removeAttached(ModRegistry.TOWN);
				town = null;
			}
			BlockPos barred = member instanceof Villager villager ? TownExile.barredTown(villager, serverLevel) : null;
			if (town == null && this.worldPosition.equals(TownCenters.townFor(serverLevel, member.blockPosition(), barred))) {
				member.setAttached(ModRegistry.TOWN, this.worldPosition);
				member.removeAttached(ModRegistry.EXILE);
				if (member instanceof Villager villager) {
					TownArrival.start(villager, serverLevel);
				}
				town = this.worldPosition;
			}

			if (this.worldPosition.equals(town)) {
				Resident resident = new Resident(member.getUUID(), VillagerNames.nameOf(member), member instanceof IronGolem);
				if (!resident.equals(this.residents.put(member.getUUID(), resident))) {
					changed = true;
				}
			}
		}

		if (changed) {
			this.setChanged();
		}
		this.syncRegistry();
	}

	/** When this Town Center is broken, its members become free to join another town and its reputation is lost. */
	@Override
	public void preRemoveSideEffects(BlockPos pos, BlockState state) {
		super.preRemoveSideEffects(pos, state);
		if (this.level instanceof ServerLevel serverLevel) {
			TownReputation.get(serverLevel).clear(pos);
			TownRegistry.get(serverLevel).remove(pos);
			AABB area = new AABB(pos).inflate(CLAIM_RADIUS * 2);
			for (Mob member : serverLevel.getEntitiesOfClass(Mob.class, area, m -> pos.equals(m.getAttached(ModRegistry.TOWN)))) {
				member.removeAttached(ModRegistry.TOWN);
			}
		}
	}

	/** Number of villagers (golems are not counted). */
	public int population() {
		return (int) this.residents.values().stream().filter(r -> !r.golem()).count();
	}

	public void removeResident(UUID uuid) {
		if (this.residents.remove(uuid) != null) {
			this.setChanged();
		}
	}

	private List<String> names(boolean golems) {
		List<String> names = new ArrayList<>();
		for (Resident resident : this.residents.values()) {
			if (resident.golem() == golems) {
				names.add(resident.name());
			}
		}
		return names;
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		this.foundedAt = input.getLongOr("founded_at", -1L);
		this.townName = input.getStringOr("name", "");
		this.residents.clear();
		input.listOrEmpty("residents", Resident.CODEC).forEach(r -> this.residents.put(r.uuid(), r));
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		output.putLong("founded_at", this.foundedAt);
		output.putString("name", this.townName);
		ValueOutput.TypedOutputList<Resident> list = output.list("residents", Resident.CODEC);
		this.residents.values().forEach(list::add);
	}

	@Override
	public Component getDisplayName() {
		return Component.translatable("container.townsfolk.town_center");
	}

	@Override
	public TownCenterMenu.Data getScreenOpeningData(ServerPlayer player) {
		return this.menuData(player);
	}

	/** This town first, then every other town in the dimension, nearest first. */
	private TownCenterMenu.Data menuData(Player player) {
		List<TownCenterMenu.TownView> towns = new ArrayList<>();
		if (this.level instanceof ServerLevel serverLevel) {
			this.syncRegistry();
			TownReputation reputation = TownReputation.get(serverLevel);
			Map<BlockPos, TownRegistry.Town> all = TownRegistry.get(serverLevel).all();
			List<BlockPos> order = new ArrayList<>(all.keySet());
			order.remove(this.worldPosition);
			order.sort(Comparator.comparingDouble(pos -> pos.distSqr(this.worldPosition)));
			order.addFirst(this.worldPosition);
			for (BlockPos pos : order) {
				TownRegistry.Town town = all.get(pos);
				if (town != null) {
					towns.add(new TownCenterMenu.TownView(pos, town.name(), town.villagers(), town.golems(),
						new ArrayList<>(town.players().values()), reputation.get(pos, player.getUUID())));
				}
			}
		}
		if (towns.isEmpty()) {
			towns.add(new TownCenterMenu.TownView(this.worldPosition, this.townName, this.names(false), this.names(true), List.of(), 0));
		}
		boolean canJoin = this.rankOf(player) == TownReputation.Rank.HERO && !this.isMember(player);
		return new TownCenterMenu.Data(towns, this.isTrustedBy(player), canJoin);
	}

	@Override
	public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
		return new TownCenterMenu(containerId, inventory, this.menuData(player));
	}
}
