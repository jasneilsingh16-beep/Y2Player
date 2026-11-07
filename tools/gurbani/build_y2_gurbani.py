#!/usr/bin/env python3
"""Puts Sri Guru Granth Sahib Ji, with English, on the Y2's card, and works out
which shabad or bani each recording on the card is.

    python tools/gurbani/build_y2_gurbani.py "G:/My Drive/Gurbani App" I:/

Reads, never writes, the Gurbani App folder:
  cache/sggs_saroop.sqlite            every line of all 1,430 angs, in Granth order
  scripts/saroop/english/ang-*.txt    condensed modern English of Prof. Sahib Singh's
                                      arth for every tuk, AI-rendered, not yet checked
                                      line by line
  scripts/saroop/saroop_data.py       the classifier (mangal, headings, rahao)
Nothing is retyped: every Gurmukhi string is copied from the database and every
English string from the English files, as export_sehaj_paath.py does.

Writes on the card, in Y2Player/Gurbani/:
  sggs.db             lines, shabads and the recording-to-Gurbani table the player reads
  to-check.csv        recordings whose shabad is not certain, with the best candidates
  overrides.tsv       path <TAB> line id, path <TAB> - for none, or path <TAB> * to undo
                      an earlier choice; the last line for a path counts. Read on every run and
                      always wins. The player adds to it when a shabad is confirmed or
                      corrected while it plays, so choices made either side are kept.

A recording is matched as certain only when the title reads like one line of the
Granth, clearly better than any line of another shabad. A likely but uncertain
match is still written, marked unconfirmed with its other candidates, and the
player says so plainly and lets it be confirmed or changed while it plays.
"""
import csv
import difflib
import json
import math
import os
import re
import sqlite3
import sys
import unicodedata
from collections import defaultdict

SURE_SCORE = .80
SURE_MARGIN = .08
CHECK_SCORE = .62
SHORT_SCORE = .90
# However high the score, one title word that fits nothing means another line.
WORD_FLOOR = .3
# The plain-spelling reading of the title must also like the line this much.
STRICT_SCORE = .80
AUDIO = {".mp3", ".m4a", ".flac", ".ogg", ".opus", ".m4b", ".wav", ".aac"}

AUTHORS = {
    "NANK": "Guru Nanak Dev Ji", "ANGD": "Guru Angad Dev Ji", "AMAR": "Guru Amar Das Ji",
    "RAMD": "Guru Ram Das Ji", "ARJN": "Guru Arjan Dev Ji", "TGBD": "Guru Tegh Bahadur Ji",
    "KABI": "Bhagat Kabir Ji", "NMDV": "Bhagat Namdev Ji", "RAVI": "Bhagat Ravidas Ji",
    "FRID": "Sheikh Farid Ji", "TRIL": "Bhagat Trilochan Ji", "BENI": "Bhagat Beni Ji",
    "DHNA": "Bhagat Dhanna Ji", "JEDV": "Bhagat Jaidev Ji", "BKHN": "Bhagat Bhikhan Ji",
    "SAIN": "Bhagat Sain Ji", "PIPA": "Bhagat Pipa Ji", "SDNA": "Bhagat Sadhna Ji",
    "RMND": "Bhagat Ramanand Ji", "PMND": "Bhagat Parmanand Ji", "SRDS": "Bhagat Surdas Ji",
    "SETB": "Satta and Balvand", "SNDR": "Baba Sundar Ji", "KLSR": "Bhatt Kal", "JALP": "Bhatt Jalap",
    "KIRT": "Bhatt Kirat", "BKHA": "Bhatt Bhikha", "SALH": "Bhatt Sal", "BHLH": "Bhatt Bhal",
    "NALH": "Bhatt Nal", "GYND": "Bhatt Gyand", "MTHR": "Bhatt Mathura", "BALH": "Bhatt Bal",
    "HRBN": "Bhatt Harbans", "ALAM": "Mardana",
}

# Nitnem recordings by name: the bani's heading on the saroop (its 'B' line) and the
# heading that ends it. Rehras and the Dasam Granth banis are not in this text.
BANIS = {
    "japji": ("RBP6", "9ANB", True),        # from the Mool Mantar, to So Dar
    "sohila": ("EJU3", None, False),        # to the next bani heading (Sri Raag)
    "sukhmani": ("WEC7", None, False),
    "anand": ("LTKA", None, False),
    "asavaar": ("6WX1", "1233", False),     # Asa di Vaar: ang 462 to the end of ang 475
}
BANI_NAMES = [("japji", "japji"), ("jap ji", "japji"), ("sohila", "sohila"),
              ("sukhmani", "sukhmani"), ("anand sahib", "anand"),
              ("asa di vaar", "asavaar"), ("asa ki vaar", "asavaar")]
