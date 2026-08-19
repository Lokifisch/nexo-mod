package dev.nexoclient.nexomod.screen;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import dev.nexoclient.nexomod.cosmetics.NexoCosmetics;

/**
 * The cosmetics picker — capes for now, the only cosmetic type
 * {@code CosmeticsService} accepts uploads for (see its Phase B scope note).
 * A row per catalog item plus a fixed "Official Cape" row; see
 * {@code VanillaCapeSuppressionMixin} for why that row is just an unequip
 * call rather than its own tracked cosmetic.
 *
 * <p>Wallet balance and ownership are fetched async on open and the list is
 * rebuilt once each lands, the same "instant feedback where it's free,
 * eventual consistency otherwise" shape the rest of cosmetics networking
 * uses (see {@code CosmeticsEquipped#applyLocalEquip}).
 */
public class NexoCosmeticsScreen extends NexoOptionScreen {
	private volatile Integer balance;
	/** Local optimistic copy — updated the moment a purchase succeeds, not just on the next fetch. */
	private final Set<Integer> owned = ConcurrentHashMap.newKeySet();

	public NexoCosmeticsScreen(Screen lastScreen) {
		super(lastScreen, Component.translatable("nexomod.cosmetics.title"), new NexoCosmeticsOptionList(
				Minecraft.getInstance(), 0, 0, HEADER_MARGIN, BASE_LIST_ENTRY_WIDTH, LIST_ENTRY_HEIGHT, LIST_ENTRY_SPACING, null));
		((NexoCosmeticsOptionList) list).setOwningScreen(this);

		NexoCosmetics.fetchWallet(value -> {
			balance = value;
			refreshList();
		});
		NexoCosmetics.fetchOwned(value -> {
			if (value != null) {
				owned.addAll(value);
			}
			refreshList();
		});
	}

	/** Balance in coins, or null while the initial fetch is still in flight. */
	Integer balance() {
		return balance;
	}

	boolean owns(int cosmeticId) {
		return owned.contains(cosmeticId);
	}

	void markOwned(int cosmeticId) {
		owned.add(cosmeticId);
	}

	/** Re-fetches the balance after a purchase, rather than guessing the new figure client-side. */
	void refreshWallet() {
		NexoCosmetics.fetchWallet(value -> {
			balance = value;
			refreshList();
		});
	}

	void refreshList() {
		if (list != null) {
			list.init();
		}
	}
}
