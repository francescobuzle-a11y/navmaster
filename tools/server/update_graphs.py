#!/usr/bin/env python3
"""Downloads (and keeps up to date) the GraphHopper graphs of the NavMaster server.

The same packages the tablets download, from the repository's releases (dati-<country>):
gh.tar.gz (graph), gh-camion.tar.gz and gh-auto.tar.gz (landmarks) and valichi.json, plus
catalog.json (the countries with their borders). Only what changed is downloaded (sha256 of the
release manifest, as RegionManager on the tablet); the packages are kept next to the graph so a
new graph can be rebuilt with the landmarks already there.

Layout: <data>/catalog.json, <data>/<country>/{gh/, gh*.tar.gz, valichi.json, .state.json}

  update_graphs.py [--data /opt/navmaster/data] [--countries italy,austria | all]

Exit code 0; prints "CHANGED" on the last line when a graph changed (the service is then restarted
by the caller, install.sh / the systemd timer).
"""
import argparse
import hashlib
import json
import os
import shutil
import sys
import tarfile
import time
import urllib.request

RELEASES = "https://github.com/francescobuzle-a11y/navmaster/releases/download"
PACKAGES = ["gh.tar.gz", "gh-camion.tar.gz", "gh-auto.tar.gz"]


def log(*a):
    print(time.strftime("%Y-%m-%d %H:%M:%S"), *a, flush=True)


def fetch_json(url):
    with urllib.request.urlopen(url, timeout=60) as r:
        return json.load(r)


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def download(url, dest, sha, size):
    """Downloads url to dest (resuming a partial file), checks the sha256."""
    part = dest + ".part"
    for attempt in range(5):
        have = os.path.getsize(part) if os.path.exists(part) else 0
        if have > size:
            os.remove(part)
            have = 0
        req = urllib.request.Request(url)
        if have:
            req.add_header("Range", "bytes=%d-" % have)
        try:
            with urllib.request.urlopen(req, timeout=120) as r, open(part, "ab" if have and r.status == 206 else "wb") as f:
                shutil.copyfileobj(r, f, 1 << 20)
        except Exception as e:  # network: try again from where it stopped
            log("  download interrupted (%s), retry %d" % (e, attempt + 1))
            time.sleep(10 * (attempt + 1))
            continue
        if os.path.getsize(part) == size and sha256_of(part) == sha:
            os.replace(part, dest)
            return
        log("  wrong size or sha256, downloading again")
        os.remove(part)
    raise RuntimeError("download failed: " + url)


def fetch_parts(region, entry, dest):
    """A file of the manifest (one or more parts joined) to dest."""
    parts = entry["parts"]
    if len(parts) == 1:
        p = parts[0]
        download("%s/dati-%s/%s" % (RELEASES, region, p["name"]), dest, p["sha256"], p["size"])
        return
    with open(dest + ".joined", "wb") as out:
        for p in parts:
            tmp = dest + "." + p["name"]
            download("%s/dati-%s/%s" % (RELEASES, region, p["name"]), tmp, p["sha256"], p["size"])
            with open(tmp, "rb") as f:
                shutil.copyfileobj(f, out, 1 << 20)
            os.remove(tmp)
    os.replace(dest + ".joined", dest)


def extract(tgz, into):
    with tarfile.open(tgz, "r:gz") as t:
        for m in t.getmembers():
            # flat files only, never outside the folder
            name = os.path.basename(m.name)
            if not m.isfile() or not name or name.startswith("."):
                continue
            src = t.extractfile(m)
            with open(os.path.join(into, name), "wb") as f:
                shutil.copyfileobj(src, f, 1 << 20)


def update_country(data, cid):
    folder = os.path.join(data, cid)
    os.makedirs(folder, exist_ok=True)
    state_file = os.path.join(folder, ".state.json")
    state = json.load(open(state_file)) if os.path.exists(state_file) else {}
    try:
        man = fetch_json("%s/dati-%s/manifest.json" % (RELEASES, cid))
    except Exception as e:
        log("%s: no manifest (%s), skipped" % (cid, e))
        return False
    files = {e["file"]: e for e in man.get("files", [])}
    if "gh.tar.gz" not in files:
        log("%s: no GraphHopper graph in the release, skipped" % cid)
        return False

    def key(e):
        return ",".join(p["sha256"] for p in e["parts"])

    changed = [f for f in PACKAGES + ["valichi.json"] if f in files and state.get(f) != key(files[f])]
    if not changed and os.path.exists(os.path.join(folder, "gh", "properties")):
        log("%s: up to date" % cid)
        return False
    for f in changed:
        log("%s: downloading %s (%.0f MB)" % (cid, f, files[f]["size"] / 1e6))
        fetch_parts(cid, files[f], os.path.join(folder, f))
    # the graph: rebuilt in gh.new from the three packages, then swapped (the server is restarted after)
    if any(f in PACKAGES for f in changed) or not os.path.exists(os.path.join(folder, "gh", "properties")):
        new = os.path.join(folder, "gh.new")
        shutil.rmtree(new, ignore_errors=True)
        os.makedirs(new)
        for p in PACKAGES:
            tgz = os.path.join(folder, p)
            if os.path.exists(tgz):
                extract(tgz, new)
        if not os.path.exists(os.path.join(new, "properties")):
            shutil.rmtree(new, ignore_errors=True)
            raise RuntimeError(cid + ": graph incomplete")
        old = os.path.join(folder, "gh")
        if os.path.exists(old):
            shutil.rmtree(old + ".old", ignore_errors=True)
            os.replace(old, old + ".old")
        os.replace(new, old)
        shutil.rmtree(old + ".old", ignore_errors=True)
    for f in changed:
        state[f] = key(files[f])
    state["built"] = man.get("built")
    json.dump(state, open(state_file, "w"), indent=1)
    log("%s: graph of %s ready" % (cid, man.get("built")))
    return True


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default="/opt/navmaster/data")
    ap.add_argument("--countries", default="all")
    a = ap.parse_args()
    os.makedirs(a.data, exist_ok=True)
    cat = fetch_json(RELEASES + "/dati-catalogo/catalog.json")
    tmp = os.path.join(a.data, "catalog.json.new")
    json.dump(cat, open(tmp, "w"))
    os.replace(tmp, os.path.join(a.data, "catalog.json"))
    ids = [c["id"] for c in cat["countries"] if c.get("available", True)]
    if a.countries != "all":
        want = [x.strip() for x in a.countries.split(",") if x.strip()]
        ids = [i for i in ids if i in want]
    total = shutil.disk_usage(a.data)
    log("countries: %d, free disk %.0f GB" % (len(ids), total.free / 1e9))
    changed = False
    failed = []
    for cid in ids:
        try:
            changed |= update_country(a.data, cid)
        except Exception as e:
            log("%s: FAILED: %s" % (cid, e))
            failed.append(cid)
    if failed:
        log("failed: " + ",".join(failed) + " (run again later)")
    print("CHANGED" if changed else "UNCHANGED")


if __name__ == "__main__":
    main()
