package dev.nexoclient.nexomod.capes;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;

import dev.nexoclient.nexomod.NexoMod;
import dev.nexoclient.nexomod.screen.NexoStyle;

/**
 * Cape review for reviewers: each waiting cape's outside and elytra, with Approve
 * and Reject, then the approved ones with Take down. The service decides who may;
 * this screen is only reachable for {@link NexoCapes#REVIEWERS}.
 */
public class NexoCapeReviewScreen extends Screen {
	private static final int CARD_W = 120;
	private static final int CARD_H = 150;
	private static final int GAP = 10;

	private final Screen parent;
	private List<NexoCapes.Pending> capes;
	private String message = "Loading…";
	private int page;

	public NexoCapeReviewScreen(Screen parent) {
		super(Component.literal("Cape review"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		if (capes == null) {
			run(NexoCapes::reviewQueue);
		}
		layoutButtons();
	}

	private interface Call {
		List<NexoCapes.Pending> get() throws Exception;
	}

	private void run(Call call) {
		message = "Working…";
		CompletableFuture.supplyAsync(() -> {
			try {
				return call.get();
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		}, Util.nonCriticalIoPool()).whenComplete((result, error) -> minecraft.execute(() -> {
			if (error != null) {
				NexoMod.LOGGER.warn("[nexomod] Capes: review failed.", error);
				message = "Could not reach the cape service. Is this the reviewer's account?";
			} else {
				capes = result;
				message = result.isEmpty() ? "No capes yet." : null;
			}
			rebuildWidgets();
		}));
	}

	private int perPage() {
		return Math.max(1, (width - 40) / (CARD_W + GAP)) * Math.max(1, (height - 90) / (CARD_H + GAP));
	}

	private int columns() {
		return Math.max(1, (width - 40) / (CARD_W + GAP));
	}

	private int cardX(int index) {
		int cols = columns();
		int left = (width - cols * (CARD_W + GAP) + GAP) / 2;
		return left + (index % cols) * (CARD_W + GAP);
	}

	private int cardY(int index) {
		return 34 + (index / columns()) * (CARD_H + GAP);
	}

	private void layoutButtons() {
		int bottom = height - 28;
		addRenderableWidget(Button.builder(Component.literal("<"), b -> {
			page = Math.max(0, page - 1);
			rebuildWidgets();
		}).bounds(width / 2 - 154, bottom, 20, 20).build());
		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).bounds(width / 2 - 100, bottom, 200, 20).build());
		addRenderableWidget(Button.builder(Component.literal(">"), b -> {
			if ((page + 1) * perPage() < (capes == null ? 0 : capes.size())) {
				page++;
				rebuildWidgets();
			}
		}).bounds(width / 2 + 134, bottom, 20, 20).build());
		if (capes == null) {
			return;
		}
		int first = page * perPage();
		for (int i = first; i < Math.min(capes.size(), first + perPage()); i++) {
			NexoCapes.Pending cape = capes.get(i);
			int x = cardX(i - first);
			int y = cardY(i - first) + CARD_H - 24;
			if (cape.approved()) {
				addRenderableWidget(Button.builder(Component.literal("Take down"), b -> run(() -> NexoCapes.review(cape.id(), false)))
						.bounds(x + 4, y, CARD_W - 8, 20).build());
			} else {
				addRenderableWidget(Button.builder(Component.literal("Approve"), b -> run(() -> NexoCapes.review(cape.id(), true)))
						.bounds(x + 4, y, CARD_W / 2 - 6, 20).build());
				addRenderableWidget(Button.builder(Component.literal("Reject"), b -> run(() -> NexoCapes.review(cape.id(), false)))
						.bounds(x + CARD_W / 2 + 2, y, CARD_W / 2 - 6, 20).build());
			}
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		g.centeredText(font, title, width / 2, 12, NexoStyle.TEXT_PRIMARY);
		if (message != null) {
			g.centeredText(font, Component.literal(message), width / 2, height / 2, NexoStyle.TEXT_SECONDARY);
		}
		if (capes == null) {
			return;
		}
		int first = page * perPage();
		for (int i = first; i < Math.min(capes.size(), first + perPage()); i++) {
			NexoCapes.Pending cape = capes.get(i);
			int x = cardX(i - first);
			int y = cardY(i - first);
			g.fill(x, y, x + CARD_W, y + CARD_H, 0xC0101020);
			g.text(font, font.plainSubstrByWidth(cape.name(), CARD_W - 8), x + 4, y + 4, NexoStyle.TEXT_PRIMARY);
			g.text(font, cape.approved() ? "Approved" : "Waiting", x + 4, y + 14, cape.approved() ? NexoStyle.MUTED : NexoStyle.TEXT_SECONDARY);
			Identifier texture = NexoCapes.texture(cape.id(), !cape.approved());
			if (texture != null) {
				// The outside of the cape (10x16 at 1,1) and of the elytra (10x20 at 36,2), on a 64x32 layout.
				g.blit(RenderPipelines.GUI_TEXTURED, texture, x + 8, y + 28, 1F, 1F, 50, 80, 10, 16, 64, 32);
				g.blit(RenderPipelines.GUI_TEXTURED, texture, x + 66, y + 28, 36F, 2F, 40, 80, 10, 20, 64, 32);
			}
		}
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
