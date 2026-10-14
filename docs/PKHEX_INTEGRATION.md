# PKHeX integration feasibility report

Research only. Nothing from the PKHeX repo was built or run. All repo claims below come from reading source.
Paths are relative to the PKHeX repo root unless stated. Anything from general knowledge is marked **(unverified)**.

## 0. Provenance and local toolchain

| Item | Value |
|---|---|
| Clone | `...\scratchpad\src\PKHeX` (`--depth 1`) |
| Commit | `641c26ba100225f3605e5a44b61b3b16e15a3774`, committed 2026-10-13 01:23:57 -0500 |
| Version | `Directory.Build.props:4` -> `26.08.26` |
| Clone caveat | After checkout, `git status` listed files as deleted/untracked. The working tree is nonetheless populated (about 11.7k files, 1820 `.cs` in PKHeX.Core). Presumably a Windows checkout quirk. It does not affect reading the source. |

Local toolchain (read-only checks):

| Tool | Finding | Consequence for a PoC |
|---|---|---|
| `dotnet --info` | SDK **9.0.308** only (host reports 10.0.13). Runtimes: NETCore.App 8.0.22 and 8.0.32, AspNetCore 8.0/9.0. No .NET 10 runtime or SDK. | PKHeX.Core targets **net10.0** with LangVersion 14, so it cannot compile with SDK 9. A .NET 10 SDK is required. Installing needs the user's go-ahead. |
| `dotnet workload list` | No workloads installed | `android` / `maui-android` would have to be installed for option (b). |
| Android SDK | `%LOCALAPPDATA%\Android\Sdk` has NDK 28.2.13676358 and 30.0.16248370, CMake 3.22.1 and 4.1.2, build-tools, platforms, emulator | The NDK is available. |
| `java`, `clang`, `cmake` on PATH | Not found in the bash PATH | Gradle presumably uses its own JDK (Android Studio JBR?). Not checked. |
| WSL | `wsl` exists | A candidate Linux host for NativeAOT `linux-bionic` builds. Distro and contents not checked. |

## 1. PKHeX.Core shape

| Question | Answer (source) |
|---|---|
| Target framework | `net10.0` (`PKHeX.Core/PKHeX.Core.csproj:4`). Tests also use net10.0. The Drawing and WinForms projects are `net10.0-windows` (`PKHeX.Drawing/PKHeX.Drawing.csproj:4`, `PKHeX.WinForms/PKHeX.WinForms.csproj:5`). |
| Language version | `LangVersion` 14, `Nullable` enabled (`Directory.Build.props:5-6`). The Debug build uses TreatWarningsAsErrors (`PKHeX.Core.csproj:31`). |
| NuGet dependencies | **None.** The csproj has no `PackageReference` and no `ProjectReference`. It uses BCL only, including System.Text.Json source generation (see below). |
| WinForms / System.Drawing | **Free.** `grep System.Drawing\|System.Windows` over `PKHeX.Core/**/*.cs` found nothing. System.Drawing.Common is referenced only by `PKHeX.Drawing`. |
| Native / P/Invoke | None. No `DllImport` or `LibraryImport`. The `unsafe` / `Marshal` matches are `CollectionsMarshal.AsSpan` only (e.g. `Editing/Database/TrainerDatabase.cs:40`). Pure managed code. |
| Embedded resources | `PKHeX.Core.csproj:35-38`: `<EmbeddedResource Include="Resources\**" />`. Source tree is about 16 MB, 1731 files: `text` 8.1 MB, `legality` 4.8 MB (`mgdb` 2.6 MB, `wild` 2.1 MB), `byte` 2.2 MB, `localize` 0.5 MB. Gen1-3 specifics are small: `Resources/legality/gen1` 10 KB, `gen2` 11 KB, `gen3` 16 KB. Compiled DLL size not measured; **(unverified)** it is roughly the same order as the resources, i.e. tens of MB. |
| How resources are read | `Util/EmbeddedResourceCache.cs:25,59,76`: `Assembly.GetManifestResourceNames()` and `GetManifestResourceStream`, built at startup from `typeof(Util).Assembly` (`Util/ResourceUtil.cs:10`). Works under NativeAOT and trimming because embedded resources are retained (**unverified** on Android specifically). |
| Reflection | Limited and annotated. 14 `[RequiresUnreferencedCode]` markers, all on non-core paths: batch editor (`Editing/Bulk/Base/BatchEditingBase.cs:126,150,157`), `PKM.cs:1043` (cross-format property copy), `Ribbons/RibbonInfo.cs:53`, `Saves/Access/SaveBlockMetadata.cs:15`, and `Util/ReflectUtil.cs:46+`. The core load, read, write and checksum path does not use reflection. JSON uses source generators: `[JsonSerializable]` + `JsonSerializerContext` in `Legality/Localization/*.cs` and `Editing/BattleTemplate/*.cs`. This is trim and AOT friendly. |
| Trim/AOT declarations | The csproj has **no** `IsTrimmable`, `IsAotCompatible` or `EnableTrimAnalyzer`. The authors annotate reflection, but trim/AOT cleanliness is not enforced or claimed. Treat it as "probably OK, must be tested". |
| Static initialisation | Several large eager statics, e.g. `Legality/Encounters/Data/EncounterEvent.cs:18-45` loads all gen4-9 mystery gift DBs in static field initialisers when `EncounterEvent` is first touched. `GameInfo` builds strings eagerly (`Game/GameStrings/GameInfo.cs:19`). Expect a noticeable first-use cost when legality is used (**unverified**, measure it). |
| Size | 1820 `.cs` files under PKHeX.Core. Saves for all generations live in the same assembly. |
| License | GPL-3.0 (`LICENSE`; `PackageLicenseExpression` is `GPL-3.0-or-later` at `PKHeX.Core.csproj:15`). |