# A recording titled as a whole bani, anywhere on the card, as folded words. What may
# follow the name is only a note: "Sahib", "Path", a raag, a taal, a year. Anything
# else ("... Katha And Anand Sahib") means the title is something other than the bani.
BANI_TITLES = [   # the name as sung, then the bani; matched on folded words (see bani_of)
    (("japji", "jap ji"), "japji"),
    (("sohila",), "sohila"),
    (("sukhmani",), "sukhmani"),
    (("anand sahib",), "anand"),
    (("asa di var", "asa ki var", "asa ke var", "asa de var", "aasa di vaar", "asa di vaar", "asa ki vaar"), "asavaar"),
]
BANI_NOTES = "sahib ji path paath full complete v version live new old raag rag".split()
# Kirtan of a bani is sung to a taal; the singer is then usually on its opening, not on all of it.
TAALS = {"ektaal", "tintaal", "teentaal", "jhaptaal", "chautaal", "keharwa", "kaharwa", "dadra", "rupak",
         "deepchandi", "chartaal", "ektal", "tintal"}


# ---------------------------------------------------------------- romanisation
CONS = {
    "ਕ": "k", "ਖ": "kh", "ਗ": "g", "ਘ": "gh", "ਙ": "n", "ਚ": "ch", "ਛ": "chh", "ਜ": "j", "ਝ": "jh",
    "ਞ": "n", "ਟ": "t", "ਠ": "th", "ਡ": "d", "ਢ": "dh", "ਣ": "n", "ਤ": "t", "ਥ": "th", "ਦ": "d",
    "ਧ": "dh", "ਨ": "n", "ਪ": "p", "ਫ": "ph", "ਬ": "b", "ਭ": "bh", "ਮ": "m", "ਯ": "y", "ਰ": "r",
    "ਲ": "l", "ਵ": "v", "ੜ": "r", "ਸ": "s", "ਹ": "h", "ਸ਼": "sh", "ਖ਼": "kh", "ਗ਼": "g", "ਜ਼": "z",
    "ਫ਼": "f", "ਲ਼": "l",
}
VOWELS = {"ਅ": "a", "ਆ": "aa", "ਇ": "i", "ਈ": "ee", "ਉ": "u", "ਊ": "oo", "ਏ": "e", "ਐ": "ai",
          "ਓ": "o", "ਔ": "au", "ੳ": "", "ੲ": ""}
SIGNS = {"ਾ": "aa", "ਿ": "i", "ੀ": "ee", "ੁ": "u", "ੂ": "oo", "ੇ": "e", "ੈ": "ai", "ੋ": "o", "ੌ": "au"}
GURMUKHI = re.compile(r"[\u0A00-\u0A7F]")


def roman_word(word):
    """A plain romanisation of one Gurmukhi word, as kirtan titles are spelt."""
    word = unicodedata.normalize("NFC", word)
    out, chars, i = [], list(word), 0
    while i < len(chars):
        c = chars[i]
        nxt = chars[i + 1] if i + 1 < len(chars) else ""
        if c in CONS or (c + "਼") in CONS and nxt == "਼":
            if nxt == "਼":
                c, i = c + "਼", i + 1
                nxt = chars[i + 1] if i + 1 < len(chars) else ""
            out.append(CONS.get(c, ""))
            if nxt in SIGNS or nxt == "੍" or nxt == "":
                pass
            else:
                out.append("a")
        elif c in VOWELS:
            out.append(VOWELS[c])
        elif c in SIGNS:
            # Gurbani's closing sihari and aunkar are grammar, not sung.
            if i == len(chars) - 1 and c in "ਿੁ" and len(out) > 1:
                pass
            else:
                out.append(SIGNS[c])
        elif c in "ੰਂ":
            out.append("n")
        elif c == "ੱ":
            if nxt in CONS:
                out.append(CONS[nxt][0])
        i += 1
    text = "".join(out)
    return text[:-1] if len(text) > 2 and text.endswith("a") and text[-2] not in "aeiou" else text


