# GBFlash save-game porting spec (FlashGBX -> Kotlin/Android)

Source of truth: FlashGBX upstream commit 2d9682c (GPL-3.0). Every non-trivial claim cites `file:line`.
File names are relative to `FlashGBX/FlashGBX/` (the Python package). Abbreviations:
`LK` = `LK_Device.py`, `HW` = `hw_GBFlash.py`, `MAP` = `Mapper.py`, `CT` = `CartridgeTypes.py`,
`RFD` = `RomFileDMG.py`, `RFA` = `RomFileAGB.py`, `CLI` = `FlashGBX_CLI.py`, `GUI` = `FlashGBX_GUI.py`.

Notation: `u8/u16/u32` = unsigned; `BE` = big-endian (Python `>`), `LE` = little-endian (`<`).
`[A6 01 ...]` = literal bytes in hex. "ACK" = one response byte equal to `0x01` or `0x03` (see 1.4).
"fw>=12" = `FW["fw_ver"] >= 12`. All offsets are hex unless stated.

Scope: GBFlash (Lesserkuma CFW, `cfw_id=='L'`), DMG (GB/GBC) and AGB (GBA) **save backup/restore only**.
The firmware source is NOT in the repo (only `res/fw_GBFlash.zip`, a binary; its `fw.ini` says `fw_ver = L15`,
`fw_buildts = 1780508702`), so firmware-internal behaviour below is marked **(inferred)** where it is deduced
from how Python uses the protocol.

---------------------------------------------------------------------------------------------------

## 0. Corrections / surprises relative to the brief

1. Latest bundled firmware is **L15** (`res/fw_GBFlash.zip:fw.ini`), not 12. `DEVICE_MAX_FW = 12` (`HW:15`) is never
   referenced. fw>=15 changes the keep-alive check to a PING (`LK:250-255`), see Appendix A.
2. The connect sequence opens the port **twice** (open -> query FW -> close -> re-open), `HW:41-44`, `LK:286-297`.
3. `SET_VARIABLE` always carries a **4-byte value** regardless of the variable's declared width (`LK:635-637`).
4. For `count==1`, `_read()` returns a Python `int`, for `count>1` a `bytearray` (`LK:590-593`). Not a protocol matter,
   but it explains odd-looking code (`temp & 1`).
5. The AGB save type is **not** parsed from ROM strings ("SRAM_V", "FLASH1M_V" ...). It comes from: (a) the optional
   patch marker at header 0xBE/0xBF (`RFA:52-111`), (b) the `db_AGB.json` lookup keyed by header SHA-1 (`CLI:770-781`,
   `GUI:3513-3519`), (c) DACS special-case, or (d) active probing (`LK:1393-1525`). `CT.AgbSaveTypes.GetStringFromSaveLib`
   (`CT:189`) is only used for display of externally supplied strings.
6. The DMG save type is the header RAM-size byte (0x149) **used directly as a key** into `DmgSaveTypes.RAM_TYPES`
   (`CLI:1276`, `GUI:3370-3372`, `CT:238-255`) with a few mapper-specific overrides (`RFD:150-171`).
7. Per-command wire chunking is much smaller than `MAX_BUFFER_*`: DMG save read uses 0x200 per request, DMG/AGB write
   uses 256 bytes per request (`WriteRAM` default `max_length=256`, `LK:1835`), header reads use 64 bytes
   (`ReadROM` default `max_length=64`, `LK:1675`).
8. `ReadHeader` is where the voltage is (re)asserted: 5V for DMG, 3.3V for AGB (`LK:1071-1080`). The save worker does
   NOT assert DMG voltage (`LK:3363-3380`) - it relies on the earlier `SetMode`.
9. Dead code: after a DMG `_cart_write(..., sram=True)` the `DMG_WRITE_CS_PULSE=0` reset at `LK:719` is unreachable
   (early `return` at `LK:694`). So `DMG_WRITE_CS_PULSE` stays 1 after any sram-write (RTC writes, TAMA5).
10. A latent data-corruption bug exists in the backup retry logic (`LK:3672-3683`), see 8.5. Do not port it literally.
11. Real-time-clock presence for MBC3 is only "true" if a probe sees a non-0/non-FF uniform register page
    (`MAP:422-446`); a freshly reset RTC (all 0) is reported as "no RTC".

---------------------------------------------------------------------------------------------------

## 1. Transport

### 1.1 USB / serial parameters

| Item | Value | Source |
|---|---|---|
| USB VID/PID | `0x1A86` / `0x7523` (WCH CH340/CH341 USB-UART) | `HW:36` |
| Baud | 2,000,000 (`max_baud` default); `ChangeBaudRate` is a no-op for GBFlash | `HW:27,42-43,142-143` |
| Data bits / parity / stop | pyserial defaults 8 / N / 1 (never overridden: grep for `bytesize/parity/stopbits` finds nothing) | `HW:43`, `LK:289` |
| Flow control | none (pyserial defaults `rtscts=False, xonxoff=False, dsrdtr=False`) | same |
| DTR / RTS | **Never set anywhere.** pyserial asserts both DTR and RTS on open by default. Whether the board needs them is unknown (see 9.2 Q2). The first command is sent immediately after open with a 75 ms response window, so the board does not reset-and-reboot on open. | `LK:84-88` |
| Exclusive open | `exclusive=True` on the probe open only | `LK:289` |
| Read timeout | probe open: 0.1 s; FW query first byte: 0.075 s; after init `DEVICE_TIMEOUT = 1.0 s` (class default `LK:132`; GUI overrides from setting `SerialTimeout`, default "1", `GUI:1411`) | `HW:43,84,89,71` |
| Write timeout | none | - |
| Framing | none; commands are raw byte strings, each issued as ONE `write()` + `flush()` | `LK:550-551` |
| Optional per-write delay | `WRITE_DELAY` (GUI setting "WriteDelay", default disabled): sleep 1.4 ms after every write. Auto-enabled once `WRITE_ERRORS > 3`. Also forced on macOS only for GBxCart RW (not GBFlash). | `LK:446-449,503-505,556-557` |

Port selection: enumerate USB devices, keep those with VID 0x1A86 and PID 0x7523, try each in order, stop at the first
that identifies as a compatible GBFlash (`HW:31-77`). On Android use the CH34x driver of a USB-serial library and
the equivalent VID/PID permission filter.

### 1.2 Write semantics

* `_write(data, wait=False)`: `data` is either a single int (sent as 1 byte) or a byte array; one write+flush; if
  `wait` then `wait_for_ack()` (`LK:535-559`).
* Each logical command (opcode + parameters) must go out as one contiguous write. Payload data for write commands
  (`B3`, `C4`, `C6`, `C7`, `B6`) is sent as a **second** write after the opcode byte(s) (`LK:1853-1854`).

### 1.3 Read semantics (`_read(count)`, `LK:561-593`)

1. If `in_waiting > 1000` it only logs a warning.
2. `buffer = read(count)` using the current timeout (normally 1 s).
3. If short: loop up to 50 times {sleep 10 ms; stop when `in_waiting == count - len(buffer)` EXACTLY}. If the loop
   ended with `hp > 0`, read the remaining bytes. (Equality test, not `>=`: if more bytes than needed are pending the
   loop spins for 0.5 s then falls through to the error path.) Kotlin equivalent: "read until `count` bytes or a
   deadline" is functionally sufficient.
4. If still short: count a read error, then `while in_waiting > 0 { reset_input_buffer(); sleep(0.5) }`,
   `reset_output_buffer()`, **return False**.
5. Returns `buffer[0]` (int) when `count==1`, otherwise a byte array.

Callers treat a `False` as "communication error". Bulk reads (`ReadROM`/`ReadRAM`) convert a short read to an empty
buffer (`LK:1701-1703,1767`); the backup loop then shrinks the chunk size (8.5).

### 1.4 ACK / error convention

`wait_for_ack()` (`LK:461-507`): read 1 byte; success iff value in `[0x01, 0x03]`. Meaning of `0x03` vs `0x01` is not
documented anywhere in the Python (both are success). Failure cases:

| Received | Treatment |
|---|---|
| `0x02` | "device reported an error" |
| `False` (timeout) | "timeout error" |
| anything else | "communication error" |

On any failure (unless `CANCEL_ARGS["from_user"]`): set `ERROR=True`, `CANCEL=True`, `WRITE_ERRORS += 1`; if
`WRITE_ERRORS > 3` enable the 1.4 ms per-write delay; return False. `CANCEL` is polled at the top of every iteration of the
save loop, which then aborts and (cart power control present) power-cycles the cartridge (`LK:3631-3638`).

Which commands expect an ACK is listed in section 3 (many differ for fw>=12 versus older).

### 1.5 Retry / resync (`_try_write`, `LK:509-533`)

Used by `_set_fw_variable` (fw>=12 only, `LK:639-642`) and `_cart_write` (fw>=12 only, `LK:714-717`).

```
retries = 5
while retries > 0:
    ack = write(data) ; wait_for_ack()          # 1 s timeout
    if CANCEL_ARGS.from_user: return False
    if ack is not False:                         # 0x01/0x03
        ERROR = False; CANCEL = False; CANCEL_ARGS = {}
        return ack
    retries -= 1
    hp = 20; temp = 0
    while temp not in (1, 2) and hp > 0:         # resync
        reset_output_buffer(); reset_input_buffer()
        write(0x00); flush()
        temp = _read(1)                          # may time out (1 s) -> False, which also drains input
        hp -= 1
    # then loop: resend the whole command
return False
```
Interpretation (inferred): the firmware's command parser is stuck waiting for more bytes; padding with `0x00` until it
answers 0x01/0x02 re-synchronises it. The failed attempt's `ERROR/CANCEL` flags are cleared only by a later success.
Callers of `_set_fw_variable`/`_cart_write` ignore the return value; the effect of exhausting the retries is that
`CANCEL` stays True and the save loop aborts at its next check.

### 1.6 Liveness: `IsConnected`, `CheckActive`, `Close`

* `IsConnected()` (`LK:384-403`): False if no port/closed; drains the input buffer (while `in_waiting>0` {read+discard,
  reset, sleep 50 ms}); resets output buffer; then `CheckActive()`. A serial exception whose first arg starts with
  `ClearCommError failed` closes the port.
* `CheckActive()` (`LK:239-275`): returns True without I/O if the last successful check was < 1 s ago.
  1. `pcb_name is None` (old firmware) -> re-run `LoadFirmwareVersion`.
  2. else if fw>=15: send `[FE <rand u8>]`, expect 1 byte `== (~rand) & 0xFF`.
  3. else: `GET_VARIABLE CART_MODE` (`AD 01 00000000`, section 3.1) and require value `<= 2` (`mode > len(modes)` fails). For fw<10 `_get_fw_variable` returns 0
     without I/O so the check is vacuous.
  4. On failure: reset buffers, close the port, `DEVICE=None`, return False.
* `Close(cartPowerOff=False)` (`LK:405-420`): if connected: if `cartPowerOff` and cart power control: `SET_VARIABLE AUTO_POWEROFF_TIME=0`
  then `CART_PWR_OFF` (`F3`, ACK for fw>=12); else `SET_VOLTAGE_3_3V` (`A4`, ACK for fw>=12). Then close the port. The CLI uses
  `SetAutoPowerOff(0)` + `Close(cartPowerOff=True)` (`CLI:595-603`). **Do this on every app disconnect/exit** so the cart is
  unpowered and the voltage falls back to 3.3 V.

---------------------------------------------------------------------------------------------------

## 2. Handshake

### 2.1 Sequence (`HW:27-79`, `LK:286-297`)

1. Open port at 2,000,000 baud, timeout 0.1 s (probe).
2. `LoadFirmwareVersion()` (below). Close port. If it failed, try next port.
3. Re-open at 2,000,000 baud, timeout 0.1 s (keeps the FW dict from step 2).
4. Compatibility checks (`HW:59-65`): if any of these is true the device is rejected and the port closed:
   * `pcb_ver` not in `{5, 12, 13}`
   * `cfw_id != 'L'`
   * `fw_ver < 1` (DEVICE_MIN_FW)
