package com.townsfolk.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

/**
 * Vanilla-looking GUI pieces built from Minecraft's own textures and colours, so the mod ships no
 * GUI textures of its own: the container frame is cut from the player inventory texture.
 */
final class Panels {
	static final int TEXT = 0xFF404040;
	static final int TEXT_LIGHT = 0xFF707070;
	static final Identifier SLOT_SPRITE = Identifier.withDefaultNamespace("container/slot");

	private static final Identifier INVENTORY = Identifier.withDefaultNamespace("textures/gui/container/inventory.png");
	private static final int INV_W = 176;
	private static final int INV_H = 166;
	private static final int EDGE = 4;
	private static final int FACE = 0xFFC6C6C6;

	private static final int SUNKEN_DARK = 0xFF373737;
	private static final int SUNKEN_LIGHT = 0xFFFFFFFF;
	static final int SUNKEN_FILL = 0xFF8B8B8B;

	private static final Identifier BAR_BACKGROUND_GREEN = Identifier.withDefaultNamespace("boss_bar/green_background");
	private static final Identifier BAR_PROGRESS_GREEN = Identifier.withDefaultNamespace("boss_bar/green_progress");
	private static final Identifier BAR_BACKGROUND_RED = Identifier.withDefaultNamespace("boss_bar/red_background");
	private static final Identifier BAR_PROGRESS_RED = Identifier.withDefaultNamespace("boss_bar/red_progress");
	private static final int BAR_W = 182;
	static final int BAR_H = 5;
	/** Width of the rounded end cap on each side of a boss bar sprite. */
	private static final int BAR_CAP = 3;

	private Panels() {
	}

	/** A vanilla container window of any size: the inventory texture's frame, stretched, around the vanilla grey. */
	static void window(GuiGraphicsExtractor graphics, int x, int y, int w, int h) {
		int inner = INV_W - 2 * EDGE;
		// Corners.
		piece(graphics, x, y, 0, 0, EDGE, EDGE, EDGE, EDGE);
		piece(graphics, x + w - EDGE, y, INV_W - EDGE, 0, EDGE, EDGE, EDGE, EDGE);
		piece(graphics, x, y + h - EDGE, 0, INV_H - EDGE, EDGE, EDGE, EDGE, EDGE);
		piece(graphics, x + w - EDGE, y + h - EDGE, INV_W - EDGE, INV_H - EDGE, EDGE, EDGE, EDGE, EDGE);
		// Edges: a plain one-pixel strip of the frame, stretched.
		piece(graphics, x + EDGE, y, 2 * EDGE, 0, w - 2 * EDGE, EDGE, 1, EDGE);
		piece(graphics, x + EDGE, y + h - EDGE, 2 * EDGE, INV_H - EDGE, w - 2 * EDGE, EDGE, 1, EDGE);
		piece(graphics, x, y + EDGE, 0, 2 * EDGE, EDGE, h - 2 * EDGE, EDGE, 1);
		piece(graphics, x + w - EDGE, y + EDGE, INV_W - EDGE, 2 * EDGE, EDGE, h - 2 * EDGE, EDGE, 1);
		graphics.fill(x + EDGE, y + EDGE, x + w - EDGE, y + h - EDGE, FACE);
	}

	private static void piece(GuiGraphicsExtractor graphics, int x, int y, int u, int v, int w, int h, int srcW, int srcH) {
		graphics.blit(RenderPipelines.GUI_TEXTURED, INVENTORY, x, y, u, v, w, h, srcW, srcH, 256, 256);
	}

	/** A sunken box like vanilla slots and text areas: dark top-left edge, light bottom-right edge. */
	static void inset(GuiGraphicsExtractor graphics, int x, int y, int w, int h, int fill) {
		graphics.fill(x, y, x + w, y + h, SUNKEN_DARK);
		graphics.fill(x + 1, y + 1, x + w, y + h, SUNKEN_LIGHT);
		graphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, fill);
	}

	static void slot(GuiGraphicsExtractor graphics, int x, int y) {
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT_SPRITE, x, y, 18, 18);
	}

	/**
	 * A vanilla boss bar, {@code width} wide (narrower than the 182px sprite: the middle is cut, the
	 * end caps kept), filled to {@code progress}. Red for hostile, green otherwise.
	 */
	static void bossBar(GuiGraphicsExtractor graphics, int x, int y, int width, float progress, boolean red) {
		barPart(graphics, red ? BAR_BACKGROUND_RED : BAR_BACKGROUND_GREEN, x, y, width, width);
		int filled = Math.round(width * Mth.clamp(progress, 0.0F, 1.0F));
		if (filled > 0) {
			barPart(graphics, red ? BAR_PROGRESS_RED : BAR_PROGRESS_GREEN, x, y, width, filled);
		}
	}

	/** Draws the first {@code shown} pixels of a {@code width}-wide bar built from a 182px sprite. */
	private static void barPart(GuiGraphicsExtractor graphics, Identifier sprite, int x, int y, int width, int shown) {
		int body = Math.min(shown, width - BAR_CAP);
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, BAR_W, BAR_H, 0, 0, x, y, body, BAR_H);
		if (shown > width - BAR_CAP) {
			int cap = shown - (width - BAR_CAP);
			graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, BAR_W, BAR_H, BAR_W - BAR_CAP, 0, x + width - BAR_CAP, y, cap, BAR_H);
		}
	}
}
