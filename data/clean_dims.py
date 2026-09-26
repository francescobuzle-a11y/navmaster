#!/usr/bin/env python3
"""Removes the size and weight limits that are not numbers ("maxheight=default", "maxweight=none",
"unsigned" ...) from the roads before the Valhalla graph is built.

In OpenStreetMap these values mean "no signed limit", but the graph builder can read them as a
limit of zero: a lorry then may not use that road at all. On the A14 at Rimini Sud a single
"maxheight=default" was enough to send every lorry off the motorway for 20 km. Cars are not
affected, which is why only lorries and buses took the long way round.

Only the few ways with such values are rewritten (osmium tags-filter → OPL edit → osmium
apply-changes), so even the largest countries take seconds.

Usage: clean_dims.py <roads.osm.pbf> [temp-dir]   (the file is replaced in place)
Never fails the build: on any error the file stays as it was.
"""
import os
import re
import subprocess
import sys

KEYS = ("maxheight", "maxheight:physical", "maxwidth", "maxwidth:physical", "maxlength", "maxweight",
        "maxweightrating", "maxaxleload", "maxweight:hgv", "maxheight:hgv", "maxlength:hgv")
BAD = ("default", "none", "unsigned", "below_default", "no_indications", "unknown", "fixme", "no", "no_sign",
       "not_signed", "variable")
KEY_RE = re.compile(r"^(maxheight|maxwidth|maxlength|maxweight|maxweightrating|maxaxleload)(:physical|:hgv)?$")


def unescape(s):
    return re.sub(r"%([0-9a-fA-F]+)%", lambda m: chr(int(m.group(1), 16)), s)


def is_bad(key, value):
    if not KEY_RE.match(unescape(key)):
        return False
    v = unescape(value).strip().lower()
    return v in BAD or not re.search(r"\d", v)


def fix_line(line):
    """The OPL line with the bad tags removed and the version raised, or None if nothing to fix."""
    fields = line.rstrip("\n").split(" ")
    changed = False
    for i, f in enumerate(fields):
        if f.startswith("T") and len(f) > 1:
            kept = []
            for pair in f[1:].split(","):
                k, _, v = pair.partition("=")
                if is_bad(k, v):
                    changed = True
                else:
                    kept.append(pair)
            fields[i] = "T" + ",".join(kept)
    if not changed:
        return None
    for i, f in enumerate(fields):
        if f.startswith("v") and f[1:].isdigit():
            fields[i] = "v" + str(int(f[1:]) + 1)
    return " ".join(fields) + "\n"


def main():
    src = sys.argv[1]
    # temporary files (and the rewritten copy) in a second folder when the disk of the first is short
    work = sys.argv[2] if len(sys.argv) > 2 else os.path.dirname(os.path.abspath(src))
    bad_pbf = os.path.join(work, "dims_bad.osm.pbf")
    bad_opl = os.path.join(work, "dims_bad.opl")
    fix_opl = os.path.join(work, "dims_fix.opl")
    fix_osc = os.path.join(work, "dims_fix.osc")
    out = os.path.join(work, "dims_clean.osm.pbf")
    try:
        exprs = [f"w/{k}={','.join(BAD)}" for k in KEYS]
        subprocess.run(["osmium", "tags-filter", "-R", src, *exprs, "-o", bad_pbf, "--overwrite"], check=True)
        subprocess.run(["osmium", "cat", bad_pbf, "-f", "opl", "-o", bad_opl, "--overwrite"], check=True)
        fixed = []
        removed = {}
        with open(bad_opl, encoding="utf-8") as fh:
            for line in fh:
                nl = fix_line(line)
                if nl:
                    fixed.append(nl)
                    for pair in line.split(" T", 1)[1].split(" ")[0].split(",") if " T" in line else []:
                        k, _, v = pair.partition("=")
                        if is_bad(k, v):
                            removed[f"{unescape(k)}={unescape(v)}"] = removed.get(f"{unescape(k)}={unescape(v)}", 0) + 1
        if not fixed:
            print("clean_dims: nothing to clean")
            return
        with open(fix_opl, "w", encoding="utf-8") as fh:
            fh.writelines(fixed)
        subprocess.run(["osmium", "cat", fix_opl, "-o", fix_osc, "--overwrite"], check=True)
        subprocess.run(["osmium", "apply-changes", src, fix_osc, "-o", out, "--overwrite"], check=True)
        subprocess.run(["mv", "-f", out, src], check=True)
        print(f"clean_dims: {len(fixed)} ways cleaned: " + ", ".join(f"{k} ×{n}" for k, n in sorted(removed.items(), key=lambda x: -x[1])[:15]))
    except Exception as ex:
        print(f"clean_dims: skipped ({ex})")
    finally:
        for f in (bad_pbf, bad_opl, fix_opl, fix_osc, out):
            if os.path.exists(f):
                os.remove(f)


if __name__ == "__main__":
    main()
