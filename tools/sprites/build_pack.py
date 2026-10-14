#!/usr/bin/env python3
"""Build the GBFlash sprite asset pack from msikma/pokesprite.

Usage (always run in isolated mode; the source trees are treated as untrusted data):

    python -I tools/sprites/build_pack.py --pokesprite <pokesprite clone> --pkhex <PKHeX clone> \
        --out pkassets [--generated YYYY-MM-DD] [--commit <sha>] [--commit-date <date>] \
        [--pkhex-commit <sha>] [--pkhex-commit-date <date>]

Requirements: Python 3.9+, standard library only. PNG sizes are read from the IHDR
chunk and files are copied byte-for-byte (no re-encoding, no Pillow).

Inputs
  * pokesprite (images + data/pokemon.json, item-map.json, item-unlinked.json,
    item-legacy.json, misc.json, other-sprites.json): every image that goes into the
    pack comes from here.
  * PKHeX (data only, no images): ItemConverter.cs / ItemStorage*.cs / item name lists
    / Ball.cs. Used for the Gen 1/2/3 -> modern item id tables, the Ball enum, and to
    cross-check pokesprite's `item_XXXX` numbering against PKHeX item ids.
Nothing from either tree is ever executed.

Determinism: output depends only on input files and the metadata arguments;
--generated defaults to today (UTC), so pass it for byte-identical rebuilds.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import re
import shutil
import struct
import sys
import unicodedata
from pathlib import Path

if sys.version_info < (3, 9):  # pragma: no cover
    sys.exit("build_pack.py needs Python 3.9 or newer")

SCHEMA = 2
ASSET_ROOT = "src/main/assets"
NAN_ITEM = 128  # ItemConverter.NaN: "no modern equivalent"
LAST_GEN3_SPECIES = 386
FEMALE = "female"  # key suffix ("-f" would collide with Unown form F)
SAFE = re.compile(r"[a-z0-9._-]+")

# PKHeX form index -> pokesprite form slug ("$" = default form) for Gen 1-3.
# Unown: 0..25 = A..Z, 26 = '!', 27 = '?' (Gen 2 only has 0..25).
GEN3_FORMS = {
    201: ["$"] + list("bcdefghijklmnopqrstuvwxyz") + ["exclamation", "question"],
    351: ["$", "sunny", "rainy", "snowy"],
    386: ["$", "attack", "defense", "speed"],
}
# pokesprite "alias" slugs that do not exist as separate files get alias keys anyway.

# PKHeX Ball enum name -> pokesprite items/ball slug
BALL_SLUG_OVERRIDE = {
    "LAPoke": "hisuian-poke", "LAGreat": "hisuian-great", "LAUltra": "hisuian-ultra",
    "LAHeavy": "hisuian-heavy", "LAFeather": "feather", "LAWing": "wing", "LAJet": "jet",
    "LALeaden": "leaden", "LAGigaton": "gigaton", "LAOrigin": "origin",
}
# Groups whose pokesprite item ids do NOT correspond to PKHeX item ids (one icon per
# move type, ids assigned arbitrarily). Keyed by type only.
TYPE_ICON_GROUPS = ("tm", "hm", "tr")


class PackError(Exception):
    pass


# ---------------------------------------------------------------- helpers
def png_size(path: Path) -> tuple[int, int]:
    with open(path, "rb") as fh:
        head = fh.read(24)
    if len(head) < 24 or head[:8] != b"\x89PNG\r\n\x1a\n" or head[12:16] != b"IHDR":
        raise PackError(f"not a PNG: {path}")
    return struct.unpack(">II", head[16:24])


def read_json(p: Path):
    return json.loads(p.read_text(encoding="utf-8"))


def read_text_any(path: Path) -> list[str]:
    raw = path.read_bytes()
    if raw[:2] in (b"\xff\xfe", b"\xfe\xff"):
        return raw.decode("utf-16").splitlines()
    return raw.decode("utf-8-sig").splitlines()


_COMMENT = re.compile(r"//[^\n]*")


def parse_ushort_array(cs_path: Path, name: str) -> list[int]:
    src = _COMMENT.sub("", cs_path.read_text(encoding="utf-8"))
    m = re.search(r"\b" + re.escape(name) + r"\s*=>\s*\[(.*?)\];", src, re.S)
    if not m:
        raise PackError(f"array {name} not found in {cs_path}")
    out = []
    for tok in re.split(r"[,\s]+", m.group(1).strip()):
        if not tok:
            continue
        if tok == "NaN":
            out.append(NAN_ITEM)
        elif tok.isdigit():
            out.append(int(tok))
        else:
            raise PackError(f"unexpected token {tok!r} in {name}")
    return out


def norm_name(s: str) -> str:
    s = unicodedata.normalize("NFKD", s.lower())
    s = "".join(c for c in s if not unicodedata.combining(c))
    s = re.sub(r"pok[e�]?", "pok", s)  # Poke / mangled e-acute
    return re.sub(r"[^a-z0-9]", "", s)


def detect_commit(repo: Path) -> str | None:
    git = repo / ".git"
    try:
        head = (git / "HEAD").read_text().strip()
        if not head.startswith("ref:"):
            return head
        ref = head.split(None, 1)[1]
        p = git / ref
        if p.is_file():
            return p.read_text().strip()
        for line in (git / "packed-refs").read_text().splitlines():
            if line.endswith(" " + ref):
                return line.split()[0]
    except OSError:
        pass
    return None


def compress_ranges(nums: list[int]) -> str:
    nums = sorted(set(nums))
    parts, i = [], 0
    while i < len(nums):
        j = i
        while j + 1 < len(nums) and nums[j + 1] == nums[j] + 1:
            j += 1
        parts.append(str(nums[i]) if i == j else f"{nums[i]}-{nums[j]}")
        i = j + 1
    return ", ".join(parts)


def dumps(v) -> str:
    return json.dumps(v, separators=(",", ":"), ensure_ascii=False)


# ---------------------------------------------------------------- pack state
class Pack:
    def __init__(self, src: Path, out: Path) -> None:
        self.src = src
        self.assets = out / ASSET_ROOT
        self.entries: dict[str, dict] = {}
        self.files: dict[str, int] = {}          # rel path -> bytes (physical files)
        self.placed: dict[tuple[Path, str], str] = {}
        self.missing: list[dict] = []
        self.categories: dict[str, str] = {}     # key -> category (for stats)

    def place(self, key: str, srcfile: Path, rel: str, category: str, **extra) -> None:
        """Copy srcfile to rel (once per distinct source+rel) and register key."""
        if key in self.entries:
            raise PackError(f"duplicate key {key}")
        if not re.fullmatch(r"[a-z0-9._/-]+", rel):
            raise PackError(f"unsafe asset path {rel!r}")
        if rel not in self.files:
            dst = self.assets / rel
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(srcfile, dst)
            self.files[rel] = dst.stat().st_size
            self._dims = getattr(self, "_dims", {})
            self._dims[rel] = png_size(dst)
        w, h = self._dims[rel]
        e = {"path": rel, "w": w, "h": h}
        e.update(extra)
        self.entries[key] = e
        self.categories[key] = category

    def alias(self, key: str, target: str, category: str) -> None:
        if key in self.entries:
            raise PackError(f"duplicate key {key}")
        self.entries[key] = dict(self.entries[target])
        self.categories[key] = category

    def miss(self, key: str, category: str, expected: str) -> None:
        self.missing.append({"key": key, "category": category, "expected": expected})


# ---------------------------------------------------------------- pokemon
def build_pokemon(pack: Pack, ps: Path, pokemon: dict, other: dict) -> dict:
    """Place box sprites. Returns the index structure for the manifest."""
    g8 = ps / "pokemon-gen8"
    g7 = ps / "pokemon-gen7x"
    # stem -> (dex, form) for every form listed in gen-7 or gen-8 data
    stem_key: dict[str, str] = {}
    index: dict[str, dict] = {}
    for idx in sorted(pokemon, key=int):
        info = pokemon[idx]
        dex = int(idx)
        slug = info["slug"]["eng"]
        forms_out = []
        seen = set()
        for gen in ("gen-8", "gen-7"):
            for f, fi in info.get(gen, {}).get("forms", {}).items():
                if f in seen:
                    continue
                seen.add(f)
                stem = slug if f == "$" else f"{slug}-{f}"
                stem_key[stem] = f"{dex}" if f == "$" else f"{dex}-{f}"
                forms_out.append((f, fi, gen))
        form_list = []
        gen8_forms = info.get("gen-8", {}).get("forms", {})
        for f, fi, gen in forms_out:
            row = {"form": f}
            if fi.get("is_alias_of"):
                row["aliasOf"] = fi["is_alias_of"]
            if fi.get("is_unofficial_icon"):
                row["unofficial"] = True
            if gen == "gen-8" and fi.get("has_female"):
                row["female"] = True
            if gen == "gen-7" and f not in gen8_forms:
                row["gen7Only"] = True
            if dex in GEN3_FORMS and f in GEN3_FORMS[dex]:
                row["pkhexForm"] = GEN3_FORMS[dex].index(f)
            elif dex in GEN3_FORMS and f == "a":  # Unown A alias
                row["pkhexForm"] = 0
            form_list.append(row)
        index[str(dex)] = {"name": info["name"], "slug": info["slug"], "forms": form_list}

    # ---- gen8 regular / shiny (+ female)
    sets = [("box", g8 / "regular"), ("box_shiny", g8 / "shiny")]
    for setname, base in sets:
        for idx in sorted(pokemon, key=int):
            dex = int(idx)
            slug = pokemon[idx]["slug"]["eng"]
            forms = pokemon[idx]["gen-8"]["forms"]
            cat = f"pokemon_{setname}"
            # real files first, aliases after
            for f, fi in forms.items():
                if fi.get("is_alias_of"):
                    continue
                stem = slug if f == "$" else f"{slug}-{f}"
                key_tail = f"{dex}" if f == "$" else f"{dex}-{f}"
                srcf = base / f"{stem}.png"
                key = f"pokemon/{setname}/{key_tail}"
                if srcf.is_file():
                    pack.place(key, srcf, f"sprites/pokemon/{setname}/{key_tail}.png", cat, dex=dex)
                else:
                    pack.miss(key, cat, f"pokemon-gen8/{base.name}/{stem}.png")
                if fi.get("has_female"):
                    fsrc = base / FEMALE / f"{stem}.png"
                    fkey = f"{key}-{FEMALE}"
                    if fsrc.is_file():
                        pack.place(fkey, fsrc, f"sprites/pokemon/{setname}/{key_tail}-{FEMALE}.png",
                                   f"pokemon_{setname}_female", dex=dex)
                    else:
                        pack.miss(fkey, f"pokemon_{setname}_female",
                                  f"pokemon-gen8/{base.name}/{FEMALE}/{stem}.png")
            for f, fi in forms.items():
                tgt = fi.get("is_alias_of")
                if not tgt:
                    continue
                key = f"pokemon/{setname}/{dex}-{f}"
                tkey = f"pokemon/{setname}/{dex}" if tgt == "$" else f"pokemon/{setname}/{dex}-{tgt}"
                if tkey in pack.entries:
                    pack.alias(key, tkey, cat)
                else:
                    pack.miss(key, cat, f"alias of {tgt}")
            # PKHeX numeric form-index aliases for Gen 1-3 species
            if dex in GEN3_FORMS:
                for i, f in enumerate(GEN3_FORMS[dex]):
                    if i == 0:
                        continue
                    tkey = f"pokemon/{setname}/{dex}-{f}"
                    nkey = f"pokemon/{setname}/{dex}-{i}"
                    if tkey in pack.entries:
                        pack.alias(nkey, tkey, cat)

    # ---- right-facing (only exists in pokemon-gen7x)
    for setname, base in [("box_right", g7 / "regular" / "right"), ("box_shiny_right", g7 / "shiny" / "right")]:
        for f in sorted(base.glob("*.png")):
            stem = f.stem
            if stem not in stem_key:
                raise PackError(f"unknown right-facing sprite {f}")
            tail = stem_key[stem]
            pack.place(f"pokemon/{setname}/{tail}", f, f"sprites/pokemon/{setname}/{tail}.png",
                       f"pokemon_{setname}", dex=int(tail.split("-")[0]))

    # ---- egg / unknown / mega sigil
    for o in other["pokemon"]:
        stem = o["file"]
        slug = o["slug"]["eng"]
        key = {"mega": "mega-evolution-sigil"}.get(stem, stem)
        pack.place(f"pokemon/box/{key}", g8 / f"{stem}.png", f"sprites/pokemon/box/{key}.png", "pokemon_special")
    return index


# ---------------------------------------------------------------- items
def item_name_check(pkhex_names: list[str], item_map: dict) -> dict:
    """Compare pokesprite item_XXXX numbering with PKHeX modern item names."""
    ok = 0
    bad = []
    skipped = 0
    for k, v in sorted(item_map.items()):
        iid = int(k[5:])
        group, slug = v.split("/", 1)
        if group in TYPE_ICON_GROUPS:
            skipped += 1
            continue
        name = pkhex_names[iid] if iid < len(pkhex_names) else ""
        a, b = norm_name(name), norm_name(re.sub(r"--.*", "", slug))
        if a == b or a.startswith(b) or b.startswith(a) or (b and b in a):
            ok += 1
        else:
            bad.append({"id": iid, "pkhexName": name.replace("�", "e"), "pokesprite": v})
    return {"checked": ok + len(bad), "matched": ok, "mismatches": bad,
            "skippedTypeIconGroups": skipped}


def build_items(pack: Pack, ps: Path, item_map: dict, unlinked: dict, legacy: dict) -> dict:
    ids_by_file: dict[str, list[int]] = {}
    for k, v in item_map.items():
        ids_by_file.setdefault(v, []).append(int(k[5:]))
    legacy_by_file: dict[str, dict] = {}
    for k, v in legacy.items():
        for gen, f in v.items():
            legacy_by_file[f] = {"legacyOf": int(k[5:]), "gen": gen}

    for setname, folder, cat in (("items", "items", "item"), ("items_outline", "items-outline", "item_outline")):
        base = ps / folder
        # every file: with id keys when listed in item-map (except type-icon groups)
        for srcf in sorted(base.rglob("*.png")):
            rel = srcf.relative_to(base).with_suffix("").as_posix()  # group/slug
            group, slug = rel.split("/", 1)
            path = f"sprites/{setname}/{rel}.png"
            ids = ids_by_file.get(rel, [])
            if group in TYPE_ICON_GROUPS:
                pack.place(f"{cat}_type/{rel}", srcf, path, f"{cat}_type_icon")
                continue
            if ids:
                for n, iid in enumerate(sorted(ids)):
                    pack.place(f"{cat}/{iid}", srcf, path, cat)
                continue
            extra = {}
            if rel in unlinked:
                extra["unlinked"] = unlinked[rel]
            if rel in legacy_by_file:
                extra.update(legacy_by_file[rel])
            pack.place(f"{cat}_other/{rel}", srcf, path, f"{cat}_other", **extra)
        # ids listed in the map but no file
        for k, v in sorted(item_map.items()):
            g = v.split("/")[0]
            if g in TYPE_ICON_GROUPS:
                continue
            if not (base / f"{v}.png").is_file():
                pack.miss(f"{cat}/{int(k[5:])}", f"{cat}_missing_file", f"{folder}/{v}.png")
    return {}


def build_balls(pack: Pack, ps: Path, pkhex: Path) -> dict:
    src = (pkhex / "PKHeX.Core/Game/Enums/Ball.cs").read_text(encoding="utf-8")
    body = re.search(r"enum Ball\s*:\s*byte\s*\{(.*?)\}", src, re.S).group(1)
    body = _COMMENT.sub("", body)
    enum = {m.group(1): int(m.group(2)) for m in re.finditer(r"(\w+)\s*=\s*(\d+)", body)}
    result = {}
    for folder, setname in (("items", "items"), ("items-outline", "items_outline")):
        cat = "ball" if folder == "items" else "ball_outline"
        for name, num in sorted(enum.items(), key=lambda kv: kv[1]):
            if num == 0:
                continue
            slug = BALL_SLUG_OVERRIDE.get(name, name.lower())
            tkey = f"ball/{slug}" if folder == "items" else f"ball_outline/{slug}"
            # primary slug keys first
            if tkey not in pack.entries:
                f = ps / folder / "ball" / f"{slug}.png"
                if f.is_file():
                    pack.place(tkey, f, f"sprites/{setname}/ball/{slug}.png", cat)
                else:
                    pack.miss(tkey, cat, f"{folder}/ball/{slug}.png")
                    continue
            nkey = f"ball/{num}" if folder == "items" else f"ball_outline/{num}"
            pack.alias(nkey, tkey, cat)
            if folder == "items":
                result[str(num)] = slug
        # remaining ball files not in the PKHeX enum (e.g. park)
        for f in sorted((ps / folder / "ball").glob("*.png")):
            tkey = f"ball/{f.stem}" if folder == "items" else f"ball_outline/{f.stem}"
            if tkey not in pack.entries:
                pack.place(tkey, f, f"sprites/{setname}/ball/{f.stem}.png", cat)
    return result


# ---------------------------------------------------------------- misc
def build_misc(pack: Pack, ps: Path, misc: dict) -> dict:
    base = ps / "misc"
    key_for_file: dict[str, str] = {}
    for srcf in sorted(base.rglob("*.png")):
        rel = srcf.relative_to(base).with_suffix("").as_posix()
        cat = rel.split("/", 1)[0]
        pack.place(rel, srcf, f"sprites/misc/{rel}.png", f"misc_{cat}")
        key_for_file[rel] = rel

    def conv(v):
        if isinstance(v, str):
            r = v[:-4] if v.endswith(".png") else v
            return key_for_file.get(r, v)
        if isinstance(v, list):
            return [conv(x) for x in v]
        return v

    index = {}
    for cat, rows in misc.items():
        out_rows = []
        for r in rows:
            r = dict(r)
            if "files" in r:
                r["files"] = {g: conv(f) for g, f in r["files"].items()}
            out_rows.append(r)
        index[cat] = out_rows
    # files declared in misc.json but absent on disk
    for cat, rows in misc.items():
        for r in rows:
            for g, fl in r.get("files", {}).items():
                for f in (fl if isinstance(fl, list) else [fl]):
                    if f[:-4] not in key_for_file:
                        pack.miss(f[:-4], "misc_missing_file", f"misc/{f}")
    return index


# ---------------------------------------------------------------- item id maps (PKHeX data)
def build_item_maps(pkhex: Path) -> tuple[dict, list[str], list[int], list[int]]:
    core = pkhex / "PKHeX.Core"
    conv = core / "PKM/Util/Conversion/ItemConverter.cs"
    item2to4 = parse_ushort_array(conv, "Item2to4")
    item3to4 = parse_ushort_array(conv, "Item3to4")
    text = core / "Resources/text/items"
    g1 = read_text_any(text / "gen1/text_ItemsG1_en.txt")
    g2 = read_text_any(text / "gen2/text_ItemsG2_en.txt")
    modern = read_text_any(text / "text_Items_en.txt")

    def index_by_name(lst):
        d: dict[str, int] = {}
        for i, nm in enumerate(lst):
            k = norm_name(nm)
            if k and i > 0 and k not in d:
                d[k] = i
        return d

    g2_by, mod_by = index_by_name(g2), index_by_name(modern)
    alias = {"elixir": "elixer", "maxelixir": "maxelixer"}
    gen1_to_gen2 = [0] * 256
    direct: dict[int, int] = {}
    for idx, nm in enumerate(g1):
        if idx == 0 or idx > 255 or not nm.strip() or nm.startswith("?"):
            continue
        k = alias.get(norm_name(nm), norm_name(nm))
        if k in g2_by:
            gen1_to_gen2[idx] = g2_by[k]
        elif k in mod_by:
            direct[idx] = mod_by[k]
    gen1 = [(item2to4[g] if 0 < g < len(item2to4) and item2to4[g] != NAN_ITEM else 0) for g in gen1_to_gen2]
    for i, v in direct.items():
        gen1[i] = v
    unmapped = [{"gen1Id": i, "name": g1[i]} for i in range(1, min(len(g1), 256))
                if gen1[i] == 0 and g1[i].strip() and not g1[i].startswith("?")]
    clean = lambda t: [0 if v == NAN_ITEM else v for v in t]
    maps = {
        "doc": "Index tables: value = PKHeX modern item id (the n in manifest key item/{n}); 0 = no "
               "modern equivalent. gen2/gen3 are PKHeX's ItemConverter.Item2to4/Item3to4. "
               "gen1BagByName is NOT PKHeX behaviour (see README).",
        "gen2": clean(item2to4),
        "gen3": clean(item3to4),
        "gen1BagByName": gen1,
        "gen1BagUnmapped": unmapped,
    }
    return maps, modern, item2to4, item3to4


def storage_ids(pkhex: Path, item2to4: list[int], item3to4: list[int]) -> set[int]:
    items = pkhex / "PKHeX.Core/Items"
    ids: set[int] = set()
    s2 = items / "ItemStorage2.cs"
    for nm in ("General", "Balls", "KeyCrystal", "Machine"):
        ids.update(item2to4[i] for i in parse_ushort_array(s2, nm) if i < len(item2to4))
    s3 = items / "ItemStorage3RS.cs"
    g3: list[int] = []
    for nm in ("General", "Key", "Machine", "Berry", "Balls"):
        g3 += parse_ushort_array(s3, nm)
    g3 += parse_ushort_array(items / "ItemStorage3E.cs", "Key")
    g3 += parse_ushort_array(items / "ItemStorage3FRLG.cs", "Key")
    ids.update(item3to4[i] for i in g3 if i < len(item3to4))
    ids.discard(NAN_ITEM)
    ids.discard(0)
    return ids


# ---------------------------------------------------------------- main build
def build(args: argparse.Namespace) -> None:
    ps = Path(args.pokesprite).resolve()
    pkhex = Path(args.pkhex).resolve()
    out = Path(args.out)
    for p, what in ((ps / "data/pokemon.json", "pokesprite"), (pkhex / "PKHeX.Core", "PKHeX")):
        if not p.exists():
            raise PackError(f"{p} not found; is --{what.lower()} a {what} clone?")

    pokemon = read_json(ps / "data/pokemon.json")
    item_map = read_json(ps / "data/item-map.json")
    unlinked = read_json(ps / "data/item-unlinked.json")
    legacy = read_json(ps / "data/item-legacy.json")
    misc = read_json(ps / "data/misc.json")
    other = read_json(ps / "data/other-sprites.json")

    # Manual override: Exp. Share (held item, id 216, used in Gen 2/3) is unlinked in pokesprite
    # ("key-item/exp-share", id unknown) although the art is the Exp. Share.
    ITEM_OVERRIDES = {216: "key-item/exp-share"}
    for iid, rel in ITEM_OVERRIDES.items():
        item_map.setdefault("item_%04d" % iid, rel)
    maps, modern_names, item2to4, item3to4 = build_item_maps(pkhex)
    check = item_name_check(modern_names, item_map)
    _NAMES[:] = modern_names

    if out.exists():
        if any(out.iterdir()) and not (out / "manifest.json").is_file():
            raise PackError(f"{out} exists but has no manifest.json; refusing to delete it")
        for child in sorted(out.iterdir()):  # keep `out` itself (it may be someone's cwd)
            if child.is_dir():
                shutil.rmtree(child)
            else:
                child.unlink()

    pack = Pack(ps, out)
    pk_index = build_pokemon(pack, ps, pokemon, other)
    build_items(pack, ps, item_map, unlinked, legacy)
    balls = build_balls(pack, ps, pkhex)
    misc_index = build_misc(pack, ps, misc)

    # ---- Gen 1-3 coverage report
    reach = sorted({v for v in item2to4 + item3to4 if v not in (0, NAN_ITEM)})
    st = storage_ids(pkhex, item2to4, item3to4)
    reach = sorted(set(reach) | st)
    no_icon, type_only = [], []
    for i in reach:
        if f"item/{i}" in pack.entries:
            continue
        (type_only if i in tm_like_ids(item_map, modern_names) else no_icon).append(i)
    for i in no_icon:
        pack.miss(f"item/{i}", "gen123_item_without_icon", modern_names[i] if i < len(modern_names) else "?")
    for i in type_only:
        pack.miss(f"item/{i}", "gen123_tm_hm_no_per_id_icon", modern_names[i] if i < len(modern_names) else "?")
    # Gen 1-3 species coverage (kept in `missing` so it is explicit even if empty)
    for sp in range(1, LAST_GEN3_SPECIES + 1):
        for s in ("box", "box_shiny"):
            if f"pokemon/{s}/{sp}" not in pack.entries:
                pack.miss(f"pokemon/{s}/{sp}", "gen123_pokemon", "see missing list")

    commit = args.commit or detect_commit(ps)
    pkhex_commit = args.pkhex_commit or detect_commit(pkhex)
    manifest_head = {
        "schema": SCHEMA,
        "assetRoot": ASSET_ROOT,
        "note": "Paths are relative to src/main/assets (= Android asset paths). Images are NOT GPL-3.0; "
                "private use only, delete before distribution. See README.md.",
        "source": {"repo": "https://github.com/msikma/pokesprite", "commit": commit,
                   "commitDate": args.commit_date, "licenseOfRepoCodeAndData": "MIT"},
        "pkhexDataOnly": {"repo": "https://github.com/kwsch/PKHeX", "commit": pkhex_commit,
                          "commitDate": args.pkhex_commit_date,
                          "usedFor": "item id conversion tables, Ball enum, item id cross-check (no images)"},
        "generated": args.generated,
        "generator": "tools/sprites/build_pack.py",
    }
    cats = {}
    for k, e in pack.entries.items():
        c = pack.categories[k]
        cats.setdefault(c, set()).add(e["path"])
    stats = {c: {"keys": sum(1 for k in pack.entries if pack.categories[k] == c),
                 "files": len(p), "bytes": sum(pack.files[x] for x in p)}
             for c, p in sorted(cats.items())}
    manifest = dict(manifest_head)
    manifest["formIndex"] = {str(k): v for k, v in GEN3_FORMS.items()}
    manifest["ballIdToSlug"] = balls
    manifest["itemOverrides"] = {str(k): v for k, v in ITEM_OVERRIDES.items()}
    manifest["itemIdMaps"] = maps
    manifest["itemIdCheck"] = check
    manifest["stats"] = stats
    manifest["pokemonIndex"] = pk_index
    manifest["miscIndex"] = misc_index
    manifest["entries"] = dict(sorted(pack.entries.items()))
    manifest["missing"] = sorted(pack.missing, key=lambda m: (m["category"], m["key"]))
    write_manifest(out / "manifest.json", manifest)
    write_readme(out, pack, manifest, misc, reach, no_icon, type_only, args, commit, pkhex_commit)
    print_summary(pack, manifest, stats)


_TM_CACHE: dict = {}


def tm_like_ids(item_map: dict, names: list[str]) -> set[int]:
    """Modern ids of TM/HM/TR items (by PKHeX name), for which pokesprite has type icons only."""
    key = id(names)
    if key not in _TM_CACHE:
        _TM_CACHE[key] = {i for i, n in enumerate(names) if re.fullmatch(r"(TM|HM|TR)\d+", n.strip())}
    return _TM_CACHE[key]


def write_manifest(path: Path, m: dict) -> None:
    """One entry per line so diffs and greps stay usable."""
    parts = []
    big = ("pokemonIndex", "miscIndex", "entries")
    keys = list(m)
    lines = ["{"]
    for n, k in enumerate(keys):
        comma = "," if n < len(keys) - 1 else ""
        v = m[k]
        if k in big and isinstance(v, dict):
            lines.append(f'{json.dumps(k)}: {{')
            items = list(v.items())
            for j, (kk, vv) in enumerate(items):
                lines.append(f"{json.dumps(kk, ensure_ascii=False)}:{dumps(vv)}" + ("," if j < len(items) - 1 else ""))
            lines.append("}" + comma)
        else:
            lines.append(f"{json.dumps(k)}: {dumps(v)}{comma}")
    lines.append("}")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")


# ---------------------------------------------------------------- reports
def print_summary(pack: Pack, m: dict, stats: dict) -> None:
    total = sum(pack.files.values())
    print(f"files: {len(pack.files)}  keys: {len(pack.entries)}  bytes: {total}  ({total / 1048576:.2f} MiB)")
    for c, s in stats.items():
        print(f"  {c:28s} {s['keys']:5d} keys {s['files']:5d} files {s['bytes'] / 1024:9.1f} KiB")
    print(f"missing entries: {len(pack.missing)}")
    cc: dict[str, int] = {}
    for x in pack.missing:
        cc[x["category"]] = cc.get(x["category"], 0) + 1
    for c, n in sorted(cc.items()):
        print(f"  {c}: {n}")
    chk = m["itemIdCheck"]
    print(f"item id check: {chk['matched']}/{chk['checked']} match, {len(chk['mismatches'])} mismatches")


def fmt_missing(pack: Pack) -> str:
    by: dict[str, list[dict]] = {}
    for x in pack.missing:
        by.setdefault(x["category"], []).append(x)
    lines = []
    for cat, rows in sorted(by.items()):
        keys = [r["key"] for r in rows]
        if cat.startswith("pokemon_") or cat == "gen123_pokemon":
            dexes = sorted({int(k.split("/")[-1].split("-")[0]) for k in keys})
            low = [d for d in dexes if d <= LAST_GEN3_SPECIES]
            tails = sorted(k.split("/", 2)[2] for k in keys if int(k.split("/")[-1].split("-")[0]) <= LAST_GEN3_SPECIES)
            lines.append(f"- `{cat}` ({len(keys)}): species with a miss: {compress_ranges(dexes)}"
                         + (f"; of which Gen 1-3 keys: {', '.join(tails[:40])}" + (" ..." if len(tails) > 40 else "")
                            if tails else "; none in Gen 1-3"))
        elif cat.startswith("gen123_"):
            ids = [int(r["key"].split("/")[1]) for r in rows]
            names = {r["key"].split("/")[1]: r["expected"] for r in rows}
            lines.append(f"- `{cat}` ({len(keys)}): item ids {compress_ranges(ids)} ("
                         + ", ".join(f"{i}={names[str(i)]}" for i in (ids[:1] + ids[-1:])) + " ...)")
        else:
            lines.append(f"- `{cat}` ({len(keys)}): " + ", ".join(keys[:30]) + (" ..." if len(keys) > 30 else ""))
    return "\n".join(lines) if lines else "- none"


def write_readme(out, pack, m, misc, reach, no_icon, type_only, args, commit, pkhex_commit) -> None:
    total = sum(pack.files.values())
    stats = m["stats"]
    table = "\n".join(f"| `{c}` | {s['keys']} | {s['files']} | {s['bytes'] / 1024:.1f} KiB |" for c, s in stats.items())
    gen3_ribbons = sorted(
        r["name"]["eng"] for r in misc["ribbon"] if r.get("origin_gen") in (3,) or (r.get("origin_gen") or 99) <= 3)
    chk = m["itemIdCheck"]
    bad = "\n".join(f"  - {b['id']}: PKHeX `{b['pkhexName']}` vs pokesprite `{b['pokesprite']}`" for b in chk["mismatches"]) or "  - none"
    spot = {i: m["entries"].get(f"item/{i}", {}).get("path", "-") for i in (1, 17, 234)}
    cmd = (f"python -I tools/sprites/build_pack.py --pokesprite <pokesprite clone> --pkhex <PKHeX clone> "
           f"--out pkassets --generated {args.generated} --commit {commit}"
           + (f" --commit-date {args.commit_date}" if args.commit_date else "")
           + (f" --pkhex-commit {pkhex_commit}" if pkhex_commit else "")
           + (f" --pkhex-commit-date {args.pkhex_commit_date}" if args.pkhex_commit_date else ""))
    text = README.format(
        commit=commit, commit_date=args.commit_date or "unknown", generated=args.generated, cmd=cmd,
        pkhex_commit=pkhex_commit, pkhex_date=args.pkhex_commit_date or "unknown",
        nfiles=len(pack.files), nkeys=len(pack.entries), total_bytes=total, total_mib=f"{total / 1048576:.2f}",
        table=table, missing=fmt_missing(pack),
        n_reach=len(reach), n_noicon=len(no_icon), n_typeonly=len(type_only),
        noicon_list=", ".join(f"{i} ({_nm(m, i)})" for i in no_icon) or "none",
        n2=len(m["itemIdMaps"]["gen2"]), n3=len(m["itemIdMaps"]["gen3"]),
        chk_matched=chk["matched"], chk_total=chk["checked"], chk_skipped=chk["skippedTypeIconGroups"],
        chk_bad=bad, spot1=spot[1], spot17=spot[17], spot234=spot[234],
        gen1_unmapped=", ".join(f"{u['gen1Id']} ({u['name'].replace(chr(0xfffd), 'e')})"
                                for u in m["itemIdMaps"]["gen1BagUnmapped"]) or "none",
        gen3_ribbons=", ".join(gen3_ribbons),
    )
    (out / "README.md").write_text(text, encoding="utf-8", newline="\n")


_NAMES: list[str] = []


def _nm(m, i):
    return _NAMES[i].replace("�", "e") if i < len(_NAMES) else "?"


README = """\
# pkassets - sprite pack from pokesprite (private use only)

