#!/usr/bin/env python3
"""Gathers the drivers' answers about the fixed speed cameras ("c'era?" after a camera, see
app/.../live/CameraVotes.kt) from their ntfy topic, which keeps messages for 12 hours, into one
file kept on the release "voti-autovelox" (voti.json): the tablets download it twice a day, so an
answer is not lost when a tablet stays off for days.

Usage: collect_votes.py <old voti.json> <new voti.json> [topic] [server]
An answer: {"i": random id, "la", "lo": the camera, "y": 1 it was there / 0 it was not,
"d": random tag of the tablet, "t": seconds}. No names, no accounts.
"""
import json
import sys
import time
import urllib.request

TOPIC = "navmaster-eu1-cam"
SERVER = "https://ntfy.sh"
MAX_AGE_S = 2 * 365 * 24 * 3600
MAX_VOTES = 200_000


def valid(o, t):
    """The answer of a message, or None (the same checks as the app's CameraVotes.fromPayload)."""
    try:
        if str(o.get("v")) != "1" or o.get("a") != "cam":
            return None
        i, d = str(o["i"]), str(o["d"])
        la, lo, y = float(o["la"]), float(o["lo"]), int(o["y"])
    except (KeyError, TypeError, ValueError):
        return None
    if not (6 <= len(i) <= 40) or not (0 < len(d) <= 40) or not (27 <= la <= 72) or not (-32 <= lo <= 45) or y not in (0, 1):
        return None
    return {"i": i, "la": round(la, 5), "lo": round(lo, 5), "y": y, "d": d, "t": int(t)}


def read_topic(topic, server):
    url = f"{server.rstrip('/')}/{topic}/json?poll=1&since=12h"
    req = urllib.request.Request(url, headers={"User-Agent": "NavMaster votes (github.com/francescobuzle-a11y/navmaster)"})
    text = urllib.request.urlopen(req, timeout=60).read().decode("utf-8", "replace")
    out = []
    for line in text.splitlines():
        try:
            msg = json.loads(line)
        except ValueError:
            continue
        if msg.get("event") != "message":
            continue
        try:
            body = json.loads(msg.get("message") or "")
        except ValueError:
            continue
        v = valid(body, msg.get("time") or 0) if isinstance(body, dict) else None
        if v:
            out.append(v)
    return out


def main():
    old_path, new_path = sys.argv[1], sys.argv[2]
    topic = sys.argv[3] if len(sys.argv) > 3 else TOPIC
    server = sys.argv[4] if len(sys.argv) > 4 else SERVER
    try:
        old = json.load(open(old_path, encoding="utf-8")).get("votes") or []
    except (OSError, ValueError):
        old = []
    by_id = {}
    for o in old:
        v = valid(dict(o, v=1, a="cam"), o.get("t") or 0)
        if v:
            by_id[v["i"]] = v
    before = len(by_id)
    new = read_topic(topic, server)
    for v in new:
        by_id.setdefault(v["i"], v)
    now = time.time()
    kept = sorted((v for v in by_id.values() if now - v["t"] < MAX_AGE_S), key=lambda v: v["t"])[-MAX_VOTES:]
    with open(new_path, "w", encoding="utf-8") as f:
        json.dump({"version": 1, "updated": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "votes": kept}, f, separators=(",", ":"))
    print(f"votes: {before} before, {len(new)} on the topic, {len(by_id) - before} new, {len(kept)} kept")


if __name__ == "__main__":
    main()