## 2. Gen 1 / 2 / 3 save API

### 2.1 Loading and type detection

* Entry point: `SaveUtil.TryGetSaveFile(Memory<byte> data, out SaveFile? result, string? path = null)` (`Saves/Util/SaveUtil.cs:494`), plus `GetSaveFile(Memory<byte>, path)` (`:487`). Order of attempts: custom readers (`:109`, a ZipReader), then exact-size detection (`GetSaveFileInternal`), then the `Handlers` list (`:117`).
* Detection `GetTypeInfo` (`:165`) tests Gen1, Gen2, Gen3 first. It is purely structural. No filename is needed.
  * **Gen 1:** data length must equal `SIZE_G1RAW` = 0x8000 (`:94, :236`). It then checks that two Pokemon lists have a valid count and 0xFF terminator at the international offsets (`IsG1INT`, `:206`) or the Japanese offsets (`IsG1JPN`, `:207`). Yellow is detected by `SAV1.IsYellowINT/JPN` (`Saves/SAV1.cs:570-571`).
  * **Gen 2:** length is 0x8000 or 0x10000 (`SIZE_G2RAW_U/J`, `:89-90`, `IsSizeGen2` `:149`). It distinguishes GS-INT, Crystal-INT, GS-JPN, Crystal-JPN and GS-KOR via list checks (`:208-212`, `IsG2` `:252-278`). **Crystal-Korean does not exist in the detection**, only GS-KOR.
  * **Gen 3:** length must equal 0x20000 (`:284`). It scans the two 64 KiB slots for all main sectors (`SAV3.IsAllMainSectorsPresent`), then `GetVersionG3SAV` (`:302`) picks FRLG, RS or Emerald from the word at 0xAC. **A 64 KiB RS save is not accepted** as-is, because `IsG3` requires exactly 0x20000 (`:284`). A GBA cart reader would need to pad to 128 KiB (**inference, test it**).
* Overrides: `SaveUtil.TryOverride(sav, SaveTypeInfo, ...)` (`:517`) and `SaveTypeInfo(Type, SubVersion, Language)` (`Saves/Util/Detection/SaveTypeInfo.cs:9`) allow forcing a type, which is useful because the app already knows the cart title and header.
* Direct construction (skips detection): `new SAV1(Memory<byte>, LanguageID, GameVersion)` (`Saves/SAV1.cs:49`), `new SAV2(Memory<byte>, LanguageID, GameVersion)` (`Saves/SAV2.cs:61`), `SAV3` is abstract with `SAV3RS`, `SAV3E`, `SAV3FRLG` (`Saves/SAV3.cs:10`).