5. Soft warning only: `fw_ts > DEVICE_LATEST_FW_TS[pcb_ver]` (all of 5,10..13 -> `1780508702`) "firmware newer than tool".
6. `MAX_BUFFER_READ=0x1000`, `MAX_BUFFER_WRITE=0x800`, `timeout = 1.0 s` (`HW:67-71`).
7. App-level policy (not in `Initialize`): `FirmwareUpdateAvailable()` (`HW:183-190`) sets `FW_UPDATE_REQ=True` when
   `pcb_ver==5 or fw_ts < 1730592000` (2024-11-03); the CLI then refuses to continue (`CLI:157-159`) and the GUI blocks
   (`GUI:1465-1492`). Port this as "unsupported firmware, update required".
8. App-level setup after connect (GUI `GUI:1398-1411`; CLI `CLI:166-167`): `SetWriteDelay(False)`, `SetAutoPowerOff(v)`,
   `SetDMGReadMethod(1)`, `SetAGBReadMethod(2)`, `SetTimeout(1.0)`. CLI uses `SetAutoPowerOff(1500)` and
   `SetAGBReadMethod(2)` only. GUI value: setting `AutoPowerOff` (menu default "350", but `SetAutoPowerOff()` reads default "0";
   inconsistent, see 9.2 Q3).

### 2.2 `LoadFirmwareVersion` (`HW:81-140`)

```
set timeout 0.075 s ; reset_input_buffer ; reset_output_buffer
write  [A1]                                   # QUERY_FW_INFO, no ACK
size = read(1) ; restore timeout 1 s
if no byte: FAIL (FW=None)
if size != 8: FAIL
info = read(8)  ->  struct ">cHBI":
    cfw_id  : 1 ASCII char       (must be 'L')
    fw_ver  : u16 BE
    pcb_ver : u8
    fw_ts   : u32 BE (unix seconds)
# defaults
pcb_name=None ; cart_power_ctrl=False ; bootloader_reset=False ; ofw_ver=None
if cfw_id=='L' and fw_ver >= 12:
    n    = read(1)                   # name length
    name = read(n)                   # UTF-8, NULs stripped, trimmed; on decode error -> "Unnamed Device"
    flags1 = read(1):  bit0 cart_power_ctrl, bit1 cart_presence_switch, bit2 cart_mode_switch
    flags2 = read(1):  bit0 bootloader_reset, bit7 unregistered (clone-detection flag)
```
* Any exception -> reset buffers, close port, FAIL.
* **Known Python quirk:** if `n == 1`, `_read(1)` returns an int and `len(name)` raises -> device would be rejected. Real
  firmware sends a longer name (e.g. "GBFlash"); Kotlin should just read `n` bytes.
* Derived values: `DEVICE_NAME = pcb_name` (if non-empty); `PCB_VERSIONS = {5:'', 12:'v1.2', 13:'v1.3'}`
  (`HW:17`) so **PCB v1.3A should report `pcb_ver == 13`** (the "A" revision letter cannot be seen by software; verify on hardware).
  `GetFullName`: pcb<13 with cart power control = "GBFlash v1.2 + PLUGIN 01"; otherwise "GBFlash v1.3".
* `fw_dt` = local time ISO string of `fw_ts` (display only).
* `CanPowerCycleCart() == FW["cart_power_ctrl"]` (`HW:168`). The v1.3 board has this built in; v1.2 needs the "plugin".
  If it is False, the code takes the "no power control" branches in `SetMode`/`ReadHeader` (4.3).

Synthetic example reply for L15 / PCB 13 / ts 0x6A20681E / name "GBFlash" / flags 0x01,0x01 (constructed from the
layout above, not captured from hardware):
`08 4C 00 0F 0D 6A 20 68 1E 07 47 42 46 6C 61 73 68 01 01`.

---------------------------------------------------------------------------------------------------

## 3. Command and variable reference

Opcodes are from `LK:31-80`. Only those used by in-scope flows are listed. "ACK>=12" = ACK expected for fw>=12 (via `wait=True`
or `_try_write`); older firmware never ACKs these. Multi-byte integers are BE unless stated.

### 3.1 Commands

| Opcode | Name | Request bytes | Response | ACK | Used by |
|---|---|---|---|---|---|
| `A1` | QUERY_FW_INFO | `A1` | see 2.2 | none | handshake |
| `A2` | SET_MODE_AGB | `A2` | - | ACK>=12 | `SetMode`, `CartPowerOn`, save worker |
| `A3` | SET_MODE_DMG | `A3` | - | ACK>=12 | same |
| `A4` | SET_VOLTAGE_3_3V | `A4` | - | ACK>=12 | `SetMode(AGB)`, `ReadHeader(AGB)`, worker(AGB), `Close` |
| `A5` | SET_VOLTAGE_5V | `A5` | - | ACK>=12 | `SetMode(DMG)`, `ReadHeader(DMG)` |
| `A6` | SET_VARIABLE | `A6 <size u8: 1/2/4> <key u32> <value u32>` (always 10 bytes) | - | `_try_write` ACK (fw>=12), none (<12) | everywhere |
| `A8` | SET_ADDR_AS_INPUTS | `A8` | - | ACK>=12 | DMG header end, DMG save cleanup, `CartPowerOff` w/o power ctrl |
| `A9` | CLK_TOGGLE | fw>=12: `A9 <num u32>`; fw<12: `A9 AA` repeated `num` times, only if power ctrl | - | ACK (fw>=12) | MBC3 RTC (`MAP:422-523`) |
| `AC` | DISABLE_PULLUPS | `AC` | - | **always** ACK (`wait=True`, only sent if fw>=8) | `ReadHeader` (`LK:1070`) |
| `AD` | GET_VARIABLE | `AD <size u8> <key u32>` | 4 bytes u32 BE | none | `CheckActive`, auto-poweroff |
| `B1` | DMG_CART_READ | `B1` | `TRANSFER_SIZE` bytes | none | DMG ROM/RAM read |
| `B2` | DMG_CART_WRITE | `B2 <addr u32> <value u8>` | - | `_try_write` ACK (fw>=12) | mapper writes (RAM enable, bank select) |
| `B3` | DMG_CART_WRITE_SRAM | `B3` then a second write of `TRANSFER_SIZE` data bytes | - | ACK after data (`wait=True`) | DMG RAM write, `_cart_write(sram=True)` |
| `B4` | DMG_MBC_RESET | `B4` | - | always ACK (`wait=True`, plain `_write`, no resync) | `ReadHeader`, worker, power-on |
| `B5` | DMG_MBC7_READ_EEPROM | `B5` | `TRANSFER_SIZE` bytes | none | MBC7 (P2) |
| `B6` | DMG_MBC7_WRITE_EEPROM | `B6`, then data | 1 byte, value ignored | not checked | MBC7 (P2) |
| `C1` | AGB_CART_READ | `C1` | `TRANSFER_SIZE` bytes | none | AGB ROM read (header) |
| `C2` | AGB_CART_WRITE | `C2 <addr>>1 u32> <value u16>` | - | `_try_write` ACK (fw>=12) | `_cart_write(0, 0xFF)`, bank switch `0x1000000` |
| `C3` | AGB_CART_READ_SRAM | `C3` | `TRANSFER_SIZE` bytes | none | SRAM/FRAM/Flash save read |
| `C4` | AGB_CART_WRITE_SRAM | `C4`, then data | - | ACK after data | SRAM write; 1-byte writes (`LK:700-705`) |
| `C5` | AGB_CART_READ_EEPROM | `C5 <t u8>`; `t`=1 (4K) / 2 (64K) | `TRANSFER_SIZE` bytes | none | EEPROM read |
| `C6` | AGB_CART_WRITE_EEPROM | `C6 <t u8>`, then data | - | ACK after data | EEPROM write |
| `C7` | AGB_CART_WRITE_FLASH_DATA | `C7 <m u8>`, then data; `m`=1 normal, 2 for Atmel `0x1F3D` | - | ACK after data | Flash save write |
| `C9` | AGB_BOOTUP_SEQUENCE | `C9` | - | ACK>=12 | AGB power-on (`LK:894,917,1080`) |
| `CA` | AGB_READ_GPIO_RTC | `CA` | 8 bytes (fw>=12) | none | AGB RTC (P2) |
| `D4` | CART_WRITE_FLASH_CMD | fw>=6: `D4 <flash u8> <n u8> n x (<addr u32> <value u16>)`; fw<6: value is u8 | 1 byte, **must equal `01`** (not `03`) | read directly, no resync | AGB flash ID/erase/bank switch; `flash`=0 for save memory |
| `F2` | CART_PWR_ON | `F2` | - | ACK (`wait=True`) | `CartPowerOn` |
| `F3` | CART_PWR_OFF | `F3` | - | ACK>=12 | `CartPowerOff`, `Close` |
| `F4` | QUERY_CART_PWR | `F4` | 1 byte: 0 = off, nonzero(1) = on | none | `CartPowerOn` |
| `F5` | SET_PIN | `F5 <mask u32> <high u8>` | - | ACK (`wait=True`; fw>=12 only, else no-op) | `SetMode` only if no cart power control (4.3) |
| `FE` | PING | `FE <rand u8>` | 1 byte `~rand & 0xFF` | none | `CheckActive`, fw>=15 only |

Not needed for MVP but referenced by flows you may later port: `A7` SET_FLASH_CMD (DACS), `AE/AF` GET/SET_VAR_STATE
(power-cycle-reconnect), `A9`, `D1`, `D2`, `D3`, `D5`, `F6` GET_SWITCH_STATE (fw>=15), `F1` BOOTLOADER_RESET.

Helper-level encodings (the Python wrappers; these are the units to implement):

| Helper | Wire sequence | Source |
|---|---|---|
| `setVar(name, v)` | `A6 <size> <key u32> <v u32>`; fw>=12 -> `_try_write` | `LK:620-642` |
| `getVar(name)` | `AD <size> <key u32>` -> u32 (returns 0 without I/O if fw<10) | `LK:595-618` |
| `dmgCartWrite(addr, v)` | `B2 <addr u32> <v u8>` (+ACK/resync fw>=12) | `LK:695-698,714-717` |
| `dmgCartWriteSram(addr, v)` | `setVar DMG_WRITE_CS_PULSE=1; setVar ADDRESS=addr; setVar TRANSFER_SIZE=1; write B3; write v (+ACK)`; **no reset of CS pulse** (dead code 719) | `LK:688-694` |
| `agbCartWrite(addr, v16)` | `C2 <addr>>1 u32> <v u16>` (+ACK/resync fw>=12) | `LK:709-717` |
| `agbCartWriteSram(addr, v)` | `setVar TRANSFER_SIZE=1; setVar ADDRESS=addr; write C4; write v (+ACK)` | `LK:700-705` |
| `cartWriteFlash(cmds, flash)` | `D4 <flash> <n> {addr(u32) val(u16)}*n`; then read 1 byte, must be `01`. For AGB with `flash=1` addr is `addr>>1`; with `flash=0` (save memory) addr is raw. | `LK:721-756` |
| `clkToggle(n)` | fw>=12: `A9 <n u32>` + ACK | `LK:758-763` |
| `dmgRomRead(addr, len, maxChunk)` | `setVar TRANSFER_SIZE=min(len,maxChunk); setVar ADDRESS=addr; setVar DMG_ACCESS_MODE=1;` then `ceil(len/maxChunk)` x {`B1`; read chunk} | `LK:1675-1708` |
| `dmgRamRead(off, len, maxChunk)` | `setVar TRANSFER_SIZE=min(len,maxChunk); setVar ADDRESS=0xA000+off; setVar DMG_ACCESS_MODE=3; setVar DMG_READ_CS_PULSE=1;` then loop {`B1`; read chunk}; finally `setVar DMG_READ_CS_PULSE=0` | `LK:1746-1775` |
| `dmgRamWrite(off, data, maxChunk=256)` | `setVar TRANSFER_SIZE=min(len,maxChunk); setVar ADDRESS=0xA000+off; setVar DMG_ACCESS_MODE=4; setVar DMG_WRITE_CS_PULSE=1;` loop {`B3`; data chunk (+ACK)}; finally `setVar ADDRESS=0; setVar DMG_WRITE_CS_PULSE=0` | `LK:1835-1863` |
| `agbRomRead(byteAddr, len, maxChunk)` | `setVar TRANSFER_SIZE; setVar ADDRESS=byteAddr>>1;` loop {`C1`; read} (ROM address in 16-bit words) | `LK:1675-1708` |
| `agbSaveRead(addr, len, cmd, maxChunk)` | `setVar TRANSFER_SIZE=min(len,maxChunk); setVar ADDRESS=addr;` loop {write `cmd` (1 or 2 bytes); read chunk} | `LK:1746-1775` |
| `agbSaveWrite(addr, data, cmd, maxChunk=256)` | `setVar TRANSFER_SIZE=min(len,maxChunk); setVar ADDRESS=addr;` loop {write `cmd`; write chunk (+ACK)} | `LK:1835-1863` |

