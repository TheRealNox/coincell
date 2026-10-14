# GBFlash UI / UX Design

Status: proposal v1 (design only, no code changed). Target: Pixel 8 Pro, Android 14+, Material 3, Jetpack Compose, dynamic colour, light and dark.

Basis: `MainScreen.kt` (single scrolling column of cards: Connection, Firmware, Platform radio list, raw-text header card, Backup card with inline history, collapsible hex and debug log) and `PORTING_SPEC.md` 4.2 / 8.2 (AGB is always 3.3 V and set before cart power; 5 V on a GBA cart can damage it; the platform is never auto-detected; the firmware keeps the last voltage, so a mode change must be an explicit user action followed by SetMode; disconnect does CART_PWR_OFF, or falls back to 3.3 V).

Note: a screenshot of the phone was attempted (`scratchpad\design\current.png`) but it came out fully black (screen off or secure surface). The "current UI" analysis therefore comes from the source, not from a visual.

Assumption flagged for engineering: the app can power the cart off without closing the USB session (CART_PWR_OFF, firmware flag "power ctrl"). If a board lacks this flag, "Power off cart" falls back to "Disconnect" and the swap flow tells the user to unplug the reader (see Open questions).

---

## 1. Design principles

1. **Hardware state is always visible.** One status surface (the Device Status Bar) is on every top-level screen. The user never has to guess whether the cart is powered or what voltage it is at.
2. **The library is the product; the reader is a tool.** Everything that does not need hardware (browse, export, note, delete, edit) works with no reader and never shows a "connect first" wall.
3. **Destructive actions are hard to do by accident.** Restore and delete are never one tap, never the primary-coloured default, and always name the game and backup concerned. Friction is proportional to damage: delete = confirm dialog; restore = full-screen review plus a deliberate hold-to-confirm.
4. **No default, no memory for voltage.** The cartridge platform has no preselection and is forgotten at every power-off, disconnect, or swap. A wrong platform is the only way the app can hurt hardware, so it gets the most careful UI.
5. **Never overwrite history.** Backups are immutable. Edits, restore pre-backups and imports all create new entries.
6. **Say what is true, then what to do.** Every error states the fact (what happened, in plain words) and offers exactly one recovery action. Raw hex and TX/RX stay in Settings > Debug log, never in the main flow.
7. **Not colour alone.** Every state and warning uses an icon plus a text label plus colour. Platform identity uses shape plus label plus colour.

---

## 2. Information architecture and navigation

### 2.1 Top-level destinations (bottom navigation bar, 3 items)

| Destination | Icon (Material Symbols) | Purpose |
|---|---|---|
| **Library** | `inventory_2` | All games, backup history, export, delete, notes, entry to the editor and to restore. Works offline. |
| **Device** | `usb` | Reader connection, cartridge platform, cart identity, actions on the inserted cart (back up, restore, swap). |
| **Settings** | `settings` | Preferences, debug log, raw header, firmware details, licences, about. |

Why not Device / Library / Editor: the editor is not a place you go, it is something you do to one save. It opens from Game detail or Backup detail as a full-screen flow (hides the bottom bar), like a document editor. Putting it in the bar would give it an empty state ("no save open") and hide the real path (pick a save first).

Start destination: **Library** when the app is opened normally and at least one backup exists. **Device** when the app is launched by the USB-attach intent, or on first launch (empty library). Tab selection is otherwise restored.

Navigation pattern:
- Phone portrait: `NavigationBar` (3 items) + top app bar. Detail screens (Game, Backup, Restore, Editor) are pushed on top, with a back arrow; the bottom bar stays on Game detail and Backup detail (so Device status stays reachable), and hides on Restore flow, backup progress and the Editor.
- Medium/Expanded (tablet, unfolded Fold): `NavigationRail` + list-detail (`ListDetailPaneScaffold`): Library list on the left, Game detail on the right. Editor uses a 2-pane: box grid left, Pokemon detail right. Nothing else changes.

### 2.2 Relationship between the hardware session and the library

A single `DeviceSession` state (process-wide, owned by a ViewModel above the nav host) is surfaced in three ways:

1. **Device Status Bar** (persistent, top of Library, Device, Game detail, Backup detail). A one-line `Surface` under the top app bar, 48 dp tall, tappable (goes to Device). Content: status icon + text + (when relevant) platform chip. See 5.4 for the full state table. Offline browsing is not blocked by it: when nothing is plugged in it is a quiet, neutral "No reader connected" line with a `Connect` text button, not an error.
2. **Operation banner** (while an operation runs, on every screen, including Library): the status bar turns into a progress strip: `Backing up Pokemon Red  63%` and keeps a Cancel action. Navigating away never hides a running operation. Operation screens (backup progress, restore progress) are the detailed view of the same state.
3. **Contextual hardware actions in the library**. Game detail shows "Back up this cart" or "Restore to cart" only when relevant, and the latter is enabled only when a cart is connected and identified. When no cart is present the button stays visible but disabled with the reason ("Connect the reader and read a cartridge first", with a link). This teaches users that restore exists without making them hunt.

Cross-linking rule: a connected, identified cartridge is matched to a library entry by identity (platform + title + header checksum). Device Home shows "In your library: 3 backups, latest 12 Oct" with a link to that Game detail; Game detail shows "This game is in the reader now" chip when it matches.

### 2.3 Screen map

```
App
|- Library (tab)
|    |- Game detail (backup history)
|    |    |- Backup detail
|    |    |    |- Export (system file picker)  | Note edit (dialog) | Delete (dialog)
|    |    |    |- Restore flow --> R1 Review --> R2 Pre-backup --> R3 Write progress --> R4 Result
|    |    |    '- Open in Editor (Gen 1-3 Pokemon only) --> Editor
|    |    |- Back up this cart --> Backup progress --> Backup result
|    |    '- ROM dump section (later) --> Dump detail --> Export ROM (file picker) | Delete dump
|    |         (a dump is made from Device > Cart card > "Dump ROM" --> Dump progress --> Dump result)
|    '- (later) Import .sav, Compare
|- Device (tab)
|    |- Platform picker (full-screen sheet) --> Confirm platform (bottom sheet)
|    |- Cart card --> Backup flow  | Restore picker (choose a backup) --> Restore flow
|    '- Swap cartridge guided flow
|- Settings (tab)
|    |- Appearance, Backup defaults, Safety, Debug log, Raw header, Firmware, Licences, About
'- Editor (full screen, no bottom bar)
     |- Save overview
     |    |- Party & Boxes --> Pokemon detail --> Edit (Summary, Stats, Moves, Misc) 
     |    |- Trainer
     |    |- Items
     |    '- Legality report
     '- Save as new backup --> (optional) Restore to cart
```

---

## 3. Key user flows

### 3.1 First launch and USB permission

1. Launch (empty library, no reader). Land on **Device**. Show an illustrated empty state: "Plug in your GBFlash reader" with a USB-C cable drawing and one line: "You can also browse and edit saves without a reader" with a `Go to Library` text button.
2. User plugs in. Android shows its "Open GBFlash?" system sheet (USB attach intent filter); the app opens or comes to the front. Status bar: `Reader found, permission needed` (icon `key`).
3. The Device screen shows a card: "Allow GBFlash to use the USB reader" with a primary `Allow access` button. It triggers the system permission dialog. If the user denies: card changes to "Permission denied. GBFlash cannot talk to the reader without it." with `Ask again` and a `Why is this needed?` expandable (one sentence).
4. Granted: status `Connecting...` (indeterminate small progress), then `Reader connected` with firmware line ("GBFlash fw 15, PCB v1.4") shown in a compact reader card; full firmware details are in Settings.
5. Connected, no platform: the **Cartridge card** shows the platform picker inline (see 4.2). No cart is powered, and the card says so: "Cartridge power: off".
6. No onboarding carousel. One-time dismissible tip card on first connect: "Pick the cartridge type yourself. The reader cannot tell GB and GBA apart, and the wrong choice can damage a GBA cartridge."

### 3.2 Backing up a cart's save (including RTC choice)

Precondition: reader connected.
1. **Pick platform.** Tap `GB / GBC` or `GBA` tile (4.2). Confirm sheet; user confirms. Only now does the app power the cart (5 V or 3.3 V).
2. App reads the header automatically (it is part of confirming). Cart card shows title, mapper, ROM/save size, header checksum validity, logo validity, RTC badge if declared.
3. If logo invalid or blank: the card shows an error state "No cartridge found, or the wrong type is selected" (icon `error`) with `Power off and re-pick`. Backup is disabled.
4. **Back up.** Primary button `Back up save`. If the header declares RTC, a short pre-step appears inline (not a dialog) with a segmented choice, **default "Include clock (RTC)"**:
   - `Save + clock` (recommended), `Save only`.
   - One-line explanation: "This cartridge has a real-time clock (used by Pokemon Gold/Silver/Crystal for day/night events). Include it so a restore keeps the time."
   If the RTC cannot be read, the backup is still allowed, with a warning chip "Clock not read" and the choice is greyed with the reason (current behaviour: header declares RTC but unreadable).
