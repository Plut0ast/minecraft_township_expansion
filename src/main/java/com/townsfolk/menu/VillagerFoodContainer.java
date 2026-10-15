package com.townsfolk.menu;

import com.townsfolk.town.VillagerFood;
import net.minecraft.world.Container;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** The villager's food slot as a one-slot container for the villager screen. */
public class VillagerFoodContainer implements Container {
	private final Villager villager;

	public VillagerFoodContainer(Villager villager) {
		this.villager = villager;
	}

	@Override
	public int getContainerSize() {
		return 1;
	}

	@Override
	public boolean isEmpty() {
		return VillagerFood.get(this.villager).isEmpty();
	}

	@Override
	public ItemStack getItem(int slot) {
		return VillagerFood.get(this.villager);
	}

	@Override
	public ItemStack removeItem(int slot, int count) {
		ItemStack current = VillagerFood.get(this.villager);
		if (current.isEmpty() || count <= 0) {
			return ItemStack.EMPTY;
		}
		int taken = Math.min(count, current.getCount());
		VillagerFood.set(this.villager, current.copyWithCount(current.getCount() - taken));
		return current.copyWithCount(taken);
	}

	@Override
	public ItemStack removeItemNoUpdate(int slot) {
		ItemStack current = VillagerFood.get(this.villager);
		VillagerFood.set(this.villager, ItemStack.EMPTY);
		return current;
	}

	@Override
	public void setItem(int slot, ItemStack stack) {
		VillagerFood.set(this.villager, stack);
	}

	@Override
	public void setChanged() {
	}

	@Override
	public boolean stillValid(Player player) {
		return this.villager.isAlive() && player.distanceToSqr(this.villager) <= 64.0;
	}

	@Override
	public void clearContent() {
		VillagerFood.set(this.villager, ItemStack.EMPTY);
	}
}