Single-byte reads (`_cart_read(addr)` with no length, `LK:644-678`): DMG -> `dmgRomRead(addr,1)` if `addr<0xA000`
else `dmgRamRead(addr-0xA000,1)`; AGB save-memory (`agb_save_flash=True`) -> `agbSaveRead(addr,1,C3)` returning the byte;
AGB ROM (`agb_save_flash=False`) -> 2-byte read at `addr>>1` returned as u16 BE.

**Assumption (inferred, central to the whole port):** the firmware keeps `ADDRESS` as running state and advances it by the
transfer size after every `B1/C1/C3/C5/B3/C4/C6/C7` command. Python sets `ADDRESS` once and then issues the data command
`ceil(len/chunk)` times to read consecutive data (`LK:1697-1706`, `1763-1770`, `1852-1857`). The unit of `ADDRESS` is bytes for
DMG and AGB SRAM/Flash, 16-bit words for AGB ROM, and **8-byte blocks** for EEPROM (`int(pos/8)`, `LK:3662,3714`). Verify on hardware
by reading a known cart with one `ADDRESS` set and two successive `C3`/`B1` commands.

### 3.2 Firmware variables (`LK:82-110`)

`setVar`/`getVar` use `[width, key]`; `size byte = 1/2/4` for width 8/16/32. The key is sent as u32 BE; the value always as u32 BE.

| Name | Width | Key | Notes / values |
|---|---|---|---|
| ADDRESS | 32 | 0x00 | running address (see above) |
| AUTO_POWEROFF_TIME | 32 | 0x01 | unit not stated in Python; 0 = off. Values seen: 350 (GUI menu), 1500 (CLI), 5000 (during transfers, `LK:998,1324`). Likely milliseconds (inferred). |
| TRANSFER_SIZE | 16 | 0x00 | bytes per data command |
| BUFFER_SIZE | 16 | 0x01 | flash buffer / 3D memory (not used for saves) |
| DMG_ROM_BANK | 16 | 0x02 | MBC6/Xploder only |
| STATUS_REGISTER | 16 | 0x03 | unused here |
| LAST_BANK_ACCESSED | 16 | 0x04 | unused here |
| STATUS_REGISTER_MASK | 16 | 0x05 | worker sets `0x80` |
| STATUS_REGISTER_VALUE | 16 | 0x06 | worker sets `0x80` |
| CART_MODE | 8 | 0x00 | 0 = none, 1 = DMG, 2 = AGB |
| DMG_ACCESS_MODE | 8 | 0x01 | 1 = ROM read, 3 = RAM read, 4 = RAM write (2 not used by Python) |
| FLASH_COMMAND_SET / FLASH_METHOD / FLASH_WE_PIN / FLASH_PULSE_RESET / FLASH_COMMANDS_BANK_1 / FLASH_SHARP_VERIFY_SR / FLASH_DOUBLE_DIE | 8 | 0x02-0x07, 0x0A | flashcart programming; MBC6/Xploder/DACS/dev-cart paths only |
| DMG_READ_CS_PULSE | 8 | 0x08 | 1 while reading cart RAM, else 0 |
| DMG_WRITE_CS_PULSE | 8 | 0x09 | 1 while writing cart RAM (TAMA5 always 1), else 0 |
| DMG_READ_METHOD | 8 | 0x0B | 0 RD, 1 A15 (default), 2 SlowA15; fw>=12 only (`LK:302-306`) |
| AGB_READ_METHOD | 8 | 0x0C | 0 Single (class default), 1 MemCpy, 2 Stream (GUI/CLI default); fw>=12 only (`LK:308-312`) |
| CART_POWERED | 8 | 0x0D | read-only state |
| PULLUPS_ENABLED | 8 | 0x0E | only set when a flashcart profile requests WR pull-up; skip |
| AUTO_POWEROFF_ENABLED | 8 | 0x0F | 1/0 |
| AGB_IRQ_ENABLED | 8 | 0x10 | set to 0 in AGB `SetMode` (fw>=12) |
| DMG_AUDIO_ENABLED | 8 | 0x11 | fw>=14 only, dev-cart paths; not needed |

Gotcha: `_set_fw_variable`/`_get_fw_variable` resolve the key with `if key in k` (substring match on the table key) and
take the first hit in dict order (`LK:625-631`). With the current table every name used matches only itself first,
so exact lookup is equivalent. Do not copy the substring logic.

---------------------------------------------------------------------------------------------------

## 4. Mode setup and cartridge power

### 4.1 `SetMode(mode)` (`LK:958-981`) for fw>=12

DMG:
1. `A3` (ACK) 2. `A5` (ACK, 5 V) 3. `setVar DMG_READ_METHOD = self.DMG_READ_METHOD` (default 1) 4. `setVar CART_MODE = 1`
5. remember MODE="DMG" 6. `setVar ADDRESS = 0` 7. power step (4.3)

AGB:
1. `A2` (ACK) 2. `A4` (ACK, 3.3 V) 3. `setVar AGB_READ_METHOD = self.AGB_READ_METHOD` (2 after app setup, else 0)
4. `setVar CART_MODE = 2` 5. `setVar AGB_IRQ_ENABLED = 0` 6. MODE="AGB" 7. `setVar ADDRESS = 0` 8. power step.

For fw<12: no ACKs, no read-method/IRQ variables (the `SetXReadMethod` helpers return early if fw<12), `setVar` is unacknowledged.

### 4.2 Voltage safety rules (hard requirements for the Android app)

* AGB: always 3.3 V (`A4`) and **before** cart power is applied. Never send `A5` or `A3` while a GBA cart may be inserted. The Python tool
  never auto-detects the platform for this reason; the user picks it (`CLI:240-247`, `GUI:3203-3250`, which also shows a
  warning "GBA cartridges must not be inserted in Game Boy mode").
* DMG: 5 V (`A5`). `DetectFlash(limitVoltage=True)` can use 3.3 V for DMG (`LK:2379-2380`, only for flashcart detection). Not needed for saves.
* On disconnect: `CART_PWR_OFF` (power ctrl) else `A4` (`LK:405-420`).
* The firmware keeps the last voltage, so mode switching in the app must be an explicit user action followed by `SetMode`; do not
  rely on `GetMode()` (reads `CART_MODE`, throttled to once per second, `LK:936-956`).
* fw>=15 boards may report `cart_mode_switch` (flags1 bit2) and `GET_SWITCH_STATE` (`F6`, `LK:344-373`) returns bit0 = presence, bit1 = mode (1 = DMG, 0 = AGB). Optional.

### 4.3 Cart power (`CartPowerOn/Off/Cycle`, `LK:828-919`) for cart_power_ctrl == True, fw>=12

`CartPowerOn()`:
```
write F4 ; r = read(1)
if r == 0:                                      # was off
    write (A3 if MODE=="DMG" else A2) ; ACK     # mode re-asserted
    write F2 ; ACK                              # (GBxCart RW has a special path; not applicable)
    write F4 ; read(1)   # expect 1 (only logs a warning otherwise)
    if MODE == DMG: write B4 ; ACK              # DMG_MBC_RESET
    if MODE == AGB: write C9 ; ACK              # AGB_BOOTUP_SEQUENCE
# if already powered: nothing is sent
```
`CartPowerOff()`: write `F3`, ACK (fw>=12). `CartPowerCycle()` = off, then on (resets the mapper; this is also what the
tool does on abort, `LK:3637`).
When called: from `SetMode` (end), `ReadHeader` start (`LK:1066-1068`), `TransferData` start (`LK:5213-5214`), `SetProgress(INITIALIZE)` (`LK:1049`).

If cart_power_ctrl is False (older board without plugin): `SetMode` instead calls `SetPin(["PIN_AUDIO"], True)` for DMG or
`SetPin(["PIN_AUDIO"], False)` for AGB (`LK:975-981`); `CartPowerOn` only runs `C9` for AGB (`LK:914-917`); `CartPowerOff` sends `A8`
(`LK:843`). `SetPin` (fw>=12): bit index list `[CART_POWER=0, PIN_CLK=1, PIN_WR=2, PIN_RD=3, PIN_CS=4, PIN_A0..PIN_A23=5..28, PIN_CS2=29, PIN_AUDIO=30]`,
request `F5 <mask u32 = OR of 1<<idx> <high u8>` + ACK (`LK:314-342`). The v1.3 board reports power control, so this is only a fallback.

### 4.4 Auto power-off (`LK:983-1019`)

`SetAutoPowerOff(v)` (power ctrl only): `setVar AUTO_POWEROFF_ENABLED = 1 if v else 0; setVar AUTO_POWEROFF_TIME = v`; sets `SKIP_POWERCYCLE = (v == 0)`.
During every backup/restore/detect the tool wraps the work: if `AUTO_POWEROFF_ENABLED == 1`, remember `AUTO_POWEROFF_TIME`, set it to 5000, and restore it afterwards
(`LK:991-1019,3326-3331`). **Simplest compliant policy for the MVP:** call `SetAutoPowerOff(0)` after connect (what the CLI does for `interactive`/disconnect, `CLI:598,625`); no
wrap needed; remember to power the cart off on disconnect. If you enable it, implement the get/set wrap exactly.

---------------------------------------------------------------------------------------------------

## 5. Header reading and save-type detection

### 5.1 `ReadHeader` common prelude (`LK:1061-1085`), fw>=12, power ctrl

1. `IsConnected()` (drain + liveness). 2. `CartPowerOn()` (4.3). 3. `AC` (ACK; if fw>=8).
4. DMG: `A5` (ACK); `B4` (ACK); `setVar DMG_READ_CS_PULSE=0`; `setVar DMG_WRITE_CS_PULSE=0`.
   AGB: `A4` (ACK); if no power ctrl: `C9` (ACK).
5. `header = ReadROM(0, 0x180)` with the default `max_length=64`, i.e. `TRANSFER_SIZE=64`, six `B1`/`C1` commands (`LK:1085,1675-1708`).
   DMG: `setVar ADDRESS=0; setVar DMG_ACCESS_MODE=1`. AGB: `setVar ADDRESS=0`.
   The length passed must be a multiple of the chunk (otherwise Python over-reads, `LK:1679-1697`). Reading all 0x180 in one request is probably fine (max buffer 0x1000)
   but keep the 64-byte behaviour until validated.
6. On an empty/short read `RomFileDMG/AGB.GetHeader()` returns `{}` and `ReadHeader` returns False (`LK:1107`).

### 5.2 DMG header parse (`RFD:92-139`, `RFD:150-171`)

Offsets in the 0x180-byte buffer:

