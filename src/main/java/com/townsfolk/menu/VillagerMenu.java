package com.townsfolk.menu;

import com.townsfolk.ModRegistry;
import com.townsfolk.town.Sentry;
import com.townsfolk.town.VillagerCommands;
import com.townsfolk.town.VillagerFood;
import java.util.Map;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.Container;
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
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/** A villager's or wandering trader's armour + hand slots, the player's inventory, and Trade / order buttons. */
public class VillagerMenu extends AbstractContainerMenu {
	public static final int BUTTON_TRADE = 0;
	private static final Identifier EMPTY_HAND_ICON = Identifier.withDefaultNamespace("container/slot/sword");
	public static final int BUTTON_COMMAND = 1;
	public static final int EQUIPMENT_SLOTS = VillagerEquipmentContainer.SLOTS.length;
	/** Every villager has this many pockets (vanilla has 8), shown beside its food slot. */
	public static final int POCKETS = 3;
	/** Where a farmer's carry slots sit, and how much taller the screen gets to fit them. */
	public static final int CARRY_Y = 96;
	public static final int CARRY_EXTRA_HEIGHT = 26;
	/** The bottom row: food slot first, then the pockets after a small gap. */
	public static final int FOOD_X = 8;
	public static final int POCKET_X = 34;

	private static final Map<EquipmentSlot, Identifier> EMPTY_ICONS = Map.of(
		EquipmentSlot.HEAD, InventoryMenu.EMPTY_ARMOR_SLOT_HELMET,
		EquipmentSlot.CHEST, InventoryMenu.EMPTY_ARMOR_SLOT_CHESTPLATE,
		EquipmentSlot.LEGS, InventoryMenu.EMPTY_ARMOR_SLOT_LEGGINGS,
		EquipmentSlot.FEET, InventoryMenu.EMPTY_ARMOR_SLOT_BOOTS
	);

	/**
	 * {@code townName} is empty when the villager has no town. {@code canCommand}: the viewer is Trusted
	 * by the villager's town and may give orders. {@code canEdit}: the viewer is one of the town's players
	 * and may change its gear and pockets. {@code canRename}: the viewer is a Friend of the town.
	 * {@code showFood}: villager food is turned on in the settings, so the food slot is shown.
	 */
	public record Data(int entityId, String name, Component profession, String townName, boolean canTrade, boolean canCommand, boolean canEdit,
		int carrySlots, boolean canArm, boolean canFeed, boolean showHand, boolean canRename, boolean showFood) {
		/** Written out by hand: vanilla's composite codec takes at most twelve fields. */
		public static final StreamCodec<RegistryFriendlyByteBuf, Data> STREAM_CODEC = StreamCodec.of(
			(buf, data) -> {
				buf.writeVarInt(data.entityId());
				buf.writeUtf(data.name());
				ComponentSerialization.TRUSTED_STREAM_CODEC.encode(buf, data.profession());
				buf.writeUtf(data.townName());
				buf.writeBoolean(data.canTrade());
				buf.writeBoolean(data.canCommand());
				buf.writeBoolean(data.canEdit());
				buf.writeVarInt(data.carrySlots());
				buf.writeBoolean(data.canArm());
				buf.writeBoolean(data.canFeed());
				buf.writeBoolean(data.showHand());
				buf.writeBoolean(data.canRename());
				buf.writeBoolean(data.showFood());
			},
			buf -> new Data(buf.readVarInt(), buf.readUtf(), ComponentSerialization.TRUSTED_STREAM_CODEC.decode(buf), buf.readUtf(),
				buf.readBoolean(), buf.readBoolean(), buf.readBoolean(), buf.readVarInt(), buf.readBoolean(), buf.readBoolean(),
				buf.readBoolean(), buf.readBoolean(), buf.readBoolean())
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
			// Only nitwits (who can be armed as Sentries) show a hand slot.
			if (data.showHand()) {
				this.addSlot(new HandSlot(equipment, VillagerEquipmentContainer.HAND_INDEX, 77, 72, data.canEdit(), data.canArm()));
			}
			if (this.hasFoodSlot()) {
				Container food = villager instanceof Villager v ? new VillagerFoodContainer(v) : new SimpleContainer(1);
				this.addSlot(new FoodSlot(food, FOOD_X, CARRY_Y, data.canFeed(), data.canEdit()));
			}
			// The villager's pockets: changing them needs Friend.
			for (int i = 0; i < this.carrySlots(); i++) {
				this.addSlot(new PocketSlot(villager.getInventory(), i, this.pocketX() + i * 18, CARRY_Y, data.canEdit(), false));
			}
		} else {
			// The villager is not loaded on this client; show empty placeholders rather than crash.
			SimpleContainer placeholder = new SimpleContainer(EQUIPMENT_SLOTS);
			for (int i = 0; i < 4; i++) {
				this.addSlot(new Slot(placeholder, i, 8, 18 + i * 18));
			}
			if (data.showHand()) {
				this.addSlot(new Slot(placeholder, VillagerEquipmentContainer.HAND_INDEX, 77, 72));
			}
			if (this.hasFoodSlot()) {
				this.addSlot(new FoodSlot(new SimpleContainer(1), FOOD_X, CARRY_Y, false, false));
			}
			SimpleContainer carryPlaceholder = new SimpleContainer(Math.max(1, this.carrySlots()));
			for (int i = 0; i < this.carrySlots(); i++) {
				this.addSlot(new PocketSlot(carryPlaceholder, i, this.pocketX() + i * 18, CARRY_Y, false, false));
			}
		}

		this.addStandardInventorySlots(inventory, 8, 104 + (this.carrySlots() > 0 ? CARRY_EXTRA_HEIGHT : 0));
	}