5. **Progress screen** (4.3): reads and verifies each chunk twice; cancel allowed (nothing is written to the cart, so cancel is safe).
6. **Result** (4.3): success with game, size, CRC32, chips (Verified, RTC, Suspect), `Add note`, `View in library`, `Done`. If the data looks empty/suspect (all 00 or FF): a warning result "This save looks empty" with the saved entry still stored and flagged. Wording: "Stored anyway so you can check it. Is the battery dead, or is this game just unplayed?"
7. After the result, the cart remains powered; the Device screen foregrounds `Power off cart` / `Swap cartridge` as the primary next action (see 3.5).

### 3.3 Browsing the library, history, export, delete, note

1. **Library**: one row per game (title, platform badge, backup count, latest date, chips). Search plus filter chips (`All`, `GB`, `GBC`, `GBA`, `Pokemon`) and sort (Recent, A-Z).
2. Tap a game: **Game detail** with game identity header, "In the reader now" chip if matched, and the backup history list (newest first). Each row: timestamp, size, CRC32 short, chips (RTC, Suspect, Edited), note excerpt.
3. Tap a backup: **Backup detail**: full metadata, note, actions.
4. **Export**: `Export .sav` -> if RTC data exists, a bottom sheet asks `With clock (+48 bytes footer, FlashGBX/emulator format)` or `Save only` (default: with clock if the user's Settings default says so; otherwise ask) -> system `CreateDocument` picker, suggested filename `<Title>_<yyyy-MM-dd_HH-mm>.sav` -> snackbar "Exported to Downloads/..." with `Share` action.
5. **Note**: tap the note field (or `Add note`), a dialog with a text field (max 140 chars) and `Save`.
6. **Delete**: `Delete` (overflow menu, not a visible button) -> confirm dialog naming game and timestamp; the delete button text is "Delete backup" in error colour; if it is the last backup of the game, the dialog adds "This is the only backup of this game. The game will disappear from your library." After deletion: snackbar with `Undo` for 8 seconds (soft delete, actual file removal after the snackbar expires). Undo is the preferred safety net over typed confirmation, because deleting is frequent and reversible within the window.
7. Offline: everything above works with no reader. Hardware-only actions are disabled with the reason.

### 3.4 Restoring a backup to a cart (all safety steps)

Entry points: Backup detail > `Restore to cart...`, or Device > Cart card > `Restore a backup...` (opens a picker limited to backups of the matching game first, with "Other games" collapsed below).

Steps (a stepper at the top: Review, Safety copy, Writing, Done):

1. **R1 Review.** Full-screen comparison (4.7):
   - Left "On the cartridge now" (identity read from the header just now: title, platform, ROM checksum, save size) and right "Will be written" (game identity of the backup, timestamp, size, CRC32, note, chips). On phone portrait they are stacked cards with a down-arrow between them.
   - **Identity check row**: big, icon plus text.
     - Match (platform + title + header checksum): green-ish tonal `Same game` with `check_circle`.
     - Mismatch: error container card "These are different games" listing exactly which field differs (title / checksum / platform / save size). Restore is not blocked outright for mismatch of title or checksum (valid use: repro carts, other regions), but requires an extra step: the user must type the title of the cartridge ("type POKEMON RED to continue"). **Platform mismatch or save-size mismatch is blocked** with no override ("A GB backup cannot be written to a GBA cartridge" / "Backup is 32 KiB but this cartridge has 8 KiB of save space").
   - **Safety copy notice** (not a choice, not skippable): "Before writing, GBFlash will back up the save currently on the cartridge." With a link-style explanation, no toggle. Rationale: a skip toggle is a footgun.
   - RTC line: if the backup has clock data and the cart has an RTC: "Clock will also be restored" with a switch (default on). If the backup has no clock but the cart has one: "The cartridge clock will not be changed."
   - Consequence line in plain words: "This replaces the save on the cartridge. The current save is backed up first, so you can undo this."
   - Action: `Cancel` (outlined, left) and `Continue` (filled tonal, not error colour).
2. **R2 Safety copy.** Automatic: reads the cartridge save (same engine as backup, verified twice), stores it as a new library entry flagged "Pre-restore copy" with an automatic note ("Automatic copy before restoring backup of 12 Oct"). Progress as in backup. Cancel is allowed here (nothing written yet). If the read fails or it can't be verified: restore does not proceed; error with `Retry` and `Cancel`. If the safety copy is identical (same CRC32) to an existing latest backup, it is still stored (cheap, simple, and explicit), tagged "Duplicate of ...".
3. **R2b Final confirm.** After the safety copy succeeds, a `Hold to restore` button (press and hold 1.5 s with a filling ring; also reachable via TalkBack as a `Restore now` custom action with a confirmation dialog) with the summary "Write Pokemon Red backup 12 Oct 14:02 to the cartridge". The hold gesture exists because a tap is too easy; the TalkBack alternative is a normal two-step dialog.
4. **R3 Writing.** Full screen, cannot be dismissed with back (back shows a dialog). Content (4.7): large progress ring plus percent plus bytes, copy "Writing save... Do not unplug the reader or remove the cartridge." Screen is kept awake (`FLAG_KEEP_SCREEN_ON`), a foreground service keeps the operation alive if the app is backgrounded, and the Device Status Bar/notification shows progress.
   **Cancel policy**: cancelling a write mid-way leaves a half-written save, so Cancel is available but not prominent: a text button `Stop...` that opens a dialog "Stopping now leaves the cartridge with a partly written save. You can retry or restore the safety copy. Stop anyway?" `Keep writing` is the default focus; `Stop anyway` is error-coloured. After stopping, the result screen is the "Interrupted" variant with `Retry restore` and `Restore safety copy` as the two actions.
5. **R4 Verify.** Automatic read-back and compare (spec: restore verifies by read-back), as the last 10% of the stepper: "Verifying...". 
6. **R5 Result.**
   - Success: `check_circle`, "Restored and verified", game + backup, "Read-back matches CRC32 XXXXXXXX", `Done` (primary), `Power off cart` (secondary), `Undo restore` (text, "Writes the safety copy back", goes through the same flow with the safety copy preselected, skipping the identity warning).
   - Verify failed: error container, "The cartridge does not hold the data we wrote. The safety copy is stored in your library." Actions `Try again`, `Restore safety copy`. Likely cause list (collapsed): dirty contacts, battery, failing SRAM.
   - Unplugged mid-write: see 3.6 states (hardware error variant), result says "Reader disconnected during writing. The save on the cartridge may be incomplete." with `Reconnect` which then offers `Restore backup again` or `Restore safety copy`.

### 3.5 Swapping cartridges

Voltage is the risk: the app cannot sense a swap. The flow makes "power off, then swap, then re-pick" the only easy path.

- Everywhere the cart is powered, the Device Status Bar shows `Cart powered, 5 V` (or `3.3 V`). The Cart card has `Swap cartridge` as the **primary** action once an operation is complete (above `Back up` again? No: back up/restore remain on the card, `Swap cartridge` is a full-width tonal button in the card footer, always visible while the cart is powered).
- **Swap guided flow** (bottom sheet, 3 steps):
  1. Tap `Swap cartridge`: the app immediately powers the cart off (CART_PWR_OFF) and forgets platform and header. Sheet: "Cartridge power is off. It is safe to remove the cartridge now." with a power-off icon and the status bar changed to `Cart power off` (neutral).
  2. "Insert the new cartridge. Then tell GBFlash what kind it is." Single button `Choose cartridge type`.
  3. Opens the platform picker with no preselection (4.2).
- Safety nets besides the flow:
  - **Idle auto power-off**: after 3 minutes with no operation, the cart is powered off automatically and the status bar says `Cart power off (idle)`; the platform is forgotten. (Value configurable in Settings, min 1 min; never "never".)
  - **USB unplug** resets everything (platform forgotten).
  - **App backgrounded** for more than 30 s: same as idle power-off. On return the status bar says what happened.
  - **Foreground "you are done" prompt**: after a backup or restore result, the primary button is `Done`, and the secondary one is `Power off cart`; `Done` on the Device screen leaves the power state visible, in warning-style tonal (not error) so it is not mistaken for the idle state.
  - The reader's physical connector cannot be hot-swapped safely, so the sheet text says "Remove the cartridge only while power is off (the status bar shows 'Cart power off')".
  - After a swap, if the user picks the *same* platform, there is still the confirm sheet; there is no "same as last time" shortcut.
- If hardware lacks power control: the flow says "Unplug the reader from the phone, swap the cartridge, plug it in again" and the status after reconnect is the default connected, no platform chosen state.

### 3.6 Opening a Pokemon save and editing a party Pokemon

1. Library > `Pokemon Red` > tap a backup > `Open in editor` (visible only when the game is a supported Pokemon title, Gen 1-3; detection by title and the PKHeX engine's save type detection; otherwise hidden, never an error).
2. **Editor > Save overview** (4.8): trainer name, ID, play time, badges; counts of party and box Pokemon; legality summary chip ("2 issues"); `Edit trainer`, `Party & Boxes`, `Items`, `Legality`.
3. `Party & Boxes`: top "Party" row of 6 slots, then a box selector and a 6x5 (or box-size) grid. Tap a slot to open **Pokemon detail**.
4. Pokemon detail: header (species name, level, OT, shiny/legal chips), tabs `Summary`, `Stats`, `Moves`, `Misc`. `Edit` toggles field editors (each field is tappable and opens an inline editor or a sheet).
5. Edit level 54 -> 55: stepper/field. The changed value gets a tonal "modified" dot, and the screen bar shows `1 unsaved change` with `Revert`. Stats recalculate live.
6. Back arrow with unsaved changes: dialog "Save as new backup?" `Save as new backup`, `Discard`, `Keep editing`.
7. `Save as new backup`: the editor writes a **new** library entry (note auto: "Edited: Level of Pidgeot 54 to 55", flagged `Edited`) and shows a result sheet: `Done`, `View in history`, `Restore to cart...` (disabled with reason if no cart).
8. If legality has errors, saving is still allowed (user's choice), with the warning first: "This save has 1 legality issue. It may not work in online play or in games that check it." with `Save anyway`.

---

## 4. Screens

Convention: `[ ]` button, `( )` selectable, `[=====   ]` progress, `{chip}` chip. Widths are about 360 dp.

### 4.1 Device / Home

States: (a) no reader, (b) permission needed, (c) connecting, (d) connected, no platform, (e) cart powered + header OK, (f) operation running (shows banner instead), (g) errors.

Wireframe: connected, GB cart identified.

```
+--------------------------------------+
| Device                          (gear)|  top app bar (large on scroll top)
+--------------------------------------+
| (usb) Reader connected   (5V) GB/GBC |  Device Status Bar
+--------------------------------------+
|  +--------------------------------+  |
|  |  _______                       |  |  Cartridge card
|  | |  ___  |  Pokemon Red         |  |  (generic cart silhouette, GB colour)
|  | | |   | |  {GB} {RTC?}         |  |
|  | | |___| |  MBC3 + RAM + BATT   |  |
|  | |_______|  ROM 1 MiB  Save 32 K|  |
|  |  Header checksum OK  Logo OK   |  |
|  |  In library: 3 backups (12 Oct)|  |
|  |                                |  |
|  |  [   Back up save           ]  |  |  filled
|  |  [ Restore a backup...      ]  |  |  outlined
|  |  [ Swap cartridge (power off)]  |  |  tonal, full width
|  |  (later) overflow: Dump ROM...  |  |  see 3.7; secondary, in the card's overflow menu
|  +--------------------------------+  |
|  +--------------------------------+  |
|  | Reader: GBFlash fw 15 PCB v1.4 |  |  compact reader card
|  | [Disconnect]            [Details]|  |
|  +--------------------------------+  |
+--------------------------------------+
|  Library        [Device]     Settings |
+--------------------------------------+
```

Wireframe: connected, no platform chosen (the unpowered, safest state).

```
+--------------------------------------+
| Device                                |
+--------------------------------------+
| (usb) Reader connected - cart power off|
+--------------------------------------+
|  Which cartridge is in the reader?    |
|  Choose the type yourself. The reader |
|  can't tell, and the wrong choice can |
|  damage a GBA cartridge.              |
|  +----------------+ +---------------+ |
|  |   ________     | |  _________    | |
|  |  |  ____  |    | | |  _____  |   | |
|  |  | |    | |    | | | |     | |   | |
|  |  | |____| |    | | | |_____| |   | |
|  |  |__    __|    | | |_________|   | |
|  |    |__|        | |   (notch)     | |
|  |  GB / GBC      | |   GBA         | |
|  |  Game Boy,     | |  Game Boy     | |
|  |  Color         | |  Advance      | |
|  |  Powers at 5 V | |  Powers 3.3 V | |
|  +----------------+ +---------------+ |
|  [ How do I tell them apart? ]         |
+--------------------------------------+
```

Content: Device Status Bar, cartridge card (or picker), reader card, optional info tip. Main actions: pick platform, back up, restore, swap, disconnect.

State table for the non-happy paths (see 5.4 for visuals):

| State | Card shown | Primary recovery action |
|---|---|---|
| No reader plugged in | Illustration + "Plug in your GBFlash reader" | `Check connection` (retry scan); link `Browse library` |
| USB permission needed | "Allow access to the reader" | `Allow access` |
| Permission denied | explanation | `Ask again` / `Open system settings` |
| Connecting | spinner row | none (`Cancel` after 10 s) |
| Connection failed | error card, reason | `Try again`, with "Unplug and plug in again" hint |
| Connected, no platform | picker (above) | pick platform |
| Cart powered, header read | cartridge card | back up / restore / swap |
| Operation in progress | operation banner; actions disabled | `Cancel` |
| Dirty contact / blank logo | error card on the cartridge card "No cartridge, or contacts need cleaning, or wrong type selected" | `Power off and try again`; tip "clean the gold contacts with isopropyl alcohol" |
| Timeout during operation | error result with partial info | `Retry` / `Power off cart` |
| Reader unplugged mid-operation | error result (see 4.3 / 4.7) "Reader disconnected" | `Reconnect` |

### 4.2 Platform picker

Appears inline in the Device screen when connected and no platform, and as a full-screen sheet from `Swap` and `Change type`. Two large tiles, **no default selection**, nothing focused, `Continue` does not exist (tapping a tile opens the confirm sheet). Tiles use platform colour, a distinct silhouette, a text name, and a voltage line with an icon.

Silhouettes (drawn with Compose `Path`, no Nintendo shapes copied from artwork, only generic proportions):
- **GB/GBC**: wide cartridge, about 65 x 58 mm ratio, two cut corners at the bottom (the real cart notch), label window. The `GBC` variant has a cut upper-right corner (the Color-only carts) as a small "Color" ghost. Both listed in one tile because the reader treats them identically.
- **GBA**: smaller, narrower, about 60 x 35 mm landscape, with a rounded notch at one end and a ribbed grip area.
- Size in the picker is to scale relative to each other (GBA clearly smaller), which is also the real-world tell.

Confirm sheet (modal bottom sheet, opens on tile tap; **different content per choice**).

```
+--------------------------------------+
|  ________                             |
| |  __  |   GB / GBC cartridge         |
| | |__| |   5 V                        |
| |______|                              |
|                                       |
|  (!) A GBA cartridge must NOT be in   |
|      this mode. 5 V can damage it.    |
|                                       |
|  Is your cartridge wide, with a flat  |
|  label and two cut corners (GB/GBC)?  |
|  GBA cartridges are small and narrow. |
|                                       |
|  [  Power on at 5 V (GB / GBC)  ]     |
|  [  Back, I picked the wrong one ]    |
+--------------------------------------+
```

Rules:
- Asymmetry: choosing **GB/GBC** is the dangerous mistake (5 V on GBA), so that sheet carries the warning, the "how to tell" line with both silhouettes side by side, and the button label restates the voltage. Choosing **GBA** gets a lighter sheet (3.3 V is safe for GB/GBC carts in practice; the wrong choice only gives a failed read and a blank logo), but it still requires a confirm tap, to keep the muscle memory consistent.
- Primary button text includes the platform name and voltage, so it cannot be confirmed without reading the choice.
- The button is disabled for 1 second after the sheet opens (progress ring on the button) the first 3 times, then the delay stops. This prevents reflex tapping while the user is still learning. (Optional; decision for the product owner, see Open questions.)
- Platform chosen is stored **only in memory** and cleared at power-off, idle timeout, disconnect and swap.
- Wrong platform is detected after the fact (blank logo, invalid checksum) and shows "This does not look like a GB/GBC cartridge. Power off now, then check the type." with `Power off cart` as the primary action, **before** anything else.
- `How do I tell them apart?` opens a comparison sheet: both silhouettes at scale, plus text ("GBA cartridges are about 3.5 x 6 cm; Game Boy and Game Boy Color cartridges are about 6.5 x 5.8 cm with a notched corner. When in doubt, choose GBA first: it is the safe voltage.").
  Wait: this advice only holds if the cart is really GB/GBC; if it is truly a GB cart in GBA mode, the logo reads blank and the app asks to re-pick. This is the safe direction, so the sheet recommends it.
- TalkBack: each tile is a `Button` with content description "Game Boy and Game Boy Color cartridge, 5 volts. Warning: do not use for Game Boy Advance cartridges." The confirm sheet gets focus on open and announces the warning first.
- Colour is not the only signal: icon, shape, text, voltage text.

### 4.3 Backup progress and result

Progress (full screen, bottom bar hidden; same layout will later serve GBA):

```
+--------------------------------------+
| (x)  Backing up                       |
+--------------------------------------+
|                                       |
|            ,-----------.              |
|           /    63 %     \             |  circular determinate progress
|           \  20 / 32 KiB/             |
|            '-----------'              |
|                                       |
|   Pokemon Red  {GB}                   |
|   Reading and verifying: every block  |
|   is read twice.                      |
|                                       |
|   Keep the reader plugged in.         |
|   Nothing is written to the cartridge.|
|                                       |
|   [ Cancel ]                           |
+--------------------------------------+
```

- States: Reading header (indeterminate), Reading save (determinate), Reading clock (indeterminate, short), Verifying (part of the same bar; label switches), Storing. Cancel is safe and immediate.
- Errors: the progress screen swaps to the result screen in its error form (below).

Result, success:

```
+--------------------------------------+
|  Backup complete                      |
|      (check_circle)                   |
|  Pokemon Red  {GB}                    |
|  12 Oct 2026, 14:02                   |
|  32 KiB   CRC32 A1B2C3D4              |
|  {Verified} {RTC}                     |
|  [ Add note ]                         |
|                                       |
|  [ View in library ]  (filled)        |
|  [ Power off cart / Swap ] (tonal)    |
|  [ Done ]                (text)       |
+--------------------------------------+
```

Result variants:
- **Suspect**: tertiary/warning container, `warning` icon, "This save looks empty (all 0xFF)." with explanation and `Keep it` / `Delete` buttons (default keep).
- **Verification failed** (two reads disagree): error container, `error` icon, "The two reads did not match. The cartridge may have dirty contacts or a weak battery. Nothing was stored." `Clean contacts tips`, `Try again`, `Power off cart`.
- **Timeout**: "The cartridge did not answer in time." `Try again` / `Power off cart`.
- **Reader unplugged**: "The reader was unplugged during the backup. Nothing was stored." `Reconnect`.
- **Cancelled**: neutral, "Backup cancelled. Nothing was stored." `Back`.
- **RTC read problem**: success plus warning chip, details in the result card.

### 4.4 Library (all games)

```
+--------------------------------------+
| Library                  (search)     |
+--------------------------------------+
| (usb) No reader connected  [Connect]  |  Device Status Bar (quiet variant)
+--------------------------------------+
| [All] [GB] [GBC] [GBA] [Pokemon]      |  filter chips (scrollable)
+--------------------------------------+
| +-----+ Pokemon Red             3     |  list item: platform badge tile,
| | GB  | Latest 12 Oct 14:02  {RTC}    |  title, count, latest, chips
| +-----+                               |
| +-----+ Pokemon Crystal   {!}   7     |
| | GBC | Latest 10 Oct   {Suspect}{RTC}|
| +-----+                               |
| +-----+ Pokemon Emerald         1     |
| | GBA | Latest 2 Oct                  |
| +-----+                               |
|                                       |
|                     ( + Back up cart ) |  extended FAB, only when a cart is ready
+--------------------------------------+
|  [Library]      Device     Settings   |
+--------------------------------------+
```

- Row leading element: a 48 dp **platform badge**, not box art: a rounded-square tile in the platform container colour with the platform abbreviation ("GB", "GBC", "GBA") as text, plus a silhouette glyph. The title is the identity.
- Same title, different checksum (revision/region): shown as separate rows; disambiguation line "Rev 1, checksum 0x9E" under the title when a duplicate title exists.
- States:
  - **Empty** (no backups): illustration + "No saves yet. Back up a cartridge to start your library." `Go to Device`. Secondary: "Import a .sav file" (disabled, "coming later").
  - **Loading**: skeleton rows (shimmer-free, 3 static placeholders; the library is local and fast).
  - **No results** for search/filter: "No games match" with `Clear filters`.
  - **Error** (corrupt index): "Could not read the library index" with `Retry` and `Open Settings` (debug log).
- Actions: tap row -> Game detail; long-press -> multi-select (export many; delete many later); search; filter; sort.
- Optional grouping: toggle in Settings for "Group by platform" (off by default).

### 4.4b ROM dump screens (later phase, structure reserved now)

Placement rationale: `Dump ROM...` lives in the Cartridge card as a **secondary** action (overflow of the card, or a tonal-outlined button once the feature ships), below Back up / Restore. Saves are the app's main job and the common action; a dump takes minutes and is rarely repeated.

Library model: a dump is an artefact of the **same game entry** (same identity: platform + title + header checksum). Game detail gets two sections, `Save backups` (history, as today) and `ROM dump` (zero or one item, shown as a single row, not a history). A second dump of the same game replaces nothing silently: if one exists and the new CRC matches, the app says "Already dumped, identical" and discards the new one; if it differs, it asks `Keep both` / `Replace`. Dumps are large (32 KiB to 32 MiB), so the row shows size prominently and Settings gets a storage line ("Library: 118 MiB, of which ROM dumps 96 MiB") with `Delete dump` available.

```
Game detail, below the history:
|  ROM dump                             |
|  +--------------------------------+  |
|  | (memory) Pokemon Red.gb  1 MiB |  |
|  | 12 Oct 2026   {Verified: header|  |
|  |  checksum}  CRC32 1A2B3C4D     |  |
|  | [ Export ROM ]            (...) |  |
|  +--------------------------------+  |
|  (none yet:) "No ROM dump. Dump it from the cartridge." [ Go to Device ]
```

Dump progress (a long read, minutes): same full-screen layout as backup progress (4.3) plus speed and time:

```
|  Dumping ROM                    [Cancel]
|     ,-----------.   63 %               |
|    /  20 / 32 MiB \                    |
|     '-----------'                       |
|  Speed 118 KiB/s    About 1 min 40 s left|
|  Pokemon Red {GB}  Keep the reader plugged in.|
|  Nothing is written to the cartridge.   |
```

- ETA: from a moving average over the last 10 seconds, shown only after 5 s, rounded ("About 2 min"); never a countdown to the second. Speed in KiB/s or MiB/s.
- Cancel: always allowed and safe (read-only); discards the partial data after a confirm dialog only if more than 30% is done ("Discard the partial dump? It cannot be resumed.").
- Foreground service, screen-on and the operation banner apply as for any operation; a notification shows progress and `Cancel`.
- The cart stays powered during the dump, so the status bar keeps showing the voltage.

Verification result (dump result screen): a ladder, best evidence first, each line with icon and text:
1. `Verified: matches known-good database` (`verified`, success) when the SHA-1/CRC matches an entry of an optional offline hash database the user can import (No-Intro style DAT; not bundled, see IP below).
2. `Header checksum matches` (`check_circle`, success-tonal, labelled "Self-consistent") when the computed global checksum (GB/GBC) equals the stored one. For GBA there is no global checksum, only the header complement check, so the line reads "Header checksum matches (partial check)".
3. `Checksum does not match` (`warning`, tertiary, not error: many valid carts and hacks have wrong global checksums): "The dump is internally inconsistent. Contacts may be dirty. Dump again to compare." with `Dump again` (compares the two dumps, which is the real integrity test) and `Keep anyway`.
4. Always shown: size, CRC32, SHA-1 (copyable).
Actions: `Export ROM` (primary), `View in library`, `Power off cart`, `Done`.

Export flow: `Export ROM` opens the system `CreateDocument` picker with the extension chosen by platform (`.gb` for GB, `.gbc` for GBC, `.gba` for GBA), name `<Title>.<ext>`; success snackbar with `Share`. Export is offline-capable, like save export.

IP note: ROM images are copyrighted. The app's copy states "Only dump cartridges you own" once in the dump confirm sheet (a single sentence, no legal text), ships no ROMs, no hash database and no box art, and the hash database is user-imported. The fallback visuals are the same generic silhouettes.

### 4.5 Game detail (backup history)

```
+--------------------------------------+
| <   Pokemon Red                 (...) |
+--------------------------------------+
| (usb) Reader connected  {In reader}   |
+--------------------------------------+
|  +--------------------------------+  |
|  | [GB]  Pokemon Red              |  |  identity header
|  |  Checksum 0x9E   Save 32 KiB   |  |
|  |  MBC3  {RTC?}                  |  |
|  |  [ Back up this cart ] (filled)|  |  visible only when the cart in the
|  +--------------------------------+  |  reader is this game
|  History (3)                          |
|  +--------------------------------+  |
|  | 12 Oct 2026, 14:02    {Edited} |  |
|  | 32 KiB  CRC A1B2C3D4           |  |
|  | "Before Elite Four"            |  |
|  +--------------------------------+  |
|  | 10 Oct 2026, 09:40    {Suspect}|  |
|  | 32 KiB  CRC 00000000           |  |
|  +--------------------------------+  |
|  | 2 Oct 2026, 18:21  {Pre-restore}||
|  +--------------------------------+  |
+--------------------------------------+
```

- App bar overflow `(...)`: Rename display name (optional, future), Delete game (all backups; strong confirm), Export all.
- Row: tap opens Backup detail; the whole row is the touch target (min 64 dp tall); swipe is not used (not discoverable, and risky near delete).
- States: **Empty history** is only possible transiently (game vanishes when the last backup is deleted); if shown: "No backups left" plus `Back up this cart`. **Loading**: placeholders. **Error**: file missing for an entry: row shows `error` icon "File missing" and a `Remove entry` action.
- Row tags: `Edited` (made in the editor), `Pre-restore` (automatic safety copy), `Suspect`, `RTC`, `Imported` (later).

### 4.6 Backup detail

```
+--------------------------------------+
| <   Backup, 12 Oct 2026 14:02    (...)|
+--------------------------------------+
|  Pokemon Red  {GB}                    |
|  {Verified} {RTC} {Edited}            |
|  +--------------------------------+  |
|  | Size        32 KiB (32768 B)   |  |
|  | CRC32       A1B2C3D4   (copy)  |  |
|  | Clock       Included (48 B)    |  |
|  | Created     12 Oct 2026 14:02  |  |
|  | Source      Cartridge read     |  |
|  +--------------------------------+  |
|  Note                                 |
|  +--------------------------------+  |
|  | Before Elite Four        (edit)|  |
|  +--------------------------------+  |
|  [ Open in editor ]      (filled)     |  only when supported
|  [ Export .sav ]         (tonal)      |
|  [ Restore to cart... ]  (outlined)   |  disabled with reason offline
+--------------------------------------+
   overflow: Delete backup (error colour), Compare (later)
```

- If suspect: a warning card on top with the reason and what to do ("The save is all 0xFF, which usually means an empty or unplayed game, or a dead battery").
- Restore placement: **outlined** and the last button, not filled; it is not the lead action. `Delete` is only in the overflow.
- Disabled-state explanation is text under the button, not just greying ("Connect the reader and read a cartridge to restore").

### 4.7 Restore: review, confirmation, progress, result

Review (R1):

```
+--------------------------------------+
| (x)  Restore to cartridge             |
|  Review  >  Safety copy > Write > Done|
+--------------------------------------+
|  ON THE CARTRIDGE NOW                 |
|  +--------------------------------+  |
|  | [GB] Pokemon Red               |  |
|  |  Checksum 0x9E  Save 32 KiB    |  |
|  |  Save will be replaced         |  |
|  +--------------------------------+  |
|            (arrow_downward)           |
|  WILL BE WRITTEN                      |
|  +--------------------------------+  |
|  | [GB] Pokemon Red               |  |
|  |  12 Oct 2026 14:02  {Edited}   |  |
|  |  CRC A1B2C3D4   32 KiB         |  |
|  |  "Before Elite Four"           |  |
|  +--------------------------------+  |
|  (check_circle) Same game             |
|    platform, title, checksum match    |
|  Clock: (x) also restore the clock    |
|                                       |
|  We back up the cartridge's current   |
|  save first, so you can undo this.    |
|                                       |
|  [ Cancel ]            [ Continue ]   |
+--------------------------------------+
```

Mismatch variant (replaces the "Same game" row):

```
|  +--------------------------------+  |
|  | (warning) These are different  |  |  error container, 2 dp error border
|  |  games                         |  |
|  |  Title:     POKEMON RED vs     |  |
|  |             POKEMON BLUE       |  |
|  |  Checksum:  0x9E vs 0x0B       |  |
|  |  Writing this may corrupt the  |  |
|  |  game or make it unplayable.   |  |
|  |  Type the cartridge title to   |  |
|  |  continue:                     |  |
|  |  [ POKEMON RED_____________ ]  |  |
|  +--------------------------------+  |
```

Rules: `Continue` disabled until the typed text matches. Platform or size mismatch: no input shown, `Continue` replaced by "Choose a different backup" and the reason.

R2b hold-to-confirm:

```
+--------------------------------------+
|  Safety copy saved (check_circle)     |
|  "Automatic copy before restore"      |
|                                       |
|  Ready to write                       |
|  Pokemon Red backup of 12 Oct 14:02   |
|  to the cartridge                     |
|                                       |
|        ( HOLD to restore )            |  hold ring, 1.5 s
|  [ Cancel ]                           |
+--------------------------------------+
```

R3 progress (writing):

```
+--------------------------------------+
|  Restoring  Review > Safety > [Write] |
+--------------------------------------+
|  (lock icon)                          |
|            ,-----------.              |
|           /    41 %     \             |
|           \ 13 / 32 KiB /             |
|            '-----------'              |
|  (warning) Do not unplug the reader   |
|  or remove the cartridge.             |
|  Keep this screen open.               |
|                                       |
|  [ Stop... ]   (text button)          |
+--------------------------------------+
```

R5 result (success):

```
+--------------------------------------+
|  Restore complete (check_circle)      |
|  Pokemon Red, backup of 12 Oct 14:02  |
|  Verified: cartridge matches the      |
|  backup (CRC32 A1B2C3D4)              |
|  {Verified}                           |
|  Safety copy kept in your library.    |
|  [ Done ]                (filled)     |
|  [ Power off cart ]      (tonal)      |
|  [ Undo restore ]        (text)       |
+--------------------------------------+
```

State and error variants are listed in 3.4 (verify failed, interrupted, unplugged, safety copy failed). Never show a success-coloured result unless read-back matched.

### 4.8 Pokemon editor screens

The editor is a full-screen flow with its own top bar showing `<`, the game name, the unsaved-changes indicator, and an overflow (`Revert all`, `Legality report`, `Save as new backup`). It works offline. The Device Status Bar is **not** shown inside the editor (no hardware involvement); the editor top bar contains a small `usb` indicator only if a matching cart is connected, to hint that `Restore` is available.

**Save overview**

```
+--------------------------------------+
| <  Pokemon Red - backup 12 Oct   (...)|
|    {GB} Editing: 1 change             |
+--------------------------------------+
|  Trainer                              |
|  +--------------------------------+  |
|  | RED        ID 12345            |  |
|  | Play time 24:13  Money 12,400  |  |
|  | Badges 5/8    (edit) >         |  |
|  +--------------------------------+  |
|  [ Party & Boxes ]  6 + 42 Pokemon  > |
|  [ Items ]          47 items        > |
|  [ Legality ]  (check) 2 issues     > |
|                                       |
|  [ Save as new backup ]               |  bottom bar, enabled when dirty
+--------------------------------------+
```

**Party and boxes grid**

```
+--------------------------------------+
| <  Party & Boxes                      |
+--------------------------------------+
|  Party                                |
|  [1][2][3][4][5][6]   horizontally    |
|   Pidgeot Lv55 ...    scrollable      |
|  Box  [<]  Box 3 / 12  [>]  (grid)    |
|  +----+----+----+----+----+----+      |
|  |    |    |    |    |    |    |      |  slot: species name abbreviated,
|  | Pi | Ra | .. | .. | Ka | .. |      |  level, status dot, shiny star
|  | 55 | 12 |    |    | 30 |    |      |
|  +----+----+----+----+----+----+      |
|   ... rows                             |
|  Long-press: Move, Swap, Delete        |
+--------------------------------------+
```

- Slot (about 56 dp square, min 48): **no sprites**: a rounded tile with the 3-letter species code over the level, a coloured **type-neutral** background (tonal surface variant), a legality dot (icon `warning` or `error` or none), a shiny star icon (`star`) with label. Optional sprite hook, see 6.5.
- Species names come from PKHeX's data (names are facts, not artwork; the engine's own licence, GPLv3, applies, see Open questions).
- Slots in party can be re-ordered by drag handle; boxes use move mode (tap source, tap destination) because drag across a scrollable grid is fragile on phones.

**Pokemon detail**

```
+--------------------------------------+
| <  PIDGEOT                  [Edit]    |
+--------------------------------------+
|  Lv 55  Normal/Flying  {Legal}        |
|  OT RED  ID 12345  Met: Route 22      |
|  HP  [=========   ] 148 / 148         |
|  [Summary] [Stats] [Moves] [Misc]     |
|  ---------------------------------   |
|  (tab content)                        |
+--------------------------------------+
```

**Stats tab** (see 6.1 for the density approach).

```
|  Stat   Base   DV/IV   EV    Total    |
|  HP      83     12     ####    148    |
|  Atk     80      9               ..   |
|  Def     75     10                    |
|  Spd     91      8                    |
|  Spc     70     11   (Gen 1: Special) |
|  Hidden Power: Fighting 38    (Gen 2) |
|  [ Edit values ]                      |
```

**Moves tab**: 4 rows: name, type chip (text+icon), PP / PP Ups. Tap a row -> searchable move picker (bottom sheet) that flags moves not legally learnable as `warning` suggestions, not blocks.

**Edit (field editing)**: full-screen sheet per group with steppers and number fields; `Summary` fields (species, level, nickname, OT), `Stats` (DV/IV, EV, nature for Gen 3), `Moves`, `Misc` (held item, ball, met location, friendship, ability, shiny). Edits are applied to the in-memory save instantly and marked.

**Trainer screen**: name, ID, secret ID (Gen 3), gender (Gen 3), money, play time, badges (8 toggle chips with text "Boulder, Cascade..." names are facts), rival name (Gen 1/2). Validation inline (name length, charset), error text under fields.

**Items screen**: tabs by pocket (Gen 1: Items, PC; Gen 2: Items, Key, Balls, TM/HM, PC; Gen 3: Items, Key, Balls, TMs, Berries, PC). List rows: item name, quantity stepper. `Add item` -> searchable picker. No item icons (see 6.5).

States for editor screens:

| State | Treatment |
|---|---|
| Loading (parsing save) | Full-screen centred progress with "Reading save..." (under 1 s in practice) |
| Unsupported / corrupt save | "This save could not be read as <game>" with `Back`, and `View raw size/CRC` |
| Empty slot | Neutral tile, `Add Pokemon` (later; first phase read-only for empty slots) |
| Read-only (first editor milestone) | All edit buttons hidden; same screens |
| Error on field | Inline text and error icon, `Save as new backup` disabled until resolved |

### 4.9 Settings

```
+--------------------------------------+
| Settings                              |
+--------------------------------------+
| Appearance                            |
|   Theme: ( ) System (x) Dark ( ) Light|
|   Use dynamic colour [switch]         |
| Backups                               |
|   Ask before exporting clock data     |
|   Include RTC by default  [switch]    |
|   Export file name format >           |
| Safety                                |
|   Power off cart after idle: 3 min >  |
|   Show platform tips again            |
| Reader                                |
|   Firmware and device info          > |
| Data                                  |
|   Library location (app-private)      |
|   Export library as ZIP (later)       |
| Advanced                              |
|   Debug log (TX/RX)                 > |
|   Raw cartridge header              > |
| About                                 |
|   Open source licences              > |
|   About GBFlash                     > |
+--------------------------------------+
```

- **Debug log**: monospace, selectable, `Copy`, `Share`, `Clear`, with a switch "Record TX/RX" (off by default, to cut memory use). This is the current collapsible, promoted.
- **Raw header**: hex dump with the parsed fields above it (currently the large text header card).
- **Firmware info**: version, PCB, name, build date, capabilities table (power control, presence switch, mode switch, bootloader reset) as rows with check icons; plus a plain sentence "This reader cannot detect a cartridge" when presence switch is false.
- **Licences**: Open source libraries list, including PKHeX licence text (GPLv3 consequences, Open question 8), and a statement "Pokemon and Game Boy are trademarks of their owners. GBFlash is not affiliated or endorsed."

---

## 5. Components and visual language

### 5.1 Colour roles and platform distinction

Material 3 with dynamic colour on Android 12+ (fallback: a static baseline scheme from seed `#5B5FC7`, a muted indigo). The app's semantic colours live in a `GbfExtendedColors` holder derived from the scheme so they follow the wallpaper but stay distinguishable.

| Role | Use | Source |
|---|---|---|
| `primary` / `onPrimary` | main action per screen (one filled button) | dynamic |
| `secondaryContainer` | selected nav item, tonal buttons, neutral chips | dynamic |
| `surfaceContainer*` | cards (tonal elevation, no shadows in dark) | dynamic |
| `tertiaryContainer` | warnings (suspect, edited, advisory) | dynamic tertiary, ensures contrast |
| `errorContainer` / `error` | failures, mismatches, delete, voltage danger | dynamic |
| Success | "verified", "match", "complete" | `harmonize(Color(0xFF2E7D32), primary)` container and on-colour, 4.5:1 minimum, always with `check_circle` icon and the word |

**Platform colours.** Dynamic colour means the app cannot rely on fixed brand hues, and three hues that all drift toward the wallpaper would collapse. Rule: platform identity is carried by **shape and text first, tint second**.
- Each platform has a fixed **glyph shape** and a fixed **label**: GB = square-ish cartridge with two cut corners, GBC = same with a "C" ring mark (outline) and a corner cut, GBA = small landscape cartridge. They are drawn in `onSurfaceVariant`.
- Tint is a **harmonised** fixed hue per platform, blended 30-40% toward `primary` using `MaterialColors.harmonize` (Compose: `Color.harmonizeWith`) so it sits with the wallpaper but remains separable: GB = olive/green (`#6B8E23` family), GBC = purple/teal (`#7E57C2` family), GBA = indigo/blue (`#3F51B5` family). The tint is applied only to the 48 dp platform badge background (as a container colour with text on top, contrast checked) and a 4 dp left stripe on game rows. If two tints end up within a perceptual threshold (delta E below 15) after harmonisation, fall back to `secondaryContainer`, `tertiaryContainer`, `surfaceVariant` for GB/GBC/GBA (still labelled), so the design never depends on hue.
- **Voltage chips** are *not* platform-tinted: 5 V chip uses `tertiaryContainer` + `bolt` icon + "5 V"; 3.3 V uses `secondaryContainer` + `bolt` + "3.3 V". The warning copy about GBA uses `errorContainer` + `warning` icon + text.

### 5.2 Typography

Material 3 type scale, Roboto Flex / system default, with these specific uses:

| Use | Style |
|---|---|
| Screen titles | `titleLarge` (top app bar, small variant), `headlineMedium` on result screens |
| Game titles in lists/cards | `titleMedium` (1 line, ellipsis, with full title on detail) |
| Body copy, safety explanations | `bodyMedium` (never smaller than 14 sp for warnings) |
| Chips, captions | `labelMedium` / `labelSmall` (12 sp minimum for chips; no text under 11 sp anywhere except hex) |
| Data (CRC32, checksums, sizes, hex, stats numbers) | **Monospace** (`FontFamily.Monospace` or Roboto Mono), `bodyMedium`, tabular figures via `fontFeatureSettings = "tnum"` |
| Progress percentage | `displayMedium` monospace digits |

Respect system font scale up to 200%: wireframes reflow; chips wrap; stat table switches to stacked layout above 1.3x.

### 5.3 Card patterns

| Pattern | Use | Style |
|---|---|---|
| **Status card** | connection, permission, errors | `Card` with icon, title, one sentence, one action; container colour by severity |
| **Cartridge card** | identified cart | `ElevatedCard`/`surfaceContainerHigh`; silhouette, title, spec rows, chips, actions |
| **List card (game / backup)** | library | `ListItem`-based, full-row clickable, 64-80 dp, platform badge leading |
| **Key-value card** | backup detail, header | label (`onSurfaceVariant`) left, monospace value right; copy action on long values |
| **Comparison card pair** | restore review | two cards with a connector arrow; "now" is neutral, "will be written" uses `secondaryContainer` |
| **Warning card** | suspect, mismatch, voltage | container colour + leading icon + bold first sentence + 2 dp outline in the content colour |

Cards are 16 dp radius (M3 medium), 16 dp content padding, 12 dp spacing between, 16 dp screen margins. One filled button per card at most.

### 5.4 Status chips and the Device Status Bar

Chips are `AssistChip`-style, read-only, 32 dp tall visually with a 48 dp touch target only when tappable (tapping opens a short explanation sheet).

| Chip | Icon | Colour | Text | Meaning |
|---|---|---|---|---|
| Verified | `verified` | success | "Verified" | read twice, identical (or restore read-back matched) |
| RTC | `schedule` | secondaryContainer | "RTC" or "Clock" | backup includes clock data |
| RTC not read | `schedule` + `warning` | tertiaryContainer | "Clock not read" | header declares RTC, could not be read |
| Suspect | `warning` | tertiaryContainer | "Looks empty" | all 00/FF or no data |
| Mismatch | `error` | errorContainer | "Different game" | identity mismatch (restore review, cart vs backup) |
| Edited | `edit` | secondaryContainer | "Edited" | produced by the editor |
| Pre-restore | `history` | surfaceVariant | "Pre-restore copy" | automatic safety copy |
| In reader | `usb` | primaryContainer | "In reader" | this game is the inserted cartridge |
| Voltage | `bolt` | see 5.1 | "5 V" / "3.3 V" | current cart voltage |
| Legal / issues | `check_circle` / `report` | success / error | "Legal" / "2 issues" | editor legality |

**Device Status Bar**: single row, leading icon, label, trailing chip or text action. States:

| State | Icon | Text | Container | Trailing |
|---|---|---|---|---|
| No reader | `usb_off` | No reader connected | surfaceVariant (quiet) | `Connect` |
| Permission needed | `key` | USB permission needed | tertiaryContainer | `Allow` |
| Connecting | spinner | Connecting... | surfaceVariant | none |
| Connected, no platform | `usb` | Reader connected, cart power off | secondaryContainer | `Choose type` |
| Cart powered | `power` | Cart powered {5 V} GB/GBC | **tertiaryContainer** (attention colour, since the cart is live) | `Power off` |
| Operation | progress | Backing up Pokemon Red 63% | primaryContainer | `Cancel` |
| Error | `error` | Reader problem: <short> | errorContainer | `Fix` (goes to Device with the recovery card) |

"Cart powered" is deliberately the attention colour and persistent, because a powered cart is the state where a swap would be dangerous.

### 5.5 Progress patterns

| Case | Pattern |
|---|---|
| Short indeterminate (connect, header read) | Small circular indeterminate indicator inline in the status row |
| Backup / restore bytes | Large circular determinate indicator + percent + "x / y KiB" + phase label; the phase label changes (Reading, Verifying, Storing / Safety copy, Writing, Verifying) |
| Multi-step flows (restore) | Stepper (4 labelled steps) at the top, current step bold; steps not colour-only (check icon for done) |
| Background | Operation banner in the Device Status Bar; foreground service notification with the same text and progress, `Cancel` action only for backups |
| Reduced motion | No indicator animation beyond determinate fill; numbers still update |

Progress announcements for TalkBack: `liveRegion = Polite`, at 10% steps only.

### 5.6 Confirmation patterns (increasing friction)

| Level | Pattern | Used for |
|---|---|---|
| 0 | Immediate, with snackbar `Undo` | delete a backup, edit a note |
| 1 | `AlertDialog` naming the thing, destructive button in error colour, default focus on safe choice | delete game, discard editor changes, stop backup |
| 2 | Review screen (comparison) then `Continue` | restore (matching game) |
| 3 | Review screen + typed confirmation (cartridge title) | restore of a different game |
| 4 | Hold-to-confirm button (1.5 s) + dialog alternative for accessibility | restore write (always) |
| Block | Explanation, no override | platform or size mismatch |
| Modal sheet with warning + restated voltage | platform choice | 4.2 |

### 5.7 Iconography (Material Symbols, Rounded, 24 dp, weight 400)

| Concept | Symbol |
|---|---|
| Library / Device / Settings | `inventory_2` / `usb` / `settings` |
| Reader unplugged | `usb_off` |
| USB permission | `key` |
| Cart powered | `power` |
| Cart power off | `power_off` |
| Voltage | `bolt` |
| Backup | `download` (cart to phone) |
| Restore | `upload` (phone to cart) |
| Export | `ios_share` / `save_alt` |
| Delete | `delete` |
| Note | `sticky_note_2` |
| RTC / clock | `schedule` |
| Verified | `verified` |
| Warning / error | `warning` / `error` |
| Success | `check_circle` |
| Different game | `compare_arrows` |
| History | `history` |
| Edit | `edit` |
| Editor entry | `edit_square` |
| Party / boxes | `groups` / `grid_view` |
| Trainer | `person` |
| Items | `backpack` |
| Legality | `fact_check` |
| Swap cart | `swap_horiz` |
| Platform glyphs | custom vector drawables (generic silhouettes), not Material |

### 5.8 Motion (brief)

- Standard M3 motion: shared-axis (X) between list and detail; container transform from game row to Game detail; fade-through between bottom tabs.
- Hold-to-confirm: a ring fill over 1.5 s with a haptic tick at start and a stronger haptic on completion; release resets with a quick reverse.
- Status bar state changes crossfade (150 ms); the "Cart powered" bar does not pulse (no looping animation).
- Progress: determinate only, animate value changes over 200 ms.
- Respect "Remove animations": all of the above become instant fades.

### 5.9 Accessibility notes

- Touch targets 48 x 48 dp minimum (chips only if interactive, rows 64 dp, grid slots 56 dp).
- Contrast: text 4.5:1, icons and chip borders 3:1; dynamic colour palettes can fail on tinted containers, so verify with `onXContainer` pairs only; custom tints (platform, success) are checked at build time by a unit test of the contrast ratio.
- TalkBack: every icon button has a label; status bar is a single focusable element with a composed description ("Cart powered, 5 volts, Game Boy and Game Boy Color. Tap to open Device"); changes in hardware state are announced with `liveRegion = Assertive` for errors and voltage changes, `Polite` otherwise.
- Silhouettes have text equivalents; no information in colour alone (checked in 5.1 and 5.4).
- Hold-to-confirm has the alternative `Restore now` custom accessibility action that opens a dialog.
- Dark theme is the primary tested theme (the owner uses it); light is verified for contrast.

---

## 6. Pokemon editor specifics

### 6.1 Making dense data usable on a phone

- **Progressive disclosure**: Pokemon detail has 4 tabs (Summary, Stats, Moves, Misc); the default shows what players care about (species, level, moves summary, held item); numbers are one tap away. Edit mode is a separate toggle, so browsing stays safe and uncluttered.
- **Stats as a table plus a bar**: one row per stat with bar (visual, 0-255 scale), the total (monospace), and the DV/IV and EV values. Tapping a row opens a bottom sheet editor for that stat, not a grid of text fields.
- **Per generation**: the stats tab is generation-aware, driven by the engine:
  - Gen 1: DV (0-15) and Stat Exp (0-65535); the Special stat is one row; HP DV is derived and shown read-only.
  - Gen 2: DV (0-15) and Stat Exp; Special Attack/Defense separated; the Hidden Power type and power are shown as derived; shininess is derived from DV and shown read-only with a `Make shiny` helper that sets legal DVs.
  - Gen 3: IV (0-31) and EV (0-255, 510 total cap shown as a "x / 510" meter, over-limit in warning colour), Nature as a picker (shows the +/- stats with arrows and text), Ability (slot 1/2).
  The column headers change: "DV" vs "IV", "Stat Exp" vs "EV"; a small info icon explains the term in one sentence.
- **Sheet editors**: number fields use a slider plus a stepper plus direct keyboard entry; the sheet shows the resulting stat live ("Atk 112 -> 118").
- **Pickers over free text**: species, moves, items, locations, balls are searchable lists (bottom sheet, search field focused, recent first). Free text only for nickname and OT.
- **Quick actions** (overflow in Pokemon detail): `Max EVs (legal)`, `Perfect IVs`, `Heal`, `Make shiny` (where legal). Each is a labelled action that applies to the working copy and is revertable.
- **Compare edits**: modified fields show the previous value as a caption ("was 54") and a dot; `Revert field` on long-press.
- **Landscape/tablet**: Stats and Moves side by side.

### 6.2 Legality results

Legality (PKHeX's checker) is shown in three places at three levels of detail:

1. **Save overview**: one summary row, "Legality: 2 issues in 47 Pokemon" with icon `fact_check` and a chip ("Legal" or "N issues").
2. **Grid slots**: a small `warning` (tertiary, for "fishy") or `error` (for "illegal") glyph in the corner; the legend is shown once at the top of the grid. The slot's content description includes the status ("Pidgeot level 55, illegal").
3. **Pokemon detail header**: chip `Legal` / `Fishy` / `Illegal`, tappable to open **Legality report**: a list of check results grouped by severity (Error, Fishy, Info), each with a plain-language message from the engine, the field it concerns (with a `Go to field` link), and for common ones, a `Fix` suggestion where the engine offers one.
- **Philosophy**: advisory, not blocking. The user owns their save. The tool shows facts and lets the user decide, with one extra confirm on save (3.6 step 8).
- Legality is computed off the main thread, cached per Pokemon, and re-run after each edit with a debounce (300 ms), showing a small spinner chip while pending.
- Wording: avoid "cheated" or "hacked". Use "Does not match a legal game history".
- Gen 1-3 legality is more limited in the engine than later gens; the UI says "Basic checks" in the report footer if the engine's coverage is partial.

### 6.3 Unsaved edits and the history rule

- Opening a save in the editor loads a **working copy in memory** from the backup; the original library entry is never modified.
- Edits apply to the working copy. The editor bar shows `N unsaved changes` with `Revert all`.
- `Save as new backup` creates a **new** library entry: same game identity, new timestamp, size, CRC32, RTC data carried over unchanged from the source backup, tag `Edited`, optional note with an auto-generated summary of changes (editable before saving), and a pointer to its **parent backup** ("Based on backup of 12 Oct 14:02"). The history in Game detail shows it at the top; the parent is untouched.
- Each save is a snapshot; continuing to edit after saving starts a new set of changes against the saved state, and a second save makes another new entry. To avoid spamming history, the Save dialog offers "Replace my previous edit from this session" only for entries created in the current editor session and not yet exported or restored (default off, owner decision, see Open questions).
- **Autosave of drafts**: the working copy is persisted as a draft in app storage (not in the library) every few seconds and on pause, so process death does not lose work. The draft is shown as a banner in Backup detail ("You have unsaved edits. Resume / Discard"), and never counts as a backup. If a draft is discarded, there's a confirm.
- Leaving with changes: dialog (3.6 step 6).
- Library never gets partial or invalid data: `Save as new backup` runs the engine's save-writer (including checksum fix-ups) and checks the result re-parses and has the same size as the original before storing.

### 6.4 Editor and restore

- The only way to get an edited save on the cartridge is through **Restore**, which means the edit flow ends in the same safe pipeline as any restore. There is no "write directly to cartridge" in the editor.
- After `Save as new backup`, the confirmation sheet offers `Restore to cart...`, enabled when a matching cartridge is connected, otherwise disabled with the reason and a `Connect` link; choosing it opens the Restore flow (3.4) with this backup preselected. The restore review shows the `Edited` chip and the parent relation ("Edited from 12 Oct backup"), so the user sees that this is a modified save.
- The safety copy that restore makes is of what is on the cartridge at that time (usually the older, unedited save), so "Undo" works.
- If the editor changed identity-affecting data (it doesn't: the identity is platform + title + header checksum from the ROM header, not from the save), no change; this is documented so users aren't scared by a "Different game" warning on edited saves. Edited entries always keep the original game identity.

### 6.5 Asset and IP policy (sprites, item icons, box art, logos)

| Where assets would help | IP concern | Asset-free fallback (the design) |
|---|---|---|
| Pokemon sprites in party/box grid and detail | Sprites and artwork are copyrighted by Nintendo / Game Freak / The Pokemon Company | Tile with 3-letter species code + level; species name text; **type shown as text + icon chip** (type names are facts; Material icons such as `local_fire_department`, `water_drop`, `bolt`, `eco`, `pest_control` etc. mapped neutrally, with the text always present); shiny shown as `star` icon + "Shiny" label. |
| Item icons | Copyrighted | Item name text; category glyph per pocket (`backpack`, `key`, `sports_baseball` for balls, `eco` for berries, `album` for TM/HM). |
| Cartridge box art / label images | Copyrighted | Generic cartridge silhouettes per platform, drawn as vectors, platform badge with text, game title as text. No attempt to match the real label. |
| Nintendo and Game Freak logos, the Game Boy wordmark, Pokemon logo | Trademarks | Not used anywhere, including the launcher icon (generic: a cartridge silhouette with a circular arrow). Platform names appear as plain text for identification (nominative use), plus the disclaimer in Settings > About. |
| Header boot logo (the Nintendo logo bitmap in the ROM header) | The app checks the bytes only, for validity | Never rendered; only "Logo: valid / invalid" text. |
| Type and ball colours | Colour schemes of the franchise | Use app tonal colours; if a type colour helps, it is a neutral categorical palette (12 hues, colour-blind-safe, always with text), not the franchise palette, and optional. |
| Game data (names of species, moves, items, locations) | Facts, but the strings come from PKHeX resources | Use via the engine; licence follows PKHeX (see open question 8). |
| Optional future: user-supplied sprite pack | Allowed if the user supplies it, we don't ship it | A Settings hook "Sprite pack (advanced)" can be added later; the app never depends on it and never bundles one. |

---

## 7. Phased implementation plan

Mapped to engineering milestones. Each phase lists the **minimum good-looking version (MGV)** and the fate of today's UI pieces.

### Phase 0 (do with M2 backup, now): Shell and theme

- Introduce `GbfTheme` (dynamic colour, extended colours), `Scaffold` + `NavigationBar` with 3 tabs, nav host, `DeviceSession` ViewModel state exposed app-wide, **Device Status Bar**.
- MGV: Device tab (reorganised current screen), Library tab with a simple list of games, Settings tab with Debug log and firmware.
- Today's pieces become:

| Current piece | Becomes |
|---|---|
| `TopAppBar("GBFlash")` | per-screen top bars; app name only on Device |
| `ConnectionCard` | Device Status Bar (compact) + Device status/recovery card (full) |
| `FirmwareCard` | Reader card (summary) + Settings > Firmware details |
| `PlatformCard` radio list | Platform picker tiles + confirm sheet (4.2); radio list deleted |
| `HeaderCard` raw text | Cartridge card (parsed fields, chips); raw text moves to Settings > Raw header |
| error `Card` | status/error card with recovery action |
| `BackupCard` backup button + progress | Cart card action + Backup progress screen + Backup result |
| `SummaryBlock` | Backup result screen |
| `BackupRow` list + export/delete | Game detail history rows + Backup detail actions |
| Export `AlertDialog` (RTC) | Export bottom sheet (same logic) |
| Delete `AlertDialog` | Delete dialog + snackbar Undo |
| `Collapsible` hex and debug log | Settings > Advanced screens |
| `agbSaveHint` probe card | Settings > Advanced (until GBA milestone) |

### Phase 1 (M2 completion): Backup, RTC, library basics

- MGV: platform picker with confirm sheet; Device screen cartridge card; backup progress and result with RTC choice; Game detail and Backup detail (export, delete with undo, note); minimal Library list (title, platform badge, count); swap flow and idle power-off.
- Not yet: search, filters, multi-select, tablet layout.

### Phase 2 (M3 restore)

- MGV: Restore review (match / mismatch / block), automatic safety copy, hold-to-confirm, progress with the stop dialog, result with `Undo restore`, foreground service plus screen-on, error variants.
- Requires from engineering: safety copy flag `Pre-restore`, identity compare function returning which fields differ, read-back verify, write cancel semantics.
- Good-looking minimum: the comparison cards and the stepper; the typed-confirmation variant can ship a little later but must be present before mismatched restores are enabled (until then, mismatch = block).

### Phase 3 (M4 full library browser)

- MGV: Library with search, filter chips, sort, empty/no-result states, multi-select export, Game detail polish, "In reader" matching, tablet list-detail (`ListDetailPaneScaffold`), disambiguation for same-title games.

### Phase 4 (M5 GBA)

- MGV: GBA tile fully enabled in the picker (already designed), GBA header in the cartridge card, backup and restore through the same screens. GBA save types (SRAM, Flash, EEPROM) shown as the "Save type" row. Additional safety: the GBA restore review has the same layout; the `Flash` write takes longer (erase), so the progress screen shows an "Erasing" phase and a longer-time hint.
- GBA 3.3 V is lower risk for GB/GBC carts; the picker copy is already asymmetric.

### Phase 5 (M6 Pokemon editor)

- Step 5a (read-only viewer, MGV): editor entry, Save overview, party and boxes grid, Pokemon detail (read-only), Trainer and Items read-only, legality summary. Ships the whole visual design with no risk to saves.
- Step 5b: editing of party Pokemon (level, moves, stats, held item), trainer fields, items; unsaved-changes model; `Save as new backup`.
- Step 5c: boxes editing (move/swap), species change, legality report with `Go to field`, draft autosave.
- Step 5d: edited-restore integration (the flow in 6.4).

### Phase 7 (later, after GBA): ROM dumping

- Reserved now at no cost: the Cartridge card overflow menu, a `ROM dump` section slot in Game detail, the data model (a game owns `saveBackups[]` and `romDump?`), a generic `LongOperation` progress screen with optional speed/ETA fields (backup and restore use it without them), and the storage line in Settings.
- MGV: `Dump ROM` action, dump progress with speed/ETA/cancel, result with header-checksum verification, `Export ROM`, a ROM row in Game detail. Hash-database matching and "dump twice and compare" follow.

### Phase 6 (later)

- Import `.sav` (Library FAB menu: `Import`, with the same game identity matching via header-less heuristics; imported entries tagged `Imported`), compare two backups (side-by-side diff of hex, and for Pokemon, a diff of party/trainer fields).

---

## 8. Open questions for the product owner

1. **Cart power control**: do all target readers (your unit, and others you want to support) have CART_PWR_OFF? If not, should the swap flow instead require unplugging the reader (my fallback), and should the app refuse to run on readers without power control?
2. **Idle auto power-off**: is 3 minutes right, and should the user be allowed to disable it? (I recommend a minimum of 1 minute and no "never".)
3. **Platform confirm friction**: is the 1-second button delay for the first few picks acceptable, or too annoying for your own heavy use? Should it be removed once a user has done N successful sessions?
4. **Restore of a different game**: do you want to allow it at all (typed confirmation, my recommendation, because repro carts and regions are legitimate), or block it entirely until later?
5. **Safety copy cost**: always keeping an automatic pre-restore backup grows the library. Should these be auto-pruned (e.g. keep the last 5 per game), or kept forever until the user deletes them?
6. **Editor save behaviour**: every `Save as new backup` makes a new entry. Do you want the "replace my previous edit from this session" shortcut, or is a stricter "never replace anything" better for you?
7. **Editor scope on first release**: is a read-only viewer (5a) worth shipping alone, or should the first public version wait for party editing?
8. **PKHeX licence**: PKHeX is GPLv3. Using its engine means the app (or at least the linked part) must be distributed under compatible terms. Is the app open source, and is that acceptable? This also affects any strings and resource files taken from it.
9. **Export defaults**: should RTC be included by default when exporting (emulator-compatible 48-byte footer), or "save only" by default?
10. **Library location**: app-private storage means an uninstall deletes all backups. Do you want automatic scheduled export to a folder you choose (Storage Access Framework), or Android Auto Backup, as a safety net?
11. **Naming**: is "GBFlash" the app's public name, given it is also the hardware's name? A different product name avoids confusion with Geek Simon's product and trademark questions; the launcher icon is designed generic either way.
13. **ROM dumps**: one dump per game (my recommendation, with `Keep both` only on differing content) or a history like saves? Is an optional user-imported hash database (No-Intro DAT) acceptable, and should the "Only dump cartridges you own" sentence appear?
12. **Tablet and foldable**: should these be a Phase 3 deliverable (list-detail) or deferred until after the editor?
