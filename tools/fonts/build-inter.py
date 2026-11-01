#!/usr/bin/env python3
"""Cuts the app's Inter faces from Inter's variable font, so the Y2 draws text
the way the design boards do in a browser.

    python3 tools/fonts/build-inter.py InterVariable.ttf app/src/main/assets/fonts

InterVariable.ttf is in the Inter release zip (https://github.com/rsms/inter,
v4.1). Needs fontTools.

Why each step, against what KitKat does with a static font:
  * Optical size. A browser sets Inter's opsz axis from the font size, which
    tightens and firms the letters as they grow. The static "Text" cut is drawn
    for 14 px, so on the Y2 titles came out looser and paler than on the boards.
    Each face is cut at the size it is mostly used at.
  * No hinting. Browsers ignore TrueType hints; KitKat's FreeType obeys them,
    snapping stems to whole pixels and changing the letter shapes.
  * Letter-spacing. The boards draw the Now Playing title at -0.4 px, and
    letterSpacing only came to Android in API 21, so that face has it built into
    its advance widths.
  * Tabular figures are the default digits, so times and counters hold still
    (KitKat cannot switch on tnum either).
  * Latin only, as before: Gurmukhi falls back to the system font.
"""
import sys
from pathlib import Path

from fontTools.subset import Options, Subsetter
from fontTools.ttLib import TTFont, newTable
from fontTools.varLib.instancer import instantiateVariableFont

# name, weight, optical size, letter-spacing in em. The optical size is the px
# size each face is mostly drawn at on the Y2, which is what a browser would pick.
FACES = [
    # Second lines, values, times and the status bar: 14-15 px.
    ("inter_regular.ttf", 400, 15, 0.0),
    # Row titles: 17-19 px.
    ("inter_medium.ttf", 500, 18, 0.0),
    # Screen titles, pane titles and labels: 14-17 px.
    ("inter_semibold.ttf", 600, 16, 0.0),
    # The Now Playing title alone: 23-24 px, -0.4 px on the boards.
    ("inter_bold.ttf", 700, 24, -0.017),
]

# The same letters the earlier bundle carried: Latin with its accents, punctuation,
# the euro, arrows, the minus sign, the play triangle and the tick.
RANGES = [
    (0x0020, 0x007E), (0x00A0, 0x024F), (0x0300, 0x036F), (0x2010, 0x2027), (0x2030, 0x2057),
    (0x20AC, 0x20AC), (0x2116, 0x2116), (0x2122, 0x2122), (0x2190, 0x2193), (0x2212, 0x2212),
    (0x25B6, 0x25B6), (0x2713, 0x2713),
]
UNICODES = [code for start, end in RANGES for code in range(start, end + 1)]


def freeze_tabular_figures(font: TTFont) -> None:
    gsub = font["GSUB"].table
    mapping = {}
    for record in gsub.FeatureList.FeatureRecord:
        if record.FeatureTag != "tnum":
            continue
        for index in record.Feature.LookupListIndex:
            for subtable in gsub.LookupList.Lookup[index].SubTable:
                mapping.update(getattr(subtable, "mapping", {}) or {})
    for table in font["cmap"].tables:
        for code, glyph in list(table.cmap.items()):
            if glyph in mapping:
                table.cmap[code] = mapping[glyph]


def remove_hinting(font: TTFont) -> None:
    for tag in ("fpgm", "prep", "cvt ", "hdmx", "LTSH", "VDMX"):
        if tag in font:
            del font[tag]
    for name in font.getGlyphOrder():
        glyph = font["glyf"][name]
        if hasattr(glyph, "program"):
            glyph.program.fromBytecode(b"")
    gasp = newTable("gasp")
    gasp.version = 1
    gasp.gaspRange = {0xFFFF: 0x000A}  # smooth at every size, never grid-fit
    font["gasp"] = gasp


def track(font: TTFont, em: float) -> None:
    if em == 0.0:
        return
    units = round(em * font["head"].unitsPerEm)
    hmtx = font["hmtx"]
    for name, (advance, lsb) in list(hmtx.metrics.items()):
        if advance > 0:
            hmtx.metrics[name] = (max(0, advance + units), lsb)


def build(source: Path, out: Path, name: str, weight: int, opsz: int, tracking: float) -> None:
    font = TTFont(source)
    font = instantiateVariableFont(font, {"wght": weight, "opsz": opsz})
    freeze_tabular_figures(font)
    options = Options()
    options.layout_features = ["kern", "calt", "liga", "ccmp", "locl", "mark", "mkmk"]
    options.name_IDs = ["*"]
    options.notdef_outline = True
    options.hinting = False
    subsetter = Subsetter(options)
    subsetter.populate(unicodes=UNICODES)
    subsetter.subset(font)
    remove_hinting(font)
    track(font, tracking)
    font["OS/2"].usWeightClass = weight
    font.save(out / name)
    print(f"{name}: wght {weight}, opsz {opsz}, tracking {tracking} em, {(out / name).stat().st_size // 1024} KB")


def main() -> None:
    source, out = Path(sys.argv[1]), Path(sys.argv[2])
    out.mkdir(parents=True, exist_ok=True)
    for face in FACES:
        build(source, out, *face)


if __name__ == "__main__":
    main()