### 2.2 Classes

| Game | Class | Notes |
|---|---|---|
| Red/Blue/Yellow | `SAV1` (`Saves/SAV1.cs:10`) | `Japanese` flag; 8 boxes of 30 if Japanese, else 12 of 20 (`:237,:244`); `Korean => false` (`:19`). |
| Gold/Silver/Crystal | `SAV2` (`Saves/SAV2.cs:10`) | `Japanese`, `Korean` flags; 9x30 JP, else 14x20 (`:268,:275`). |
| Ruby/Sapphire/Emerald/FRLG | `SAV3RS`, `SAV3E`, `SAV3FRLG` (base `SAV3`) | Plus side games `SAV3Colosseum`, `SAV3XD`, `SAV3RSBox`. |
| Stadium | `SAV1Stadium`, `SAV1StadiumJ`, `SAV2Stadium` | Not relevant. |

### 2.3 Size variants, footers and appended bytes

* **Trailing RTC footer is explicitly handled.** `SaveHandlerFooterRTC` (`Saves/Util/Recognition/SaveHandlerFooterRTC.cs:8-44`) accepts files whose length minus a footer of 0x0C..0x30 bytes (even only, plus a special case of 7 for FlashGBX) equals 0x20000, 0x10000 or 0x8000. The footer is split off, and kept in `SaveFileMetadata` (`Saves/SaveFileMetadata.cs:31,77-78`). On `Write()` it is **re-appended unchanged** unless `BinaryExportSetting.ExcludeFooter` is passed. A 48-byte (0x30) BGB/VBA-M footer is exactly the max allowed. It is only tried **after** exact-size detection fails (`SaveUtil.cs:494-507`).
* **For us this is optional.** Our `save.sav` is already RTC-free (32 KiB for GB/GBC), so exact-size detection applies directly. Keep `rtc.bin` entirely out of PKHeX and untouched. Passing only `save.sav` is the safest path, and PKHeX never needs to know about RTC.
* Japanese Gen 1/2 saves are also 0x8000 and are told apart by list offsets (see above). The 0x10000 Gen 2 size is a padded variant (`SIZE_G2RAW_J`).
* Virtual Console: `SAV1.IsVirtualConsole` is derived from the **filename** (`SAV1.cs:14`: starts with "sav" and contains ".dat"). Irrelevant for cart dumps (defaults to "GB-era").
* Korean Gen 2: `SAV2.Korean` with its own checksum (`SAV2.cs:198-200, 222`).

### 2.4 Reading and writing data

* `SaveFile.Buffer` is the `Memory<byte>` passed in (`Saves/SaveFile.cs:13,21`). **Edits mutate the caller's array in place.** Pass a copy.
* Party: `sav.PartyData` (`:181`, `IList<PKM>`), `GetPartySlotAtIndex(i)` (`:213`), `SetPartySlotAtIndex(pk, i)` (`:215`), `PartyCount`.
* Boxes: `BoxData` (`:420`), `GetBoxSlotAtIndex(box, slot)` (`:556`), `SetBoxSlotAtIndex`, `BoxCount`, `BoxSlotCount`. Gen 1 box storage is unpacked into a buffer on load and merged back on export (`SAV1.cs:61-100, 132-180`).
* Entities: `PK1` (`PKM/PK1.cs:7`), `PK2`, `PK3`. `pk.Species` maps from internal to national IDs (`PK1.cs:57`). Strings come from `Nickname`, `OriginalTrainerName` (`PKM/Shared/GBPKML.cs:64,76`).
* Trainer: `sav.OT`, `TID16`, `Money`, `Badges`, `PlayedHours/Minutes/Seconds`, `RivalName` (`SAV1.cs:268-305, 326-370, 404`). Items: `sav.Inventory` -> `PlayerBag1` pouches (`SAV1.cs:424`, `Items/Bags/PlayerBag1.cs:13-22`). `ItemStorage1/2/3*` (`Items/`) describe valid items per game.
* Setting a slot runs `UpdatePKM` / `SetPartyValues` (`SaveFile.cs:240-264`), which fixes derived values (party stats etc.) and dex flags according to `EntityImportSettings`. The PKM type must match `sav.PKMType`, otherwise it throws (`SaveFile.cs:~236`).

