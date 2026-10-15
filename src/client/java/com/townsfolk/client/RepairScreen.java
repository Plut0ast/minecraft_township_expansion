package com.townsfolk.client;

import com.townsfolk.menu.RepairMenu;
import com.townsfolk.town.RepairPayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/**
 * A smith's repair window, laid out like an anvil: damaged item + emeralds = repaired item. Also adds
 * the Repair button to smiths' trade screens.
 */
public class RepairScreen extends AbstractContainerScreen<RepairMenu> {
	private static final Identifier ARROW = Identifier.withDefaultNamespace("container/furnace/burn_progress");
	private static final Identifier CANNOT = Identifier.withDefaultNamespace("container/anvil/error");

	/** The villager whose trade screen is open, if it is a smith (-1 otherwise). Set by the server. */
	private static int repairTrader = -1;

	public RepairScreen(RepairMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title, 176, 166);
	}

	/** Hooks up the Repair button on smiths' trade screens. */
	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(RepairPayloads.Trader.TYPE, (payload, context) -> repairTrader = payload.villagerId());
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (!(screen instanceof MerchantScreen) || repairTrader < 0) {
				return;
			}
			int villagerId = repairTrader;
			// Forget it once this trade screen closes, so another trader never shows the button.
			ScreenEvents.remove(screen).register(closed -> repairTrader = -1);
			int left = (width - 276) / 2;
			int top = (height - 166) / 2;
			Screens.getWidgets(screen).add(Button.builder(Component.translatable("gui.townsfolk.repair.button"),
					b -> ClientPlayNetworking.send(new RepairPayloads.Open(villagerId)))
				.bounds(left + 276 + 4, top + 4, 56, 20)
				.build());
		});
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
		boolean canRepair = this.menu.getSlot(RepairMenu.RESULT).hasItem();
		boolean hasItem = this.menu.getSlot(RepairMenu.INPUT).hasItem();
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, ARROW, x + 102, y + 47, 24, 16);
		if (hasItem && !canRepair) {
			graphics.blitSprite(RenderPipelines.GUI_TEXTURED, CANNOT, x + 103, y + 45, 28, 21);
		}
	}

	@Override
	protected void extractLabels(GuiGraphicsExtractor graphics, int xm, int ym) {
		graphics.text(this.font, Component.translatable("gui.townsfolk.repair.title", this.menu.data().smith()), this.titleLabelX, this.titleLabelY, Panels.TEXT, false);
		graphics.text(this.font, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY, Panels.TEXT, false);
		int cost = this.menu.cost();
		Component line = cost > 0
			? Component.translatable("gui.townsfolk.repair.cost", cost)
			: Component.translatable("gui.townsfolk.repair.hint." + RepairMenu.Kind.values()[Math.floorMod(this.menu.data().kind(), 4)].name().toLowerCase());
		graphics.text(this.font, line, this.imageWidth - 8 - this.font.width(line), 22, Panels.TEXT_LIGHT, false);
	}
}
