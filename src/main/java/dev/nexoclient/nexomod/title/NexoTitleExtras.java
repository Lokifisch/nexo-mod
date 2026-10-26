package dev.nexoclient.nexomod.title;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import org.joml.Matrix3x2fStack;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.components.PlayerSkinWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.EventLoopGroupHolder;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.PlayerSkin;

import dev.nexoclient.nexomod.badge.NexoOnlineCount;
import dev.nexoclient.nexomod.hud.NexoHudVisibility;
import dev.nexoclient.nexomod.screen.AccountSwitcherScreen;
import dev.nexoclient.nexomod.screen.NexoShapes;
import dev.nexoclient.nexomod.screen.NexoStyle;
import dev.nexoclient.nexomod.servers.NexoQuickConnect;
import dev.nexoclient.nexomod.servers.NexoServerEntry;
import dev.nexoclient.nexomod.servers.NexoServerList;

/**
 * The right half of the title screen, which the Nexo layout left empty: an
 * account chip (top right), the live Nexo player count, a "What's new" card
 * for the latest release, a quick-join card with ping and player counts, and
 * a turnable model of the signed-in player.
 *
 * <p>Everything here lives in this package on purpose: {@code
 * NexoTitleLayoutMixin} sorts the title screen's widgets into its left column
 * every frame and skips this package by name. Parts that don't fit a small
 * window are dropped, least useful first, rather than overlapping the column.
 */
public final class NexoTitleExtras {
	private static final int MARGIN = 12;
	private static final int COLUMN_WIDTH = 220;
	private static final int ROW_HEIGHT = 22;
	private static final int MAX_SERVERS = 4;
	private static final float SMALL = 0.75F;
	private static final ExecutorService PING_POOL = Executors.newFixedThreadPool(2, runnable -> {
		Thread thread = new Thread(runnable, "nexomod title ping");
		thread.setDaemon(true);
		return thread;
	});

	/** Per title screen, so a resize (which re-runs init) doesn't ping everything again. */
	private static final Map<Screen, State> STATES = new WeakHashMap<>();

	private record Server(ServerData data, NexoServerEntry quick) {
	}

	private static final class State {
		final ServerStatusPinger pinger = new ServerStatusPinger();
		final List<Server> servers = new ArrayList<>();
		/** Set when the screen goes: a ping still connecting then cleans up after itself. */
		volatile boolean closed;
		boolean pinged;
	}

	/** Column geometry for the current frame, set by placement. */
	private record Column(int x0, int x1, int notesTop, int notesBottom, int serversTop) {
	}

	private static final Map<Screen, Column> COLUMNS = new WeakHashMap<>();

	/**
	 * Minecraft's own pixel font, whatever the custom-font setting has done to
	 * the default one — the card text is read, not glanced at, and the pixel
	 * font is the readable one at this size. See assets/nexomod/font/vanilla.json.
	 */
	private static final net.minecraft.network.chat.FontDescription VANILLA_FONT =
			new net.minecraft.network.chat.FontDescription.Resource(
					net.minecraft.resources.Identifier.fromNamespaceAndPath("nexomod", "vanilla"));

	private static Component vanilla(String text) {
		return Component.literal(text).withStyle(style -> style.withFont(VANILLA_FONT));
	}

