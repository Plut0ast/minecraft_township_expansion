package com.townsfolk.client;

import com.townsfolk.menu.VillagerMenu;
import com.townsfolk.town.VillagerCommands;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.inventory.Slot;

/** Villager or wandering trader preview, name and job, armour + hand slots, an order button and a Trade button. */
public class VillagerScreen extends AbstractContainerScreen<VillagerMenu> {
	private float xMouse;
	private Button commandButton;
	private float yMouse;

	public VillagerScreen(VillagerMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title, 176, 186);
		this.inventoryLabelY = this.imageHeight - 94;
	}

	@Override
	protected void init() {
		super.init();
		Button trade = Button.builder(Component.translatable("gui.townsfolk.villager.trade"), b -> {
				this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, VillagerMenu.BUTTON_TRADE);
			})
			.bounds(this.leftPos + 100, this.topPos + 70, 68, 20)
			.build();
		trade.active = this.menu.data().canTrade();
		if (!trade.active) {
			trade.setTooltip(Tooltip.create(Component.translatable("gui.townsfolk.villager.no_trades")));
		}
		this.addRenderableWidget(trade);

		this.commandButton = Button.builder(commandLabel(this.menu.command()), b -> {
				this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, VillagerMenu.BUTTON_COMMAND);
			})
			.bounds(this.leftPos + 100, this.topPos + 46, 68, 20)
			.build();
		this.commandButton.active = this.menu.data().canCommand();
		this.commandButton.setTooltip(Tooltip.create(Component.translatable(
			this.menu.data().canCommand() ? "gui.townsfolk.villager.command.tooltip" : "gui.townsfolk.villager.command.locked")));
		this.addRenderableWidget(this.commandButton);
	}

	private static Component commandLabel(VillagerCommands.Command command) {
		return Component.translatable("gui.townsfolk.villager.command." + command.name().toLowerCase());
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

		Panels.inset(graphics, x + 26, y + 17, 49, 71, 0xFF000000);
		AbstractVillager villager = this.menu.villager();
		if (villager != null) {
			InventoryScreen.extractEntityInInventoryFollowsMouse(graphics, x + 26, y + 17, x + 75, y + 88, 30, 0.0625F, this.xMouse, this.yMouse, villager);
		}
	}

	@Override
	protected void extractLabels(GuiGraphicsExtractor graphics, int xm, int ym) {
		VillagerMenu.Data data = this.menu.data();
		graphics.text(this.font, Component.translatable("gui.townsfolk.villager.title"), this.titleLabelX, this.titleLabelY, Panels.TEXT, false);
		graphics.text(this.font, Component.literal(data.name()), 100, 20, 0xFF202020, false);
		graphics.text(this.font, data.profession(), 100, 32, 0xFF606060, false);
		graphics.text(this.font, Component.translatable("gui.townsfolk.villager.hand"), 78, 62, Panels.TEXT, false);
		graphics.text(this.font, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY, Panels.TEXT, false);
	}
}
