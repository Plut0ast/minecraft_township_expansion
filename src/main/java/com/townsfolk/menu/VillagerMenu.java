package com.townsfolk.menu;

import com.townsfolk.ModRegistry;
import com.townsfolk.town.VillagerCommands;
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
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/** A villager's or wandering trader's armour + hand slots, the player's inventory, and Trade / order buttons. */
public class VillagerMenu extends AbstractContainerMenu {
	public static final int BUTTON_TRADE = 0;
	public static final int BUTTON_COMMAND = 1;
	public static final int EQUIPMENT_SLOTS = VillagerEquipmentContainer.SLOTS.length;

	private static final Map<EquipmentSlot, Identifier> EMPTY_ICONS = Map.of(
		EquipmentSlot.HEAD, InventoryMenu.EMPTY_ARMOR_SLOT_HELMET,
		EquipmentSlot.CHEST, InventoryMenu.EMPTY_ARMOR_SLOT_CHESTPLATE,
		EquipmentSlot.LEGS, InventoryMenu.EMPTY_ARMOR_SLOT_LEGGINGS,
		EquipmentSlot.FEET, InventoryMenu.EMPTY_ARMOR_SLOT_BOOTS
	);

	/**
	 * {@code townName} is empty when the villager has no town. {@code canCommand}: the viewer is Trusted
	 * by the villager's town and may give orders. {@code canEdit}: the viewer may change its gear.
	 */
	public record Data(int entityId, String name, Component profession, String townName, boolean canTrade, boolean canCommand, boolean canEdit) {
		public static final StreamCodec<RegistryFriendlyByteBuf, Data> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, Data::entityId,
			ByteBufCodecs.STRING_UTF8, Data::name,
			ComponentSerialization.TRUSTED_STREAM_CODEC, Data::profession,
			ByteBufCodecs.STRING_UTF8, Data::townName,
			ByteBufCodecs.BOOL, Data::canTrade,
			ByteBufCodecs.BOOL, Data::canCommand,
			ByteBufCodecs.BOOL, Data::canEdit,
			Data::new
		);
	}

	private final Data data;
	private final @Nullable AbstractVillager villager;
	/** The villager's current order (ordinal of {@link VillagerCommands.Command}), synced to the client. */
	private final DataSlot command;

	/** Client-side constructor: finds the villager by its network id. */
	public VillagerMenu(int containerId, Inventory inventory, Data data) {
		this(containerId, inventory, data,
			inventory.player.level().getEntity(data.entityId()) instanceof AbstractVillager v ? v : null);
	}

	public VillagerMenu(int containerId, Inventory inventory, Data data, @Nullable AbstractVillager villager) {
		super(ModRegistry.VILLAGER_MENU, containerId);
		this.data = data;
		this.villager = villager;
		this.command = this.addDataSlot(villager != null && !inventory.player.level().isClientSide()
			? new DataSlot() {
				@Override
				public int get() {
					return VillagerCommands.commandOf(villager).ordinal();
				}

				@Override
				public void set(int value) {
				}
			}
			: DataSlot.standalone());

		if (villager != null) {
			VillagerEquipmentContainer equipment = new VillagerEquipmentContainer(villager);
			for (int i = 0; i < 4; i++) {
				EquipmentSlot slot = VillagerEquipmentContainer.SLOTS[i];
				this.addSlot(new LockableArmorSlot(equipment, villager, slot, i, 8, 18 + i * 18, EMPTY_ICONS.get(slot), data.canEdit()));
			}
			this.addSlot(new HandSlot(equipment, VillagerEquipmentContainer.HAND_INDEX, 77, 72, data.canEdit()));
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

	public VillagerCommands.Command command() {
		VillagerCommands.Command[] commands = VillagerCommands.Command.values();
		int ordinal = this.command.get();
		return ordinal >= 0 && ordinal < commands.length ? commands[ordinal] : VillagerCommands.Command.WANDER;
	}

	@Override
	public boolean clickMenuButton(Player player, int buttonId) {
		if (buttonId == BUTTON_COMMAND) {
			if (this.villager == null || !this.data.canCommand()) {
				return false;
			}
			// Re-check on the server: reputation may have changed since the menu opened.
			if (!player.level().isClientSide()) {
				if (!VillagerCommands.canCommand(player, this.villager)) {
					return false;
				}
				VillagerCommands.cycle(player, this.villager);
			}
			return true;
		}
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

	/** Armour slot that is read-only unless the viewer may change this villager's gear. */
	private static class LockableArmorSlot extends ArmorSlot {
		private final boolean editable;

		LockableArmorSlot(VillagerEquipmentContainer equipment, AbstractVillager villager, EquipmentSlot slot, int index, int x, int y,
			Identifier emptyIcon, boolean editable) {
			super(equipment, villager, slot, index, x, y, emptyIcon);
			this.editable = editable;
		}

		@Override
		public boolean mayPlace(ItemStack stack) {
			return this.editable && super.mayPlace(stack);
		}

		@Override
		public boolean mayPickup(Player player) {
			return this.editable && super.mayPickup(player);
		}
	}

	/**
	 * Hand slot that is read-only unless the viewer may change this villager's gear, and that refuses
	 * to touch items the villager is only holding temporarily (trade previews, bone meal).
	 */
	private static class HandSlot extends Slot {
		private final VillagerEquipmentContainer equipment;
		private final boolean editable;

		HandSlot(VillagerEquipmentContainer equipment, int index, int x, int y, boolean editable) {
			super(equipment, index, x, y);
			this.equipment = equipment;
			this.editable = editable;
		}

		@Override
		public boolean mayPlace(ItemStack stack) {
			return this.editable && this.equipment.villager().getMainHandItem().isEmpty();
		}

		@Override
		public boolean mayPickup(Player player) {
			return this.editable && this.equipment.handIsPlayerControlled() && super.mayPickup(player);
		}
	}
}
