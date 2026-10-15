package com.townsfolk.menu;

import com.townsfolk.ModRegistry;
import com.townsfolk.work.SmithWork;
import com.townsfolk.work.WorkHelper;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.equipment.Equippable;
import org.jspecify.annotations.Nullable;

/**
 * A smith's repair service for players: put a damaged item and emeralds in, take it out repaired
 * (enchantments kept). Each smith repairs its own kind of gear.
 */
public class RepairMenu extends AbstractContainerMenu {
	/** What a smith repairs. */
	public enum Kind {
		ARMOR, LEATHER, WEAPON, TOOL;

		public static @Nullable Kind of(Villager villager) {
			if (WorkHelper.is(villager, VillagerProfession.ARMORER)) {
				return ARMOR;
			}
			if (WorkHelper.is(villager, VillagerProfession.LEATHERWORKER)) {
				return LEATHER;
			}
			if (WorkHelper.is(villager, VillagerProfession.WEAPONSMITH)) {
				return WEAPON;
			}
			if (WorkHelper.is(villager, VillagerProfession.TOOLSMITH)) {
				return TOOL;
			}
			return null;
		}

		public boolean accepts(ItemStack stack) {
			if (!stack.isDamageableItem()) {
				return false;
			}
			return switch (this) {
				case ARMOR -> isArmor(stack);
				case LEATHER -> isArmor(stack) && isLeather(stack);
				case WEAPON -> SmithWork.isWeapon(stack);
				case TOOL -> SmithWork.isTool(stack);
			};
		}

		private static boolean isArmor(ItemStack stack) {
			Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
			return equippable != null && equippable.slot().getType() == EquipmentSlot.Type.HUMANOID_ARMOR;
		}

		private static boolean isLeather(ItemStack stack) {
			return stack.is(Items.LEATHER_HELMET) || stack.is(Items.LEATHER_CHESTPLATE) || stack.is(Items.LEATHER_LEGGINGS) || stack.is(Items.LEATHER_BOOTS);
		}
	}

	public record Data(int villagerId, int kind, Component smith) {
		public static final StreamCodec<RegistryFriendlyByteBuf, Data> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, Data::villagerId,
			ByteBufCodecs.VAR_INT, Data::kind,
			ComponentSerialization.TRUSTED_STREAM_CODEC, Data::smith,
			Data::new
		);
	}

	public static final int INPUT = 0;
	public static final int EMERALDS = 1;
	public static final int RESULT = 2;

	private final Data data;
	private final Kind kind;
	private final @Nullable Villager smith;
	private final SimpleContainer inputs = new SimpleContainer(2) {
		@Override
		public void setChanged() {
			super.setChanged();
			RepairMenu.this.slotsChanged(this);
		}
	};
	private final ResultContainer result = new ResultContainer();
	/** Emeralds the current item costs (0 when nothing repairable is in), synced for the screen. */
	private final DataSlot cost = DataSlot.standalone();

	public RepairMenu(int containerId, Inventory inventory, Data data) {
		this(containerId, inventory, data,
			inventory.player.level().getEntity(data.villagerId()) instanceof Villager v ? v : null);
	}

	public RepairMenu(int containerId, Inventory inventory, Data data, @Nullable Villager smith) {
		super(ModRegistry.REPAIR_MENU, containerId);
		this.data = data;
		this.kind = Kind.values()[Math.floorMod(data.kind(), Kind.values().length)];
		this.smith = smith;

		this.addSlot(new Slot(this.inputs, 0, 27, 47) {
			@Override
			public boolean mayPlace(ItemStack stack) {
				return RepairMenu.this.kind.accepts(stack);
			}
		});
		this.addSlot(new Slot(this.inputs, 1, 76, 47) {
			@Override
			public boolean mayPlace(ItemStack stack) {
				return stack.is(Items.EMERALD);
			}
		});
		this.addSlot(new Slot(this.result, 0, 134, 47) {
			@Override
			public boolean mayPlace(ItemStack stack) {
				return false;
			}

			@Override
			public void onTake(Player player, ItemStack carried) {
				RepairMenu.this.pay(player);
				super.onTake(player, carried);
			}
		});
		this.addStandardInventorySlots(inventory, 8, 84);
		this.addDataSlot(this.cost);
	}

	public Data data() {
		return this.data;
	}

	public int cost() {
		return this.cost.get();
	}

	/** Emeralds by how tough the gear is: leather, wood and gold 1; stone, chainmail and iron 2; diamond 4; netherite tools 6. */
	public static int costFor(ItemStack stack) {
		int durability = stack.getMaxDamage();
		if (durability <= 100) {
			return 1;
		}
		if (durability <= 300) {
			return 2;
		}
		if (durability <= 1600) {
			return 4;
		}
		return 6;
	}

	@Override
	public void slotsChanged(Container container) {
		super.slotsChanged(container);
		ItemStack item = this.inputs.getItem(INPUT);
		if (item.isEmpty() || !this.kind.accepts(item) || !item.isDamaged()) {
			this.cost.set(0);
			this.result.setItem(0, ItemStack.EMPTY);
			return;
		}
		int price = costFor(item);
		this.cost.set(price);
		if (this.inputs.getItem(EMERALDS).getCount() >= price) {
			ItemStack repaired = item.copy();
			repaired.setDamageValue(0);
			this.result.setItem(0, repaired);
		} else {
			this.result.setItem(0, ItemStack.EMPTY);
		}
		this.broadcastChanges();
	}

	/** Taking the repaired item uses up the damaged one and the emeralds. */
	private void pay(Player player) {
		int price = this.cost.get();
		this.inputs.removeItem(INPUT, 1);
		this.inputs.removeItem(EMERALDS, price);
		if (!player.level().isClientSide() && this.smith != null) {
			player.level().playSound(null, this.smith.blockPosition(), SoundEvents.ANVIL_USE, SoundSource.NEUTRAL, 0.6F, 1.0F);
		}
	}

	@Override
	public ItemStack quickMoveStack(Player player, int slotIndex) {
		Slot slot = this.slots.get(slotIndex);
		if (!slot.hasItem()) {
			return ItemStack.EMPTY;
		}
		ItemStack stack = slot.getItem();
		ItemStack original = stack.copy();
		int playerStart = RESULT + 1;
		int playerEnd = this.slots.size();
		if (slotIndex == RESULT) {
			if (!this.moveItemStackTo(stack, playerStart, playerEnd, true)) {
				return ItemStack.EMPTY;
			}
			slot.onTake(player, original);
		} else if (slotIndex < playerStart) {
			if (!this.moveItemStackTo(stack, playerStart, playerEnd, true)) {
				return ItemStack.EMPTY;
			}
		} else if (stack.is(Items.EMERALD)) {
			if (!this.moveItemStackTo(stack, EMERALDS, EMERALDS + 1, false)) {
				return ItemStack.EMPTY;
			}
		} else if (!this.slots.get(INPUT).mayPlace(stack) || !this.moveItemStackTo(stack, INPUT, INPUT + 1, false)) {
			return ItemStack.EMPTY;
		}
		if (stack.isEmpty()) {
			slot.setByPlayer(ItemStack.EMPTY);
		} else {
			slot.setChanged();
		}
		return original;
	}

	@Override
	public void removed(Player player) {
		super.removed(player);
		this.clearContainer(player, this.inputs);
	}

	@Override
	public boolean stillValid(Player player) {
		return this.smith != null && this.smith.isAlive() && player.distanceToSqr(this.smith) <= 64.0;
	}
}
