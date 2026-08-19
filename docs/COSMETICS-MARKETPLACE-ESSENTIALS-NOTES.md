# Cosmetics marketplace — design notes ("make it like Essentials")

Not built yet. Saved 2026-08-19 so the research isn't lost before there's
time to act on it. When this gets picked up, treat it as a design reference,
not a spec — pick what's worth doing, in whatever order makes sense then.

## Where this came from

Essential ships a cosmetics browser they call the "Wardrobe"
(`Essential-Mod/gui/essential/src/main/kotlin/gg/essential/gui/wardrobe/`).
Per this repo's own rules, `Essential-Mod/` is source-available, not open
source, and forbids derivative works — everything below is a description of
UX patterns in plain English, written from reading their screens, not copied
code. Nothing here should ever be implemented by porting code from that
directory; it's a description to build fresh from, same as every other
feature in `Mod/`.

## What their store actually looks like

- **Navigation**: persistent left sidebar (Featured / Outfits / Skins /
  Emotes / Cosmetics), each with an icon + accent color. Cosmetics/Emotes
  expand into per-slot sub-categories (capes, hats, back items, wings,
  effects...). Empty sub-categories show greyed/struck-through rather than
  disappearing. Search box + an "owned only" filter sit above the grid.
- **Layout**: full in-game overlay (not a hard alt-screen takeover) —
  sidebar + a scrollable grid of square tiles (art + name label) + a live 3D
  player-preview pane that updates as you browse/equip.
- **Rarity**: five tiers (Common/Uncommon/Rare/Epic/Legendary), each a
  distinct color shown as a thin bar along a tile's bottom edge plus a
  subtle color wash behind the preview art.
- **Price / buy / equip**: price tag (or "FREE") in a tile's corner when
  unowned; struck-through original price above a discounted one during
  sales. Clicking unowned opens a confirmation dialog (name, discount line,
  total, Purchase/Cancel). Owned items skip the price and just equip on
  click.
- **Owned/equipped state**: green checkmark badge once owned (a separate
  color variant for legacy-owned). Equipped state shown via the tile's
  outline, not a separate badge. Items with variants show a swatch stack on
  hover.
- **Featured/new**: a separate curated "Featured" tab with a variable-size
  grid (some tiles span multiple cells). Small badges for "NEW", "SAVE
  &lt;amount&gt;", percent-off, and a countdown-clock badge for limited-time
  items.
- **Currency purchases**: coins bought as fixed bundles shown as their own
  tiles (bundle art, coin amount, price, a bonus-coins callout, a
  "most popular" ribbon on one tile).
- **Creator codes**: account-level, not per-item — entering a code in the
  currency-purchase dialog tags future purchases as supporting that
  creator. No per-cosmetic "made by" credit; gifted items instead show
  "Gifted by [player]" on the owned checkmark.

## Mapping onto Nexo's cosmetics system as it exists today

What's already there that this would build on: `CosmeticsCatalog.Item`
(id/type/name/price/creator_uuid), owned/equipped tracking in
`NexoCosmeticsScreen`/`NexoCosmeticsOptionList`, the wallet/purchase flow in
`CosmeticsService`.

Roughly cheapest-to-most-work, not a commitment to build in this order:

1. **"FREE" label instead of $0**, and a real purchase confirmation dialog
   before spending coins. Pure UI, no schema change.
2. **Owned/equipped badges** (checkmark corner, outline instead of a
   separate "Equipped" button state) — the picker already tracks this data,
   this is a rendering change to `NexoCosmeticsOptionList`'s rows.
3. **Rarity tiers** — needs a `rarity` column on `nexo_cosmetics_catalog`
   (small migration) plus a color-bar render in the picker. Admin sets it
   when adding a catalog item.
4. **Featured / new badges** — needs a `featured` bool and `created_at`
   already exists implicitly via row insertion order; a "new" badge is just
   "created within N days."
5. **Sidebar + tile grid** instead of the current scrollable text-row list —
   the real UI rebuild. Bigger lift: needs actual cosmetic preview art
   (currently textures are 64×32 cape sheets, not tile-sized icons), a grid
   widget, category tabs.
6. **Coin bundles as purchasable tiles** — this is Essential's *real-money*
   purchase flow specifically. Belongs with the real-money phase already
   agreed as future work (see the cosmetics marketplace plan), not the
   free-currency version running now.
7. **Account-level creator codes** — cleaner than the per-item attribution
   idea floated earlier. Also belongs with the real-money phase, since a
   creator code only means something once a purchase can be attributed to
   real revenue.
8. **Live 3D player-preview pane** — a real feature on its own (rendering
   the local player's model live inside a menu), not a styling change.
   Worth doing, but scope it separately from a "make it look nicer" pass.

## Explicitly not adopting

- Essential's exact five-tier rarity naming/colors — fine as inspiration,
  Nexo should pick its own if this gets built, not reuse theirs verbatim.
- Anything under `Essential-Mod/` gets read again for this — re-derive from
  memory of this document instead where possible, to keep the "inspiration
  only, never copied" line clean over time.