| Field | Offset | Rule |
|---|---|---|
| Nintendo logo | 0x104-0x133 (0x30 bytes) | `logo_correct = SHA1 == 0745fdef34132d1b3d488cfbdf0379a39fd54b4c` (`RFD:98`) |
| `empty` | | `buffer[0x104:0x134]` all equal to `buffer[0x104]` (`RFD:96`) |
| `empty_nocart` | | whole buffer is 0x00 (`RFD:97`) |
| Title | 0x134-0x142 (15 bytes) if `buffer[0x143] in (0x80,0xC0)` else 0x134-0x143 (16 bytes) | ASCII decode with replacement; strip trailing NULs; collapse runs (2+ `_` -> `_`, 2+ NUL -> NUL, 2+ whitespace -> first whitespace) via `re.sub(r"((_)_+|(\x00)\x00+|(\s)\s+)", "\2\3\4")`; NUL -> `_`; keep only `string.printable` chars (`RFD:105-111`) |
| CGB flag | 0x143 | 0x80 supported, 0xC0 required |
| Game code (CGB only) | last 4 chars of cleaned title | if `len(raw.rstrip NUL)==15`, `code[0] in "ABHKV"` and `code[3] in "ABDEFIJKPSUXY"`, then code = last 4 chars and title drops them + trailing `_` (`RFD:114-119`) |
| SGB flag | 0x146 | 0x03 supported |
| **Mapper (cart type)** | **0x147** | `mapper_raw` |
| ROM size code | 0x148 | index into `32 KiB << n` (`CT:8-9`, `RomSizes`), n=0..12 for DMG |
| **RAM size code** | **0x149** | `ram_size_raw` |
| Old licensee | 0x14B | 0x33 = new licensee at 0x144-0x145 |
| Version | 0x14C | |
| Header checksum | 0x14D | `x=0; for i in 0x134..0x14C: x = x - b[i] - 1; x &= 0xFF` (`RFD:37-44`) |
| Global checksum | 0x14E-0x14F (BE) | `sum(all 0x180 bytes with 14E/14F zeroed) & 0xFFFF` - over the 0x180 header only, so it will not equal the real ROM checksum; informational (`RFD:46-58`) |

Cart-type byte -> mapper name table `MAPPER_TYPES` (`MAP:82-121`) and mapper class table `MAPPER_MAP` (`MAP:124-147`). Relevant ids:

| `mapper_raw` | Name | Class | RAM behaviour |
|---|---|---|---|
| 0x00, 0x08, 0x09 | none / ROM+RAM / +BATTERY | base `DMG_Mapper` | generic (same as MBC5 writes) |
| 0x01, 0x02, 0x03 | MBC1 (+SRAM, +BATTERY) | `DMG_MBC1` | 6.2 |
| 0x05, 0x06 | MBC2 (+BATTERY) | `DMG_MBC2` | 6.3 |
| 0x0F, 0x10, 0x11, 0x12, 0x13 | MBC3 variants (0x0F/0x10 have RTC) | `DMG_MBC3` | 6.4 |
| 0x110 | MBC30 (synth.: `0x10` with RAM code 0x05) | `DMG_MBC3` | 6.4 |
| 0x19-0x1E | MBC5 (+RUMBLE/+SRAM/+BATTERY) | `DMG_MBC5` | 6.5 |
| 0x20 | MBC6 | `DMG_MBC6` | P2 |
| 0x22 | MBC7 | `DMG_MBC7` | P2 |
| 0xFC | Pocket Camera (MAC-GBD) | `DMG_GBD` (subclass of MBC5) | out of scope |
| 0xFD | TAMA5 | | P2 |
| 0xFE | HuC-3 | | P2 |
| 0xFF | HuC-1 | | P2 |
| 0x0B, 0x0D | MMM01 | | base RAM ops |
| 0x101, 0x103 | MBC1M (synth.) | `DMG_MBC1M` (subclass of MBC1) | same RAM ops as MBC1 |
| 0x104, 0x105, 0x201-0x206 | M161, GB-Memory, unlicensed | | out of scope |

Synthetic `mapper_raw` overrides applied by `GetHeader()` unless `unchanged=True` (`RFD:150-439`): MBC2 (0x06) forces `ram_size_raw=0x100`;
`0x10` with RAM code 0x05 -> `0x110`; MBC6 -> RAM key 0x104; MBC7 + title `KORO2 KIRBY`/`KIRBY TNT` -> RAM key 0x101, title `CMASTER` -> 0x102;
TAMA5 -> 0x103; MBC1M title/checksum list -> `+0x100`; plus GB-Memory, M161, MMM01, 256M, Wisdom Tree, Xploder, Datel, Sachen, MBCX, PHOTO (all unlicensed/out of scope; just
treat any `mapper_raw >= 0x200` or unknown as "not supported in MVP"). `IsSupportedMbc` whitelist (`LK:277-278`): 0x00 0x01 0x02 0x03 0x05 0x06 0x08 0x09 0x0B 0x0D 0x0F 0x10 0x11 0x12 0x13
0x19-0x1E 0x20 0x22 0xFC-0xFF 0x101 0x103 0x104 0x105 0x110 0x201-0x206.

Special read cases in `ReadHeader` (DMG): if logo invalid and no "Future Console Design" string, the header is re-read with length 0x280 and
re-parsed (`LK:1096-1101`); "TETRIS" title with unexpected SHA-1 (Sachen) also triggers a 0x280 re-read (`LK:1093-1095`); `mapper_raw == 0x203` Xploder special (`LK:1102-1106`). Ignore for MVP, but keep the "logo invalid -> re-read once" behaviour.
Finally (`LK:1269-1270`): `A8` (ACK).

### 5.3 DMG save type and size

Authoritative rule used by both front ends (`CLI:1257-1278`, `GUI:3370-3372`):

```
if mapper_raw == 0x06            -> save_type 0x100  (MBC2, 0x200 bytes)
elif mapper_raw == 0x22 and title in {"KORO2 KIRBY", "KIRBY TNT"} -> 0x101 (MBC7 2K EEPROM, 0x100 bytes)
elif mapper_raw == 0x22 and title == "CMASTER"                    -> 0x102 (MBC7 4K EEPROM, 0x200 bytes)
elif mapper_raw == 0xFD          -> 0x103 (TAMA5, 0x20 bytes)
elif mapper_raw == 0x20          -> 0x104 (MBC6, 0x108000 bytes)
else                             -> save_type = header[0x149]   (0..5)
save_type == 0 -> "no save / cannot auto-detect": ask the user
```
(`RFD` already applies these overrides into `ram_size_raw`, so `save_type = ram_size_raw` suffices.) For `mapper_raw == 0` the CLI substitutes MBC5 for the *mapper*
(`CLI:1240-1241`); behaviour is identical because the RAM-enable and bank writes of the base class and MBC5 are the same.
MBC2 with cart type 0x05 (no battery) keeps RAM code 0 -> no save; correct.

`DmgSaveTypes.RAM_TYPES` (`CT:239-255`); `save_type` is the first column:

| save_type | Size (bytes) | Label | Notes |
|---|---|---|---|
| 0x00 | 0 | None | |
| 0x01 | 0x800 | 16K SRAM (2 KiB) | header RAM code 1 |
| 0x02 | 0x2000 | 64K SRAM (8 KiB) | code 2 |
| 0x03 | 0x8000 | 256K SRAM (32 KiB) | code 3 |
| 0x05 | 0x10000 | 512K SRAM (64 KiB) | code 5 (MBC30) |
| 0x04 | 0x20000 | 1M SRAM (128 KiB) | code 4 |
| 0x100 | 0x200 | 4K SRAM (512 x 4 bit) | MBC2 |
| 0x101 | 0x100 | MBC7 2K EEPROM | P2 |
| 0x102 | 0x200 | MBC7 4K EEPROM | P2 |
| 0x103 | 0x20 | TAMA5 | P2 |
| 0x104 | 0x108000 | MBC6 SRAM+FLASH | P2 |
| 0x201 / 0x203 / 0x204 / 0x205 | | unlicensed | out of scope |

(RAM size code 0x01 means 2 KiB in the header spec and 0x800 here; code 0x05 = 64 KiB sits between 0x03 and 0x04 in the table order.)

Optional active size probe (used by "Detect Cartridge" for odd carts, `LK:1372-1450`): run a backup with `save_type=0x04` (128 KiB, 16 banks of 8 KiB), compute
`_find_repeating_size(data)` (below), map size -> save_type through the table (first matching row), then MBC30 heuristics: if size > 0x10000 and mbc != MBC7, treat as 32 KiB MBC3+RTC
if bytes `0x8000..0xFFFF` look like RTC mirrors (every 0x40th 3-byte group constant) or equal the first half, else as 64 KiB if `0x1A000..0x1FFFF` look constant. Not required if header RAM code is valid.

```
find_repeating_size(data, max_size = len(data), min_size = 0x20):
    offset = max_size
    while offset >= min_size:
        offset = offset // 2
        if data[0:offset] != data[offset:2*offset]:
            offset = offset * 2 ; break
    return offset                      # smallest power-of-two prefix that tiles the buffer (min result 0x10)
```
(`LK:2748-2756`)

### 5.4 DMG RTC presence (affects the backup "include RTC" option)

`mapper_raw` in `{0x0F, 0x10, 0x110}` (and 0x206 MBCX) can have an RTC; `{0xFD}` TAMA5 and `{0xFE}` HuC-3 always return `HasRTC=True` (`MAP:1055,1278`).
For MBC3 the tool probes (`MAP:422-446`, run inside `ReadHeader` only when `logo_correct`, `LK:1112-1125`):
```
enableRAM(false); enableRAM(true); latch()            # 4.x below
for i in 8..12:
    clkToggle(60) ; dmgCartWrite(0x4000, i)
    data = dmgRamRead(0x880, 0x100)                   # 256 bytes at 0xA880
    if len(data)==0: return false
    if data[0] in (0x00, 0xFF): continue
    found = true
    if data is not 256 x data[0]: found=false; break
enableRAM(false); dmgCartWrite(0x4000, 0)
HasRTC = found
```
For the MVP either replicate this or treat header-declared RTC ids 0x0F/0x10/0x110 as "has RTC" and let the user choose. Note GUI asks the user whether to store RTC (`GUI:2350-2359`);
the CLI uses a flag (`--store-rtc`). Default behaviour is "ask".

### 5.5 AGB header parse (`RFA:185-270`)

| Field | Offset | Rule |
|---|---|---|
| Logo | 0x04-0x9F (0x9C bytes) | `logo_correct = SHA1 == 17daa0fec02fc33c0f6abb549a8b80b6613b48ee` (`RFA:202`) |
| `empty` | | `buffer[0x04:0xA0]` all equal to `buffer[0x04]` or `empty_nocart` (`RFA:200`) |
| `empty_nocart` | | SHA-1 of `buffer[0:0x180]` is one of `4fe93eeebc5593fe2e231a3986ce86c95c1100dd`, `a503a1b5f5ddbefc87c79b1359f7e1a5cfe0ac9f`, `4686e381b24a2db07de83d452fa31e8a044b3a50`; or SHA-1 of `buffer[0x10:0x50]` is `2bdc7def6c481fbfeeb880b1d0fdf6575d6a39be` or `09b90e535e8550f890a4f477137e4559a5c0a445` (`RFA:189-198`) - open-bus patterns for "no cartridge" |
| Title | 0xA0-0xAB (12 bytes) | same cleanup as DMG; `\n` removed |
| Game code | 0xAC-0xAF (4 bytes) | NUL-trimmed, printable only |
| Maker | 0xB0-0xB1 | |
| Fixed 0x96 | 0xB2 | `96h_correct` |
| Version | 0xBC | |
| Header checksum | 0xBD | `x = -(sum(b[0xA0..0xBC])) - 0x19 & 0xFF` (`RFA:36-43`) |
| Patch marker | 0xBE = 0x4C ('L'), flags at 0xBF | decoded by `GetSaveTypeFromHeaderMarker` (below) |
| Header SHA-1 (DB key) | | `SHA1(buffer[0:0x100])` if `len(game_code)==4 and game_code[0]=='M'` else `SHA1(buffer[0:0x180])`; lowercase hex (`RFA:227-230`) |
| Vast Fame | 0x15C-0x16B | equals `B4 00 9F E5 99 10 A0 E3 00 10 C0 E5 AC 00 9F E5` (unlicensed protected carts; out of scope) |
| DACS 8M | | title `NGC-HIKARU3`, code `GHTJ`, header checksum 0xB3 -> save_type 6 (out of scope) |
| e-Reader | | title `CARDE READER`/code `PEAJ`/cs 0x9E, `CARDEREADER+`/`PSAJ`/0x85, `CARDE READER`/`PSAE`/0x95 (special calibration handling; out of scope) |

