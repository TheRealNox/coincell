# pkassets - sprite pack from pokesprite (private use only)

## Rights notice - read first

The images in this directory come from the **msikma/pokesprite** repository
(<https://github.com/msikma/pokesprite>, commit `c5aaa610ff2acdf7fd8e2dccd181bca8be9fcb3e`, 2022-07-22) and were copied
**unmodified**. They depict Pokemon, items, ribbons and other game artwork owned by
**Nintendo, Creatures Inc., GAME FREAK inc. and The Pokemon Company**. They are
**not** covered by the GPL-3.0 licence of the GBFlash app.

* They are included for **private, personal use only**.
* **This whole directory must be deleted before any public distribution** (public
  repository, release APK/AAB, Play Store, ...).
* Nothing outside `pkassets/` may hard-depend on these files. The app must treat every
  lookup as optional and fall back to text (names) when the pack or a single file is absent.
  Removal: delete `pkassets/` (and the one line that includes it in the Gradle settings, if
  one was added).

### pokesprite licence (MIT, for its code and data) and attribution

pokesprite is Copyright (c) Michiel Sikma and contributors, released under the MIT licence
(`license.md` in the repository: permission to use, copy, modify, merge, publish, distribute
and sell, provided the copyright notice and permission notice are included, software provided
"AS IS" without warranty). The MIT licence covers pokesprite's own code and data files
(the JSON data, names and slugs embedded in `manifest.json`); it **does not** license the
Nintendo/Creatures/GAME FREAK artwork itself. Credit: Michiel Sikma and the pokesprite
contributors (<https://github.com/msikma/pokesprite/blob/master/contributors.md>).

## What this is

All sprites the app could use, laid out as an Android asset directory
(`src/main/assets/sprites/...`) plus `manifest.json` mapping logical keys to files and sizes.
Files are byte-for-byte copies (PNG kept as is; re-encoding gains nothing).

* Source: pokesprite `c5aaa610ff2acdf7fd8e2dccd181bca8be9fcb3e` (2022-07-22); last upstream commit is from 2022, so there
  are no Gen 9 sprites (no species > 905).
* Data only (no images) from PKHeX `641c26ba100225f3605e5a44b61b3b16e15a3774` (2026-10-13): Gen 1/2/3 item
  conversion tables, Ball enum, item-name cross-check.
* Generated: 2026-10-14; generator `tools/sprites/build_pack.py` (standard library only).

## Regenerate

```
python -I tools/sprites/build_pack.py --pokesprite <pokesprite clone> --pkhex <PKHeX clone> --out pkassets --generated 2026-10-14 --commit c5aaa610ff2acdf7fd8e2dccd181bca8be9fcb3e --commit-date 2022-07-22 --pkhex-commit 641c26ba100225f3605e5a44b61b3b16e15a3774 --pkhex-commit-date 2026-10-13
```

Deterministic: same pokesprite/PKHeX files and same metadata arguments give identical
bytes (`--generated` defaults to today). Never run anything from the clones. The script
refuses to delete an `--out` directory that has no `manifest.json`.

## Contents

5434 physical files, 5920 manifest keys (aliases share a file), 4485487 bytes
(4.28 MiB).

| Category | Keys | Files | Size |
|---|---:|---:|---:|
| `ball` | 75 | 38 | 18.6 KiB |
| `ball_outline` | 75 | 38 | 30.4 KiB |
| `item` | 868 | 849 | 304.4 KiB |
| `item_other` | 185 | 185 | 53.2 KiB |
| `item_outline` | 868 | 849 | 604.2 KiB |
| `item_outline_other` | 185 | 185 | 119.6 KiB |
| `item_outline_type_icon` | 40 | 40 | 35.3 KiB |
| `item_type_icon` | 40 | 40 | 18.1 KiB |
| `misc_body-style` | 28 | 28 | 36.6 KiB |
| `misc_mark` | 45 | 45 | 304.6 KiB |
| `misc_origin-marks` | 16 | 16 | 11.5 KiB |
| `misc_ribbon` | 162 | 162 | 855.6 KiB |
| `misc_seals` | 9 | 9 | 33.6 KiB |
| `misc_special-attribute` | 11 | 11 | 11.2 KiB |
| `misc_type-logos` | 36 | 36 | 32.0 KiB |
| `misc_types` | 54 | 54 | 165.4 KiB |
| `pokemon_box` | 1478 | 1329 | 822.0 KiB |
| `pokemon_box_female` | 20 | 20 | 10.6 KiB |
| `pokemon_box_right` | 111 | 111 | 38.7 KiB |
| `pokemon_box_shiny` | 1478 | 1329 | 869.4 KiB |
| `pokemon_box_shiny_female` | 20 | 20 | 11.1 KiB |
| `pokemon_box_shiny_right` | 111 | 111 | 42.1 KiB |
| `pokemon_special` | 5 | 5 | 1.3 KiB |

### What is included

* `pokemon-gen8` regular and shiny box sprites (68x56) for every species/form (1-905),
  gen-8 female variants, egg, Manaphy egg, unknown, unknown (Gen 5), Mega sigil.
* Right-facing variants (`box_right`, `box_shiny_right`) from `pokemon-gen7x`; this is the
  only reason `pokemon-gen7x` is used. Its other 2300 files are older-style duplicates of the
  gen8 set and were skipped.
* Items (32x32) and `items-outline` (32x32, same art with the white Gen 8 style outline).
  Outline versions are kept because they stay readable on dark backgrounds (dark app themes,
  selected rows); they cost under 1 MB. Use `item/{id}` on light, `item_outline/{id}` on dark.
* Balls (also in items), ribbons (40x40 originals and 128x128 `ribbon/gen8/...`), marks,
  origin marks, special attributes (shiny stars/leaf, Gmax, alpha...), types, type logos,
  body styles, seals.
* **Skipped:** `icons/` (legacy 40x30 box icons and old 32x32 items, fully superseded by
  `pokemon-gen8`/`items`) and the rest of `pokemon-gen7x`.

### Layout

```
pkassets/manifest.json  README.md
pkassets/src/main/assets/sprites/
  pokemon/{box,box_shiny,box_right,box_shiny_right}/{dex}[-{form}][-female].png
  pokemon/box/{egg,egg-manaphy,unknown,unknown-gen5,mega-evolution-sigil}.png
  items/{group}/{slug}.png      items_outline/{group}/{slug}.png     (pokesprite's own layout)
  misc/{category}/[gen*/]{name}.png
```

## Lookup rules (what the Kotlin side must follow)

`manifest.json` -> `entries[key] = {"path": "<asset path>", "w": px, "h": px}`; `assets.open(path)`.

### Pokemon

* `pokemon/{set}/{dex}` default form; `pokemon/{set}/{dex}-{form}` other forms;
  `...-female` suffix for the female-specific sprite (`-f` was avoided: Unown form `f` exists).
  `set` = `box`, `box_shiny`, `box_right`, `box_shiny_right`. `dex` is the national dex number
  (no padding). Gen 3 internal species ids must be converted to national numbers first.
* Female: only 20 sprites exist (Pikachu forms, Hippopotas, Hippowdon, Unfezant, Frillish,
  Jellicent, Pyroar, Meowstic, Indeedee, Basculegion, Wobbuffet). Use `-female` when the
  Pokemon is female and the key exists, otherwise the default key.
* Form names are pokesprite slugs (see `pokemonIndex[dex].forms[].form`), e.g. `3-mega`,
  `6-mega-x`, `25-gmax`, `19-alola`, `201-exclamation`, `869-...` (Alcremie: `{cream}-{sweet}`
  slugs). `aliasOf` forms (e.g. Unown `a`, Deoxys `normal`) resolve to the default file.
  `unofficial: true` marks community-made icons.
* **Gen 1-3 numeric form aliases** (so keys match PKHeX form indices; see `formIndex`):
  Unown 0..25 = A..Z (`201`, `201-1` ... `201-25`), 26 = `!` (`201-26`), 27 = `?` (`201-27`);
  Castform 1 sunny, 2 rainy, 3 snowy (`351-1..3`); Deoxys 1 attack, 2 defense, 3 speed (`386-1..3`).
  Same aliasing in every set. The slug names (`201-b`, `201-exclamation`, `386-attack`) work too.
* Form value sources: Gen 3 Unown form from PID `((pid&0x3000000)>>18 | (pid&0x30000)>>12 |
  (pid&0x300)>>6 | (pid&3)) % 28` (PKHeX `PKHeX.Core/PKM/Util/EntityPID.cs:76-80`,
  `PKM/Shared/G3PKM.cs:64-66`); Gen 1/2 Unown from DVs (`PKM/Shared/GBPKM.cs:151-171`), valid 0-25
  in Gen 2, 0-27 in Gen 3 (`Legality/Tables/FormInfo.cs:408`). Castform is battle-only (a stored
  PK3 always has form 0). Deoxys in Gen 3 is not stored; PKHeX derives it from the game
  version (`PKHeX.Drawing.PokeSprite/Builder/SpriteBuilder.cs:82-88,113-114`): FireRed 1 (Attack),
  LeafGreen 2 (Defense), Emerald 3 (Speed), Ruby/Sapphire 0.
* Shiny: use `box_shiny`; pokesprite has no overlay star graphic for box sprites, but
  `special-attribute/shiny-stars` / `shiny-leaf` exist if you want a marker.
* Egg: `pokemon/box/egg` (a whole-egg sprite, 68x56) replaces the species sprite;
  PKHeX instead draws the egg over the species (`SpriteBuilder.cs:217-237`), the app may do either.
  Empty slot: no sprite (draw nothing).
* Fallback: missing key -> the default form key -> `pokemon/box/unknown` -> text.

### Items

* `item/{n}` and `item_outline/{n}`: **n = PKHeX modern item id** (the id PKHeX uses for
  sprites, `bitem_{n}`). Convert game-specific ids first:
  * Held items: Gen 2 -> `itemIdMaps.gen2[id]` (`ItemConverter.Item2to4`, 256 entries,
    `PKHeX.Core/PKM/Util/Conversion/ItemConverter.cs:66-94`), Gen 3 -> `itemIdMaps.gen3[id]`
    (`Item3to4`, 339 entries, `:97-134`; ids beyond the table, e.g. HMs and FRLG key items,
    are NaN). 0 = no equivalent. PKHeX applies these in `PK2.cs:58`, `PK3.cs:88`; PK1 has no
    held item (`PK1.cs:157`).
  * Gen 1 bag: PKHeX pushes Gen 1 ids through the *Gen 2* table, which is wrong for most
    ids (`ItemConverter.cs:175`; Gen 1 id 4 is Poke Ball, Gen 2 id 4 is Great Ball).
    `itemIdMaps.gen1BagByName` is an **addition of this pack**: Gen 1 id -> modern id via
    English names. Unmapped: 21 (BoulderBadge), 22 (CascadeBadge), 23 (ThunderBadge), 24 (RainbowBadge), 25 (SoulBadge), 26 (MarshBadge), 27 (VolcanoBadge), 28 (EarthBadge), 45 (Bike Voucher), 50 (PP Up (Unused)), 59 (Coin), 70 (Oak's Parcel), 71 (Itemfinder), 75 (Exp. All).
* **TM/HM/TR:** pokesprite draws one icon per move type, and its `item_XXXX` ids for TM/HM/TR
  are not PKHeX ids (it maps item 328 = TM01 to `tm/dark`; TM01 is Focus Punch, Fighting).
  Those ids are therefore **not** exposed as `item/{n}`; use `item_type/tm/{type}`,
  `item_type/hm/{type}`, `item_type/tr/{type}` (and `item_outline_type/...`) and pick the
  type from the move. Note PKHeX itself maps Gen 2 TMs to Gen 4 TM ids by index only, so a
  per-TM-id lookup would be wrong for Gen 2 anyway.
* Manual override (not in pokesprite's data): item 216 (Exp. Share, held item in Gen 2/3) uses
  `key-item/exp-share`, which pokesprite leaves unlinked (`manifest.itemOverrides`).
* Unlinked pokesprite items (no PKHeX id, e.g. Gen 3 key items with different art, `bicycle`,
  `bike-voucher`, duplicates) are under `item_other/{group}/{slug}`, with `unlinked` /
  `legacyOf` metadata when pokesprite has it.
* **Item id cross-check** (pokesprite numbering vs PKHeX `text_Items_en.txt`): 802 of
  868 comparable ids match by name (case/accent-insensitive prefix match; 37
  TM/HM/TR ids skipped as above). Spot checks: Master Ball 1 -> `sprites/items/ball/master.png`, Potion 17 -> `sprites/items/medicine/potion.png`,
  Leftovers 234 -> `sprites/items/hold-item/leftovers.png`. Differences found:
  - 259: PKHeX `Leek` vs pokesprite `hold-item/stick`
  - 433: PKHeX `Guidebook` vs pokesprite `key-item/journal`
  - 434: PKHeX `Sticker Case` vs pokesprite `key-item/seal-case`
  - 436: PKHeX `Sticker Bag` vs pokesprite `key-item/seal-bag`
  - 453: PKHeX `Lunar Feather` vs pokesprite `key-item/lunar-wing`
  - 464: PKHeX `Secret Medicine` vs pokesprite `key-item/secret-potion`
  - 482: PKHeX `Silver Feather` vs pokesprite `key-item/silver-wing`
  - 483: PKHeX `Rainbow Feather` vs pokesprite `key-item/rainbow-wing`
  - 565: PKHeX `Health Feather` vs pokesprite `medicine/health-wing`
  - 566: PKHeX `Muscle Feather` vs pokesprite `medicine/muscle-wing`
  - 567: PKHeX `Resist Feather` vs pokesprite `medicine/resist-wing`
  - 568: PKHeX `Genius Feather` vs pokesprite `medicine/genius-wing`
  - 569: PKHeX `Clever Feather` vs pokesprite `medicine/clever-wing`
  - 570: PKHeX `Swift Feather` vs pokesprite `medicine/swift-wing`
  - 571: PKHeX `Pretty Feather` vs pokesprite `valuable-item/pretty-wing`
  - 778: PKHeX `Waterium Z` vs pokesprite `z-crystals/primarium-z--held`
  - 780: PKHeX `Grassium Z` vs pokesprite `z-crystals/decidium-z--held`
  - 798: PKHeX `Decidium Z` vs pokesprite `z-crystals/grassium-z--held`
  - 800: PKHeX `Primarium Z` vs pokesprite `z-crystals/waterium-z--held`
  - 966: PKHeX `Health Candy L` vs pokesprite `av-candy/health-l`
  - 967: PKHeX `Mighty Candy L` vs pokesprite `av-candy/mighty-l`
  - 968: PKHeX `Tough Candy L` vs pokesprite `av-candy/tough-l`
  - 969: PKHeX `Smart Candy L` vs pokesprite `av-candy/smart-l`
  - 970: PKHeX `Courage Candy L` vs pokesprite `av-candy/courage-l`
  - 971: PKHeX `Quick Candy L` vs pokesprite `av-candy/quick-l`
  - 972: PKHeX `Health Candy XL` vs pokesprite `av-candy/health-xl`
  - 973: PKHeX `Mighty Candy XL` vs pokesprite `av-candy/mighty-xl`
  - 974: PKHeX `Tough Candy XL` vs pokesprite `av-candy/tough-xl`
  - 975: PKHeX `Smart Candy XL` vs pokesprite `av-candy/smart-xl`
  - 976: PKHeX `Courage Candy XL` vs pokesprite `av-candy/courage-xl`
  - 977: PKHeX `Quick Candy XL` vs pokesprite `av-candy/quick-xl`
  - 978: PKHeX `Bulbasaur Candy` vs pokesprite `poke-candy/green`
  - 979: PKHeX `Charmander Candy` vs pokesprite `poke-candy/red`
  - 980: PKHeX `Squirtle Candy` vs pokesprite `poke-candy/blue`
  - 981: PKHeX `Caterpie Candy` vs pokesprite `poke-candy/brown`
  - 982: PKHeX `Weedle Candy` vs pokesprite `poke-candy/purple`
  - 983: PKHeX `Pidgey Candy` vs pokesprite `poke-candy/yellow`
  - 984: PKHeX `Rattata Candy` vs pokesprite `poke-candy/pink`
  - 985: PKHeX `Spearow Candy` vs pokesprite `poke-candy/gray`
  - 986: PKHeX `Ekans Candy` vs pokesprite `poke-candy/white`
  - 987: PKHeX `Pikachu Candy` vs pokesprite `poke-candy/black`
  - 1231: PKHeX `Lonely Mint` vs pokesprite `mint/attack`
  - 1232: PKHeX `Adamant Mint` vs pokesprite `mint/attack`
  - 1233: PKHeX `Naughty Mint` vs pokesprite `mint/attack`
  - 1234: PKHeX `Brave Mint` vs pokesprite `mint/attack`
  - 1235: PKHeX `Bold Mint` vs pokesprite `mint/defense`
  - 1236: PKHeX `Impish Mint` vs pokesprite `mint/defense`
  - 1237: PKHeX `Lax Mint` vs pokesprite `mint/defense`
  - 1238: PKHeX `Relaxed Mint` vs pokesprite `mint/defense`
  - 1239: PKHeX `Modest Mint` vs pokesprite `mint/special-attack`
  - 1240: PKHeX `Mild Mint` vs pokesprite `mint/special-attack`
  - 1241: PKHeX `Rash Mint` vs pokesprite `mint/special-attack`
  - 1242: PKHeX `Quiet Mint` vs pokesprite `mint/special-attack`
  - 1243: PKHeX `Calm Mint` vs pokesprite `mint/special-defense`
  - 1244: PKHeX `Gentle Mint` vs pokesprite `mint/special-defense`
  - 1245: PKHeX `Careful Mint` vs pokesprite `mint/special-defense`
  - 1246: PKHeX `Sassy Mint` vs pokesprite `mint/special-defense`
  - 1247: PKHeX `Timid Mint` vs pokesprite `mint/speed`
  - 1248: PKHeX `Hasty Mint` vs pokesprite `mint/speed`
  - 1249: PKHeX `Jolly Mint` vs pokesprite `mint/speed`
  - 1250: PKHeX `Naive Mint` vs pokesprite `mint/speed`
  - 1251: PKHeX `Serious Mint` vs pokesprite `mint/neutral`
  - 1710: PKHeX `Poké Ball` vs pokesprite `ball/hisuian-poke`
  - 1711: PKHeX `Great Ball` vs pokesprite `ball/hisuian-great`
  - 1712: PKHeX `Ultra Ball` vs pokesprite `ball/hisuian-ultra`
  - 1748: PKHeX `Heavy Ball` vs pokesprite `ball/hisuian-heavy`
  Only 259 (PKHeX `Leek`, formerly Stick) matters for Gen 1-3 and it is a plain rename. The
  rest are renames (Feather/Wing, Guidebook/Journal, Sticker/Seal), pokesprite grouping choices
  (one icon per stat for Mints, per colour for Pokemon Candy, per size for AV candies), or
  pokesprite swapping Z-Crystal pairs (778/780/798/800: Waterium/Primarium and Grassium/Decidium
  icons look swapped, Gen 7 only). Every other id, including all Gen 1-3 items, matches.
* Gen 1-3 reachable items: 272 modern ids come out of the converter tables / bag
  lists (`ItemStorage2.cs`, `ItemStorage3RS.cs`, `ItemStorage3E.cs`, `ItemStorage3FRLG.cs`);
  56 are TM/HM (type icons only) and 2 have no pokesprite icon at all:
  426 (???), 427 (???). Colosseum/XD items (ids 500+) have no converter entry.

### Balls

`ball/{n}` where n = PKHeX `Ball` enum value (`PKHeX.Core/Game/Enums/Ball.cs`: Master 1 ... Premier 12,
Cherish 16, ... LAOrigin 37), or `ball/{slug}` (`poke`, `master`, ... `hisuian-poke`, `park`).
`ball_outline/...` likewise. Gen 3 stores 1-12; Gen 1/2 Pokemon have no ball (use `poke`).
Balls are also reachable as `item/{id}` (ids 1-16 equal the Ball ids).

### Ribbons and other misc

Key = path under `misc/` without `.png`: `ribbon/{name}` (40x40), `ribbon/gen8/{name}`
(128x128, where pokesprite has it), `mark/...`, `origin-marks/{home|legends-arceus}/...`,
`special-attribute/...`, `types/{gen8|go|masters}/...`, `type-logos/...`, `body-style/...`,
`seals/...`. `miscIndex[category]` carries names (eng/jpn/romaji), `origin_gen`, descriptions
and the gen -> key map, so match ribbons by name or `origin_gen`.
Ribbons with `origin_gen` <= 3: Artist Ribbon, Beauty Ribbon, Beauty Ribbon Hyper, Beauty Ribbon Master, Beauty Ribbon Super, Champion Ribbon, Cool Ribbon, Cool Ribbon Hyper, Cool Ribbon Master, Cool Ribbon Super, Country Ribbon, Cute Ribbon, Cute Ribbon Hyper, Cute Ribbon Master, Cute Ribbon Super, Earth Ribbon, Effort Ribbon, Land Ribbon, Marine Ribbon, National Ribbon, Sky Ribbon, Smart Ribbon, Smart Ribbon Hyper, Smart Ribbon Master, Smart Ribbon Super, Tough Ribbon, Tough Ribbon Hyper, Tough Ribbon Master, Tough Ribbon Super, Victory Ribbon, Winning Ribbon, World Ribbon.

## Missing / not available

Gen 1-3 Pokemon: nothing is missing (all 386 species, every Gen 1-3 form, normal and shiny).
Everything below is *expected* (from pokesprite's own data files or from PKHeX's Gen 1-3
tables) but absent. Full list in `manifest.json` -> `missing`.

- `gen123_item_without_icon` (2): item ids 426-427 (426=???, 427=??? ...)
- `gen123_tm_hm_no_per_id_icon` (56): item ids 328-377, 420-425 (328=TM01, 425=HM06 ...)

No status-condition icons, held-item marker, party marks, slot highlight accents or
legality icons exist in pokesprite (the previous PKHeX-sourced pack had them); draw those in
the app.

## Notes

* pokesprite Pokemon/item sprites are 68x56 / 32x32; misc images range from 16 px to 256 px.
* Keys and paths use only `[a-z0-9._/-]`.
