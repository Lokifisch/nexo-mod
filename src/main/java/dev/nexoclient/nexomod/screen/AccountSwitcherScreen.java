package dev.nexoclient.nexomod.screen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.client.gui.components.ScrollableLayout;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import dev.nexoclient.nexomod.auth.AccountStore;
import dev.nexoclient.nexomod.auth.LauncherAccount;
import dev.nexoclient.nexomod.auth.LoginFlow;
import dev.nexoclient.nexomod.auth.MinecraftAccount;
import dev.nexoclient.nexomod.auth.SessionSwap;

/** Popup for picking, adding, or removing signed-in Minecraft accounts. */
public class AccountSwitcherScreen extends NexoModalScreen {
	private static final int ROW_WIDTH = 240;
	private static final int ROW_HEIGHT = 24;
	private static final int ROW_SPACING = 4;
	/** Account rows shown at once; past this the list scrolls. */
	private static final int VISIBLE_ROWS = 5;
	/** The "..." logout button sharing each row with the account itself. */
	private static final int LOGOUT_BUTTON_WIDTH = 20;

	/** Rebuilt per init() for the same reason {@link NexoModalScreen}'s layout is. */
	private LinearLayout accountList;
	private ScrollableLayout accountScroll;
	private int rowCount;

	public AccountSwitcherScreen(Screen parent) {
		super(Component.translatable("nexomod.accounts.title"), parent);
	}

	@Override
	protected void init() {
		super.init();
		LauncherAccount.captureIfNeeded();

		accountList = LinearLayout.vertical().spacing(ROW_SPACING);
		layout.defaultCellSetting().alignHorizontallyCenter();
		layout.addChild(new StringWidget(title.copy().withStyle(style -> style.withColor(NexoStyle.TEXT_ACTIVE_ACCENT).withBold(true)), font));

		AccountStore store = AccountStore.get();
		// The live session, not the store's marker, decides which row is "current" —
		// it's the only source that also covers the launcher's own un-stored account.
		UUID activeUuid = minecraft.getUser().getProfileId();

		rowCount = 0;
		for (RowEntry entry : buildRows(store)) {
			LinearLayout row = accountList.addChild(LinearLayout.horizontal().spacing(4));
			boolean isCurrent = activeUuid != null && activeUuid.equals(entry.uuid);
			boolean isOffline = entry.storedAccount() != null && entry.storedAccount().offline();
			row.addChild(new AccountRowWidget(0, 0, ROW_WIDTH, ROW_HEIGHT, entry.uuid, entry.name, isCurrent, entry.isLauncherAccount, isOffline,
					() -> switchTo(entry)));

			NexoButton logoutButton = NexoButton.builder(Component.literal("..."), () -> confirmLogout(entry)).size(LOGOUT_BUTTON_WIDTH, ROW_HEIGHT).build();
			logoutButton.active = !entry.isLauncherAccount;
			row.addChild(logoutButton);
			rowCount++;
		}

		// Measure before wrapping — see NexoQolOverlayScreen.init() for why an
		// unmeasured content layout makes ScrollableLayout hide its own rows.
		accountList.arrangeElements();
		// maxHeight is set for real in repositionElements; this value only has to
		// be non-zero for the container to be constructible.
		accountScroll = new ScrollableLayout(minecraft, accountList, listMaxHeight());
		accountScroll.setMinWidth(ROW_WIDTH + ROW_SPACING + LOGOUT_BUTTON_WIDTH);
		layout.addChild(accountScroll);

		LinearLayout buttonRow = layout.addChild(LinearLayout.horizontal().spacing(4));
		buttonRow.defaultCellSetting().paddingTop(10);
		buttonRow.addChild(NexoButton.builder(Component.translatable("nexomod.accounts.add"), () -> LoginFlow.start(this)).build());
		buttonRow.addChild(NexoButton.builder(Component.translatable("nexomod.accounts.offline"), () -> minecraft.setScreen(new OfflineLoginScreen(this))).build());
		buttonRow.addChild(NexoButton.builder(CommonComponents.GUI_DONE, this::onClose).build());

		finishLayout();
	}

	/**
	 * {@link #VISIBLE_ROWS} rows at most, but never more than there are accounts —
	 * one signed-in account should get a one-row panel, not a tall box with four
	 * empty slots — and never more than the window can hold above the title and
	 * the button row.
	 */
	private int listMaxHeight() {
		int wanted = VISIBLE_ROWS * ROW_HEIGHT + (VISIBLE_ROWS - 1) * ROW_SPACING;
		int content = Math.max(1, rowCount) * ROW_HEIGHT + Math.max(0, rowCount - 1) * ROW_SPACING;
		int available = height - 120;
		return Math.max(ROW_HEIGHT, Math.min(Math.min(wanted, content), available));
	}

	@Override
	protected void repositionElements() {
		// Guards the super call made from NexoModalScreen.init(), before init()
		// here has had a chance to build the scroll area.
		if (accountScroll != null) {
			accountScroll.setMaxHeight(listMaxHeight());
		}
		super.repositionElements();
	}

	private record RowEntry(UUID uuid, String name, boolean isLauncherAccount, MinecraftAccount storedAccount) {}

	/** Every stored account, plus (if it isn't already one of them) a synthetic row for whatever account actually launched the game. */
	private static List<RowEntry> buildRows(AccountStore store) {
		Map<UUID, RowEntry> rows = new LinkedHashMap<>();
		for (MinecraftAccount account : store.accounts()) {
			rows.put(account.uuid(), new RowEntry(account.uuid(), account.name(), LauncherAccount.is(account.uuid()), account));
		}
		LauncherAccount.user().ifPresent(user -> rows.putIfAbsent(
				user.getProfileId(), new RowEntry(user.getProfileId(), user.getName(), true, null)));
		return new ArrayList<>(rows.values());
	}

	private void switchTo(RowEntry entry) {
		if (entry.storedAccount() == null) {
			// Not a stored (mod-managed) account — must be the launcher's own, un-refreshable session.
			SessionSwap.restoreLauncherAccount();
			minecraft.setScreen(new AccountSwitcherScreen(parent));
			return;
		}
		AtomicBoolean cancelled = new AtomicBoolean(false);
		if (entry.storedAccount().isExpired()) {
			// The refresh is several network round-trips; without a visible
			// waiting state the click just looks ignored for a few seconds.
			minecraft.setScreen(new SigningInScreen(new AccountSwitcherScreen(parent), cancelled,
					Component.translatable("nexomod.accounts.switchingTitle"),
					Component.translatable("nexomod.accounts.switching")));
		}
		LoginFlow.switchTo(entry.storedAccount(), cancelled,
				() -> minecraft.setScreen(new AccountSwitcherScreen(parent)),
				error -> minecraft.setScreen(new net.minecraft.client.gui.screens.AlertScreen(
						() -> minecraft.setScreen(new AccountSwitcherScreen(parent)),
						Component.translatable("nexomod.login.failedTitle"),
						Component.literal(error.getMessage() != null ? error.getMessage() : error.toString()))));
	}

	private void confirmLogout(RowEntry entry) {
		if (entry.isLauncherAccount()) {
			return;
		}
		minecraft.setScreen(new ConfirmScreen(
				confirmed -> {
					if (confirmed) {
						AccountStore.get().remove(entry.uuid());
					}
					minecraft.setScreen(new AccountSwitcherScreen(parent));
				},
				Component.translatable("nexomod.accounts.logoutTitle"),
				Component.translatable("nexomod.accounts.logoutConfirm", entry.name())));
	}
}