If the logo is wrong, `ReadHeader` writes `agbCartWrite(0, 0xFF)` and re-reads once (`LK:1172-1175`). ROM size detection (`LK:1192-1213`, mirror search from 0x10000 doubling at +0xA0 for 16 bytes) and the
`AGBFLASHDACS` check at `0x1FFE000` are not needed for saves.

`GetSaveTypeFromHeaderMarker(buffer)` (`RFA:52-111`): requires `buffer[0xBE]==0x4C`. `f = buffer[0xBF]`; size code `f & 7`: 0->0, 1->512, 2->8192, 3->32768, 4->65536, 5->131072 (6,7 invalid -> None);
medium `(f>>3)&3`; batteryless `f & 0x20`; bits 0x40/0x80 (waitstate / fake RTC) ignored.

| medium | result |
|---|---|
| 0 | `(0,0)` only if size==0 and not batteryless, else None |
| 1 SRAM | batteryless -> `(9, size)` (size must be nonzero); size<=32768 -> `(3, size)`; 65536 -> `(7, size)`; 131072 -> `(8, size)` |
| 2 EEPROM | (not batteryless) size 0 -> `(1, 0)` (4K/64K unresolved), 512 -> `(1,512)`, 8192 -> `(2,8192)` |
| 3 FLASH | (not batteryless) 131072 -> `(5, size)`, otherwise `(4, size)` |

This marker is only produced by Lesserkuma's patchers; real games do not have it.

### 5.6 AGB save-type lookup tables

`AgbSaveTypes.SAVE_TYPES` (`CT:97-108`). The index is the `save_type` argument everywhere:

| idx | Size (bytes) | Name | Read cmd | Write cmd | Chunk (backup read / restore write) |
|---|---|---|---|---|---|
| 0 | 0 | none | | | |
| 1 | 512 | 4K EEPROM | `C5 01` | `C6 01` | 0x100 / 0x40 |
| 2 | 8192 | 64K EEPROM | `C5 02` | `C6 02` | 0x100 / 0x40 |
| 3 | 32768 | 256K SRAM/FRAM | `C3` | `C4` | 0x2000 / 0x2000 (wire 0x1000 reads, 256 writes) |
| 4 | 65536 | 512K FLASH | `C3` | `C7 01` (`C7 02` if chip `0x1F3D`) | 0x1000 (chip dependent) |
| 5 | 131072 | 1M FLASH | `C3` | `C7 01` | 0x1000 (chip dependent) |
| 6 | 1048576 | 8M DACS | (ROM-mapped) | (flash cmds) | out of scope |
| 7 | 65536 | Unlicensed 512K SRAM | `C3` | `C4` | 0x2000 |
| 8 | 131072 | Unlicensed 1M SRAM | `C3` | `C4` | 0x2000, 2 banks |
| 9 | 0 | Unlicensed batteryless SRAM | | | out of scope (save lives in ROM flash) |

(from the `commands` table at `LK:3521-3532`.)

Flash save chip IDs `AGB_FLASH_SAVE_CHIPS` (`CT:124-135`), key = `(manufacturer<<8)|device` read by `ReadFlashSaveID`:

| ID | Chip | Size |
|---|---|---|
| 0xBFD4 | SST 39VF512 | 0x10000 |
| 0x1F3D | Atmel AT29LV512 | 0x10000 |
| 0xC21C | Macronix MX29L512 | 0x10000 |
| 0x321B | Panasonic MN63F805MNP | 0x10000 |
| 0xC209 | Macronix MX29L010 | 0x20000 |
| 0x6213 | SANYO LE26FV10N1TS | 0x20000 |
| 0xBF4B | Unlicensed SST25VF064C | 0x20000 (bootleg) |
| 0xBF5B | Unlicensed SST49LF080A | 0x20000 (bootleg) |
| 0xBF6D | Unlicensed SST39VF6401B | 0x20000 (bootleg) |
| 0xFFFF | Unlicensed 0xFFFF | 0x20000 (bootleg) |

### 5.7 `ReadFlashSaveID` (`LK:1616-1673`) - SRAM-vs-Flash probe and chip ID

All reads use `agbSaveRead(...)` (`C3`), all writes `cartWriteFlash(cmds, flash=0)` (`D4`, save-memory addressing, raw addresses).
```
d = agbSaveRead(0, 0x10)                 ; if short(<5): return FAIL
t1 = d[4]
cartWriteFlash([[0x0004, t1 ^ 0xFF]])
d = agbSaveRead(0, 0x10)                 ; t2 = d[4]
cartWriteFlash([[0x0004, t1]])           # restore
if t1 != t2: return FAIL                 # byte changed -> it is SRAM/FRAM, not Flash
b5555 = read1(0x5555) ; b2AAA = read1(0x2AAA) ; b0000 = read1(0x0000)   # saved for restore
cartWriteFlash([[0x5555,0xAA],[0x2AAA,0x55],[0x5555,0x90]])               # enter ID mode
chip = u16BE(agbSaveRead(0, 2))          # byte0 = manufacturer, byte1 = device
cartWriteFlash([[0x5555,0xAA],[0x2AAA,0x55],[0x5555,0xF0]])               # exit ID mode
sleep 10 ms ; cartWriteFlash([[0x0000, 0xF0]]) ; sleep 10 ms
if chip not in table: cartWriteFlash([[0x5555,b5555],[0x2AAA,b2AAA],[0x0000,b0000]])  # undo accidental SRAM writes
return (chip, name)
```
`FAIL` (False) for fewer than 2 bytes etc. In the save worker a FAIL for a Flash save type aborts with "Couldn't detect the save data flash chip type" (`LK:3464-3468`).
Note: this probe temporarily modifies SRAM byte 4 (and possibly 3 other bytes if the chip is unknown) and restores them; a power loss mid-probe could corrupt an SRAM save.

### 5.8 AGB save-type decision procedure (what to implement)

1. Header marker (5.5). If present, use `(save_type, save_size)`.
2. Else DB: look up the SHA-1 key in a bundled `db_AGB.json` (700 KB, 2923 entries; fields `st` = save type index above, `ss` = size, `rs` ROM size, `gn` name, `gc` code, `3d` flag; distribution of `st`: 1:1030, 0:736, 3:515, 2:409, 4:166, 5:66, 6:1). `CLI:770-781`, `GUI:3513-3519`. Recommend shipping a reduced `sha1 -> (st, ss, name)` asset.
   `st==0` means "no save" (736 entries). `3d==true` entries (GBA Video, 64 MiB) are out of scope.
3. Else (not in DB; optional "Detect save type", `LK:1393-1525`), run active probing in this order:
   a. Do a throw-away backup with `save_type=8` (128 KiB SRAM read incl. bank switch write `0x1000000`), `detect=true`, then `save_size = find_repeating_size(data)`.
   b. `ReadFlashSaveID()`; if it returns a **known** chip: `save_size = chip size`, `save_chip = name`; for bootleg IDs `0xBF4B, 0xBF5B, 0xFFFF, 0xBF6D`: if the 128 KiB probe's first 0x20000 are all 0xFF -> type 5, elif first 64 KiB equals second 64 KiB -> type 4, else type 5; for others type 5 if size 0x20000 else type 4.
      Unknown nonzero chip ID -> type 0, "Unknown FLASH save chip".
   c. If still undecided (no flash): SRAM if `save_size > 256`: 131072 -> 8; 65536 -> 7; 32768 -> 3; else if size is one of the table sizes -> that index, else none. (DACS special: if `dacs_8m` -> 6.)
   d. If `save_size <= 256`: EEPROM test: backup with type 1 -> `eeprom_4k`; backup with type 2 -> `eeprom_64k`. If `eeprom_64k` is all 0xFF or all 0x00 -> no save. Elif `eeprom_4k == eeprom_64k[:len(eeprom_4k)]` -> type 2 (8192). Else type 1 (512).
   e. 3D-memory carts skip all of this.
   Mark the result as "guessed"; always let the user confirm before a restore.

---------------------------------------------------------------------------------------------------

## 6. Save backup and restore algorithms

Both directions run through one function, `_BackupRestoreRAM_Worker` (`LK:3333-3993`), parameterised by `mode` (2 = backup, 3 = restore).
Arguments used by the front ends: `mbc` (mapper id), `save_type`, `rtc` (bool), `rtc_advance` (bool), `verify_read` (bool, GUI default True), `verify_write` (bool, default True),
`erase` (bool), `path`/`buffer`, `cart_type` (flashcart profile, `0` = generic -> ignored) (`GUI:2396,2862`, `CLI:1394-1403`).

### 6.1 Common skeleton

All steps assume fw>=12 with cart power control, `cart_type` None, mode already set.

| # | Step | Source |
|---|---|---|
| 0 | `TransferData`: clear `ERROR/CANCEL/CANCEL_ARGS`, `READ_ERRORS=WRITE_ERRORS=0`, `IsConnected()`, `CartPowerOn()` | `LK:5205-5214` |
| 1 | (optional, auto-poweroff wrap, 4.4) | `LK:3326-3331` |
| 2 | `setVar STATUS_REGISTER_MASK=0x80`; `setVar STATUS_REGISTER_VALUE=0x80` | `LK:3360-3361` |
| 3 | mode-specific prelude (6.1.1 / 6.1.2) | |
| 4 | Restore only: build `buffer` (6.7) | `LK:3544-3574` |
| 5 | per-bank / per-chunk loop | `LK:3581-3820` |
| 6 | backup: optional RTC footer, write file; restore: optional RTC write | `LK:3822-3905` |
| 7 | restore: optional write verification (6.8) | `LK:3906-3971` |
| 8 | cleanup (DMG: below; AGB: none) | `LK:3973-3993` |
| 9 | (restore auto-poweroff value) | |

On `CANCEL` at any loop top: report abort, `CartPowerCycle()` if power ctrl, return (`LK:3631-3638`). Also do this on every error path.

#### 6.1.1 DMG prelude (`LK:3363-3444`)

1. `B4` (ACK) - "fixes Taobao FRAM cart save data".
2. Reject if `mbc` not in the whitelist (5.2).
3. `save_size = RAM_TYPES size of save_type` (unless caller overrides). `ram_banks = ceil(save_size / 0x2000)` (MBC6 overrides, P2). `buffer_len = min(0x200, RAM_BANK_SIZE)` = 0x200 (MBC7: `save_size`; TAMA5: 0x20).
4. `A3` (ACK). `setVar DMG_WRITE_CS_PULSE=0`; `setVar DMG_READ_CS_PULSE=0`. (No voltage command.)
5. `EnableMapper()`: no-op for all non-TAMA5 mappers.
6. `extra_size = rtc footer size` if `rtc` (0x30 MBC3/MBC30, 0x0C HuC-3, 0x28 TAMA5).
7. Dev-cart probe (retail carts: no effect): `dmgCartWrite(0x2000,0x00); dmgCartWrite(0x4000,0x90); id = dmgRomRead(0x4000,2); dmgCartWrite(0x4000,0xF0); dmgCartWrite(0x4000,0xFF); dmgCartWrite(0x2000,0x01)`; if `id in {B0 88, B0 B0}` -> set audio-low mode (dev cart; skip). Finally `dmgCartWrite(0x4000,0x00)`. Safe to omit for retail carts; it only touches bank registers and happens while RAM is still disabled.
8. `mapper.EnableRAM(true)` (per mapper below).

#### 6.1.2 AGB prelude (`LK:3446-3535`)

