# :pkcore

Pure Kotlin/JVM port of the **Generation 1 and 2 save-editing core of [PKHeX](https://github.com/kwsch/PKHeX)**
(GPL-3.0, ported from commit `641c26b`). No Android dependencies; legality checking is out of scope.
Every ported file names its PKHeX source in its header comment, deliberate deviations are marked in comments.

```kotlin
val save = SaveFile.load(bytes)              // raw SRAM, no RTC footer; throws UnsupportedSaveException
save.game; save.region; save.language        // RED_BLUE / YELLOW / GOLD_SILVER / CRYSTAL, INTERNATIONAL / JAPANESE
save.trainer                                  // immutable Trainer snapshot
save.updateTrainer(save.trainer.copy(money = 999_999))
save.party; save.boxes                        // immutable PokemonSummary snapshots
val pk = save.getPokemon(SlotRef.Party(0)) as Pk2   // mutable copy
pk.level = 50
save.setPokemon(SlotRef.Party(0), pk)         // explicit write-back (also movePokemon / removePokemon / addPokemon)
save.inventory; save.setPocket(PocketType.ITEMS, listOf(ItemStack(18, 99)))
val out = save.export()                       // checksums fixed; unchanged saves come back byte-identical
```

## Layout

| Package | Content |
|---|---|
| `com.therealnox.gbflash.pkcore` | Public API: `SaveFile`, `Trainer`, `Pokemon`/`PokemonSummary`, `SlotRef`, `Inventory`, exceptions |
| `...pkcore.gb` | The Gen 1/2 engine: `Sav1`, `Sav2`, `Pk1`, `Pk2`, `PokeListFormat` (list packing), `GbInventory`, `SaveDetector` |
| `...pkcore.text` | `StringConverter1/2` (character tables, terminators, ligatures) |
| `...pkcore.data` | Personal tables, species/move/item names, experience, PP, species order (resources + generated tables) |

Gen 3 slots in behind `SaveFile` / `Pokemon` (new `gb`-like package + `SaveDetector` branch); nothing in the API is
Gen 1/2-specific except the concrete `Pk1`/`Pk2` classes.

## Data

`src/main/resources/.../data` holds the personal tables (byte-for-byte copies) and species / move / item names
(ja, en, fr, it, de, es). `data/*Tables.kt` and `CharTables.kt` are **generated** from the PKHeX sources by
`tools/generate_data.py <PKHeX.Core dir> <this module dir>`.

## Tests

`./gradlew :pkcore:test`. Tests that need real saves read `src/test/fixtures-local/` (git-ignored, never commit
personal saves) and skip themselves with a JUnit `Assume` when the files are missing:
`POKEMON BLUE.sav` and `Pokemon - Version Cristal (France).sav` (its 48-byte RTC footer is stripped by the test).