def fold(text, strict=False):
    """Makes the many spellings of one sung word compare equal. [strict] leaves the
    last-sound and vowel-run allowances out: it is the second opinion on a match."""
    t = text.lower()
    t = re.sub(r"[^a-z ]", " ", t)
    t = t.replace("w", "v").replace("z", "j").replace("q", "k").replace("f", "p")
    t = t.replace("y", "i")
    t = re.sub(r"([kgcjtdpbs])h+", r"\1", t)
    # Long and short vowels are one sound in a title ("Aukhi" and ਅਉਖੀ), so they are
    # joined before doubled letters are, or "ee" becomes "e" and never meets "i".
    t = t.replace("aa", "a").replace("ee", "i").replace("oo", "u").replace("ai", "e").replace("au", "o")
    t = re.sub(r"(.)\1+", r"\1", t)
    # "Piaria" and "piareya": a run of vowels in a title is a guess, so only its last counts.
    if strict:
        return " ".join(t.split())
    t = re.sub("[aeiou]{2,}", lambda m: m.group()[-1], t)
    return " ".join(_ending(w) for w in t.split())


def _ending(word):
    """A word's last sound is the least reliable: ਧਾਰਹੁ is sung "dharo", "dharu" or "dharahu"
    and a title may spell any of them. An 'h' after a vowel and then the final vowel are left off."""
    if len(word) > 2 and word.endswith("h") and word[-2] in "aeiou":
        word = word[:-1]
    if len(word) > 2 and word[-1] in "aeiou" and word[-2] not in "aeiou":
        word = word[:-1]
    return word


def skeleton(folded):
    """The consonants of a folded word. Titles drop and add short vowels freely
    ("Bandgi" for ਬੰਦਗੀ); the consonants are what they keep. ਛ, ਚ and ਸ਼ are spelt
    interchangeably with ਸ."""
    return re.sub(r"[aeiou]", "", folded).replace("c", "s").replace("j", "s")


def similarity(a, b):
    """How alike two folded strings are: by spelling, or by consonants alone."""
    full = difflib.SequenceMatcher(None, a, b).ratio()
    sa, sb = skeleton(a), skeleton(b)
    # Consonants only vouch for a pair that already looks something alike.
    if full < .5 or len(sa.replace(" ", "")) < 2 or len(sb.replace(" ", "")) < 2:
        return full
    return max(full, difflib.SequenceMatcher(None, sa, sb).ratio() - .10)


def gurmukhi_words(line):
    return [w for w in re.split(r"[\s;.,॥]+", line) if w and not re.fullmatch(r"[੦-੯]+", w)]


# ---------------------------------------------------------------- the text
def load_text(app):
    sys.path.insert(0, os.path.join(app, "scripts", "saroop"))
    import saroop_data as sd                     # noqa: E402
    from build_saroop import parse_english, ENGLISH_DIR  # noqa: E402
    paths = sorted(os.path.join(ENGLISH_DIR, f) for f in os.listdir(ENGLISH_DIR)
                   if f.startswith("ang-") and f.endswith(".txt"))
    _, english, _ = parse_english(paths)
    lines = sd.classify(sd.load_range(1, 1430, pad=0))
    rows, missing = [], []
    for seq, ln in enumerate(lines):
        kind_file, text = english.get(ln["id"], (None, None))
        kind = kind_file or ln["k"]
        if kind in "MBH":
            text = text if text is not None else ln.get("auto_en", "")
        elif text is None:
            missing.append(ln["id"])
            text = ""
        rows.append({
            "seq": seq, "id": ln["id"], "ang": ln["ang"], "kind": kind, "grp": ln["grp"],
            "gurmukhi": sd.clean(ln["g"]), "english": text.lstrip("~").strip(),
            "nosrc": 1 if text.startswith("~") else 0, "section": ln["section"],
        })
    con = sqlite3.connect(f"file:{sd.CACHE}?mode=ro", uri=True)
    author = dict(con.execute("select id, author from lines"))
    con.close()
    for row in rows:
        row["author"] = AUTHORS.get(author.get(row["id"]), author.get(row["id"]) or "")
    return rows, missing


def shabads(rows):
    """Each run of one grp is a shabad (or, in a long bani, a pauri or astpadi)."""
    out, start = [], 0
    for i in range(1, len(rows) + 1):
        if i == len(rows) or rows[i]["grp"] != rows[start]["grp"]:
            out.append((start, i - 1))
            start = i
    return out