1. `A2` (ACK); `A4` (ACK). `buffer_len = 0x2000`. `save_size` from table. `ram_banks = ceil(save_size / 0x10000)`. `agb_flash_chip = 0`.
2. type-specific: EEPROM (1,2): `buffer_len = 0x40` (restore) or `0x100` (backup). Flash (4,5): `empty_data_byte = 0xFF`; `ReadFlashSaveID()` (5.7), abort if FAIL;
   `buffer_len = 0x1000`; `0x800` for chips `0xBF4B/0xBF5B/0xFFFF`; `0x8000` for `0xBF6D`.
3. `extra_size = 0x10` if `rtc`.
4. Select `command` pair from the table in 5.6 (`commands[save_type][mode-2]`); the write command for type 4 is `C7 02` iff `agb_flash_chip == 0x1F3D`.

#### 6.1.3 Chunk loop (backup, `LK:3640-3698`)

```
pos = start_address
while pos < end_address:
    if CANCEL: abort
    data = read one chunk of buffer_len bytes (per-type read, below)   [x2 when verify_read; compare; mismatch => abort]
    if len(data) != buffer_len: (see 8.5)  -> shrink max_length, retry same pos
    out += data ; pos += buffer_len
```
`max_length` (maximum bytes per wire request) = `MAX_BUFFER_READ` = 0x1000 for GBFlash (`LK:3582`). Default start is 0x1000; the retry logic halves it down to 64.

`verify_read` (GUI default "VerifyData=enabled"): each chunk is read twice **including the full ReadRAM setup both times** and the two buffers must be identical, else abort with "Inconsistent reads" (`LK:3642-3694`). CLI does not pass `verify_read`, so it reads once.

#### 6.1.4 Chunk loop (restore, `LK:3700-3817`)

per-type write of `buffer[buffer_offset : buffer_offset + buffer_len]`, then `pos += buffer_len; buffer_offset += buffer_len`.

#### 6.1.5 DMG cleanup (`LK:3973-3980`)

`mapper.SelectBankRAM(0)`; `mapper.EnableRAM(false)`; `setVar DMG_READ_CS_PULSE=0`; `A8` (ACK) ("prevent hotplugging corruption").
Note: there is no explicit `DMG_WRITE_CS_PULSE=0` here (restore chunks already leave it 0 via `dmgRamWrite`; RTC sram-writes leave it 1 - harmless in the Python flow).

### 6.2 DMG MBC1 (ids 0x01/0x02/0x03, also MBC1M) - `MAP:375-406`

* `EnableRAM(true)`: `dmgCartWrite(0x6000, 0x01)` (banking mode 1), `dmgCartWrite(0x0000, 0x0A)`.
* `EnableRAM(false)`: `dmgCartWrite(0x0000, 0x00)`, `dmgCartWrite(0x6000, 0x00)`.
* `SelectBankRAM(i)`: `dmgCartWrite(0x4000, i & 0xFF)`; returns `(start=0, size=0x2000)`.
* Loop: `for bank in 0 until ram_banks`: `setVar DMG_WRITE_CS_PULSE = 0`; `SelectBankRAM(bank)`; `end = min(save_size, 0x2000)`; chunks of 0x200 at `pos = 0, 0x200, ..., end-0x200`.
* Backup chunk: `dmgRamRead(pos, 0x200, maxChunk=0x1000)` -> `TRANSFER_SIZE=0x200, ADDRESS=0xA000+pos, DMG_ACCESS_MODE=3, DMG_READ_CS_PULSE=1`, `B1`, read 0x200, `DMG_READ_CS_PULSE=0`.
* Restore chunk: `dmgRamWrite(pos, 512 bytes)` = two requests of 256 (see 3.1). After each: `ADDRESS=0; DMG_WRITE_CS_PULSE=0`.
* Typical sizes: 8 KiB (1 bank), 32 KiB (4 banks).

### 6.3 DMG MBC2 (ids 0x05/0x06; save_type 0x100, 0x200 bytes) - `MAP:408-416`, `LK:3665-3668,3864-3866,3923-3926`

* `EnableRAM(true)`: base `dmgCartWrite(0x0000, 0x0A)`; `false`: `dmgCartWrite(0x0000, 0x00)`.
* `SelectBankRAM(i)` writes nothing; returns `(0, 0x2000)`. `ram_banks = 1`; `end = 0x200`; single chunk `pos=0`.
* Backup: `dmgRamRead(0, 0x200)`, then **`byte &= 0x0F` for every byte** (upper nibble is open bus); the same mask is applied again over the whole buffer before the file is written.
* Restore: bytes are written as stored in the file (the cart only latches the low nibble). Verification masks both sides with 0x0F.
* .sav files from other emulators are also 512 bytes with one nibble per byte. If the file is shorter than 0x200 it is tiled (6.7).

### 6.4 DMG MBC3 / MBC30 (ids 0x0F-0x13, 0x110) - `MAP:418-625`

* RAM: `EnableRAM`, `SelectBankRAM` are the base implementations (`0x0000=0x0A/0x00`, `0x4000=bank`). 4 banks for 32 KiB (save_type 0x03), 8 banks for MBC30 64 KiB (save_type 0x05, `mapper_raw` 0x110). Chunk loop as 6.2.
* RTC registers are selected by writing `0x08..0x0C` to `0x4000` (S, M, H, DL, DH) and accessed at `0xA000`.
* **Backup RTC (`rtc == true`, after the RAM loop, `LK:3828-3832`, `MAP:451-478`):**
  ```
  latch():  clkToggle(60); dmgCartWrite(0x0000,0x0A); clkToggle(60); dmgCartWrite(0x6000,0x00); clkToggle(60); dmgCartWrite(0x6000,0x01)
  readRTC(): enableRAM(true)
             for i in 8..12: clkToggle(60); dmgCartWrite(0x4000,i); v = read1(0xA000)  # dmgRamRead(0,1)
                             footer += u32LE(v)
             footer += footer          # the 20 bytes are appended again
             footer += u64LE(unix_time_now)
             enableRAM(false); dmgCartWrite(0x4000, 0)
  ```
* **Restore RTC (`rtc == true`, after the RAM loop, `LK:3885-3891`, `MAP:526-583`):** `WriteRTC(buffer[-0x30:], advance)`:
  1. `enableRAM(true)`; `s=buf[0x00]; m=buf[0x04]; h=buf[0x08]; days=(buf[0x0C] | buf[0x10]<<8) & 0x1FF` (the carry bit 0x80 of DH is read but not written back).
  2. If `advance` (GUI default checked "Adjust RTC", `GUI:2618-2632`; CLI never advances): add the time elapsed since the footer's u64 timestamp (if the timestamp is in the past). Python's
     arithmetic: time-of-day = `(h:m:s + calendar-delta h:m:s)`, `days += (date(now) - date(then)).days`, if `days >= 512` then carry=1 and `days %= 512` (`MAP:535-575`). A footer of all zero bytes means "reset to 0d 00:00:00".
     Kotlin may instead use `total = days*86400 + h*3600 + m*60 + s + (now - then)`; this differs from Python only in the day count at the wall-clock midnight boundary.
  3. `WriteRTCDict` (`MAP:480-524`), `n = 50` clock toggles each step:
     ```
     enableRAM(true)
     clkToggle(50); dmgCartWrite(0x0000,0x0A); clkToggle(50); dmgCartWrite(0x6000,0x00); clkToggle(50); dmgCartWrite(0x6000,0x01)   # unlock+latch
     clkToggle(50); dmgCartWrite(0x4000,0x0C); clkToggle(50); dmgCartWrite_sram(0xA000, 0x40)                                       # halt
     for i in 8..12: clkToggle(50); dmgCartWrite(0x4000,i); clkToggle(50); dmgCartWrite_sram(0xA000, regs[i-8])   # regs = [s%60, m%60, h%24, days&0xFF, (days>>8)&1]
     clkToggle(50); dmgCartWrite(0x6000,0x00); clkToggle(50); dmgCartWrite(0x6000,0x01)                                             # latch
     dmgCartWrite(0x4000,0); enableRAM(false)
     ```
     (The DH write of `days>>8 & 1` clears the halt bit; the final `EnableRAM(false)` in `WriteRTC` is a second disable.)
* The user's RTC option is only offered if `has_rtc` (5.4); restoring a file lacking the 0x30 footer with `rtc=true` would misuse the last 48 data bytes - the GUI guards with `save_size_includes_rtc` (`MAP:52-64`): `(expected_size + 0x30) % file_size == 0`.

### 6.5 DMG MBC5 and no-MBC RAM (ids 0x19-0x1E, 0x00/0x08/0x09, also unknown ids using the base class) - `MAP:295-326,627-651`

* `EnableRAM(true)`: `dmgCartWrite(0x0000, 0x0A)`; `false`: `0x0000 = 0x00`.
* `SelectBankRAM(i)`: `dmgCartWrite(0x4000, i & 0xFF)`. Rumble carts (0x1C-0x1E) use bit 3 for the motor; RAM sizes (<= 128 KiB = 16 banks) never need it set by the tool, and the tool never sets it.
* Loop as 6.2. Typical: 8 KiB (1 bank), 32 KiB (4), 128 KiB (16 banks).

### 6.6 AGB algorithms

Common: `start_address=0`, `bank_size=0x10000`, `end_address = min(save_size, 0x10000)` per bank (`LK:3602-3609`). `ram_banks = ceil(save_size/0x10000)`.
Bank switch (only if `save_size > 0x10000`, i.e. 1M Flash / 1M SRAM) at the top of each bank iteration, followed by `sleep(0.05)` (`LK:3611-3626`):

| Condition | Bank switch for bank `b` |
|---|---|
| `save_type == 8` or chip in `{0xBF4B,0xBF5B,0xFFFF,0xBF6D}` | `agbCartWrite(0x1000000, b)` i.e. `C2 00 80 00 00 <b as u16>` |
| `save_type == 5` | `cartWriteFlash([[0x5555,0xAA],[0x2AAA,0x55],[0x5555,0xB0],[0x0000,b]], flash=0)` |
| otherwise | none ("unknown bank switching method" log) |

No explicit switch back to bank 0 is performed afterwards for Flash (the next operation re-selects).

**SRAM / FRAM (types 3, 7, 8)** - `buffer_len=0x2000`:
* Backup chunk: `agbSaveRead(pos, 0x2000, cmd=C3, maxChunk=0x1000)` -> `TRANSFER_SIZE=0x1000, ADDRESS=pos`, then two (`C3`, read 0x1000).
* Restore chunk: `agbSaveWrite(pos, 0x2000 bytes, cmd=C4)` -> `TRANSFER_SIZE=256, ADDRESS=pos`, 32 x (`C4`, 256 bytes + ACK). No erase, no pre-read.
* Type 3 = one bank 0x8000; type 7 = one bank 0x10000; type 8 = two banks of 0x10000 with the `0x1000000` bank-switch write.

**EEPROM (types 1, 2)** - addresses are in 8-byte blocks:
* Backup: for `pos` in 0 until save_size step 0x100: `agbSaveRead(addr=pos/8, 0x100, cmd=[C5, t], maxChunk=0x1000)` -> `TRANSFER_SIZE=0x100, ADDRESS=pos/8`; write the 2-byte command `C5 t`, read 0x100.
* Restore: for `pos` step 0x40: `agbSaveWrite(addr=pos/8, 0x40 bytes, cmd=[C6, t])` -> `TRANSFER_SIZE=0x40, ADDRESS=pos/8`; write `C6 t`, write 0x40 bytes, ACK. 512 B = 8 requests, 8 KiB = 128 requests.
* No explicit erase; the firmware handles the serial EEPROM protocol and write cycles (the ACK arrives when complete, inferred).
* 4K (512 B) vs 64K (8 KiB) must be known beforehand (DB or probe 5.8d); wrong type gives garbage/overwrites.
* Byte order inside each 8-byte block is whatever the firmware returns; the tool stores it unmodified (see 9.2 Q6).