### 2.5 Checksums and export

* `SaveFile.Write(BinaryExportSetting)` (`SaveFile.cs:45`) calls `GetFinalData()` (`:51`), which calls `SetChecksums()` and returns a **copy** (`Data.ToArray()`), then `Metadata.Finalize` re-attaches header/footer (`SaveFileMetadata.cs:70-83`).
* Gen 1: `SetChecksums` (`SAV1.cs:252`), single byte checksum; `GetFinalData` (`:132`) also re-packs boxes and party first.
* Gen 2: `SAV2.GetFinalData` (`:148`), `SetChecksums` (`:289-293`) writes two 16-bit checksums; Korean handled separately.
* Gen 3: `SAV3.GetFinalData` (`:155`) writes sectors then `SetChecksums` (`:243`), per-sector 32-bit-fold checksums. The newest active slot is the one rewritten (`ActiveSlot`, `:57,:70`).

### 2.6 Minimal sketch (C#, Gen 1)

```csharp
using PKHeX.Core;

byte[] raw = File.ReadAllBytes("save.sav");          // 0x8000, no RTC
byte[] work = (byte[])raw.Clone();                   // PKHeX edits in place
if (!SaveUtil.TryGetSaveFile(work, out var sav)) throw new InvalidDataException();

string ot = sav.OT;                                  // trainer name
foreach (PKM pk in sav.PartyData)                    // PK1 for Gen 1
    Console.WriteLine($"{pk.Species} L{pk.CurrentLevel} {pk.Nickname}");

var first = sav.GetPartySlotAtIndex(0);
first.Nickname = "TEST";                             // or set IVs/EVs/moves
sav.SetPartySlotAtIndex(first, 0);                   // validates and updates party stats

sav.Money = 999999;
byte[] edited = sav.Write().ToArray();               // checksums fixed, footer (if any) re-appended
```
Species names (as opposed to IDs) need `GameInfo.GetStrings("en")` (`Game/GameStrings/GameInfo.cs:8-29`).
This sketch has not been compiled.

## 3. Legality checking

* API: `new LegalityAnalysis(PKM pk, ...)` (`Legality/LegalityAnalysis.cs:110`). Results are in `.Results`, `.Valid`, `.Parsed`, `EncounterMatch`. Human-readable text needs the localization classes under `Legality/Localization/` and the `LegalityFormatting` helpers (`Legality/Formatting/`).
* Gen 1/2 have a dedicated route: `LegalityParseFormat.GameBoy => ParsePK1` (`LegalityAnalysis.cs:180, 223-234`), covering nickname, level, trainer, misc, PP, EV and item checks. Gen 3 uses `ParsePK3` (`:236`).
* Dependencies: encounter generators and tables in `Legality/Encounters/**` (statics, many built from embedded `.pkl` via `Util.GetBinaryResource`, e.g. `Encounters1GBEra.cs:7` `event1.pkl`), learnsets, evolution trees, `Resources/byte/*` personal tables, and the localization JSON/text. It is pure managed, with no external database.
* Failure handling: errors inside the analysis are swallowed under `#define SUPPRESS` and mark the entity invalid (`LegalityAnalysis.cs:1,101-120`). This is useful for robustness, but a crash in a trimmed or AOT build would silently become "invalid", so test with a known-good Pokemon.
* Memory and CPU: not measured. A single check is expected to be milliseconds once warmed up. The first call pays static initialisation of encounter, evolution and personal data **(unverified, measure on device)**. Per-Pokemon calls are independent and cheap for a party or box of 6-20.
* Gen 1-3 works: it is the intended domain. Caveat: the Gen 1/2 checks assume trade-back context and some edge cases rely on `EncounterEvent` statics shared with later generations (see section 1).

## 4. Integration options

Android constraints: Pixel 8 Pro, arm64-v8a, Android 16/17. Maturity statements about .NET tooling are from general knowledge (**unverified, my knowledge may be dated**) and the PoC must confirm them.