def bani_ranges(rows):
    by_id = {r["id"]: r["seq"] for r in rows}
    bands = [r["seq"] for r in rows if r["kind"] == "B"]
    result = {}
    for name, (first, end, from_top) in BANIS.items():
        start = 0 if from_top else by_id[first]
        stop = by_id[end] - 1 if end else next(b for b in bands if b > by_id[first]) - 1
        result[name] = (start, stop)
    return result


# ---------------------------------------------------------------- recordings
TITLE_CACHE = {}


def bani_of(title):
    """('anand', kirtan?) when [title] names a whole bani and nothing else, or None."""
    words = fold(re.sub(r"\(.*?\)|\[.*?\]", " ", title)).split()
    text = " ".join(words)
    for names, key in BANI_TITLES:
        hit = next((n for n in map(fold, names) if text == n or text.startswith(n + " ")), None)
        if hit is None:
            continue
        rest = text[len(hit):].split()
        taal = any(w in {fold(t) for t in TAALS} for w in rest)
        # "Part 2" is a piece of a bani: the text would not line up with the recording.
        if any(w in ("part", "pt", "prt", "pauri", "paurhi", "ashtpadi") for w in rest):
            return None
        known = {fold(w) for w in BANI_NOTES} | EXTRAS_FOLDED | {fold(t) for t in TAALS}
        if all(w in known or w.isdigit() for w in rest):
            return key, taal
    return None


def title_of(path):
    stat = os.stat(path)
    key = f"{path}|{stat.st_size}|{int(stat.st_mtime)}"
    if key not in TITLE_CACHE:
        TITLE_CACHE[key] = read_title(path)
    return TITLE_CACHE[key]


def read_title(path):
    try:
        from mutagen import File
        tags = File(path, easy=True)
        title = (tags or {}).get("title", [None])[0]
        if title:
            return title
    except Exception:                               # noqa: BLE001
        pass
    return os.path.splitext(os.path.basename(path))[0].split(" - ")[0]


def recordings(card):
    for folder, _, files in os.walk(card):
        rel_folder = os.path.relpath(folder, card).replace("\\", "/")
        if rel_folder.startswith(("Y2Player", "System Volume", "$RECYCLE", "LOST.DIR", ".")):
            continue
        for name in files:
            if os.path.splitext(name)[1].lower() in AUDIO:
                path = os.path.join(folder, name)
                yield os.path.relpath(path, card).replace("\\", "/"), path


SPOKEN = ("sikhi talks", "ndes", "plum village", "podcast", "katha")
ANG_RANGE = re.compile(r"\bang\s*0*(\d{1,4})\s*(?:[-–]|to)\s*(?:ang\s*)?0*(\d{1,4})\b", re.I)
# What singers and uploaders add after the line: raag, taal, part, version, notes.
TITLE_EXTRAS = set("""
partaal incomplete complete part pt prt katha with live studio version remix new old full short
first second third st nd rd th dhrupad dharna kirtan shabad paurhi pauri pauree vaar vaaran
keharwa kaharwa teentaal tintal dadra rupak ektaal jhaptaal chautaal chartaal deepchandi
raag rag basant bhupali kafi asa aasa bilawal bilaval malhar kalyan bhairav bhairo sarang todi
gauri gaudi sorath dhanasri dhanasari suhi soohi maru maaru kedara kanra kaanra gujri jaitsri
ramkali tilang vadhans wadhans majh maajh bihagra jaijaiwanti pahari darbari yaman desh khamaj
peelu bhimpalasi prabhati parbhati nat narayan mali goura todee siri
jan feb mar apr may jun jul aug sep oct nov dec
""".split())
EXTRAS_FOLDED = {fold(w) for w in TITLE_EXTRAS}
SUNG_FILLERS = {fold(w) for w in "re mere mera meri man mana ji jio jiu he hai o".split()}


MAX_OFFSET = 4          # a title may begin up to this many words into a line


def tokens_of(word):
    """A word as it is spelt, and as its consonants alone (a title drops short vowels)."""
    sk = skeleton(word)
    if len(sk) < 2:
        return (word,)
    # Without nasals too: ਅੰਮ੍ਰਿਤ is "anmrit" here and "amrit" in a title.
    bare = sk.replace("n", "").replace("m", "")
    return (word, "~" + sk, "^" + bare) if len(bare) >= 2 else (word, "~" + sk)