	public Data data() {
		return this.data;
	}

	/** The hand slot's index, or -1 when this villager does not show one (only nitwits do). */
	public int handSlotIndex() {
		return this.data.showHand() ? VillagerEquipmentContainer.HAND_INDEX : -1;
	}

	/** End of the armour (and hand, if shown) slots. */
	private int equipmentEnd() {
		return VillagerEquipmentContainer.HAND_INDEX + (this.data.showHand() ? 1 : 0);
	}

	/**
	 * Villagers (not wandering traders) have a food slot, right after the armour and hand slots, unless
	 * villager food is turned off in the settings.
	 */
	public boolean hasFoodSlot() {
		return this.carrySlots() > 0 && this.data.showFood();
	}

	/** Pockets sit right of the food slot, or take its place when there is none. */
	private int pocketX() {
		return this.hasFoodSlot() ? POCKET_X : FOOD_X;
	}

	public int foodSlotIndex() {
		return this.hasFoodSlot() ? this.equipmentEnd() : -1;
	}

	/** First pocket slot; the player's inventory starts after the pockets. */
	public int carryStart() {
		return this.equipmentEnd() + (this.hasFoodSlot() ? 1 : 0);
	}

	public int playerStart() {
		return this.carryStart() + this.carrySlots();
	}

	/** Pocket slots shown on this menu (3 for villagers, 0 for wandering traders). */
	public int carrySlots() {
		return Math.min(this.data.carrySlots(), POCKETS);
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
		int carryStart = this.carryStart();
		int playerStart = this.playerStart();
		int playerEnd = this.slots.size();

		if (slotIndex < playerStart) {
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
			int food = this.foodSlotIndex();
			if (!moved && food >= 0 && this.slots.get(food).mayPlace(stack)) {
				moved = this.moveItemStackTo(stack, food, food + 1, false);
			}
			int hand = this.handSlotIndex();
			if (!moved && hand >= 0 && this.slots.get(hand).mayPlace(stack)) {
				moved = this.moveItemStackTo(stack, hand, hand + 1, false);
			}
			if (!moved && this.carrySlots() > 0 && this.slots.get(carryStart).mayPlace(stack)) {
				moved = this.moveItemStackTo(stack, carryStart, playerStart, false);
			}
			if (!moved) {
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

	/** Takes food only. Anyone but the town's Enemies may feed; taking food back out needs Friend. */
	private static class FoodSlot extends Slot {
		private final boolean canFeed;
		private final boolean canTake;

		FoodSlot(Container container, int x, int y, boolean canFeed, boolean canTake) {
			super(container, 0, x, y);
			this.canFeed = canFeed;
			this.canTake = canTake;
		}

		@Override
		public boolean mayPlace(ItemStack stack) {
			return this.canFeed && VillagerFood.isFood(stack);
		}

		@Override
		public boolean mayPickup(Player player) {
			return this.canTake && super.mayPickup(player);
		}
	}

	/** A pocket slot: with Friend anything goes in and out; {@code canFeed} also lets others put food in. */
	private static class PocketSlot extends Slot {
		private final boolean editable;
		private final boolean canFeed;

		PocketSlot(Container container, int index, int x, int y, boolean editable, boolean canFeed) {
			super(container, index, x, y);
			this.editable = editable;
			this.canFeed = canFeed;
		}

		@Override
		public boolean mayPlace(ItemStack stack) {
			return this.editable || (this.canFeed && VillagerFood.isFood(stack));
		}

		@Override
		public boolean mayPickup(Player player) {
			return this.editable && super.mayPickup(player);
		}
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
		/** Handing a nitwit a sword makes it a Sentry, which needs Trusted; other villagers refuse swords. */
		private final boolean canArm;

		HandSlot(VillagerEquipmentContainer equipment, int index, int x, int y, boolean editable, boolean canArm) {
			super(equipment, index, x, y);
			this.equipment = equipment;
			this.editable = editable;
			this.canArm = canArm;
		}

		/** An empty hand shows vanilla's faded sword, like the armour slots show armour. */
		@Override
		public Identifier getNoItemIcon() {
			return EMPTY_HAND_ICON;
		}

		@Override
		public boolean mayPlace(ItemStack stack) {
			if (Sentry.isSentryWeapon(stack) && !this.canArm) {
				return false;
			}
			return this.editable && this.equipment.villager().getMainHandItem().isEmpty();
		}

		@Override
		public boolean mayPickup(Player player) {
			return this.editable && this.equipment.handIsPlayerControlled() && super.mayPickup(player);
		}
	}
}