| Aspect | (a) NativeAOT `linux-bionic-arm64` .so with `[UnmanagedCallersOnly]` exports | (b) .NET for Android (`net10.0-android`) class library or bindings embedded in Kotlin app | (c) Port Gen 1-3 to Kotlin | (d1) Run PKHeX.Core over a local out-of-process service | (d2) Mono/CoreCLR embedding via `hostfxr` / Xamarin-style native host |
|---|---|---|---|---|---|
| Maturity / support | `linux-bionic` is a supported RID for the runtime (used by Android's own tooling) and NativeAOT for it was work-in-progress or experimental when I last knew **(unverified)**. Official support for "NativeAOT .so called from Java" is not a documented, supported scenario **(unverified)**. | Official, supported product (Microsoft .NET for Android) but built as a **full Android app** using the Android workload; embedding it as a library in an existing Kotlin Gradle app is unusual and, as far as I know, not a supported scenario **(unverified)**. | Not applicable (own code). | N/A on a phone in practice. | Possible, but not a documented supported path **(unverified)**. |
| APK size | Smallest of the .NET routes. Core trimmed + NativeAOT roughly several MB plus the embedded resources (up to 16 MB uncompressed in the source tree). Can drop non-Gen1-3 resources only by editing the PKHeX source/csproj (fork). | Large: Mono/CoreCLR runtime plus BCL (about 10+ MB per ABI) plus assembly. Trimming/linker needed. | Smallest: only the tables you port. | n/a | Large (a full runtime). |
| Startup cost | Native; no JIT, lowest startup. PKHeX static init (resources, tables) still runs on first use. | Runtime start (tens to hundreds of ms) **(unverified)**; JIT or AOT profile choices. | Negligible. | n/a | Runtime start. |
| Data across boundary | Plain C ABI: pass `byte*`+len, return buffers or UTF-8 JSON, opaque `GCHandle`/int handles. Call via JNI C shim (NDK/CMake) or JNA. Needs hand-written exports and manual memory ownership rules. | JNI/Java binding generation or a custom bridge; heavier. | Native Kotlin types; no boundary. | Sockets/HTTP, JSON. | JNI + runtime hosting API. |
| Build pipeline from Gradle | Hard. NativeAOT cross-compilation generally needs the **Linux** host (or WSL) plus the NDK and the `ILCompiler` runtime packs; Windows -> bionic is not supported as far as I know **(unverified)**. A Gradle `Exec` task could invoke `dotnet publish -r linux-bionic-arm64` in WSL/CI and copy `libpkhex.so` into `jniLibs/arm64-v8a`. Practical answer: prebuild the .so in CI/WSL and check it in or fetch it as an artifact. | Needs the `android` workload and its own packaging; Gradle integration is not a documented flow **(unverified)**. | Plain Gradle. | n/a | Very complex. |
| Trimming / reflection risk with PKHeX | Highest scrutiny: the core path is reflection free (section 1), but any `RequiresUnreferencedCode` API and `Resources` handling must be verified. `System.Text.Json` is source generated. No `IsAotCompatible` is declared, so expect IL2026/IL3050 warnings to triage and perhaps need a root descriptor. | Lower risk if trimming is disabled (bigger), same issues if the linker is enabled. | None (but fidelity risk). | None. | None (full runtime). |
| Debugging | Hard: native crashes, no managed debugger; use logcat plus a desktop console harness that calls the same exports. | Better managed debugging via the IDE, but heavy tooling. | Easiest: normal Kotlin unit tests. | Easy (desktop). | Hard. |
| GPL-3.0 | PKHeX.Core is GPL-3.0-or-later and the app is GPL-3.0, so linking is compatible. Source and notices must be shipped. | Same. | A port is a derivative work: GPL-3.0 too, attribute PKHeX. | Same. | Same. |

Additional notes:

* (c) is deceptively expensive for correctness: Gen 1/2 string tables, party/box repacking, dex flags, checksums per version, and stat recalculation all live in PKHeX and are battle-tested, while legality (`EncounterFinder`, learnsets, evolutions) is large. A port gives the best UX and smallest size, but loses legality parity and ongoing upstream fixes. It becomes attractive only for **read-only parsing plus simple edits** (names, money, items, party) with the checksum logic, about the size of `SAV1`/`SAV2`/`PK1`/`PK2` (**inference, not measured**).
* (d) Other credible directions:
  * Use PKHeX.Core only on the desktop or CI to produce **golden test vectors** (JSON), then port to Kotlin and test against them. This is a low-risk hybrid of (c).
  * Use the same engine behind WebAssembly (**unverified**, not investigated).
* PKHeX.Core is already used on Android by PKHeX.Mobile (section 6), which implies that (b)-style loading of the assembly works at least for the older core, though that project is stale.

## 5. Recommendation

**First PoC: option (a), NativeAOT shared library, C ABI with opaque handles and JSON DTOs.** It is the only route with no big runtime, an easy "bytes in / bytes out" boundary and a small APK, and PKHeX.Core's reflection-light core path suits it. If the risk checks below fail, fall back to (c) with PKHeX-generated golden vectors.

Rationale for ranking: (b) is heavy and awkward to embed in a Kotlin app; (c) is the best long-term app architecture but loses legality and costs the most work; (a) tests the riskiest unknown (can PKHeX.Core run under NativeAOT bionic on a Pixel at all) in the least time.

### 5.1 Minimal API surface (C exports, UTF-8 JSON strings, caller frees)

| Export | Signature (C) | Purpose |
|---|---|---|
| `pkx_load` | `int pkx_load(const uint8_t* data, int len, int* outHandle)` | Copy bytes, detect via `SaveUtil.TryGetSaveFile`. Return an error code. |
| `pkx_info` | `char* pkx_info(int h)` | JSON: generation, game, language, OT, TID, money, checksum validity. |
| `pkx_party` | `char* pkx_party(int h)` | JSON array of Pokemon DTOs. |
| `pkx_box` | `char* pkx_box(int h, int box)` | Same for boxes. |
| `pkx_set_pokemon` | `int pkx_set_pokemon(int h, int box, int slot, const char* json)` | Apply a DTO (`box = -1` for party). Validates. |
| `pkx_set_trainer` | `int pkx_set_trainer(int h, const char* json)` | Name, money, etc. |
| `pkx_export` | `int pkx_export(int h, uint8_t** out, int* outLen)` | `Write()`, return a copy. |
| `pkx_legality` | `char* pkx_legality(int h, int box, int slot)` | Valid flag plus text report. |
| `pkx_free` / `pkx_close` | `void pkx_free(void*)`, `void pkx_close(int h)` | Memory ownership. |

Notes: exports are `[UnmanagedCallersOnly(EntryPoint = "...")]` static methods. Handles are `int` keys into a `ConcurrentDictionary<int, SaveFile>`, to avoid pinning `GCHandle`s across JNI. Never let managed exceptions cross the boundary: catch everything and return error codes. Hand-write the JSON DTOs with System.Text.Json **source generation** to stay AOT clean. Keep `rtc.bin` entirely out of the API.

### 5.2 PoC plan

1. **Risk-kill step zero (desktop, no Android):** with a .NET 10 SDK (user must approve installing it), make a throwaway console project referencing a **copy** of PKHeX.Core, run the section 2.6 sketch against a real `save.sav` (Gen 1 and Gen 2), and confirm detection and the trainer name. Also `PublishAot` on the desktop RID to see the trim/AOT warning list for PKHeX.Core. If core detection or save round trip fails here, stop.
2. **NativeAOT on Linux for the actual target:** in WSL (or a Linux CI), `dotnet publish -r linux-bionic-arm64 -p:PublishAot=true -p:NativeLib=Shared` of a small wrapper project that exports `pkx_load` + `pkx_info` only. Record .so size and any link errors. **This is the riskiest unknown: whether NativeAOT bionic produces a loadable .so on Android 16/17 and works with `System.Private.CoreLib` startup under bionic.** Verify it first with a 3-line "return 42" export before including PKHeX at all.
3. **Load on a Pixel:** minimal CMake-less route: put the .so in `jniLibs/arm64-v8a`, call it via JNA (or a small JNI shim), run `pkx_load` on a real `save.sav` and show the trainer name in logcat. Check cold-start time and RSS.
4. Add `pkx_party` and the JSON DTOs; confirm Gen 1 names and species; do Gen 2 with `rtc.bin` kept separate; test Japanese and Korean fixtures.
5. Add `pkx_set_pokemon`, `pkx_set_trainer`, `pkx_export`; verify the round trip: export must be byte-identical when unchanged, and a changed save must load in an emulator (BGB/mGBA).
6. Add `pkx_legality`, and measure the first-call latency and memory on the device. Decide whether to ship legality in v1.
7. Wire Gradle: a `buildPkhexNative` task invoking WSL/CI, or consume a prebuilt .so from CI artifacts; add ABI filter `arm64-v8a`, license notices, and decide whether to fork PKHeX to drop unneeded resources.

### 5.3 What to verify first (in order)

1. PKHeX.Core compiles under the needed SDK and publishes with `PublishAot` with acceptable warnings (needs .NET 10 SDK).
2. A trivial NativeAOT `linux-bionic-arm64` .so loads and runs on the Pixel 8 Pro.
3. PKHeX.Core inside that .so can detect and read a Gen 1 and a Gen 2 save (embedded resources and statics survive trimming).
4. Cold-start and memory cost, especially `LegalityAnalysis`.

## 6. Existing work

Seen in the repo or via read-only GitHub metadata queries (no code from those repos was fetched or run, other than reading their README and file listing):

| Finding | Source |
|---|---|
| Upstream PKHeX has no Android or mobile code and no AOT, trimming or Android mentions in docs, csproj or CI (grep for android, ios, maui, xamarin, nativeaot, IsAotCompatible, PublishAot, IsTrimmable found only unrelated text such as species names). The `.github/workflows` directory is absent in this clone. | repo grep |
| Issue #4870 "Android APK" (closed 2026-09-15), maintainer reply: "PKHeX.Mobile already exists. You need to figure out how to set it up yourself" with link to `https://github.com/kwsch/PKHeX.Mobile`. Issue #3525 (closed, 2022) has the same answer. | `gh issue view` |
| `kwsch/PKHeX.Mobile`: "Pokémon save editor for Android and iOS", C#, not archived, last push **2024-05-30**. README says it is a **Xamarin.Forms 4.0** app using Syncfusion controls (community licence); "Dependencies: TBD". It contains `PKHeX.Android`, `PKHeX.iOS`, `PKHeX.Drawing`, and its LICENSE.md is GPL-3.0 (the GitHub license detector labelled it "other"). Xamarin is end-of-life **(unverified)**, so it is evidence that PKHeX.Core has run on Android, but not a turn-key binding. | `gh api` |
| No official Kotlin/Java/JNI bindings, NuGet-for-Android package or NativeAOT exports appear in the repo. | repo grep |
| Third-party bindings or ports in other languages: possibly exist (**unverified**, not searched). | n/a |

## 7. Risks and open questions

Top risks:

1. **NativeAOT `linux-bionic` toolchain risk:** support level, Windows host limitations, and Android-specific loading are unverified. The Windows machine will probably need WSL/CI. Mitigation: step 2 of the PoC, with a trivial export first.
2. **.NET 10 requirement:** PKHeX.Core targets net10.0 (LangVersion 14), but the machine has SDK 9.0.308 only, and the PKHeX version moves fast, so tracking upstream means keeping up with the newest SDK (and an eventual fork).
3. **Trimming/AOT correctness and startup/memory:** PKHeX declares no AOT compatibility, loads big static tables and embedded resources, and `LegalityAnalysis` swallows exceptions (`LegalityAnalysis.cs:101-120`), so a trimming problem could show up as silent "invalid". Mitigation: golden tests comparing desktop and device output.

Other points:

* Footer: handled by PKHeX (`SaveHandlerFooterRTC`), but we should simply not pass `rtc.bin`. If we ever pass a combined file, check the 48-byte footer is round-tripped intact.
* Gen 3: exact 0x20000 only; 64 KiB RS saves need padding (section 2.1).
* PKHeX edits the input buffer in place; always pass a copy.
* Blocking unknowns: (1) no .NET 10 SDK locally; (2) whether the Windows host can drive the Linux-only NativeAOT build (WSL contents unchecked); (3) JDK/Gradle setup was not inspected; (4) real sizes and timings (DLL, .so, startup, legality) are unmeasured.