class Matcher:
    def __init__(self, rows, units):
        self.rows = rows
        self.unit_of = {}
        for number, (a, b) in enumerate(units):
            for s in range(a, b + 1):
                self.unit_of[s] = number
        self.lines = []
        self.index = defaultdict(list)
        self.opening = set()
        self.words_at = {}
        for a, b in units:
            first = next((s for s in range(a, b + 1) if rows[s]["kind"] in "TR"), None)
            if first is not None:
                self.opening.add(first)
        for r in rows:
            if r["kind"] not in "TR":
                continue
            words = [fold(roman_word(w)) for w in gurmukhi_words(r["gurmukhi"])]
            words = [w for w in words if w]
            if len(words) < 2:
                continue
            index = len(self.lines)
            self.lines.append((r["seq"], words))
            self.words_at[r["seq"]] = words
            # Lines are found by the words they share with a title, not by how they begin:
            # a title may start inside a line ("Khair deejai bandagi" is the middle of
            # "Sekh Faridai khair deejai bandagi"), and a singer's spelling may be off in
            # the very first word ("Kar" for ਹਰਿ).
            for token in {t for w in words[:MAX_OFFSET + 5] for t in tokens_of(w)}:
                self.index[token].append(index)
        total = max(len(self.lines), 1)
        self.idf = {t: math.log(total / len(v)) for t, v in self.index.items()}

    def candidates(self, title_words, limit=250):
        """The lines sharing at least two of the title's words (or their consonants), best first."""
        hits = defaultdict(set)
        weight = defaultdict(float)
        for n, word in enumerate(title_words):
            found = {}
            for token in tokens_of(word):
                # A word on a third of all lines ("na") narrows nothing and costs a lot.
                if self.idf.get(token, 0.0) < 1.5:
                    continue
                for i in self.index.get(token, ()):
                    found[i] = max(found.get(i, 0.0), self.idf.get(token, 0.0))
            for i, w in found.items():
                hits[i].add(n)
                weight[i] += w
        need = 1   # one word is often spelt too far off to match; the rest still find the line
        pool = [(weight[i], i) for i, got in hits.items() if len(got) >= need]
        pool.sort(reverse=True)
        return {i for _, i in pool[:limit]}

    def score(self, title_words, index):
        seq, words = self.lines[index]
        # Long titles run on into the next line.
        if len(title_words) > len(words) and index + 1 < len(self.lines):
            words = words + self.lines[index + 1][1]
        best = 0.0
        # Only a sung filler may come before the line's own first word.
        for skip in ((0, 1) if title_words[0] in SUNG_FILLERS else (0,)):
            t = title_words[skip:]
            if len(t) < 2:
                continue
            joined = " ".join(t)
            # Beginning inside the line is only believable for a title of three words or more.
            for k in range(0, (MAX_OFFSET if len(t) >= 3 else 0) + 1):
                window = words[k:k + len(t)]
                if len(window) < max(2, len(t) - 1):
                    break
                # Words the singer added, and words the title left out, cost a little.
                ratio = similarity(joined, " ".join(window)) - .04 * skip - .05 * k
                best = max(best, ratio)
        return best

    def word_floor(self, title_words, seq):
        """The worst-fitting title word, when the title is laid word by word over the line.

        A title word may stand for two line words run together ("kachhathon" for
        ਕਿਛੁ ਹਥਹੁ), or two title words for one. One word that fits nothing
        ("heera" over ਹਰਹਟ) is what a near miss looks like."""
        whole = self.words_at.get(seq, [])
        if len(title_words) > len(whole) and seq + 1 in self.words_at:
            whole = whole + self.words_at[seq + 1]
        return max((self._floor(title_words, whole[k:]) for k in range(0, MAX_OFFSET + 1) if whole[k:]),
                   default=0.0)

    @staticmethod
    def _floor(title_words, line):
        n, m = len(title_words), len(line)
        if not m:
            return 0.0
        sim = similarity
        inf = float("-inf")
        # best[i][j]: the best (sum, floor) laying title[:i] over line[:j]
        best = [[(inf, 0.0)] * (m + 1) for _ in range(n + 1)]
        best[0][0] = (0.0, 1.0)
        for i in range(n + 1):
            for j in range(m + 1):
                total, floor = best[i][j]
                if total == inf:
                    continue
                steps = []
                if i < n and j < m:
                    steps.append((1, 1, sim(title_words[i], line[j])))
                if i < n and j + 1 < m:
                    steps.append((1, 2, sim(title_words[i], line[j] + line[j + 1])))
                if i + 1 < n and j < m:
                    steps.append((2, 1, sim(title_words[i] + title_words[i + 1], line[j])))
                for di, dj, s in steps:
                    cand = (total + s, min(floor, s))
                    if cand > best[i + di][j + dj]:
                        best[i + di][j + dj] = cand
        # The title may stop before the line does.
        return max((best[n][j][1] for j in range(m + 1) if best[n][j][0] != inf), default=0.0)

    def strict_score(self, text, seq):
        """How well the title reads as the line by plain spelling, without the loose
        allowances that find lines. Loose finding, strict agreeing: both must like a line."""
        twords = [w for w in fold(self._notes_off(text), strict=True).split() if w]
        line = self.strict_words(seq)
        if len(twords) < 2 or not line:
            return 0.0
        if len(twords) > len(line) and seq + 1 in self.words_at:
            line = line + self.strict_words(seq + 1)
        joined, best = " ".join(twords), 0.0
        for k in range(0, (MAX_OFFSET if len(twords) >= 3 else 0) + 1):
            window = line[k:k + len(twords)]
            if len(window) < max(2, len(twords) - 1):
                break
            best = max(best, difflib.SequenceMatcher(None, joined, " ".join(window)).ratio() - .05 * k)
        return best

    def strict_words(self, seq):
        if not hasattr(self, "_strict"):
            self._strict = {}
        if seq not in self._strict:
            row = self.rows[seq]
            self._strict[seq] = [w for w in (fold(roman_word(x), strict=True) for x in gurmukhi_words(row["gurmukhi"])) if w]
        return self._strict[seq]

    @staticmethod
    def _notes_off(text):
        text = re.sub(r"\(.*?\)|\[.*?\]", " ", text)
        if re.search(r"\bpart\s*\d", text, re.I):
            text = re.sub(r"\b(part|pt|live|kirtan|shabad|bhai|sahib|ji)\b.*$", " ", text, flags=re.I)
        words = text.split()
        return " ".join(words[:3] + [w for w in words[3:] if fold(w) not in EXTRAS_FOLDED and not w.isdigit()])

    def title_words(self, text):
        text = re.sub(r"\(.*?\)|\[.*?\]", " ", text)
        if re.search(r"\bpart\s*\d", text, re.I):
            text = re.sub(r"\b(part|pt|live|kirtan|shabad|bhai|sahib|ji)\b.*$", " ", text, flags=re.I)
        words = [w for w in fold(text).split() if w]
        # Keep the first three words whatever they are; past them, drop raag, taal and notes.
        return words[:3] + [w for w in words[3:] if fold(w) not in EXTRAS_FOLDED and not w.isdigit()]

    def match(self, title):
        text = " ".join(roman_word(w) for w in gurmukhi_words(title)) if GURMUKHI.search(title) else title
        # "Shaad" is often how ਛਾਡਿ is spelt, and "Shekh" how ਸੇਖ is: try both readings.
        spellings = [text]
        if re.search(r"sh", text, re.I):
            spellings.append(re.sub(r"sh", "ch", text, flags=re.I))
        best_words, best_ranked = None, []
        self.last_texts = spellings
        for spelling in spellings:
            words = self.title_words(spelling)
            if len(words) < 2:
                continue
            scored = sorted(((self.score(words, i), i) for i in self.candidates(words)), reverse=True)
            best_by_unit = {}
            for s, i in scored:
                unit = self.unit_of[self.lines[i][0]]
                if unit not in best_by_unit:
                    best_by_unit[unit] = (s, i)
            ranked = [(s, self.lines[i][0]) for s, i in sorted(best_by_unit.values(), reverse=True)[:3]]
            if best_words is None or (ranked and (not best_ranked or ranked[0][0] > best_ranked[0][0])):
                best_words, best_ranked = words, ranked
        return best_words, best_ranked