	private NexoTitleExtras() {
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (screen instanceof TitleScreen && NexoHudVisibility.nexoSkinActive()) {
				build(client, screen, width, height);
			}
		});
	}

	private static void build(Minecraft mc, Screen screen, int width, int height) {
		boolean network = dev.nexoclient.nexomod.screen.NexoConfig.get().titleNetworkEnabled();
		State state = STATES.computeIfAbsent(screen, s -> network ? listServers(mc) : new State());
		Font font = mc.font;
		// From the session, not mc.getGameProfile(): that one waits on a profile
		// lookup an account switch has just restarted, which would freeze the
		// title screen until Mojang answers.
		net.minecraft.client.User user = mc.getUser();
		Supplier<PlayerSkin> skin = mc.getSkinManager().createLookup(
				new com.mojang.authlib.GameProfile(user.getProfileId(), user.getName()), false);

		AccountChip chip = new AccountChip(mc, screen, skin);
		chip.setPosition(width - MARGIN - chip.getWidth(), MARGIN);
		Screens.getWidgets(screen).add(chip);

		int leftEdge = Math.max(16, width / 12) + 170 + 16;
		int columnX1 = width - MARGIN;
		int columnX0 = columnX1 - COLUMN_WIDTH;
		boolean column = columnX0 - leftEdge >= 40;
		int y = MARGIN + 22 + 18;
		int notesTop = -1;
		int notesBottom = -1;
		int serversTop = -1;
		if (column) {
			// What's new above quick join; each dropped when the window is too short for it.
			if (network && height >= 300) {
				notesTop = y;
				notesBottom = y + Math.min(150, height / 3);
				y = notesBottom + 8;
			}
			if (!state.servers.isEmpty() && y + 18 + ROW_HEIGHT <= height - 30) {
				serversTop = y;
				// Only once the card is really shown: each ping hands that server this
				// machine's IP, so none is sent for a card nobody sees.
				startPings(mc, state);
				int rowY = y + 18;
				for (Server server : state.servers) {
					if (rowY + ROW_HEIGHT > height - 30) {
						break;
					}
					ServerRow row = new ServerRow(screen, server, COLUMN_WIDTH - 12);
					row.setPosition(columnX0 + 6, rowY);
					Screens.getWidgets(screen).add(row);
					rowY += ROW_HEIGHT;
				}
			}
		}
		COLUMNS.put(screen, column ? new Column(columnX0, columnX1, notesTop, notesBottom, serversTop) : null);

		// The player, standing in the space between the button column and the cards.
		int gapX0 = leftEdge;
		int gapX1 = column ? columnX0 - 16 : width - MARGIN;
		int modelHeight = Math.min(240, height - 90);
		int modelWidth = modelHeight * 2 / 3;
		if (gapX1 - gapX0 >= modelWidth + 20 && modelHeight >= 90) {
			PlayerSkinWidget model = new PlayerSkinWidget(modelWidth, modelHeight, mc.getEntityModels(), skin);
			// Turned towards the menu buttons on the left; dragging still turns it freely.
			((dev.nexoclient.nexomod.mixin.PlayerSkinWidgetAccessor) model).nexomod$setRotationY(-35F);
			model.setPosition((gapX0 + gapX1 - modelWidth) / 2, (height - modelHeight) / 2 + 10);
			Screens.getWidgets(screen).add(model);
		}

		// After the title screen's own drawing — it paints its background inside its
		// render pass, over anything drawn earlier. The server rows are drawn here
		// too, on their card, instead of in the widget pass beneath it.
		ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, delta) -> draw(s, graphics, font, mouseX, mouseY, delta));
		ScreenEvents.afterTick(screen).register(s -> state.pinger.tick());
		ScreenEvents.remove(screen).register(s -> {
			state.closed = true;
			state.pinger.removeAll();
			STATES.remove(s);
		});
	}

	/** Quick Servers if any are saved (they are the player's own shortlist), else the multiplayer list. */
	private static State listServers(Minecraft mc) {
		State state = new State();
		for (NexoServerEntry entry : NexoServerList.get().entries()) {
			if (state.servers.size() == MAX_SERVERS) {
				break;
			}
			if (!entry.address.isBlank()) {
				String name = entry.name.isBlank() ? entry.address : entry.name;
				state.servers.add(new Server(new ServerData(name, entry.address, ServerData.Type.OTHER), entry));
			}
		}
		if (state.servers.isEmpty()) {
			ServerList list = new ServerList(mc);
			list.load();
			for (int i = 0; i < list.size() && state.servers.size() < MAX_SERVERS; i++) {
				state.servers.add(new Server(list.get(i), null));
			}
		}
		return state;
	}

	private static void startPings(Minecraft mc, State state) {
		if (state.pinged) {
			return;
		}
		state.pinged = true;
		for (Server server : state.servers) {
			ServerData data = server.data();
			data.ping = -2L;
			data.players = null;
			PING_POOL.execute(() -> {
				if (state.closed) {
					return;
				}
				try {
					state.pinger.pingServer(data, () -> {}, () -> {}, EventLoopGroupHolder.remote(mc.options.useNativeTransport()));
				} catch (Exception e) {
					data.ping = -1L;
				}
				// The screen may have gone while this was connecting; its pinger is no
				// longer ticked, so drop the connection here instead of leaving it open.
				if (state.closed) {
					mc.execute(state.pinger::removeAll);
				}
			});
		}
	}

	private static void draw(Screen screen, GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY, float delta) {
		int online = NexoOnlineCount.get();
		if (online >= 0) {
			String text = Component.translatable("nexomod.title.online", online).getString();
			int w = Math.round(font.width(text) * SMALL) + 16;
			int x1 = screen.width - MARGIN;
			int y0 = MARGIN + 26;
			NexoShapes.outlined(graphics, x1 - w, y0, x1, y0 + 12, 0xCC101015, NexoStyle.CONTROL, 6);
			NexoShapes.fillRounded(graphics, x1 - w + 5, y0 + 4, x1 - w + 9, y0 + 8, 0xFF2CFF7A, 2);
			small(graphics, font, Component.literal(text), x1 - w + 12, y0 + 3, NexoStyle.TEXT_SECONDARY);
		}

		Column column = COLUMNS.get(screen);
		if (column == null) {
			return;
		}
		if (column.notesTop() >= 0) {
			drawNotes(graphics, font, column);
		}
		if (column.serversTop() >= 0) {
			int rows = 0;
			for (var widget : Screens.getWidgets(screen)) {
				if (widget instanceof ServerRow) {
					rows++;
				}
			}
			int y1 = column.serversTop() + 18 + rows * ROW_HEIGHT + 6;
			card(graphics, column.x0(), column.serversTop(), column.x1(), y1);
			heading(graphics, font, Component.translatable("nexomod.title.servers"), column.x0() + 8, column.serversTop() + 7);
			ServerRow.cardPass = true;
			try {
				for (var widget : Screens.getWidgets(screen)) {
					if (widget instanceof ServerRow row) {
						row.extractRenderState(graphics, mouseX, mouseY, delta);
					}
				}
			} finally {
				ServerRow.cardPass = false;
			}
		}
	}

	private static void drawNotes(GuiGraphicsExtractor graphics, Font font, Column column) {
		int x0 = column.x0();
		int y0 = column.notesTop();
		card(graphics, x0, y0, column.x1(), column.notesBottom());
		NexoWhatsNew.Notes notes = NexoWhatsNew.get();
		heading(graphics, font, Component.translatable("nexomod.title.whatsNew"), x0 + 8, y0 + 7);
		if (notes == null) {
			small(graphics, font, Component.translatable("nexomod.title.whatsNew.none"), x0 + 8, y0 + 22, NexoStyle.MUTED);
			return;
		}
		Component tag = Component.literal(notes.tag());
		small(graphics, font, tag, column.x1() - 8 - Math.round(font.width(tag) * SMALL), y0 + 7, NexoStyle.MUTED);
		int y = y0 + 21;
		for (String line : notes.lines()) {
			for (FormattedCharSequence wrapped : font.split(vanilla(line), COLUMN_WIDTH - 16)) {
				if (y + 9 > column.notesBottom() - 4) {
					return;
				}
				graphics.text(font, wrapped, x0 + 8, y, NexoStyle.TEXT_SECONDARY);
				y += 10;
			}
			y += 3;
		}
	}

	private static void card(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1) {
		NexoShapes.outlined(graphics, x0, y0, x1, y1, 0xE00C0C10, 0xFF1F1F28, 6);
	}

	private static void heading(GuiGraphicsExtractor graphics, Font font, Component text, int x, int y) {
		small(graphics, font, Component.literal(text.getString().toUpperCase(java.util.Locale.ROOT))
				.withStyle(style -> style.withBold(true)), x, y, NexoStyle.ACCENT);
	}

	private static void small(GuiGraphicsExtractor graphics, Font font, Component text, int x, int y, int color) {
		Matrix3x2fStack pose = graphics.pose();
		pose.pushMatrix();
		pose.scale(SMALL, SMALL);
		graphics.text(font, text, Math.round(x / SMALL), Math.round(y / SMALL), color);
		pose.popMatrix();
	}

	/** Face, name and an online dot; opens the account switcher (safe here: no world is loaded). */
	private static final class AccountChip extends AbstractButton {
		private final Minecraft mc;
		private final Screen screen;
		private final Supplier<PlayerSkin> skin;

		AccountChip(Minecraft mc, Screen screen, Supplier<PlayerSkin> skin) {
			super(0, 0, mc.font.width(mc.getUser().getName()) + 44, 22, Component.literal(mc.getUser().getName()));
			this.mc = mc;
			this.screen = screen;
			this.skin = skin;
			setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("nexomod.title.accounts")));
		}

		@Override
		public void onPress(InputWithModifiers input) {
			mc.gui.setScreen(new AccountSwitcherScreen(screen));
		}

		@Override
		protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
			int x0 = getX();
			int y0 = getY();
			int x1 = x0 + getWidth();
			int y1 = y0 + getHeight();
			boolean hot = isHoveredOrFocused();
			NexoShapes.outlined(graphics, x0, y0, x1, y1, 0xCC101015,
					hot ? NexoStyle.mix(NexoStyle.CONTROL, NexoStyle.ACCENT, 0.7F) : NexoStyle.CONTROL, 5);
			PlayerFaceExtractor.extractRenderState(graphics, skin.get(), x0 + 3, y0 + 3, 16);
			graphics.text(mc.font, getMessage(), x0 + 24, y0 + 7, NexoStyle.TEXT_PRIMARY);
			NexoShapes.glow(graphics, x1 - 11, y0 + 9, x1 - 7, y0 + 13, 0xFF2CFF7A, 2, 0.3F);
			NexoShapes.fillRounded(graphics, x1 - 11, y0 + 9, x1 - 7, y0 + 13, 0xFF2CFF7A, 2);
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			defaultButtonNarrationText(output);
		}
	}

	/** One server: name left, ping and player count right; a click joins it. */
	private static final class ServerRow extends AbstractButton {
		/** Set while the card is being drawn: rows draw only then, on top of it. */
		static boolean cardPass;
		private final Screen screen;
		private final Server server;

		ServerRow(Screen screen, Server server, int width) {
			super(0, 0, width, ROW_HEIGHT - 2, Component.literal(server.data().name));
			this.screen = screen;
			this.server = server;
			setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("nexomod.title.join", server.data().ip)));
		}

		@Override
		public void onPress(InputWithModifiers input) {
			if (server.quick() != null) {
				NexoQuickConnect.switchTo(server.quick());
			} else {
				ServerData data = server.data();
				ConnectScreen.startConnecting(screen, Minecraft.getInstance(), ServerAddress.parseString(data.ip), data, false, null);
			}
		}

		@Override
		protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
			if (!cardPass) {
				return;
			}
			Font font = Minecraft.getInstance().font;
			int x0 = getX();
			int y0 = getY();
			int x1 = x0 + getWidth();
			int y1 = y0 + getHeight();
			if (isHoveredOrFocused()) {
				NexoShapes.outlined(graphics, x0, y0, x1, y1, NexoStyle.mix(0xFF0C0C10, NexoStyle.ACCENT, 0.12F), NexoStyle.ACCENT, 4);
			}
			ServerData data = server.data();
			String status;
			int statusColor;
			if (data.players != null && data.ping >= 0) {
				// Clamped: a server can report any player count it likes.
				status = Math.min(data.players.online(), 999_999) + "/" + Math.min(data.players.max(), 999_999)
						+ "  " + Math.min(data.ping, 9_999) + " ms";
				statusColor = data.ping < 80 ? 0xFF2CFF7A : data.ping < 200 ? 0xFFFFD166 : NexoStyle.DANGER;
			} else if (data.ping == -1L) {
				status = Component.translatable("nexomod.title.offline").getString();
				statusColor = NexoStyle.DANGER;
			} else {
				status = "…";
				statusColor = NexoStyle.MUTED;
			}
			Component statusText = vanilla(status);
			int statusWidth = font.width(statusText);
			graphics.text(font, statusText, x1 - 6 - statusWidth, y0 + 6, statusColor);
			String name = data.name;
			int maxName = x1 - 12 - statusWidth - (x0 + 6);
			if (font.width(vanilla(name)) > maxName) {
				while (!name.isEmpty() && font.width(vanilla(name + "…")) > maxName) {
					name = name.substring(0, name.length() - 1);
				}
				name += "…";
			}
			graphics.text(font, vanilla(name), x0 + 6, y0 + 6, NexoStyle.TEXT_PRIMARY);
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			defaultButtonNarrationText(output);
		}
	}
}
