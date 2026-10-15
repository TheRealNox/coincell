# :pkcore

Pure Kotlin/JVM port of the **Generation 1, 2 and 3 save-editing core of [PKHeX](https://github.com/kwsch/PKHeX)**
(GPL-3.0, ported from commit `641c26b`). No Android dependencies; legality checking is out of scope.
Every ported file names its PKHeX source in its header comment, deliberate deviations are marked in comments.

```kotlin
val save = SaveFile.load(bytes)              // raw SRAM / 128 KiB Flash, no RTC footer; throws UnsupportedSaveException
save.game; save.region; save.language        // RED_BLUE ... CRYSTAL, RUBY_SAPPHIRE / EMERALD / FIRERED_LEAFGREEN
save.trainer                                  // immutable Trainer snapshot
save.updateTrainer(save.trainer.copy(money = 999_999))
save.party; save.boxes                        // immutable PokemonSummary snapshots
val pk = save.getPokemon(SlotRef.Party(0)) as EditablePokemon   // mutable copy (Pk1 / Pk2 / Pk3)
pk.level = 50
save.setPokemon(SlotRef.Party(0), pk)         // explicit write-back (also movePokemon / removePokemon / addPokemon)
save.inventory; save.setPocket(PocketType.ITEMS, listOf(ItemStack(18, 99)))
val out = save.export()                       // checksums fixed; unchanged saves come back byte-identical
```

## Layout

| Package | Content |
|---|---|
| `org.noxeng.coincell.pkcore` | Public API: `SaveFile`, `Trainer`, `Pokemon`/`PokemonSummary`, `SlotRef`, `Inventory`, exceptions |
| `...pkcore.gb` | The Gen 1/2 engine: `Sav1`, `Sav2`, `Pk1`, `Pk2`, `PokeListFormat` (list packing), `GbInventory`, `SaveDetector` |
| `...pkcore.gba` | The Gen 3 engine: `Sav3` (sector reassembly, active slot, checksums), `Pk3` (encryption + block shuffle), `Gen3Inventory`, `Gen3Layout` (R/S, E, FR/LG offsets) |
| `...pkcore.text` | `StringConverter1/2` (character tables, terminators, ligatures) |
| `...pkcore.data` | Personal tables, species/move/item names, experience, PP, species order (resources + generated tables) |

`EditablePokemon` holds the fields every generation shares; generation-specific ones stay on `Pk1`/`Pk2`/`Pk3` and in
`PokemonSummary.dvs`/`statExp` (Gen 1/2) or `PokemonSummary.gen3`. Gen 1/2 boxes are packed lists, Gen 3 boxes keep
gaps (`SaveFile.compactBoxes`, `BoxSummary.slots`).

Gen 3 deviations from PKHeX: the save language is guessed from the language byte of the trainer's own Pokemon (PKHeX
uses the file name; the app passes the cartridge's game code instead), an egg's OT name is decoded in the save's
alphabet, `isNicknamed` is false for eggs. Only 128 KiB saves are read, like PKHeX. Not ported: Colosseum/XD/Box
saves, Hall of Fame, secret bases, mail, decorations, ribbons as separate flags.

## Data

`src/main/resources/.../data` holds the personal tables (byte-for-byte copies) and species / move / item names
(ja, en, fr, it, de, es), plus Gen 3 abilities, natures and met locations. `data/*Tables.kt` and `CharTables.kt` are **generated** from the PKHeX sources by
`tools/generate_data.py <PKHeX.Core dir> <this module dir>`.

## Tests

`./gradlew :pkcore:test`. Tests that need real saves read `src/test/fixtures-local/` (git-ignored, never commit
personal saves) and skip themselves with a JUnit `Assume` when the files are missing:
`POKEMON BLUE.sav`, `Pokemon - Version Cristal (France).sav` (its 48-byte RTC footer is stripped by the test) and
`Pokemon - Version Saphir (France).sav` (FlashGBX's 16-byte footer stripped). Gen 3 is also covered without personal
files through synthetic saves of all three game families (`SyntheticSaves3`).