# ---------------------------------------------------------------- output
def write_db(path, rows, units, tracks, candidates):
    if os.path.exists(path):
        os.remove(path)
    con = sqlite3.connect(path)
    con.executescript("""
        CREATE TABLE info (key TEXT PRIMARY KEY, value TEXT);
        CREATE TABLE lines (seq INTEGER PRIMARY KEY, id TEXT, ang INTEGER, kind TEXT,
            gurmukhi TEXT, english TEXT, nosrc INTEGER, section TEXT, author TEXT, shabad INTEGER);
        CREATE TABLE shabads (shabad INTEGER PRIMARY KEY, first_seq INTEGER, last_seq INTEGER);
        CREATE TABLE tracks (path TEXT PRIMARY KEY COLLATE NOCASE, kind TEXT,
            first_seq INTEGER, last_seq INTEGER, focus_seq INTEGER, label TEXT, confident INTEGER);
        CREATE TABLE candidates (path TEXT COLLATE NOCASE, rank INTEGER, focus_seq INTEGER,
            line_id TEXT, score REAL, PRIMARY KEY (path, rank));
    """)
    unit_of = {}
    for number, (a, b) in enumerate(units):
        con.execute("INSERT INTO shabads VALUES (?,?,?)", (number, a, b))
        for s in range(a, b + 1):
            unit_of[s] = number
    con.executemany("INSERT INTO lines VALUES (?,?,?,?,?,?,?,?,?,?)", [
        (r["seq"], r["id"], r["ang"], r["kind"], r["gurmukhi"], r["english"], r["nosrc"],
         r["section"], r["author"], unit_of[r["seq"]]) for r in rows])
    con.executemany("INSERT INTO tracks VALUES (?,?,?,?,?,?,?)", tracks)
    con.executemany("INSERT INTO candidates VALUES (?,?,?,?,?)", candidates)
    con.executemany("INSERT INTO info VALUES (?,?)", [
        ("version", "2"),
        ("english", "Condensed English of Prof. Sahib Singh's arth, AI-rendered, not yet checked line by line"),
        ("lines", str(len(rows))),
    ])
    con.execute("CREATE INDEX lines_ang ON lines(ang)")
    con.execute("CREATE INDEX lines_id ON lines(id)")
    con.commit()
    con.execute("VACUUM")
    con.close()


