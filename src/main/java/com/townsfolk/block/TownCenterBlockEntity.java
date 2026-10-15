package com.townsfolk.block;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.townsfolk.ModRegistry;
import com.townsfolk.menu.TownCenterMenu;
import com.townsfolk.town.VillagerNames;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
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
	/** Villagers within this many blocks that have no town yet join this one. */
	public static final int CLAIM_RADIUS = 64;
	private static final int CLAIM_INTERVAL_TICKS = 100;

	private record Resident(UUID uuid, String name) {
		static final Codec<Resident> CODEC = RecordCodecBuilder.create(i -> i.group(
			UUIDUtil.CODEC.fieldOf("uuid").forGetter(Resident::uuid),
			Codec.STRING.fieldOf("name").forGetter(Resident::name)
		).apply(i, Resident::new));
	}

	private final Map<UUID, String> residents = new LinkedHashMap<>();

	public TownCenterBlockEntity(BlockPos pos, BlockState state) {
		super(ModRegistry.TOWN_CENTER_BLOCK_ENTITY, pos, state);
	}

	public static void serverTick(Level level, BlockPos pos, BlockState state, TownCenterBlockEntity townCenter) {
		if ((level.getGameTime() + pos.asLong()) % CLAIM_INTERVAL_TICKS == 0) {
			townCenter.refreshResidents();
		}
	}

	/** Claims nearby villagers without a town and refreshes the names of loaded residents. */
	public void refreshResidents() {
		if (this.level == null || this.level.isClientSide()) {
			return;
		}

		boolean changed = false;
		AABB area = new AABB(this.worldPosition).inflate(CLAIM_RADIUS);
		for (Villager villager : this.level.getEntitiesOfClass(Villager.class, area, Villager::isAlive)) {
			BlockPos town = villager.getAttached(ModRegistry.TOWN);
			if (town == null) {
				villager.setAttached(ModRegistry.TOWN, this.worldPosition);
				town = this.worldPosition;
			}

			if (town.equals(this.worldPosition)) {
				String name = VillagerNames.nameOf(villager);
				if (!name.equals(this.residents.put(villager.getUUID(), name))) {
					changed = true;
				}
			}
		}

		if (changed) {
			this.setChanged();
		}
	}

	public void removeResident(UUID uuid) {
		if (this.residents.remove(uuid) != null) {
			this.setChanged();
		}
	}

	public List<String> residentNames() {
		return new ArrayList<>(this.residents.values());
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		this.residents.clear();
		input.listOrEmpty("residents", Resident.CODEC).forEach(r -> this.residents.put(r.uuid(), r.name()));
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		ValueOutput.TypedOutputList<Resident> list = output.list("residents", Resident.CODEC);
		this.residents.forEach((uuid, name) -> list.add(new Resident(uuid, name)));
	}

	@Override
	public Component getDisplayName() {
		return Component.translatable("container.townsfolk.town_center");
	}

	@Override
	public TownCenterMenu.Data getScreenOpeningData(ServerPlayer player) {
		return new TownCenterMenu.Data(this.worldPosition, this.residentNames());
	}

	@Override
	public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
		return new TownCenterMenu(containerId, inventory, new TownCenterMenu.Data(this.worldPosition, this.residentNames()));
	}
}
