package com.townsfolk.menu;

import com.townsfolk.ModRegistry;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Exposes a villager's four armour slots and main hand as a 5-slot container. */
public class VillagerEquipmentContainer implements Container {
	public static final EquipmentSlot[] SLOTS = {
		EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.MAINHAND
	};
	public static final int HAND_INDEX = 4;

	/** Set while this container writes to the villager, so the hand-protection mixin lets the change through. */
	public static final ThreadLocal<Boolean> WRITING = ThreadLocal.withInitial(() -> false);

	private final AbstractVillager villager;

	public VillagerEquipmentContainer(AbstractVillager villager) {
		this.villager = villager;
	}

	public AbstractVillager villager() {
		return this.villager;
	}

	/** The hand slot only belongs to the player when it is empty or holds something a player gave. */
	public boolean handIsPlayerControlled() {
		return this.villager.getMainHandItem().isEmpty() || this.villager.getAttachedOrElse(ModRegistry.HAND_OWNED, false);
	}

	@Override
	public int getContainerSize() {
		return SLOTS.length;
	}

	@Override
	public boolean isEmpty() {
		for (EquipmentSlot slot : SLOTS) {
			if (!this.villager.getItemBySlot(slot).isEmpty()) {
				return false;
			}
		}
		return true;
	}

	@Override
	public ItemStack getItem(int index) {
		return this.villager.getItemBySlot(SLOTS[index]);
	}

	@Override
	public ItemStack removeItem(int index, int count) {
		ItemStack current = this.getItem(index);
		if (current.isEmpty() || count <= 0) {
			return ItemStack.EMPTY;
		}
		ItemStack remaining = current.copy();
		ItemStack taken = remaining.split(count);
		this.setItem(index, remaining);
		return taken;
	}

	@Override
	public ItemStack removeItemNoUpdate(int index) {
		ItemStack current = this.getItem(index);
		this.setItem(index, ItemStack.EMPTY);
		return current;
	}

	@Override
	public void setItem(int index, ItemStack stack) {
		EquipmentSlot slot = SLOTS[index];
		WRITING.set(true);
		try {
			this.villager.setItemSlot(slot, stack);
		} finally {
			WRITING.set(false);
		}

		if (!this.villager.level().isClientSide()) {
			// Player-given gear always drops when the villager dies.
			this.villager.setGuaranteedDrop(slot);
			if (slot == EquipmentSlot.MAINHAND) {
				if (stack.isEmpty()) {
					this.villager.removeAttached(ModRegistry.HAND_OWNED);
				} else {
					this.villager.setAttached(ModRegistry.HAND_OWNED, true);
				}
			}
		}
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
		for (int i = 0; i < SLOTS.length; i++) {
			this.setItem(i, ItemStack.EMPTY);
		}
	}
}