def read_overrides(path):
    out = {}
    if os.path.exists(path):
        for raw in open(path, encoding="utf-8-sig"):
            parts = raw.rstrip("\r\n").split("\t")
            if len(parts) >= 2 and parts[0] and not parts[0].startswith("#"):
                out[parts[0].replace("\\", "/").lower()] = parts[1].strip()
    return out


def main():
    app, card = sys.argv[1], sys.argv[2]
    sys.stdout.reconfigure(encoding="utf-8")
    rows, missing = load_text(app)
    units = shabads(rows)
    banis = bani_ranges(rows)
    seq_of_id = {r["id"]: r["seq"] for r in rows}
    unit_of = {}
    for number, (a, b) in enumerate(units):
        for s in range(a, b + 1):
            unit_of[s] = number
    matcher = Matcher(rows, units)
    out_dir = os.path.join(card, "Y2Player", "Gurbani")
    os.makedirs(out_dir, exist_ok=True)
    overrides = read_overrides(os.path.join(out_dir, "overrides.tsv"))
    cache_path = os.path.join(out_dir, ".titles.json")
    if os.path.exists(cache_path):
        TITLE_CACHE.update(json.load(open(cache_path, encoding="utf-8")))
    max_ang = max(r["ang"] for r in rows)
    first_of_ang, last_of_ang = {}, {}
    for r in rows:
        first_of_ang.setdefault(r["ang"], r["seq"])
        last_of_ang[r["ang"]] = r["seq"]

    tracks, check, candidates, counts = [], [], [], defaultdict(int)
    for rel, path in recordings(card):
        low = rel.lower()
        name = os.path.basename(low)
        if low in overrides and overrides[low] != "*":
            target = overrides[low]
            if target != "-" and target in seq_of_id:
                seq = seq_of_id[target]
                a, b = units[unit_of[seq]]
                tracks.append((rel, "shabad", a, b, seq, None, 1))
                counts["override"] += 1
            continue
        if low.startswith("sehaj paath/"):
            m = ANG_RANGE.search(name)
            if m and 1 <= int(m.group(1)) <= int(m.group(2)) <= max_ang:
                a, b = int(m.group(1)), int(m.group(2))
                tracks.append((rel, "angs", first_of_ang[a], last_of_ang[b], None, f"Angs {a}–{b}" if a != b else f"Ang {a}", 1))
                counts["sehaj paath"] += 1
            continue
        if any(low.startswith(f"{s}/") for s in SPOKEN):
            continue
        if low.startswith("nitnem/"):
            bani = next((key for word, key in BANI_NAMES if word in name), None)
            if bani:
                a, b = banis[bani]
                tracks.append((rel, "bani", a, b, None, None, 1))
                counts["bani"] += 1
            continue
        title = title_of(path)
        # A whole bani by name, wherever it is filed ("Anand Sahib", "Asa Di Vaar").
        named = bani_of(title) or bani_of(os.path.splitext(os.path.basename(path))[0].split(" - ")[0])
        if named:
            key, kirtan = named
            a, b = banis[key]
            if kirtan and key == "anand":
                # Kirtan of Anand Sahib is sung on its opening pauris, not read through.
                seq = next(q for q in range(a, b + 1) if rows[q]["kind"] in "TR")
                ua, ub = units[unit_of[seq]]
                tracks.append((rel, "shabad", ua, ub, seq, None, 0))
                counts["bani kirtan (to check)"] += 1
            else:
                tracks.append((rel, "bani", a, b, None, None, 1))
                counts["bani by title"] += 1
            continue
        words, ranked = matcher.match(title)
        strict = 0.0
        if ranked:
            # Lines are found loosely and confirmed strictly: re-rank on both opinions.
            both = sorted(((.6 * sc + .4 * max(matcher.strict_score(t, q) for t in matcher.last_texts), q,
                            max(matcher.strict_score(t, q) for t in matcher.last_texts)) for sc, q in ranked), reverse=True)
            ranked = [(c, q) for c, q, _ in both]
            strict = both[0][2]
        if not ranked:
            counts["no match"] += 1
            continue
        best, seq = ranked[0]
        margin = best - (ranked[1][0] if len(ranked) > 1 else 0)
        floor = matcher.word_floor(words, seq)
        # The same line in two shabads: kirtan is sung on the rahao, so if only one of
        # them is a rahao line, that is the shabad.
        tied = [s for score, s in ranked if best - score < .03]
        rahao = [s for s in tied if rows[s]["kind"] == "R"]
        if margin < SURE_MARGIN and len(tied) > 1 and len(rahao) == 1:
            seq, margin = rahao[0], SURE_MARGIN
            floor = matcher.word_floor(words, seq)
            counts["rahao tie-break"] += 1
        # Three words leave too much room for chance: they must be all but exact.
        needed = 2.0 if len(words) < 3 else SHORT_SCORE if len(words) == 3 else SURE_SCORE
        sure = best >= needed and margin >= SURE_MARGIN and floor >= WORD_FLOOR and strict >= STRICT_SCORE
        if sure:
            a, b = units[unit_of[seq]]
            tracks.append((rel, "shabad", a, b, seq, None, 1))
            counts["matched"] += 1
        elif best >= (CHECK_SCORE if len(words) > 2 else .85):
            check.append([rel, title] + [f"{rows[s]['id']} · {score:.2f} · Ang {rows[s]['ang']} · {rows[s]['gurmukhi']}"
                                         for score, s in ranked])
            # Shown, but marked as not confirmed, with the other candidates to try on the player.
            a, b = units[unit_of[seq]]
            tracks.append((rel, "shabad", a, b, seq, None, 0))
            for rank, (score, s) in enumerate(ranked):
                candidates.append((rel, rank, s, rows[s]["id"], round(score, 3)))
            counts["to check"] += 1
        else:
            counts["no match"] += 1

    with open(cache_path, "w", encoding="utf-8") as f:
        json.dump(TITLE_CACHE, f, ensure_ascii=False)
    write_db(os.path.join(out_dir, "sggs.db"), rows, units, tracks, candidates)
    with open(os.path.join(out_dir, "to-check.csv"), "w", newline="", encoding="utf-8-sig") as f:
        w = csv.writer(f)
        w.writerow(["path", "title", "best", "second", "third"])
        w.writerows(sorted(check))
    overrides_path = os.path.join(out_dir, "overrides.tsv")
    if not os.path.exists(overrides_path):
        with open(overrides_path, "w", encoding="utf-8") as f:
            f.write("# path<TAB>line id (from to-check.csv), or path<TAB>- for no text. Wins over matching.\n")
    print(f"lines {len(rows)}, shabads {len(units)}, tuks without English {len(missing)}")
    print(", ".join(f"{k} {v}" for k, v in sorted(counts.items())))


if __name__ == "__main__":
    main()
