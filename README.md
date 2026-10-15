<p align="center">
  <img src="docs/images/coincell-icon.png" width="128" height="128" alt="Coincell icon: a coin cell battery with a pixel heart">
</p>

<h1 align="center">Coincell</h1>

<p align="center">
  Back up, restore and edit Game Boy, Game Boy Color and Game Boy Advance save games on Android,<br>
  with a <a href="https://github.com/simonkwng/GBFlash">GBFlash</a> cartridge reader over USB-OTG.
</p>

<p align="center">
  <a href="https://www.paypal.com/donate/?hosted_button_id=DLNMU8Z7W4B7S"><img src="https://img.shields.io/badge/Donate-PayPal-00457C?logo=paypal&amp;logoColor=white" alt="Donate with PayPal"></a>
</p>

The name comes from the CR2025 coin cell inside many cartridges: when it runs out, the save is gone. Coincell copies
your saves somewhere safe before that happens.

## Features

- **Back up** GB / GBC saves (including the MBC3 real-time clock) and GBA saves (SRAM, Flash, EEPROM, GPIO clock).
  Every block is read twice and compared.
- **Restore** a backup to a cartridge. The save already on the cartridge is always backed up first, the written data
  is read back and verified, and an undo puts the previous save back.
- **Save library** on the phone, grouped by game, browsable without the reader. Export any backup as a `.sav`.
- **Import `.sav` files** from the Android file picker (from PKHeX, an emulator or FlashGBX), with or without the
  cartridge, and link them to their cartridge later.
- **Pokémon editor for Generations 1 and 2** (Red, Blue, Yellow, Gold, Silver, Crystal): party, boxes, bag, trainer,
  stats, moves and met data. Edits are always saved as a new backup; the editor never writes to a cartridge.
- **Private by design**: no internet permission, no account, no analytics. Your saves never leave the phone unless
  you export them.

## Disclaimer

**Use Coincell at your own risk.** It is provided as is, without any warranty (see sections 15 and 16 of the
[GPL-3.0](LICENSE)). Reading or writing a cartridge can go wrong, for example with dirty contacts, a weak battery or a
loose cable, and a save or a cartridge can be damaged or lost. The authors are not responsible for any loss of or
damage to your saves, cartridges, reader or phone.

**Always keep a copy of your saves outside the app**: export them (Export .sav) to a computer or cloud storage. The
library lives in the app's private storage and is deleted if the app is uninstalled or its data is cleared.

## Requirements

- Android 8.0 or later with USB-OTG (developed and tested on a Pixel 8 Pro).
- A GBFlash reader running Lesserkuma's firmware (as used by FlashGBX; tested with L15).

## Install

Download the APK from the [latest release](https://github.com/TheRealNox/coincell/releases/latest) and open it on
your phone. Updates install over the previous version and keep your library. Each release lists the signing
certificate's SHA-256 fingerprint, so you can check an APK came from here.

## Build

Open the project in Android Studio, or from the command line:

```sh
./gradlew :app:assembleDebug        # "Coincell Dev" (org.noxeng.coincell.debug), installs next to the release
./gradlew :app:testDebugUnitTest :pkcore:test
```

Release builds are signed from a `key.properties` file at the repository root (git-ignored, never commit it):

```properties
storeFile=/path/to/coincell-release.jks
storePassword=...
keyAlias=coincell
keyPassword=...
```

Without that file `./gradlew :app:assembleRelease` produces an unsigned APK, which Android will not install.

## Support

Coincell is free and has no ads or paid features. If you enjoy it, you can
[leave a tip with PayPal](https://www.paypal.com/donate/?hosted_button_id=DLNMU8Z7W4B7S): any amount helps. The
same link is under **Settings → Support Coincell** in the app.

## Licences and credits

Coincell is free software, licensed under the **GNU General Public License v3.0** (see [LICENSE](LICENSE)). It is
built on the work of others:

| Project | Used for | Licence |
|---|---|---|
| [FlashGBX](https://github.com/lesserkuma/FlashGBX) by Lesserkuma | The cartridge protocol, header parsing, save backup and restore logic (ported to Kotlin), and reduced copies of its `db_DMG.json` / `db_AGB.json` game databases (`app/src/main/assets/db_*.tsv`) | GPL-3.0 |
| [PKHeX](https://github.com/kwsch/PKHeX) by Kurt and contributors | The Generation 1 / 2 save-editing core in [`:pkcore`](pkcore/README.md) (ported to Kotlin from commit `641c26b`) and its data: base stats, species, move, item and location names | GPL-3.0 |
| [usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android) by Mike Wakerly and contributors | USB serial access to the reader's CH340 chip. `LenientCh34xSerialDriver` is a modified copy of its `Ch34xSerialDriver` (3.11.0) | MIT |
| [AndroidX](https://developer.android.com/jetpack/androidx) and [Jetpack Compose](https://developer.android.com/compose) | User interface and Android integration | Apache-2.0 |
| [Kotlin](https://kotlinlang.org/), [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines), [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) | Language and libraries | Apache-2.0 |
| [pokesprite](https://github.com/msikma/pokesprite) by Michiel Sikma and contributors | Pokémon and item sprites in the editor (optional pack in [`pkassets/`](pkassets/README.md)) | MIT for its code and data; **the images are not covered**, see below |

Used only by the tests (not part of the app): [JUnit 4](https://junit.org/junit4/) (EPL-1.0) and
[JSON-java](https://github.com/stleary/JSON-java).

The app shows these credits and the full GPL-3.0 and MIT texts under **Settings → About Coincell**.

### Sprite images

The sprites in `pkassets/` depict Pokémon, items and other game artwork owned by Nintendo, Creatures Inc., GAME FREAK
inc. and The Pokémon Company. They are not covered by Coincell's GPL-3.0 licence or by pokesprite's MIT licence; all
rights to them belong to their owners. The pack is optional: without it the app shows names instead. Details in
[`pkassets/README.md`](pkassets/README.md).

## Trademarks

Nintendo, Game Boy, Game Boy Color and Game Boy Advance are trademarks of Nintendo. Pokémon and Pokémon character
names are trademarks of Nintendo, Creatures Inc. and GAME FREAK inc. Other names are trademarks of their respective
owners.

Coincell is an independent project. It is not affiliated with, endorsed or sponsored by Nintendo, Creatures Inc.,
GAME FREAK inc., The Pokémon Company, or the makers of the GBFlash reader. Game names are used only to identify the
games whose saves the app handles.