**FLASH (types 4, 5)** - after `ReadFlashSaveID` (6.1.2):
* Backup chunk (`buffer_len` = 0x1000 default; 0x800 for chips `0xBF4B/0xBF5B/0xFFFF`; 0x8000 for `0xBF6D`): `agbSaveRead(pos, buffer_len, cmd=C3, maxChunk=0x1000)` (0x8000 -> 8 wire requests).
* Restore chunk, chip != `0x1F3D` (`LK:3719-3751`):
  1. `sector_address = pos % 0x10000`.
  2. Erase sector: `cartWriteFlash([[0x5555,0xAA],[0x2AAA,0x55],[0x5555,0x80],[0x5555,0xAA],[0x2AAA,0x55],[sector_address,0x30]], flash=0)`.
  3. Poll up to 50 times: `sleep(10 ms)`; `sr = u16BE(agbSaveRead(sector_address, 2))`; stop when `sr == 0xFFFF`. On timeout the tool prints an error but **continues** (no abort).
  4. If the chunk is not all `0xFF`: `agbSaveWrite(pos, chunk, cmd=[C7, 01])` (`TRANSFER_SIZE=256`, `ADDRESS=pos`, one `C7 01` + 256 bytes + ACK per 256 bytes). (e-Reader sector 0xF000 special-case skipped.) The firmware programs the bytes (AA/55/A0 per byte, inferred). Writing all-0xFF chunks is skipped because the erase already did it.
* Restore chunk, Atmel `0x1F3D` (`LK:3717-3718`): no erase; `agbSaveWrite(addr=pos/128, chunk, cmd=[C7, 02])` (128-byte page programming, handled by firmware; `TRANSFER_SIZE=256`).
* Sector size used: 0x1000 (SST, Macronix, Panasonic, Sanyo). The buffer_len for bootleg chips is 0x800/0x8000 as above, but the erase step is still a single `0x30` command at `sector_address` per chunk (as written in the Python). Whether that covers a whole 0x8000 chunk on `0xBF6D` is unverified - treat bootleg flash chips as best effort / out of MVP.
* Types: 4 = 64 KiB single bank; 5 = 128 KiB, banks 0 and 1 selected through the AA/55/B0 sequence with `[0, bank]` before each 64 KiB half.

**Cleanup:** none for stock carts (the Python cleanup block is DMG-only, plus an optional bootleg-mapper restore for flashcart profiles).

### 6.7 Restore buffer preparation (`LK:3544-3574`)

* `erase=True`: `buffer = [empty_data_byte] * save_size` (`0x00`; `0xFF` for Flash and MBC6/Xploder).
* else: `buffer = file bytes` (GUI rejects `size == 0` or `> 0x200000`, `GUI:2519-2523`).
* "Fill too small file": `while len(buffer) < save_size: buffer += buffer` (tiling by doubling; skipped for DACS). The buffer may therefore end up longer than `save_size`; only the first `save_size` bytes are written (plus the footer for RTC).
* A file longer than `save_size` is written truncated (extra bytes = RTC footer when `rtc`).
* e-Reader/Pocket Camera calibration preservation: out of scope.

### 6.8 Write verification (`LK:3906-3971`) - default ON (GUI `VerifyData` default enabled; CLI on unless `--no-verify-write`)

1. DMG: `mapper.SelectBankRAM(0)`.
2. `ReadROM(0, 4)` dummy read (resets address state).
3. Run a full **backup** (mode 2) of the same size into memory with `verify_write` set (no `verify_read`, no file, RTC not read, the nested run returns right after producing data without its own cleanup) - this repeats the entire prelude (`B4`, `A3`, ... `EnableRAM(true)`).
4. For MBC2 mask both sides with 0x0F.
5. Compare `read[:end]` vs `buffer[:end]`, `end = buffer_offset` (bytes actually written). Mismatch -> error "written completely, but N bytes didn't pass verification" with the first 10 offsets (abort after 10 s of reporting).
6. Then the normal cleanup (6.1.5).

---------------------------------------------------------------------------------------------------

### 6.9 Priority 2 summaries (not for MVP)

| Mapper | Summary | Source |
|---|---|---|
| MBC7 (0x22) | `EnableRAM`: `0x0000=0x0A, 0x4000=0x40` (and disable `0x00` + `0x4000=0x40`). Save is a serial EEPROM: 0x100 B (Kirby Tilt'n'Tumble `0x101`) or 0x200 B (`0x102`). `buffer_len = save_size`, single chunk. Read: `setVar TRANSFER_SIZE=32; ADDRESS=pos`; per 32 B: `B5`, read 32. Write: same vars; per 32 B: `B6`, 32 data bytes, read 1 byte (not checked). | `MAP:767-783`, `LK:1777-1796,1936-1950` |
| HuC-1 (0xFF) | MBC5-like. `EnableRAM(false)` writes `0x0000=0x0E` (IR mode) instead of 0x00. | `MAP:1026-1049` |
| HuC-3 (0xFE) | RAM like base; RTC: footer 0x0C bytes = u32LE (`minutes_total & 0xFFF | (days & 0xFFF)<<12`) + u64LE ts. Read/write via `0x0000=0x0B/0x0C/0x0D/0x00` command writes with 10-30 ms sleeps. | `MAP:1051-1245` |
| TAMA5 (0xFD) | 0x20 byte EEPROM + 0x28 byte RTC, accessed via `0xA000/0xA001` register writes using `dmgCartWriteSram`; `DMG_READ_CS_PULSE`/`DMG_WRITE_CS_PULSE` forced 1 around accesses; write path polls until two consecutive equal reads; `buffer_len = 0x20`. | `MAP:1247-1545`, `LK:1798-1833,1952-1971` |
| MBC6 (0x20) | 0x108000 bytes: 8 SRAM banks of 4 KiB then 128 flash banks of 8 KiB (`DMG_ROM_BANK`, `FLASH_METHOD=4`, `FLASH_WE_PIN=1`, sector erase every 0x20000, `WriteROM` for fw>1). Fill byte 0xFF. | `MAP:653-765`, `LK:3391-3396,3585-3593,3703-3708` |
| AGB GPIO RTC | Cart RTC at ROM 0xC4/0xC6/0xC8; footer 0x10 bytes = 7 BCD (YY MM DD WW HH MM SS; HH bit7 = PM flag) + status byte (0x40 = 24h, 0x80 = power/reset) + u64LE ts. Backup (fw>=12): `CA` -> 8 bytes `[status, YY..SS]`, `HasRTC` requires bit7 of status clear and the 6 bytes at ROM 0xC4 to differ from the mapped ones; footer = `buf[1:8] + RTCReadStatus() + ts`. Restore: `AGB_GPIO.WriteRTC(buffer[-0x10:])` (all `0xFF` footer = reset to 2000-01-01), GPIO bit-bang via `agbCartWrite`. | `MAP:1752-2101`, `LK:1220-1242,3833-3847,3892-3896` |
| DACS 8M, Vast Fame, e-Reader, GB-Memory, Pocket Camera, MBC1M-special, Xploder, Sachen, batteryless SRAM | Out of scope. | |

---------------------------------------------------------------------------------------------------

## 7. Save file format

* A `.sav` file is the raw save memory in device order, no header, no padding: backup output is `buffer` (length `save_size`) written verbatim (`LK:3867-3871`).
* Sizes: DMG per 5.3 table; AGB per 5.6 (512, 8192, 32768, 65536, 131072).
* MBC2: 512 bytes, each byte masked `& 0x0F` (`LK:3864-3866`).
* Optional RTC footer is appended after the save data and **only** if `rtc` was requested and the cart has an RTC:

| Cart | Footer size | Layout |
|---|---|---|
| MBC3 / MBC30 (0x0F, 0x10, 0x110) | 0x30 | `S,M,H,DL,DH` each as u32 LE (offsets 0x00,0x04,0x08,0x0C,0x10); the same 20 bytes again (offsets 0x14..0x27, "latched" copy); u64 LE unix timestamp at 0x28. This is the 48-byte footer used by VBA-M/mGBA-style emulators. DH bit0 = day bit 8, bit6 = halt, bit7 = carry. |
| HuC-3 | 0x0C | u32 LE `((days & 0xFFF) << 12) | (minutes_since_midnight & 0xFFF)`, u64 LE timestamp |
| TAMA5 | 0x28 | 32 bytes RTC pages + u64 LE timestamp |
| GBA GPIO RTC | 0x10 | 7 BCD bytes (YY MM DD WW HH MM SS), 1 status byte, u64 LE timestamp (`MAP:1935-1937`; fw>=12 path: `LK:3841-3842`) |

* If the RTC read returns nothing (`False/None`) an empty footer is written (`LK:3849`), i.e. file = plain save.
* Restore identifies a footer purely by `rtc=true` plus file length; the Python app guards with `save_size_includes_rtc`.
* Cross-compat (general emulator knowledge, NOT verifiable from this repo): a plain `.sav` works in emulators; the 48-byte MBC3 footer matches the layout used by VBA-M/mGBA-style `.sav` RTC data; BGB uses a different 44-byte footer which FlashGBX does not generate. FlashGBX itself only guarantees round-trip with its own files (it reads the footer back via `buffer[-0x30:]`).
* Extension: the GUI accepts `.sav .srm .fla .eep` (`GUI:27`); FlashGBX writes `.sav` (`GUI:2335`, `CLI:1235`).
* Naming (optional): `generate_filename` (`app.py:84-160`) - DMG `%TITLE%_%CODE%-%REVISION%` (or `%TITLE%-%REVISION%`), AGB `%TITLE%_%CODE%-%REVISION%`, invalid chars `<>:"/\|?*` -> `_`.

---------------------------------------------------------------------------------------------------

## 8. Gotchas

### 8.1 Timing
* `sleep(0.05)` after AGB bank switches (`LK:3626`).
* Flash sector erase poll: `sleep(0.01)` before every poll, 50 tries (`LK:3731-3746`). `ReadFlashSaveID`: `sleep(0.01)` after the `F0` reset commands twice (`LK:1656-1658`).
* Dead-time delays for RTC: HuC-3 `sleep(0.01)`/0.03 (`MAP:1070-1175`); MBC3 has none besides `clkToggle`.
* `CartPowerOn` has no sleep for GBFlash (the one `sleep(0.2)` is the GBxCart RW path, `LK:858`).
* `_read` timeout 1 s per call; error recovery sleeps 0.5 s increments.
* Timeout while waiting for an ACK of a 256-byte flash page write: the firmware ACKs after the write finishes; 1 s should be enough for SST/Macronix pages, but make the timeout configurable.

### 8.2 Voltage / safety
See 4.2. Additional: `ReadFlashSaveID` and the AGB `0x1000000` bank-switch probe write to the cartridge; on GBA ROM-only carts these writes are harmless but they are writes.
Never use `AGB_CART_WRITE` to ROM addresses on unknown cartridges beyond what is listed.

### 8.3 Dead code / misleading code
* `LK:719`: unreachable `DMG_WRITE_CS_PULSE=0` for sram writes (see section 0).
* `LK:3343-3347` initialised `sram_5`, `temp`, `audio_low` are only meaningful for flashcart profiles.
* `HW:107` branch uses `fw_ver >= 12` for name/flags; `GBxCart RW` branches (`LK:856-879,896-897,1097-1098`) never apply to GBFlash.
* `max_length = 64 if pcb_name in ("GBxCart RW", "") else MAX_BUFFER_READ` (`LK:3582`): GBFlash always gets 0x1000.
* `DEVICE_MAX_FW` unused; `DEVICE_LATEST_FW_TS` keys 10/11 unused.
* `CLI:1266-1272` auto-selects mapper-specific save type via `game_title in ("KORO2 KIRBYKKKJ", ...)` strings which never match a cleaned title; the correct value comes from `ram_size_raw` anyway.
* `sram_unstable`, `empty_data_byte` for DACS, `extra_size` for progress only.

