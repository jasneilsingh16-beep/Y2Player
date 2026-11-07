#!/usr/bin/env python3
"""Cuts the Gurmukhi faces the Shabad page draws Gurbani in, from Noto Sans Gurmukhi.

    python3 tools/fonts/build-gurmukhi.py "NotoSansGurmukhi[wdth,wght].ttf" app/src/main/assets/fonts

The variable font is in Google Fonts (ofl/notosansgurmukhi). Needs fontTools.
KitKat has no good Gurmukhi face of its own and cannot read variable fonts, so
two static weights are cut, unhinted as the Inter faces are: medium for the
tuks, semibold for the one in focus. Every Gurmukhi glyph and its shaping rules
(half forms, subscripts, matras) are kept, plus the vishraam marks and digits.
"""
import sys
from pathlib import Path

from fontTools.subset import Options, Subsetter
from fontTools.ttLib import TTFont, newTable
from fontTools.varLib.instancer import instantiateVariableFont

FACES = [("gurmukhi_medium.ttf", 500), ("gurmukhi_semibold.ttf", 600)]
UNICODES = list(range(0x0A00, 0x0A80)) + list(range(0x20, 0x7F)) + [0x00A0, 0x0964, 0x0965, 0x200C, 0x200D, 0x25CC]


def build(source: Path, out: Path, name: str, weight: int) -> None:
    font = instantiateVariableFont(TTFont(source), {"wght": weight, "wdth": 100})
    options = Options()
    options.layout_features = ["*"]
    options.name_IDs = ["*"]
    options.notdef_outline = True
    options.hinting = False
    subsetter = Subsetter(options)
    subsetter.populate(unicodes=UNICODES)
    subsetter.subset(font)
    # The font carries only the newer 'gur2' script tag; an older shaper looks for
    # 'guru'. Gurmukhi shapes the same under both, so 'guru' points at the same rules.
    import copy
    for table in ("GSUB", "GPOS"):
        records = font[table].table.ScriptList.ScriptRecord
        tags = {r.ScriptTag for r in records}
        if "gur2" in tags and "guru" not in tags:
            old = copy.deepcopy(next(r for r in records if r.ScriptTag == "gur2"))
            old.ScriptTag = "guru"
            records.append(old)
            records.sort(key=lambda r: r.ScriptTag)
            font[table].table.ScriptList.ScriptCount = len(records)
    for tag in ("fpgm", "prep", "cvt ", "hdmx", "LTSH", "VDMX"):
        if tag in font:
            del font[tag]
    gasp = newTable("gasp")
    gasp.version = 1
    gasp.gaspRange = {0xFFFF: 0x000A}
    font["gasp"] = gasp
    font["OS/2"].usWeightClass = weight
    font.save(out / name)
    print(f"{name}: wght {weight}, {(out / name).stat().st_size // 1024} KB")


def main() -> None:
    source, out = Path(sys.argv[1]), Path(sys.argv[2])
    out.mkdir(parents=True, exist_ok=True)
    for name, weight in FACES:
        build(source, out, name, weight)


if __name__ == "__main__":
    main()
