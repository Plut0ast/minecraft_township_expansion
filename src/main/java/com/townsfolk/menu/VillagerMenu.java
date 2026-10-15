package com.townsfolk.menu;

import com.townsfolk.ModRegistry;
import java.util.Map;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ArmorSlot;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/** A villager's or wandering trader's armour + hand slots, the player's inventory, and a Trade button (button id 0). */
public class VillagerMenu extends AbstractContainerMenu {
	public static final int BUTTON_TRADE = 0;
	public static final int EQUIPMENT_SLOTS = VillagerEquipmentContainer.SLOTS.length;

	private static final Map<EquipmentSlot, Identifier> EMPTY_ICONS = Map.of(
		EquipmentSlot.HEAD, InventoryMenu.EMPTY_ARMOR_SLOT_HELMET,
		EquipmentSlot.CHEST, InventoryMenu.EMPTY_ARMOR_SLOT_CHESTPLATE,
		EquipmentSlot.LEGS, InventoryMenu.EMPTY_ARMOR_SLOT_LEGGINGS,
		EquipmentSlot.FEET, InventoryMenu.EMPTY_ARMOR_SLOT_BOOTS
	);

	public record Data(int entityId, String name, Component profession, boolean canTrade) {
		public static final StreamCodec<RegistryFriendlyByteBuf, Data> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, Data::entityId,
			ByteBufCodecs.STRING_UTF8, Data::name,
			ComponentSerialization.TRUSTED_STREAM_CODEC, Data::profession,
			ByteBufCodecs.BOOL, Data::canTrade,
			Data::new
		);
	}

	private final Data data;
	private final @Nullable AbstractVillager villager;

	/** Client-side constructor: finds the villager by its network id. */
	public VillagerMenu(int containerId, Inventory inventory, Data data) {
		this(containerId, inventory, data,
			inventory.player.level().getEntity(data.entityId()) instanceof AbstractVillager v ? v : null);
	}

	public VillagerMenu(int containerId, Inventory inventory, Data data, @Nullable AbstractVillager villager) {
		super(ModRegistry.VILLAGER_MENU, containerId);
		this.data = data;
		this.villager = villager;

		if (villager != null) {
			VillagerEquipmentContainer equipment = new VillagerEquipmentContainer(villager);
			for (int i = 0; i < 4; i++) {
				EquipmentSlot slot = VillagerEquipmentContainer.SLOTS[i];
				this.addSlot(new ArmorSlot(equipment, villager, slot, i, 8, 18 + i * 18, EMPTY_ICONS.get(slot)));
			}
			this.addSlot(new HandSlot(equipment, VillagerEquipmentContainer.HAND_INDEX, 77, 72));
		} else {
			// The villager is not loaded on this client; show empty placeholders rather than crash.
			SimpleContainer placeholder = new SimpleContainer(EQUIPMENT_SLOTS);
			for (int i = 0; i < 4; i++) {
				this.addSlot(new Slot(placeholder, i, 8, 18 + i * 18));
			}
			this.addSlot(new Slot(placeholder, VillagerEquipmentContainer.HAND_INDEX, 77, 72));
		}

		this.addStandardInventorySlots(inventory, 8, 104);
	}

	public Data data() {
		return this.data;
	}

	public @Nullable AbstractVillager villager() {
		return this.villager;
	}

	@Override
	public boolean clickMenuButton(Player player, int buttonId) {
		if (buttonId != BUTTON_TRADE || this.villager == null || !this.data.canTrade()) {
			return false;
		}
		if (!player.level().isClientSide()) {
			// Hand over to vanilla trading (opening it replaces this menu); mobInteract handles the "no trades" head shake and stats.
			if (this.villager instanceof Villager v) {
				v.mobInteract(player, InteractionHand.MAIN_HAND);
			} else if (this.villager instanceof WanderingTrader trader) {
				trader.mobInteract(player, InteractionHand.MAIN_HAND);
			}
		}
		return true;
	}

	@Override
	public ItemStack quickMoveStack(Player player, int slotIndex) {
		Slot slot = this.slots.get(slotIndex);
		if (!slot.hasItem()) {
			return ItemStack.EMPTY;
		}

		ItemStack stack = slot.getItem();
		ItemStack original = stack.copy();
		int playerStart = EQUIPMENT_SLOTS;
		int playerEnd = this.slots.size();

		if (slotIndex < EQUIPMENT_SLOTS) {
			if (!this.moveItemStackTo(stack, playerStart, playerEnd, true)) {
				return ItemStack.EMPTY;
			}
		} else {
			boolean moved = false;
			for (int i = 0; i < 4 && !moved; i++) {
				Slot armor = this.slots.get(i);
				if (!armor.hasItem() && armor.mayPlace(stack)) {
					moved = this.moveItemStackTo(stack, i, i + 1, false);
				}
			}
			Slot hand = this.slots.get(VillagerEquipmentContainer.HAND_INDEX);
			if (!moved && !(hand.mayPlace(stack) && this.moveItemStackTo(stack, VillagerEquipmentContainer.HAND_INDEX, VillagerEquipmentContainer.HAND_INDEX + 1, false))) {
				return ItemStack.EMPTY;
			}
		}

		if (stack.isEmpty()) {
			slot.setByPlayer(ItemStack.EMPTY);
		} else {
			slot.setChanged();
		}
		return stack.getCount() == original.getCount() ? ItemStack.EMPTY : original;
	}

	@Override
	public boolean stillValid(Player player) {
		return this.villager != null && this.villager.isAlive() && player.distanceToSqr(this.villager) <= 64.0;
	}

	/** Hand slot that refuses to touch items the villager is only holding temporarily (trade previews, bone meal). */
	private static class HandSlot extends Slot {
		private final VillagerEquipmentContainer equipment;

		HandSlot(VillagerEquipmentContainer equipment, int index, int x, int y) {
			super(equipment, index, x, y);
			this.equipment = equipment;
		}

		@Override
		public boolean mayPlace(ItemStack stack) {
			return this.equipment.villager().getMainHandItem().isEmpty();
		}

		@Override
		public boolean mayPickup(Player player) {
			return this.equipment.handIsPlayerControlled() && super.mayPickup(player);
		}
	}
}