### 8.4 GUI/ini-dependent behaviour (defaults)
| Setting | Default | Effect |
|---|---|---|
| `VerifyData` | enabled | backup reads each chunk twice (`verify_read`); restore verifies by read-back |
| `DMGReadMethod` | 1 (A15) | `DMG_READ_METHOD` |
| `AGBReadMethod` | 2 (Stream) | `AGB_READ_METHOD` |
| `AutoPowerOff` | "350" (menu) / "0" (read) | ambiguous; CLI uses 1500 |
| `WriteDelay` | disabled | 1.4 ms per write |
| `SerialTimeout` | 1 | seconds |
| `SaveFileNameAddDateTime` | disabled | `_YYYY-MM-DD_HH-MM-SS` suffix |
| RTC prompt | ask | `rtc` and `rtc_advance` (GUI "Adjust RTC" checked) |
| `LimitBaudRate` | disabled | 1,000,000 baud instead of 2,000,000 (GBxCart only; GBFlash ignores) |
There is no `config/*.ini` that affects save transfers; `config/` contains `db_AGB.json`, `db_DMG.json`, `db_DMG_bl.json`, and flashcart profiles `fc_*.txt` (flashcart-only).
`db_DMG.json` (3130 entries, key = SHA-1 of the 0x180 header) gives names/ROM sizes only (no save type); `db_DMG_bl.json` is batteryless-SRAM data (out of scope).

### 8.5 Backup retry bug (do not port literally) (`LK:3672-3683`)
If a chunk arrives short, `max_length` is halved and the same `pos` retried, down to 64. Once `max_length` is 64 and the read is still short, `max_length` is set to 64, `_read_failed` stays False, and execution
falls through to `buffer += temp` with a **short** chunk and `pos += buffer_len` -> a silently truncated/misaligned backup. In Kotlin: retry the same position a few times with smaller chunks and then **fail** the operation.

### 8.6 Others
* `ReadROM` over-reads when `length` is not a multiple of the chunk (always 64 for headers).
* `Initialize` returns `[]` (falsy) both for "connected, no messages" and "no device"; check connection state, not the return.
* `AGB_CART_READ_SRAM` addresses for Flash/SRAM are raw byte offsets within the 64 KiB window; `agbRomRead` uses word addresses.
* Writing a save to a cartridge whose battery is weak (SRAM) without cart power control risks data loss on hot-swap (GUI warning at `GUI:2501`). With power control (v1.3) the cart stays powered.
* Restore with `erase=True` writes `0x00` for most types; `0xFF` for Flash (`empty_data_byte`).
* Restore of AGB Flash: sector erase happens before every chunk; an interrupted restore leaves a partially erased save. Always back up first (the GUI does not enforce it).
* Atmel `0x1F3D` write unit/addressing (`ADDRESS=pos/128`, `TRANSFER_SIZE=256`) is copied from Python; firmware behaviour unverified.
* If `ReadFlashSaveID` finds an SRAM (byte change sticks) while the user selected Flash, the worker aborts (no save written).

---------------------------------------------------------------------------------------------------

## 9. Kotlin module breakdown and open questions

### 9.1 Modules (implement and test in this order)

1. `UsbSerialPort` - CH340 (VID 0x1A86 / PID 0x7523) open/close at 2,000,000 8N1, permission handling, blocking `readExact(n, timeout)`, `write(bytes)`, `drainInput()`. Test: loopback / open + close, baud accepted.
2. `Transport` - ack/response conventions: `write`, `read(count)` with the section 1.3 semantics and error draining, `waitAck()` (accept 0x01/0x03), `tryWrite` with the section 1.5 resync, `WRITE_DELAY` flag, liveness (`ping`/`getVar CART_MODE`), error counters. Pure logic over `UsbSerialPort`; unit-test with a fake port.
3. `Commands` / `FwVars` - opcode and variable tables (3.1/3.2), `setVar/getVar`, `cartWriteFlash`, `clkToggle`, `dmgCartWrite`, `agbCartWrite`, `dmgRomRead/dmgRamRead/dmgRamWrite`, `agbRomRead/agbSaveRead/agbSaveWrite`.
4. `FirmwareInfo` + `DeviceSession` - handshake (2.1/2.2), compatibility and "update required" policy, name/flags parsing. **Milestone 1a: connect and print `L<fw_ver>`, pcb, name, flags.**
5. `CartPower` / `ModeController` - `SetMode`, `CartPowerOn/Off/Cycle`, `SetAutoPowerOff`, safe disconnect, voltage rules (section 4). **Milestone 1b.**
6. `HeaderReader` + `DmgHeader` / `AgbHeader` parsers (5.1, 5.2, 5.5) with logo/checksum/empty detection, title cleanup, mapper table. **Milestone 1c: read and display the header (DMG and AGB).**
7. `SaveTypeResolver` - DMG rule (5.3), AGB marker/DB lookup (5.5/5.8), bundled `db_AGB.json` subset, user override.
8. `DmgMapperOps` - interface `{enableRam, disableRam, selectRamBank, ramBankSize, ramBanks}` with impls `Base/Mbc1/Mbc2/Mbc3/Mbc5` (6.2-6.5) + later `Mbc7/HuC1/HuC3/Tama5/Mbc6`.
9. `DmgSaveEngine` - backup + restore chunk loop (6.1, 6.1.5) with MBC2 nibble handling, chunk retry policy (fixed 8.5), verify-read option.
10. `Mbc3Rtc` - latch/read/write/footer (6.4, 7).
11. `AgbSaveEngine` - SRAM/FRAM (6.6), EEPROM, then Flash (`FlashChipId`, `ReadFlashSaveID`, erase/poll/write, bank switch), bank switching.
12. `RestorePlanner` - buffer preparation (6.7), file length / footer detection, write verification (6.8).
13. `SaveFileStore` - Storage Access Framework I/O, naming, footer handling.
14. `AgbSaveProber` - optional active detection (5.8 step 3).
15. UI layer: platform selector (explicit; never auto-switch to DMG), cartridge info, backup/restore flows with progress and cancel (cancel -> `CartPowerCycle`).

### 9.2 Open questions / ambiguities not resolved from the Python

1. `ADDRESS` auto-increment and units (section 3.1) are inferred, not documented.
2. DTR/RTS: never set; pyserial's default (both asserted) is what the reference tool uses. Android libraries may default differently; try both asserted first.
3. `AUTO_POWEROFF_TIME` unit and sensible default (350 vs 1500 vs 5000); likely milliseconds.
4. Behaviour of fw 10-11 and fw<10 is only partially visible (handled by `fw_ver` branches listed in Appendix A). Treat fw<12 as unsupported unless hardware says otherwise.
5. Meaning of ACK value `0x03` vs `0x01` (both accepted).
6. EEPROM byte order inside 8-byte blocks and any host-side swap: the Python never swaps, so the firmware must deliver emulator-compatible order; not verifiable here.
7. Exact firmware limits on `TRANSFER_SIZE` (0x1000 read / 0x800 write max buffers per `HW:67-68`, but Python never exceeds 0x1000 reads and 256-byte save writes).
8. Atmel AT29LV512 (`0x1F3D`) and bootleg flash chips (0xBF4B/0xBF5B/0xFFFF/0xBF6D) behaviour.
9. Whether PCB "v1.3A" reports `pcb_ver == 13` and the device name string.
10. GUI default for the RTC "Adjust RTC" (checked) vs CLI (never advances): choose one and document it in the app.
11. Whether the dev-cart probe in 6.1.1 step 7 can be dropped for retail carts (believed yes).
12. Whether `ReadROM(0, 0x180)` can be done as one 0x180 request.
13. Python's RTC "advance" arithmetic vs plain elapsed time (6.4).

---------------------------------------------------------------------------------------------------

## Appendix A: firmware-version-dependent branches (all `FW["fw_ver"]` checks used by in-scope code)

| Condition | Effect | Source |
|---|---|---|
| fw>=15 | `CheckActive` uses `FE` ping; `SET_VAR_STATE` waits for ACK; `GET_SWITCH_STATE (F6)` available | `LK:250,934,345` |
| fw>=14 | `DMG_AUDIO_ENABLED` variable (dev carts only) | `LK:964,4001` |
| fw>=12 | name + flag bytes in FW info; ACK after `A2/A3/A4/A5/A8/A9/F3/F5/C9`, `_try_write` for `A6/B2/C2`; `SetDMGReadMethod/SetAGBReadMethod` active; `AGB_IRQ_ENABLED=0` in AGB SetMode; `AGB_READ_GPIO_RTC` command; `SET_PIN`; `CLK_TOGGLE` takes a u32; query-based `CartPowerOn` | `HW:107`, `LK:302-342,639-642,714-717,758-769,971,1224-1229` |
| fw>=10 | `getVar` does real I/O (else returns 0) | `LK:596` |
| fw>=8 | `ReadHeader` sends `DISABLE_PULLUPS (AC)` with ACK | `LK:1070` |
| fw>=6 | `D4` carries the `flashcart` flag byte and u16 values; else u8 values and a legacy path | `LK:722-743` |
| fw<12 | `CartPowerOn` uses `QUERY_CART_PWR` + `CART_PWR_ON` without ACK; `CartPowerOff`/`Close` use unacknowledged commands | `LK:899-912,837-841` |
| fw==1 | extra DACS unlock in AGB header read | `LK:1166` |
| pcb 5 or fw_ts < 1730592000 | `FW_UPDATE_REQ` -> tool refuses | `HW:183-190` |

## Appendix B: minimal "connect -> header -> DMG backup" byte trace (fw>=12, power ctrl, MBC5 32 KiB cart; all multi-byte BE)

```
A1                                   -> 08 <8 info bytes> <nameLen> <name> <flags1> <flags2>
# user picked DMG
A3 -> ACK ; A5 -> ACK
A6 01 0000000B 00000001 -> ACK       (DMG_READ_METHOD = 1)
A6 01 00000000 00000001 -> ACK       (CART_MODE = 1)
A6 04 00000000 00000000 -> ACK       (ADDRESS = 0; size byte 4, key 0x00)
F4 -> 00 ; A3 -> ACK ; F2 -> ACK ; F4 -> 01 ; B4 -> ACK
# header
F4 -> 01 ; AC -> ACK ; A5 -> ACK ; B4 -> ACK
A6 01 00000008 00000000 -> ACK       (DMG_READ_CS_PULSE = 0)
A6 01 00000009 00000000 -> ACK       (DMG_WRITE_CS_PULSE = 0)
A6 02 00000000 00000040 -> ACK       (TRANSFER_SIZE = 0x40)
A6 04 00000000 00000000 -> ACK       (ADDRESS = 0)
A6 01 00000001 00000001 -> ACK       (DMG_ACCESS_MODE = 1)
6 x ( B1 -> 64 bytes )
A8 -> ACK
# backup (save_type 0x03: 4 banks x 16 chunks of 0x200)
A6 02 00000005 00000080 ; A6 02 00000006 00000080     (STATUS_REGISTER_MASK/VALUE) each -> ACK
B4 -> ACK ; A3 -> ACK ; A6 01 00000009 0.. ; A6 01 00000008 0..
(dev-cart probe: B2 00002000 00 ; B2 00004000 90 ; read 2 @0x4000 ; B2 00004000 F0 ; B2 00004000 FF ; B2 00002000 01 ; B2 00004000 00)
B2 00000000 0A -> ACK                                   (EnableRAM)
per bank b: A6 01 00000009 00000000 ; B2 00004000 <b> ; per chunk pos:
    A6 02 00000000 00000200 ; A6 04 00000000 <0xA000+pos> ; A6 01 00000001 00000003 ; A6 01 00000008 00000001 ;
    B1 -> 512 bytes ; A6 01 00000008 00000000
cleanup: B2 00004000 00 ; B2 00000000 00 ; A6 01 00000008 00000000 ; A8 -> ACK
```
(`A6 01 <key> <value>`: `01` = size byte for 8-bit variables; `02` for 16-bit; `04` for 32-bit; key and value are both u32 BE. Keys per section 3.2.)
This trace is derived from the code paths above, not captured from hardware; compare against a real capture (e.g. a USB analyzer on FlashGBX) to validate.