## Rights notice - read first

The images in this directory come from the **msikma/pokesprite** repository
(<https://github.com/msikma/pokesprite>, commit `{commit}`, {commit_date}) and were copied
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

* Source: pokesprite `{commit}` ({commit_date}); last upstream commit is from 2022, so there
  are no Gen 9 sprites (no species > 905).
* Data only (no images) from PKHeX `{pkhex_commit}` ({pkhex_date}): Gen 1/2/3 item
  conversion tables, Ball enum, item-name cross-check.
* Generated: {generated}; generator `tools/sprites/build_pack.py` (standard library only).

## Regenerate

```
{cmd}
```

Deterministic: same pokesprite/PKHeX files and same metadata arguments give identical
bytes (`--generated` defaults to today). Never run anything from the clones. The script
refuses to delete an `--out` directory that has no `manifest.json`.

## Contents

{nfiles} physical files, {nkeys} manifest keys (aliases share a file), {total_bytes} bytes
({total_mib} MiB).

| Category | Keys | Files | Size |
|---|---:|---:|---:|
{table}

### What is included

* `pokemon-gen8` regular and shiny box sprites (68x56) for every species/form (1-905),
  gen-8 female variants, egg, Manaphy egg, unknown, unknown (Gen 5), Mega sigil.
* Right-facing variants (`box_right`, `box_shiny_right`) from `pokemon-gen7x`; this is the
  only reason `pokemon-gen7x` is used. Its other 2300 files are older-style duplicates of the
  gen8 set and were skipped.
* Items (32x32) and `items-outline` (32x32, same art with the white Gen 8 style outline).
  Outline versions are kept because they stay readable on dark backgrounds (dark app themes,
  selected rows); they cost under 1 MB. Use `item/{{id}}` on light, `item_outline/{{id}}` on dark.
* Balls (also in items), ribbons (40x40 originals and 128x128 `ribbon/gen8/...`), marks,
  origin marks, special attributes (shiny stars/leaf, Gmax, alpha...), types, type logos,
  body styles, seals.
* **Skipped:** `icons/` (legacy 40x30 box icons and old 32x32 items, fully superseded by
  `pokemon-gen8`/`items`) and the rest of `pokemon-gen7x`.

### Layout

```
pkassets/manifest.json  README.md
pkassets/src/main/assets/sprites/
  pokemon/{{box,box_shiny,box_right,box_shiny_right}}/{{dex}}[-{{form}}][-female].png
  pokemon/box/{{egg,egg-manaphy,unknown,unknown-gen5,mega-evolution-sigil}}.png
  items/{{group}}/{{slug}}.png      items_outline/{{group}}/{{slug}}.png     (pokesprite's own layout)
  misc/{{category}}/[gen*/]{{name}}.png
```

## Lookup rules (what the Kotlin side must follow)

`manifest.json` -> `entries[key] = {{"path": "<asset path>", "w": px, "h": px}}`; `assets.open(path)`.

### Pokemon

* `pokemon/{{set}}/{{dex}}` default form; `pokemon/{{set}}/{{dex}}-{{form}}` other forms;
  `...-female` suffix for the female-specific sprite (`-f` was avoided: Unown form `f` exists).
  `set` = `box`, `box_shiny`, `box_right`, `box_shiny_right`. `dex` is the national dex number
  (no padding). Gen 3 internal species ids must be converted to national numbers first.
* Female: only 20 sprites exist (Pikachu forms, Hippopotas, Hippowdon, Unfezant, Frillish,
  Jellicent, Pyroar, Meowstic, Indeedee, Basculegion, Wobbuffet). Use `-female` when the
  Pokemon is female and the key exists, otherwise the default key.
* Form names are pokesprite slugs (see `pokemonIndex[dex].forms[].form`), e.g. `3-mega`,
  `6-mega-x`, `25-gmax`, `19-alola`, `201-exclamation`, `869-...` (Alcremie: `{{cream}}-{{sweet}}`
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

* `item/{{n}}` and `item_outline/{{n}}`: **n = PKHeX modern item id** (the id PKHeX uses for
  sprites, `bitem_{{n}}`). Convert game-specific ids first:
  * Held items: Gen 2 -> `itemIdMaps.gen2[id]` (`ItemConverter.Item2to4`, {n2} entries,
    `PKHeX.Core/PKM/Util/Conversion/ItemConverter.cs:66-94`), Gen 3 -> `itemIdMaps.gen3[id]`
    (`Item3to4`, {n3} entries, `:97-134`; ids beyond the table, e.g. HMs and FRLG key items,
    are NaN). 0 = no equivalent. PKHeX applies these in `PK2.cs:58`, `PK3.cs:88`; PK1 has no
    held item (`PK1.cs:157`).
  * Gen 1 bag: PKHeX pushes Gen 1 ids through the *Gen 2* table, which is wrong for most
    ids (`ItemConverter.cs:175`; Gen 1 id 4 is Poke Ball, Gen 2 id 4 is Great Ball).
    `itemIdMaps.gen1BagByName` is an **addition of this pack**: Gen 1 id -> modern id via
    English names. Unmapped: {gen1_unmapped}.
* **TM/HM/TR:** pokesprite draws one icon per move type, and its `item_XXXX` ids for TM/HM/TR
  are not PKHeX ids (it maps item 328 = TM01 to `tm/dark`; TM01 is Focus Punch, Fighting).
  Those ids are therefore **not** exposed as `item/{{n}}`; use `item_type/tm/{{type}}`,
  `item_type/hm/{{type}}`, `item_type/tr/{{type}}` (and `item_outline_type/...`) and pick the
  type from the move. Note PKHeX itself maps Gen 2 TMs to Gen 4 TM ids by index only, so a
  per-TM-id lookup would be wrong for Gen 2 anyway.
* Manual override (not in pokesprite's data): item 216 (Exp. Share, held item in Gen 2/3) uses
  `key-item/exp-share`, which pokesprite leaves unlinked (`manifest.itemOverrides`).
* Unlinked pokesprite items (no PKHeX id, e.g. Gen 3 key items with different art, `bicycle`,
  `bike-voucher`, duplicates) are under `item_other/{{group}}/{{slug}}`, with `unlinked` /
  `legacyOf` metadata when pokesprite has it.
* **Item id cross-check** (pokesprite numbering vs PKHeX `text_Items_en.txt`): {chk_matched} of
  {chk_total} comparable ids match by name (case/accent-insensitive prefix match; {chk_skipped}
  TM/HM/TR ids skipped as above). Spot checks: Master Ball 1 -> `{spot1}`, Potion 17 -> `{spot17}`,
  Leftovers 234 -> `{spot234}`. Differences found:
{chk_bad}
  Only 259 (PKHeX `Leek`, formerly Stick) matters for Gen 1-3 and it is a plain rename. The
  rest are renames (Feather/Wing, Guidebook/Journal, Sticker/Seal), pokesprite grouping choices
  (one icon per stat for Mints, per colour for Pokemon Candy, per size for AV candies), or
  pokesprite swapping Z-Crystal pairs (778/780/798/800: Waterium/Primarium and Grassium/Decidium
  icons look swapped, Gen 7 only). Every other id, including all Gen 1-3 items, matches.
* Gen 1-3 reachable items: {n_reach} modern ids come out of the converter tables / bag
  lists (`ItemStorage2.cs`, `ItemStorage3RS.cs`, `ItemStorage3E.cs`, `ItemStorage3FRLG.cs`);
  {n_typeonly} are TM/HM (type icons only) and {n_noicon} have no pokesprite icon at all:
  {noicon_list}. Colosseum/XD items (ids 500+) have no converter entry.

### Balls

`ball/{{n}}` where n = PKHeX `Ball` enum value (`PKHeX.Core/Game/Enums/Ball.cs`: Master 1 ... Premier 12,
Cherish 16, ... LAOrigin 37), or `ball/{{slug}}` (`poke`, `master`, ... `hisuian-poke`, `park`).
`ball_outline/...` likewise. Gen 3 stores 1-12; Gen 1/2 Pokemon have no ball (use `poke`).
Balls are also reachable as `item/{{id}}` (ids 1-16 equal the Ball ids).

### Ribbons and other misc

Key = path under `misc/` without `.png`: `ribbon/{{name}}` (40x40), `ribbon/gen8/{{name}}`
(128x128, where pokesprite has it), `mark/...`, `origin-marks/{{home|legends-arceus}}/...`,
`special-attribute/...`, `types/{{gen8|go|masters}}/...`, `type-logos/...`, `body-style/...`,
`seals/...`. `miscIndex[category]` carries names (eng/jpn/romaji), `origin_gen`, descriptions
and the gen -> key map, so match ribbons by name or `origin_gen`.
Ribbons with `origin_gen` <= 3: {gen3_ribbons}.

## Missing / not available

Gen 1-3 Pokemon: nothing is missing (all 386 species, every Gen 1-3 form, normal and shiny).
Everything below is *expected* (from pokesprite's own data files or from PKHeX's Gen 1-3
tables) but absent. Full list in `manifest.json` -> `missing`.

{missing}

No status-condition icons, held-item marker, party marks, slot highlight accents or
legality icons exist in pokesprite (the previous PKHeX-sourced pack had them); draw those in
the app.

## Notes

* pokesprite Pokemon/item sprites are 68x56 / 32x32; misc images range from 16 px to 256 px.
* Keys and paths use only `[a-z0-9._/-]`.
"""


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--pokesprite", required=True, help="path to a pokesprite clone (read only)")
    ap.add_argument("--pkhex", required=True, help="path to a PKHeX clone (data only, read only)")
    ap.add_argument("--out", required=True, help="output directory (e.g. pkassets)")
    ap.add_argument("--generated", default=dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%d"))
    ap.add_argument("--commit", help="pokesprite commit sha (default: read from .git)")
    ap.add_argument("--commit-date", help="pokesprite commit date")
    ap.add_argument("--pkhex-commit", help="PKHeX commit sha (default: read from .git)")
    ap.add_argument("--pkhex-commit-date", help="PKHeX commit date")
    args = ap.parse_args(argv)
    try:
        build(args)
    except PackError as e:
        print(f"error: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
