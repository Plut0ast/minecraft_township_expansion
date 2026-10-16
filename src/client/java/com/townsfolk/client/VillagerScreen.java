package com.townsfolk.client;

import com.townsfolk.menu.VillagerMenu;
import com.townsfolk.town.RenameVillagerPayload;
import com.townsfolk.town.TownNames;
import com.townsfolk.town.VillagerCommands;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Villager or wandering trader preview, name and job, armour + hand slots, an order button and a Trade button. */
public class VillagerScreen extends AbstractContainerScreen<VillagerMenu> {
	private static final Identifier HEART_CONTAINER = Identifier.withDefaultNamespace("hud/heart/container");
	private static final Identifier HEART_FULL = Identifier.withDefaultNamespace("hud/heart/full");
	private static final Identifier HEART_HALF = Identifier.withDefaultNamespace("hud/heart/half");

	private static final ItemStack FOOD_ICON = new ItemStack(Items.BREAD);

	private float xMouse;
	private Button commandButton;
	/** Friends of the villager's town can rename it here; everyone else sees a plain title. */
	private EditBox nameBox;
	private String savedName;

	private float yMouse;

	public VillagerScreen(VillagerMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title, 176, 186 + (menu.hasCarryRow() ? VillagerMenu.CARRY_EXTRA_HEIGHT : 0));
		this.inventoryLabelY = this.imageHeight - 94;
	}

	@Override
	protected void init() {
		super.init();
		this.savedName = this.savedName == null ? this.menu.data().name() : this.savedName;
		this.nameBox = new EditBox(this.font, this.leftPos + 7, this.topPos + 4, 76, 12, Component.translatable("gui.townsfolk.villager.name"));
		this.nameBox.setMaxLength(TownNames.MAX_LENGTH);
		this.nameBox.setValue(this.savedName);
		this.nameBox.visible = this.menu.data().canRename();
		this.nameBox.setEditable(this.menu.data().canRename());
		this.nameBox.setTooltip(Tooltip.create(Component.translatable("gui.townsfolk.villager.rename")));
		this.addRenderableWidget(this.nameBox);
		Button trade = Button.builder(Component.translatable("gui.townsfolk.villager.trade"), b -> {
				this.saveName();
				this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, VillagerMenu.BUTTON_TRADE);
			})
			.bounds(this.leftPos + 100, this.topPos + 71, 68, 20)
			.build();
		trade.active = this.menu.data().canTrade();
		if (!trade.active) {
			trade.setTooltip(Tooltip.create(Component.translatable("gui.townsfolk.villager.no_trades")));
		}
		this.addRenderableWidget(trade);

		this.commandButton = Button.builder(commandLabel(this.menu.command()), b -> {
				this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, VillagerMenu.BUTTON_COMMAND);
			})
			.bounds(this.leftPos + 100, this.topPos + 49, 68, 20)
			.build();
		this.commandButton.active = this.menu.data().canCommand();
		this.commandButton.setTooltip(Tooltip.create(Component.translatable(
			this.menu.data().canCommand() ? "gui.townsfolk.villager.command.tooltip" : "gui.townsfolk.villager.command.locked")));
		this.addRenderableWidget(this.commandButton);
	}

	private static Component commandLabel(VillagerCommands.Command command) {
		return Component.translatable("gui.townsfolk.villager.command." + command.name().toLowerCase(java.util.Locale.ROOT));
	}

	@Override
	protected void containerTick() {
		super.containerTick();
		// The order is synced from the server, so refresh the label after each click lands.
		if (this.commandButton != null) {
			this.commandButton.setMessage(commandLabel(this.menu.command()));
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		this.xMouse = mouseX;
		this.yMouse = mouseY;
		super.extractRenderState(graphics, mouseX, mouseY, a);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractBackground(graphics, mouseX, mouseY, a);
		int x = this.leftPos;
		int y = this.topPos;
		Panels.window(graphics, x, y, this.imageWidth, this.imageHeight);

		for (Slot slot : this.menu.slots) {
			Panels.slot(graphics, x + slot.x - 1, y + slot.y - 1);
		}
		// Vanilla has no empty-slot icon for food, so an empty food slot shows a faded loaf of bread.
		int food = this.menu.foodSlotIndex();
		if (food >= 0 && !this.menu.slots.get(food).hasItem()) {
			int fx = x + VillagerMenu.FOOD_X;
			int fy = y + VillagerMenu.CARRY_Y;
			graphics.item(FOOD_ICON, fx, fy);
			graphics.fill(fx, fy, fx + 16, fy + 16, 0xB08B8B8B);
		}

		Panels.inset(graphics, x + 26, y + 17, 49, 71, 0xFF000000);
		AbstractVillager villager = this.menu.villager();
		if (villager != null) {
			InventoryScreen.extractEntityInInventoryFollowsMouse(graphics, x + 26, y + 17, x + 75, y + 88, 30, 0.0625F, this.xMouse, this.yMouse, villager);
		}
	}

	/** The villager's health as vanilla hearts, top right, kept live from the client's copy of the villager. */
	private void extractHearts(GuiGraphicsExtractor graphics) {
		AbstractVillager villager = this.menu.villager();
		if (villager == null) {
			return;
		}
		int hearts = Mth.ceil(villager.getMaxHealth() / 2.0F);
		int health = Mth.ceil(villager.getHealth());
		int spacing = 8;
		int startX = this.imageWidth - 8 - (hearts - 1) * spacing - 9;
		for (int i = 0; i < hearts; i++) {
			int hx = startX + i * spacing;
			graphics.blitSprite(RenderPipelines.GUI_TEXTURED, HEART_CONTAINER, hx, 5, 9, 9);
			if (health >= i * 2 + 2) {
				graphics.blitSprite(RenderPipelines.GUI_TEXTURED, HEART_FULL, hx, 5, 9, 9);
			} else if (health == i * 2 + 1) {
				graphics.blitSprite(RenderPipelines.GUI_TEXTURED, HEART_HALF, hx, 5, 9, 9);
			}
		}
	}

	/** Sends the typed name if it changed (on Enter, or when the screen closes). */
	private void saveName() {
		if (!this.menu.data().canRename()) {
			return;
		}
		String cleaned = TownNames.clean(this.nameBox.getValue());
		if (!cleaned.isEmpty() && !cleaned.equals(this.savedName)) {
			ClientPlayNetworking.send(new RenameVillagerPayload(this.menu.containerId, cleaned));
			this.savedName = cleaned;
		}
		this.nameBox.setValue(this.savedName);
		this.nameBox.setFocused(false);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.isEscape()) {
			this.onClose();
			return true;
		}
		if (this.nameBox.isFocused() && event.isConfirmation()) {
			this.saveName();
			return true;
		}
		// While typing, keys like E must go to the name box instead of closing the screen.
		return this.nameBox.keyPressed(event) || this.nameBox.canConsumeInput() || super.keyPressed(event);
	}

	/** Save before the close reaches the server, which only accepts a rename while the screen is open. */
	@Override
	public void onClose() {
		this.saveName();
		super.onClose();
	}

	/** Cuts long text (such as a 32-character town name) to fit the panel, ending in an ellipsis. */
	private String fit(Component text, int maxWidth) {
		String value = text.getString();
		if (this.font.width(value) <= maxWidth) {
			return value;
		}
		return this.font.plainSubstrByWidth(value, maxWidth - this.font.width("…")) + "…";
	}

	/** Below Friend the gear slots are read-only, so they are shaded dark over the slot and its item. */
	@Override
	public void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractContents(graphics, mouseX, mouseY, a);
		for (int i = 0; i < this.menu.playerStart(); i++) {
			// Gear and pockets need Friend; the food slot only shuts out the town's Enemies.
			boolean locked = i == this.menu.foodSlotIndex() ? !this.menu.data().canFeed() : !this.menu.data().canEdit();
			if (locked) {
				Slot slot = this.menu.slots.get(i);
				int sx = this.leftPos + slot.x;
				int sy = this.topPos + slot.y;
				graphics.fill(sx, sy, sx + 16, sy + 16, 0xB0202020);
			}
		}
	}

	@Override
	protected void extractLabels(GuiGraphicsExtractor graphics, int xm, int ym) {
		VillagerMenu.Data data = this.menu.data();
		if (!this.nameBox.visible) {
			graphics.text(this.font, Component.literal(this.savedName), this.titleLabelX, this.titleLabelY, Panels.TEXT, false);
		}
		graphics.text(this.font, data.profession(), 100, 20, 0xFF606060, false);
		Component town = data.townName().isEmpty()
			? Component.translatable("gui.townsfolk.villager.no_town")
			: Component.translatable("gui.townsfolk.villager.town", data.townName());
		graphics.text(this.font, this.fit(town, 68), 100, 31, 0xFF606060, false);
		this.extractHearts(graphics);
		graphics.text(this.font, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY, Panels.TEXT, false);
	}
}
