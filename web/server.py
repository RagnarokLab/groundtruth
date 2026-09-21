#!/usr/bin/env python3
"""
GroundTruth minimal web viewer - stdlib only (no pip installs on this host).
Read-only against the plugin's own sqlite db; never touches the world files
or any other plugin's data. Serves a static canvas map + a JSON data API.
"""
import base64
import csv
import hashlib
import hmac
import io
import json
import os
import re
import socket
import subprocess
import time
import sqlite3
import struct
import urllib.parse
import urllib.request
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

try:
    import numpy as np
except Exception:  # the pixel tier needs it; the rest of the server does not
    np = None

_decompress_pool = None


def decompress_pool():
    """ThreadPoolExecutor for chunk_pixels decompression. zlib releases the GIL, so decompressing
    the 768/512-byte blobs in parallel is a real speedup on the cold path."""
    global _decompress_pool
    if _decompress_pool is None:
        from concurrent.futures import ThreadPoolExecutor
        _decompress_pool = ThreadPoolExecutor(max_workers=6)
    return _decompress_pool


def unpack_chunk_pixels(row):
    """One chunk_pixels row -> (cx, cz, hgt(16,16), ground(16,16), rgb(16,16,3)), or None."""
    cx, cz, rgb_b, hgt_b, ghg_b = row
    try:
        hgt = np.frombuffer(zlib.decompress(hgt_b), dtype="<u2")
        rgb = np.frombuffer(zlib.decompress(rgb_b), dtype=np.uint8)
        ghg = np.frombuffer(zlib.decompress(ghg_b), dtype="<u2") if ghg_b else hgt
    except Exception:
        return None
    if hgt.size < 256 or rgb.size < 768:
        return None
    return (cx, cz, hgt.reshape(16, 16).astype(np.int32), ghg.reshape(16, 16).astype(np.int32),
            rgb.reshape(16, 16, 3))

try:
    from PIL import Image  # optional; used to crop a face from a full skin texture
except Exception:  # pragma: no cover
    Image = None

UPLOAD_KEY = os.environ.get("GT_UPLOAD_KEY", "")  # set to enable the /upload drop box; empty disables it
INCOMING_DIR = Path(os.environ.get("GT_INCOMING", "/opt/groundtruth-web/incoming"))
DB_PATH = os.environ.get("GT_DB", "/opt/minecraft/plugins/GroundTruth/groundtruth.db")
STATIC_DIR = Path(__file__).parent / "static"
SERVER_PROPERTIES = os.environ.get("GT_SERVER_PROPERTIES", "/opt/minecraft/server.properties")
PORT = int(os.environ.get("GT_PORT", "8095"))


def rcon(host, port, password, command, timeout=10):
    # Same protocol as /opt/minecraft/rcon.py, inlined so this has no extra deps.
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(timeout)
    sock.connect((host, port))

    def send(req_id, req_type, body):
        payload = struct.pack("<ii", req_id, req_type) + body.encode() + b"\x00\x00"
        sock.send(struct.pack("<i", len(payload)) + payload)

    def recv_exact(n):
        buf = b""
        while len(buf) < n:
            chunk = sock.recv(n - len(buf))
            if not chunk:
                raise ConnectionError("socket closed")
            buf += chunk
        return buf

    def recv():
        length = struct.unpack("<i", recv_exact(4))[0]
        data = recv_exact(length)
        req_id, resp_type = struct.unpack("<ii", data[:8])
        body = data[8:-2].decode(errors="replace")
        return req_id, resp_type, body

    send(1, 3, password)
    recv()
    send(2, 2, command)
    send(3, 2, "")
    parts = []
    while True:
        req_id, _, body = recv()
        if req_id == 3:
            break
        parts.append(body)
    sock.close()
    return "".join(parts)


def _rcon_config():
    props = Path(SERVER_PROPERTIES).read_text()
    port = int(re.search(r"rcon\.port=(\d+)", props).group(1))
    password = re.search(r"rcon\.password=(\S+)", props).group(1)
    return port, password


RCON_PORT, RCON_PASSWORD = _rcon_config()


def rcon_cmd(command):
    return rcon("127.0.0.1", RCON_PORT, RCON_PASSWORD, command)


def fetch_world_seed():
    # A world's seed is permanent for its lifetime, so one real RCON query at
    # startup (cached for the process lifetime) beats hardcoding a value that
    # could go stale if the world is ever regenerated.
    reply = rcon_cmd("seed")
    m = re.search(r"-?\d+", reply)
    return int(m.group(0)) if m else None


WORLD_SEED = fetch_world_seed()
print(f"World seed: {WORLD_SEED}")


def db_ro():
    # Read-only connection (mode=ro) so this can never corrupt the plugin's live db,
    # and works fine alongside its own writes via SQLite's WAL mode.
    return sqlite3.connect(f"file:{DB_PATH}?mode=ro", uri=True)


def query_data(world: str):
    conn = db_ro()
    try:
        cur = conn.cursor()
        cur.execute("SELECT cx, cz, biome FROM chunks WHERE world=?", (world,))
        chunks = [{"cx": r[0], "cz": r[1], "biome": r[2]} for r in cur.fetchall()]

        cur.execute(
            "SELECT type, min_x, min_y, min_z, max_x, max_y, max_z FROM structures WHERE world=?",
            (world,),
        )
        structures = [
            {
                "type": r[0],
                "minX": r[1], "minY": r[2], "minZ": r[3],
                "maxX": r[4], "maxY": r[5], "maxZ": r[6],
                "x": (r[1] + r[4]) // 2, "z": (r[3] + r[6]) // 2,
            }
            for r in cur.fetchall()
        ]
        return {"world": world, "seed": WORLD_SEED, "chunks": chunks, "structures": structures}
    finally:
        conn.close()


# --- Per-location queries (the API Sassy hooks into) -------------------------
# Unlike /api/data (the whole 1.2M-chunk dataset), these answer "what's around
# THIS point" cheaply, which is what an agent needs.

DIRS = ["N", "NE", "E", "SE", "S", "SW", "W", "NW"]
_SELECT = ("SELECT type, min_x, min_y, min_z, max_x, max_y, max_z "
           "FROM structures WHERE world=?")


def _compass(dx, dz):
    # Minecraft: +x = east, -x = west, +z = south, -z = north.
    import math
    ang = math.degrees(math.atan2(dx, -dz))
    return DIRS[int((ang + 22.5) // 45) % 8]


def _biome_at(conn, world, x, z):
    row = conn.execute(
        "SELECT biome FROM chunks WHERE world=? AND cx=? AND cz=?",
        (world, x >> 4, z >> 4),
    ).fetchone()
    return row[0] if row else None


def _struct_dict(row, x, z):
    hx = (row[1] + row[4]) // 2
    hy = (row[2] + row[5]) // 2
    hz = (row[3] + row[6]) // 2
    dx, dz = hx - x, hz - z
    return {
        "type": row[0], "x": hx, "y": hy, "z": hz,
        "minX": row[1], "minY": row[2], "minZ": row[3],
        "maxX": row[4], "maxY": row[5], "maxZ": row[6],
        "distance": int(round((dx * dx + dz * dz) ** 0.5)),
        "dir": _compass(dx, dz),
    }


def nearby(world, x, z, radius, limit):
    conn = db_ro()
    try:
        rows = conn.execute(
            _SELECT + " AND max_x>=? AND min_x<=? AND max_z>=? AND min_z<=?",
            (world, x - radius, x + radius, z - radius, z + radius),
        ).fetchall()
        hits = [h for h in (_struct_dict(r, x, z) for r in rows) if h["distance"] <= radius]
        hits.sort(key=lambda h: h["distance"])
        biome = _biome_at(conn, world, x, z)
        return {"world": world, "x": x, "z": z, "biome": biome,
                "indexed": biome is not None, "structures": hits[:limit]}
    finally:
        conn.close()


def find(world, type_filter, x, z, limit):
    conn = db_ro()
    try:
        rows = conn.execute(
            _SELECT + " AND type LIKE ?", (world, "%" + (type_filter or "").upper() + "%")
        ).fetchall()
        hits = sorted((_struct_dict(r, x, z) for r in rows), key=lambda h: h["distance"])
        return {"world": world, "x": x, "z": z, "typeFilter": type_filter,
                "structures": hits[:limit]}
    finally:
        conn.close()


def player_pos(player):
    # The plugin reports the player's REAL world name (so custom/multiworld setups
    # work with no dimension->world-name assumptions here).
    raw = rcon_cmd(f"groundtruth pos {player}")
    seg = raw.split("GTPOS|", 1)[-1].strip().rstrip(";")
    parts = seg.split("|")
    if len(parts) < 4 or parts[0] == "error":
        return None
    try:
        return float(parts[1]), float(parts[2]), float(parts[3]), parts[0]
    except ValueError:
        return None


def where(player, radius, limit):
    p = player_pos(player)
    if p is None:
        return {"error": f"{player} isn't online (or their position isn't readable)."}
    x, y, z, world = p
    data = nearby(world, int(x), int(z), radius, limit)
    env = next((w["env"] for w in (worlds().get("worlds") or []) if w["world"] == world), None)
    data.update({"player": player, "px": x, "py": y, "pz": z, "env": env})
    return data


def worlds():
    # Authoritative list from the plugin (Bukkit.getWorlds()), via RCON - so custom
    # dimensions (BentoBox, Incendium, parkour) show up without hardcoding.
    raw = rcon_cmd("groundtruth worlds")
    payload = raw.split("GTWORLDS|", 1)[-1]
    out = []
    for seg in payload.split(";"):
        seg = seg.strip()
        if seg.count(",") < 3:
            continue
        name, env, chunks, structs = seg.split(",", 3)
        try:
            out.append({"world": name, "env": env,
                        "chunks": int(chunks), "structures": int(structs)})
        except ValueError:
            continue
    return {"worlds": out}


def _default_world():
    # No name is assumed anywhere - default to whatever the server lists first.
    ws = worlds().get("worlds") or []
    return ws[0]["world"] if ws else None


def _ago(ms):
    """Human 'time ago' from an epoch-milliseconds timestamp."""
    if not ms:
        return "unknown"
    secs = max(0, int(time.time() - ms / 1000.0))
    if secs < 60:
        return "%ds ago" % secs
    if secs < 3600:
        return "%dm ago" % (secs // 60)
    if secs < 86400:
        return "%dh %dm ago" % (secs // 3600, (secs % 3600) // 60)
    return "%dd ago" % (secs // 86400)


def _log_rows(sql, args):
    conn = _log_conn()
    try:
        conn.row_factory = sqlite3.Row
        return [dict(r) for r in conn.execute(sql, args).fetchall()]
    except Exception:
        return []
    finally:
        conn.close()


def service_authorised(handler, qs):
    """The service key (GT_UPLOAD_KEY) or an admin login code. Used by the player-activity queries,
    which are about a real person's whereabouts and health and must not be world-readable."""
    if UPLOAD_KEY and qs.get("key", [""])[0] == UPLOAD_KEY:
        return True
    payload = verify_token(handler.headers.get("X-GT-Code") or qs.get("code", [""])[0])
    return bool(payload and payload.get("a"))


def any_user_authorised(handler, qs):
    """The service key, or any valid login code (player or admin). For player data the map shows to
    logged-in users but that should never be world-readable - live positions, the event log, chest
    contents. Gated by default; the public map stays public."""
    if UPLOAD_KEY and qs.get("key", [""])[0] == UPLOAD_KEY:
        return True
    return bool(verify_token(handler.headers.get("X-GT-Code") or qs.get("code", [""])[0]))


def chat_log(minutes=60, player=None, limit=100, q=None):
    """Player chat transcript, newest first. Feeds "what did people say?" style questions."""
    since = int((time.time() - minutes * 60) * 1000)
    sql = ("SELECT ts,world,x,y,z,actor_name,meta FROM log_events WHERE action='chat' AND ts>=?")
    args = [since]
    if player:
        sql += " AND ltrim(actor_name,'.')=?"
        args.append(player.lstrip("."))
    if q:
        sql += " AND meta LIKE ?"
        args.append("%" + q + "%")
    sql += " ORDER BY ts DESC LIMIT ?"
    args.append(max(1, min(1000, limit)))
    rows = _log_rows(sql, tuple(args))
    out = []
    for r in rows:
        try:
            meta = json.loads(r["meta"]) if r["meta"] else {}
        except Exception:
            meta = {}
        out.append({"ts": r["ts"], "ago": _ago(r["ts"]), "player": (r["actor_name"] or "").lstrip("."),
                    "message": meta.get("message"), "world": r["world"],
                    "x": r["x"], "y": r["y"], "z": r["z"]})
    summaries = ["%s: %s" % (e["player"], e["message"]) for e in out[:5]]
    summary = ("Nothing said in the last %d minutes." % minutes) if not out else \
              ("Last %d message%s. Most recent - %s" % (len(out), "" if len(out) == 1 else "s",
                                                        summaries[0]))
    return {"window_minutes": minutes, "player": player, "query": q, "count": len(out),
            "messages": out, "summary": summary}


def mob_spawns(player=None, minutes=30, radius=128, limit=20):
    """Recent hostile spawns, optionally only those near a player's current position.

    Answers "where did that zombie spawn and why": reason (NATURAL / REINFORCEMENT / SPAWNER /
    PATROL / STRUCTURE / RAID / SIEGE / COMMAND...), light levels, what block it spawned on, biome,
    difficulty, and how far the nearest player was.
    """
    since = int((time.time() - minutes * 60) * 1000)
    rows = _log_rows(
        "SELECT ts,world,x,y,z,actor_name,cause_id,meta FROM log_events "
        "WHERE action='mob-spawn' AND ts>=? ORDER BY ts DESC LIMIT ?",
        (since, max(200, limit * 10)))
    px = pz = pworld = None
    if player:
        for p in players().get("players", []):
            if (p.get("name") or "").lstrip(".").lower() == player.lstrip(".").lower():
                px, pz, pworld = p["x"], p["z"], p.get("world")
                break
    out = []
    for r in rows:
        if px is not None:
            if r["world"] != pworld:
                continue
            dx, dz = r["x"] - px, r["z"] - pz
            if dx * dx + dz * dz > radius * radius:
                continue
        try:
            meta = json.loads(r["meta"]) if r["meta"] else {}
        except Exception:
            meta = {}
        out.append({
            "ts": r["ts"], "ago": _ago(r["ts"]), "mob": r["actor_name"], "reason": r["cause_id"],
            "world": r["world"], "x": r["x"], "y": r["y"], "z": r["z"],
            "light": meta.get("light"), "block_light": meta.get("blockLight"),
            "sky_light": meta.get("skyLight"), "block_below": meta.get("blockBelow"),
            "biome": meta.get("biome"), "difficulty": meta.get("difficulty"),
            "nearest_player": meta.get("nearestPlayer"),
            "nearest_player_dist": meta.get("nearestPlayerDist"),
            "is_day": meta.get("isDay"),
        })
        if len(out) >= limit:
            break
    summary = "No hostile spawns logged in the last %d minutes%s." % (
        minutes, " near %s" % player if player else "")
    if out:
        s0 = out[0]
        why = {"NATURAL": "a natural dark spawn", "REINFORCEMENT": "a reinforcement call from a zombie you hit",
               "SPAWNER": "a spawner", "PATROL": "a patrol", "STRUCTURE": "the structure it belongs to",
               "RAID": "a raid", "SIEGE": "a siege", "COMMAND": "a command"}.get(s0["reason"], s0["reason"])
        summary = ("%s spawned %s%s - %s, light %s, on %s at %d,%d,%d."
                   % (s0["mob"], s0["ago"],
                      " %.0f blocks from %s" % (s0["nearest_player_dist"], s0["nearest_player"])
                      if s0.get("nearest_player_dist") is not None else "",
                      why, s0.get("light"), s0.get("block_below"), s0["x"], s0["y"], s0["z"]))
    return {"player": player, "window_minutes": minutes, "radius": radius, "count": len(out),
            "spawns": out, "summary": summary}


def player_attacks(name, minutes=30, limit=50):
    """Everything that damaged this player recently, newest first, plus a plain-language summary."""
    since = int((time.time() - minutes * 60) * 1000)
    rows = _log_rows(
        # Bedrock (Floodgate) names carry a leading dot in-game but not on the web, so compare
        # with it stripped on both sides.
        "SELECT ts,world,x,y,z,actor_kind,actor_id,actor_name,cause_kind,target,meta FROM log_events "
        "WHERE action='damage' AND ltrim(target,'.')=? AND ts>=? ORDER BY ts DESC LIMIT ?",
        (name, since, limit))
    attacks, by_attacker, total = [], {}, 0.0
    for r in rows:
        try:
            meta = json.loads(r["meta"]) if r["meta"] else {}
        except Exception:
            meta = {}
        dmg = float(meta.get("amount") or 0)
        total += dmg
        key = r["actor_name"] or "unknown"
        agg = by_attacker.setdefault(key, {"attacker": key, "kind": r["actor_kind"], "hits": 0, "damage": 0.0})
        agg["hits"] += 1
        agg["damage"] = round(agg["damage"] + dmg, 1)
        attacks.append({
            "ts": r["ts"], "ago": _ago(r["ts"]),
            "attacker": r["actor_name"], "attacker_kind": r["actor_kind"],
            "cause": r["cause_kind"], "weapon": meta.get("weapon"),
            "damage": dmg, "health_after": meta.get("healthAfter"),
            "world": r["world"], "x": r["x"], "y": r["y"], "z": r["z"],
        })
    worst = max(by_attacker.values(), key=lambda a: (a["damage"], a["hits"])) if by_attacker else None
    if not attacks:
        summary = "Nothing has damaged %s in the last %d minutes." % (name, minutes)
    else:
        summary = ("%s took %d hit%s (%.1f damage) in the last %d minutes, mostly from %s; "
                   "most recent %s at %d,%d,%d." % (
                       name, len(attacks), "" if len(attacks) == 1 else "s", total, minutes,
                       worst["attacker"], attacks[0]["ago"],
                       attacks[0]["x"], attacks[0]["y"], attacks[0]["z"]))
    return {"player": name, "window_minutes": minutes, "count": len(attacks),
            "total_damage": round(total, 1),
            "by_attacker": sorted(by_attacker.values(), key=lambda a: -a["damage"]),
            "attacks": attacks, "summary": summary}


def player_timeline(name, minutes=60, limit=100):
    """Recent notable activity for one player (either as actor or target), newest first."""
    since = int((time.time() - minutes * 60) * 1000)
    rows = _log_rows(
        "SELECT ts,action,world,x,y,z,actor_kind,actor_name,cause_kind,target,meta FROM log_events "
        "WHERE ts>=? AND (ltrim(actor_name,'.')=? OR ltrim(target,'.')=?) ORDER BY ts DESC LIMIT ?",
        (since, name, name, limit))
    out = []
    for r in rows:
        try:
            meta = json.loads(r["meta"]) if r["meta"] else {}
        except Exception:
            meta = {}
        a = r["action"]
        if a == "damage":
            what = "%s (%s) hit %s for %.1f" % (r["actor_name"], r["cause_kind"], r["target"],
                                                float(meta.get("amount") or 0))
        elif a == "player-death":
            what = "%s died (%s)" % (r["actor_name"], meta.get("cause"))
        elif a == "entity-kill":
            what = "%s killed %s" % (r["actor_name"], r["target"])
        elif a == "block-break":
            what = "%s broke %s" % (r["actor_name"], r["target"])
        elif a == "block-place":
            what = "%s placed %s" % (r["actor_name"], r["target"])
        elif a == "container-open":
            what = "%s opened %s" % (r["actor_name"], r["target"])
        elif a == "command":
            what = "%s ran %s" % (r["actor_name"], meta.get("cmd"))
        else:
            what = "%s %s %s" % (r["actor_name"], a, r["target"] or "")
        out.append({"ts": r["ts"], "ago": _ago(r["ts"]), "action": a, "what": what.strip(),
                    "world": r["world"], "x": r["x"], "y": r["y"], "z": r["z"]})
    return {"player": name, "window_minutes": minutes, "count": len(out), "events": out}


def player_now(name):
    """Live state for one online player, plus what last hit them."""
    out = {"player": name, "online": False}
    for p in players().get("players", []):
        if (p.get("name") or "").lstrip(".").lower() == name.lstrip(".").lower():
            out.update({"online": True, "uuid": p.get("uuid"), "world": p.get("world"),
                        "dimension": p.get("env"), "x": int(p["x"]), "y": int(p["y"]),
                        "z": int(p["z"]), "health": p.get("health"), "food": p.get("food")})
            if p.get("health") is not None:
                out["hearts"] = round(p["health"] / 2.0, 1)
            break
    last = _log_rows(
        "SELECT ts,actor_kind,actor_name,cause_kind,target,meta FROM log_events "
        "WHERE action='damage' AND ltrim(target,'.')=? ORDER BY ts DESC LIMIT 1", (name,))
    if last:
        r = last[0]
        try:
            meta = json.loads(r["meta"]) if r["meta"] else {}
        except Exception:
            meta = {}
        out["last_damage"] = {"ago": _ago(r["ts"]), "ts": r["ts"], "attacker": r["actor_name"],
                              "attacker_kind": r["actor_kind"], "cause": r["cause_kind"],
                              "damage": meta.get("amount")}
    return out


def last_attack(minutes=10):
    """The most recent damage taken by anyone currently online - answers "what just attacked me"
    without the assistant needing to know which player is speaking."""
    online = [ (p.get("name") or "").lstrip(".") for p in players().get("players", []) ]
    since = int((time.time() - minutes * 60) * 1000)
    rows = _log_rows(
        "SELECT ts,world,x,y,z,actor_kind,actor_name,cause_kind,target,meta FROM log_events "
        "WHERE action='damage' AND ts>=? ORDER BY ts DESC LIMIT 40", (since,))
    for r in rows:
        victim = (r["target"] or "").lstrip(".")
        if victim in online:
            try:
                meta = json.loads(r["meta"]) if r["meta"] else {}
            except Exception:
                meta = {}
            return {
                "player": victim, "online": True, "ago": _ago(r["ts"]), "ts": r["ts"],
                "attacker": r["actor_name"], "attacker_kind": r["actor_kind"],
                "cause": r["cause_kind"], "weapon": meta.get("weapon"),
                "damage": meta.get("amount"), "health_after": meta.get("healthAfter"),
                "world": r["world"], "x": r["x"], "y": r["y"], "z": r["z"],
                "summary": "%s hit %s for %s %s (%s) at %d,%d,%d."
                           % (r["actor_name"], victim, meta.get("amount"), r["cause_kind"],
                              _ago(r["ts"]), r["x"], r["y"], r["z"]),
            }
    return {"player": None, "online": bool(online), "attack": None,
            "summary": "Nothing has hit %s in the last %d minutes."
                       % (" or ".join(online) if online else "anyone", minutes)}


def players():
    raw = rcon_cmd("groundtruth players")
    payload = raw.split("GTPLAYERS|", 1)[-1]
    envs = {w["world"]: w["env"] for w in (worlds().get("worlds") or [])}
    out = []
    for seg in payload.split(";"):
        seg = seg.strip()
        if seg.count(",") < 5:
            continue
        parts = seg.split(",")
        name, uuid, x, y, z, world = parts[0], parts[1], parts[2], parts[3], parts[4], parts[5]
        name = name.lstrip(".")  # Floodgate bedrock prefix
        try:
            rec = {"name": name, "uuid": uuid, "x": float(x), "y": float(y),
                   "z": float(z), "world": world, "env": envs.get(world)}
            # added 2026-09-21: health/food so "what just attacked me" can say how close to death
            if len(parts) > 6 and parts[6]:
                rec["health"] = float(parts[6])
            if len(parts) > 7 and parts[7]:
                rec["food"] = int(parts[7])
            out.append(rec)
        except ValueError:
            continue
    return {"players": out}


SKIN_CACHE = Path("/opt/groundtruth-web/skin-cache")
TILES_DIR = Path("/opt/groundtruth-web/tiles")

MAX_STRUCT_PER_TYPE = int(os.environ.get("GT_MAX_STRUCT_PER_TYPE", "20000"))


def structures_only(world):
    conn = db_ro()
    try:
        # Guard: a single very common structure type (e.g. a mod that spawns a structure start for
        # every monster room) can produce >1M rows and a >100MB response, which breaks the client.
        # Exclude any type with more than MAX_STRUCT_PER_TYPE instances - it's icon spam anyway.
        rows = conn.execute(
            "SELECT type,min_x,min_y,min_z,max_x,max_y,max_z FROM structures WHERE world=? "
            "AND type NOT IN (SELECT type FROM structures WHERE world=? GROUP BY type HAVING COUNT(*) > ?)",
            (world, world, MAX_STRUCT_PER_TYPE)).fetchall()
        out = [{"type": r[0], "minX": r[1], "minY": r[2], "minZ": r[3],
                "maxX": r[4], "maxY": r[5], "maxZ": r[6],
                "x": (r[1] + r[4]) // 2, "y": (r[2] + r[5]) // 2, "z": (r[3] + r[6]) // 2}
               for r in rows]
        return {"world": world, "seed": WORLD_SEED, "structures": out}
    finally:
        conn.close()


def _decode_nbt(v):
    """The chunk_nbt.nbt column is JSON text, but tolerate a zlib BLOB if it is ever compressed."""
    if isinstance(v, (bytes, bytearray)):
        try:
            v = zlib.decompress(bytes(v)).decode("utf-8")
        except Exception:
            v = bytes(v).decode("utf-8", "replace")
    try:
        return json.loads(v)
    except Exception:
        return v


def nbt_at(world, x, y, z):
    """Everything stored at one block position (block entity + any entities standing there)."""
    conn = db_ro()
    try:
        try:
            rows = conn.execute(
                "SELECT kind,id,nbt FROM chunk_nbt WHERE world=? AND x=? AND y=? AND z=?",
                (world, x, y, z)).fetchall()
        except sqlite3.OperationalError:
            rows = []  # chunk_nbt not dumped yet
        return {"world": world, "x": x, "y": y, "z": z, "count": len(rows),
                "items": [{"kind": r[0], "id": r[1], "nbt": _decode_nbt(r[2])} for r in rows]}
    finally:
        conn.close()


def nbt_near(world, x, z, radius=48, limit=200):
    """Block entities / entities within `radius` blocks (XZ) - "what's in this build"."""
    conn = db_ro()
    try:
        try:
            rows = conn.execute(
                "SELECT kind,id,x,y,z,nbt FROM chunk_nbt WHERE world=? AND x>=? AND x<=? "
                "AND z>=? AND z<=? LIMIT ?",
                (world, x - radius, x + radius, z - radius, z + radius, limit)).fetchall()
        except sqlite3.OperationalError:
            rows = []
        return {"world": world, "count": len(rows),
                "items": [{"kind": r[0], "id": r[1], "x": r[2], "y": r[3], "z": r[4],
                           "nbt": _decode_nbt(r[5])} for r in rows]}
    finally:
        conn.close()


LOG_DB_PATH = os.environ.get("GT_LOG_DB", "/opt/minecraft/plugins/GroundTruth/groundtruth-log.db")

# --- admin panel auth (A1) -------------------------------------------------------------------
# One login code for everyone, minted by the PLUGIN (signed token). The web verifies the signature
# with the shared secret - no shared database needed. Player codes are one-shot; admin codes are
# reusable. Brute force is still defended: warn at 5 failed attempts, IP-ban (web + MC) at 10.
AUTH_SECRET = os.environ.get("GT_AUTH_SECRET", "")
CONSUMED_FILE = Path(os.environ.get("GT_CONSUMED_FILE", "/opt/groundtruth-web/consumed_codes.json"))
ADMIN_FAIL_FILE = Path(os.environ.get("GT_ADMIN_FAIL_FILE", "/opt/groundtruth-web/auth_failures.json"))
ADMIN_WARN_AT = int(os.environ.get("GT_ADMIN_WARN_AT", "5"))
ADMIN_BAN_AT = int(os.environ.get("GT_ADMIN_BAN_AT", "10"))
MC_LOG = os.environ.get("GT_MC_LOG", "/opt/minecraft/logs/latest.log")
_admin_fails = {}
_admin_banned = set()


def _b64d(s):
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))


def verify_token(code):
    """Return the payload dict for a valid, unexpired signed login code, else None."""
    if not AUTH_SECRET or not code or "." not in code:
        return None
    p, sig = code.rsplit(".", 1)
    want = hmac.new(AUTH_SECRET.encode(), p.encode(), hashlib.sha256).digest()
    try:
        got = _b64d(sig)
        if not hmac.compare_digest(want, got):
            return None
        payload = json.loads(_b64d(p))
    except Exception:
        return None
    if int(payload.get("e", 0)) < time.time() * 1000:
        return None
    return payload


def consume_once(payload):
    """True the first time this (player) token id is used; False if already consumed."""
    try:
        d = json.loads(CONSUMED_FILE.read_text())
    except Exception:
        d = {}
    j = payload.get("j")
    if j in d:
        return False
    d[j] = int(time.time())
    cutoff = time.time() - 7 * 86400
    d = {k: v for k, v in d.items() if v > cutoff}
    try:
        CONSUMED_FILE.write_text(json.dumps(d))
    except Exception:
        pass
    return True


def _load_admin_fails():
    global _admin_fails, _admin_banned
    try:
        d = json.loads(ADMIN_FAIL_FILE.read_text())
        _admin_fails = d.get("fails", {})
        _admin_banned = set(d.get("banned", []))
    except Exception:
        _admin_fails, _admin_banned = {}, set()


def _save_admin_fails():
    try:
        ADMIN_FAIL_FILE.write_text(json.dumps({"fails": _admin_fails, "banned": sorted(_admin_banned)}))
    except Exception:
        pass


def _client_ip(handler):
    """Real client IP (the public URL goes through a Cloudflare tunnel)."""
    for h in ("CF-Connecting-IP", "X-Forwarded-For", "X-Real-IP"):
        v = handler.headers.get(h)
        if v:
            return v.split(",")[0].strip()
    return handler.client_address[0]


def admin_auth(handler, qs, need_rollback=False):
    """(ok, reason) for an admin endpoint: valid token + admin flag (and the rollback flag if needed)."""
    ip = _client_ip(handler)
    if ip in _admin_banned:
        return False, "banned"
    code = handler.headers.get("X-GT-Code") or (qs.get("code", [""])[0])
    payload = verify_token(code)
    if payload and payload.get("a") == 1 and (not need_rollback or payload.get("p") == 1):
        if _admin_fails.pop(ip, None) is not None:
            _save_admin_fails()
        return True, "ok"
    e = _admin_fails.get(ip) or {"n": 0, "first": time.time()}
    e["n"] += 1
    e["last"] = time.time()
    _admin_fails[ip] = e
    if e["n"] >= ADMIN_BAN_AT:
        _admin_banned.add(ip)
        _save_admin_fails()
        try:
            rcon_cmd(f"ban-ip {ip}")
        except Exception:
            pass
        return False, "banned"
    _save_admin_fails()
    return False, "warn" if e["n"] >= ADMIN_WARN_AT else "bad"


_load_admin_fails()


def admin_health():
    out = {"log": {}, "map": {}, "server": {}}
    try:
        if os.path.exists(LOG_DB_PATH):
            conn = sqlite3.connect(f"file:{LOG_DB_PATH}?mode=ro", uri=True)
            out["log"]["events"] = conn.execute("SELECT COUNT(*) FROM log_events").fetchone()[0]
            r = conn.execute("SELECT MIN(ts), MAX(ts) FROM log_events").fetchone()
            out["log"]["oldest"], out["log"]["newest"] = r[0], r[1]
            for t in ("log_positions", "log_containers", "log_inventories", "log_sessions"):
                try:
                    out["log"][t] = conn.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0]
                except sqlite3.OperationalError:
                    out["log"][t] = None
            conn.close()
            out["log"]["db_bytes"] = os.path.getsize(LOG_DB_PATH)
    except Exception as e:
        out["log"]["error"] = str(e)
    try:
        out["server"]["logstatus"] = rcon_cmd("groundtruth logstatus")
    except Exception as e:
        out["server"]["error"] = str(e)
    try:
        out["map"]["db_bytes"] = os.path.getsize(DB_PATH) if os.path.exists(DB_PATH) else 0
    except Exception:
        pass
    return out


def admin_summary(world=None, since_ms=0):
    if not os.path.exists(LOG_DB_PATH):
        return {"enabled": False}
    conn = sqlite3.connect(f"file:{LOG_DB_PATH}?mode=ro", uri=True)
    try:
        args, where = [], "WHERE 1=1"
        if world:
            where += " AND world=?"; args.append(world)
        if since_ms:
            where += " AND ts>=?"; args.append(since_ms)
        by_action = conn.execute(f"SELECT action,COUNT(*) FROM log_events {where} GROUP BY action ORDER BY 2 DESC", args).fetchall()
        by_actor = conn.execute(
            f"SELECT COALESCE(actor_name,actor_id,'?'),COUNT(*) FROM log_events {where} "
            "GROUP BY 1 ORDER BY 2 DESC LIMIT 20", args).fetchall()
        return {"enabled": True,
                "by_action": [{"action": a, "n": n} for a, n in by_action],
                "by_actor": [{"actor": a, "n": n} for a, n in by_actor]}
    finally:
        conn.close()


def _log_conn():
    return sqlite3.connect(f"file:{LOG_DB_PATH}?mode=ro", uri=True)


_blockcolors_cache = None


def load_blockcolors():
    """{block: (r,g,b)} from blockcolors.json (built by AtlasBuilder), cached."""
    global _blockcolors_cache
    if _blockcolors_cache is not None:
        return _blockcolors_cache
    out = {}
    try:
        raw = json.loads((TILES_DIR / "blockcolors.json").read_text())
        for k, v in raw.items():
            h = v.lstrip("#")
            out[k] = (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16))
    except Exception:
        pass
    _blockcolors_cache = out
    return out


_extent_cache = {}


def db_world_extent(world, ttl=120):
    """(minCx,maxCx,minCz,maxCz,chunks) for a world, cached - the underlying aggregate scans every
    chunk in the world, so it must never run on a hot path."""
    now = time.time()
    hit = _extent_cache.get(world)
    if hit and now - hit[0] < ttl:
        return hit[1]
    conn = db_ro()
    try:
        r = conn.execute("SELECT MIN(cx),MAX(cx),MIN(cz),MAX(cz),COUNT(*) FROM chunks WHERE world=? "
                         "AND surface_y IS NOT NULL", (world,)).fetchone()
        ext = r if r and r[0] is not None else None
    except Exception:
        ext = None
    finally:
        conn.close()
    _extent_cache[world] = (now, ext)
    return ext


# Short-lived cache of finished tile payloads: zooming in and back out (or dragging back over ground
# you just looked at) then costs nothing at all. Keyed by the full request string; the terrain data
# only changes when a dump runs, so a short TTL is plenty.
_tile_cache = {}
_tile_cache_order = []
_TILE_CACHE_MAX = 120


def tile_cache_get(key):
    hit = _tile_cache.get(key)
    if not hit:
        return None
    ts, body = hit
    if time.time() - ts > 90:
        _tile_cache.pop(key, None)
        return None
    return body


def tile_cache_put(key, body):
    if len(_tile_cache) >= _TILE_CACHE_MAX:
        for k in _tile_cache_order[:60]:
            _tile_cache.pop(k, None)
        del _tile_cache_order[:60]
    _tile_cache[key] = (time.time(), body)
    _tile_cache_order.append(key)


def db_world_bounds(world):
    """(minCx, maxCx, minCz, maxCz) for a world, plus a 'core' range with a few far-flung chunks
    trimmed off each end - framing on the raw extent lets a handful of outliers shrink the view to
    a dot, which is exactly what a whole-world 3D view must not do."""
    conn = db_ro()
    try:
        r = conn.execute("SELECT MIN(cx),MAX(cx),MIN(cz),MAX(cz) FROM chunks WHERE world=? "
                         "AND surface_y IS NOT NULL", (world,)).fetchone()
        if not r or r[0] is None:
            return None
        total = conn.execute("SELECT COUNT(*) FROM chunks WHERE world=? AND surface_y IS NOT NULL",
                             (world,)).fetchone()[0]
        trim = min(300, max(0, total // 20))

        def edge(col, desc):
            v = conn.execute(
                f"SELECT {col} FROM chunks WHERE world=? AND surface_y IS NOT NULL ORDER BY {col} "
                f"{'DESC' if desc else 'ASC'} LIMIT 1 OFFSET ?", (world, trim)).fetchone()
            return v[0] if v and v[0] is not None else None

        core = [edge("cx", False), edge("cx", True), edge("cz", False), edge("cz", True)]
        return (r[0], r[1], r[2], r[3], *core)
    except Exception:
        return None
    finally:
        conn.close()


def world_min_y(world):
    """The dimension's minimum Y (chunk_pixels heights are stored relative to it)."""
    try:
        m = json.loads((TILES_DIR / world / "meta.json").read_text())
        return int(m.get("minY", -64))
    except Exception:
        return -64


def pixels_grid(world, cx0, cz0, cx1, cz1, px, deflate=False, cell_cap=400000, chunk_cap=12000):
    """Per-block surface height + colour for a region, decimated to `px` blocks per output cell.

    Source is chunk_pixels (a 16x16 grid per chunk). Heights are the MEDIAN of each cell's blocks,
    which is what keeps trees and lone peaks from spiking the terrain at fine resolutions; colours
    are the mean over the valid blocks. Output is a dense grid so the client can mesh it directly:

        i16 ox0, i16 oz0, i16 px, i32 cols, i32 rows
        then cols*rows * (i16 y, u8 r, u8 g, u8 b)   (y = -32768 means "no data")
    """
    if np is None:
        return {"error": "numpy unavailable on the server"}

    px = max(1, int(px))
    nx = cx1 - cx0 + 1
    nz = cz1 - cz0 + 1
    if nx * nz > chunk_cap:
        return {"error": "area too large"}

    min_y = world_min_y(world)
    conn = db_ro()
    try:
        rows = conn.execute(
            "SELECT cx,cz,rgb,hgt,ground_hgt FROM chunk_pixels WHERE world=? AND cx>=? AND cx<=? "
            "AND cz>=? AND cz<=?", (world, cx0, cx1, cz0, cz1)).fetchall()
    finally:
        conn.close()
    if not rows:
        return {"error": "no pixel data for this area"}

    gh = np.full((nz * 16, nx * 16), -32768, np.int32)
    gg = np.full((nz * 16, nx * 16), -32768, np.int32)
    gc = np.zeros((nz * 16, nx * 16, 3), np.uint8)
    for part in decompress_pool().map(unpack_chunk_pixels, rows):
        if part is None:
            continue
        cx, cz, hgt, ghg, rgb = part
        yy, xx = (cz - cz0) * 16, (cx - cx0) * 16
        gh[yy:yy + 16, xx:xx + 16] = hgt + min_y
        gg[yy:yy + 16, xx:xx + 16] = ghg + min_y
        gc[yy:yy + 16, xx:xx + 16] = rgb

    # pad up to a whole number of cells, then aggregate each px*px block
    ph, pw = (-gh.shape[0]) % px, (-gh.shape[1]) % px
    if ph or pw:
        gh = np.pad(gh, ((0, ph), (0, pw)), constant_values=-32768)
        gg = np.pad(gg, ((0, ph), (0, pw)), constant_values=-32768)
        gc = np.pad(gc, ((0, ph), (0, pw), (0, 0)))
    rowsn, cols = gh.shape[0] // px, gh.shape[1] // px
    if cols * rowsn > cell_cap:
        return {"error": "area too large"}

    def aggregate(a):
        blk = a.reshape(rowsn, px, cols, px).transpose(0, 2, 1, 3).reshape(rowsn, cols, px * px)
        ok = blk != -32768
        med = np.where(ok.any(axis=2), np.median(np.where(ok, blk, np.nan), axis=2), -32768)
        return np.nan_to_num(med, nan=-32768).astype(np.int32), ok

    med_surface, valid = aggregate(gh)
    med_ground, _ = aggregate(gg)
    # chunks the ground backfill has not reached yet show their surface
    med_ground = np.where(med_ground == -32768, med_surface, med_ground)

    cb = gc.reshape(rowsn, px, cols, px, 3).transpose(0, 2, 1, 3, 4).reshape(rowsn, cols, px * px, 3)
    v3 = valid[..., None]
    cnt = np.maximum(v3.sum(axis=2), 1)
    col = ((cb.astype(np.int32) * v3).sum(axis=2) // cnt).astype(np.uint8).reshape(rowsn, cols, 3)

    ox0 = (cx0 * 16) // px
    oz0 = (cz0 * 16) // px
    count = cols * rowsn
    rec = np.empty((count, 7), np.uint8)
    rec[:, 0:2] = med_surface.reshape(-1).astype(">i2").view(np.uint8).reshape(-1, 2)
    rec[:, 2:4] = med_ground.reshape(-1).astype(">i2").view(np.uint8).reshape(-1, 2)
    rec[:, 4:7] = col.reshape(-1, 3).astype(np.uint8)
    raw = struct.pack(">hhhii", ox0, oz0, px, cols, rowsn) + rec.tobytes()
    res = {"world": world, "ox0": ox0, "oz0": oz0, "px": px, "cols": cols, "rows": rowsn,
           "cells": int(valid.any(axis=2).sum())}
    if deflate:
        res["deflated"] = True
        res["data"] = base64.b64encode(zlib.compress(raw, 6)).decode("ascii")
    else:
        res["data"] = base64.b64encode(raw).decode("ascii")
    return res


_biome_tints_cache = None
_DEFAULT_TINT = {"grass": (0x91, 0xBD, 0x59), "foliage": (0x77, 0xAB, 0x2F),
                 "dry_foliage": (0x77, 0xAB, 0x2F), "water": (0x3F, 0x76, 0xE4)}


def load_biome_tints():
    """Per-biome tints from biome_tints.json (built by AtlasBuilder), cached."""
    global _biome_tints_cache
    if _biome_tints_cache is not None:
        return _biome_tints_cache
    try:
        _biome_tints_cache = json.loads((TILES_DIR / "biome_tints.json").read_text())
    except Exception:
        _biome_tints_cache = {}
    return _biome_tints_cache


def color_channel(name):
    """Which tint channel a block uses. Mirror of the dumper's Dumper.colorChannel so the coarse
    chunk colour matches what the offline dump put in the per-block detail layer."""
    b = name.split(":", 1)[1] if ":" in name else name
    if "water" in b or b == "bubble_column" or "seagrass" in b or "kelp" in b or b == "lily_pad":
        return "water"
    if "dry_grass" in b or "leaf_litter" in b or "dry_foliage" in b:
        return "dry_foliage"
    if b.endswith("_leaves") and not any(x in b for x in ("spruce", "birch", "azalea", "cherry", "pale_oak")):
        return "foliage"
    if "vine" in b:
        return "foliage"
    if b in ("grass_block", "grass", "short_grass", "tall_grass", "fern", "large_fern") \
            or "sugar_cane" in b or "potted_fern" in b:
        return "grass"
    return "none"


def biome_tinted(biome, block, rgb):
    """Apply the biome's tint to a block colour (grass/foliage/water), leaving the rest alone."""
    if not biome or not block:
        return rgb
    ch = color_channel(block)
    if ch == "none":
        return rgb
    entry = load_biome_tints().get(biome)
    if not entry:
        return rgb
    hexv = entry.get(ch)
    if not hexv:
        return rgb
    h = hexv.lstrip("#")
    br, bg, bb = int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16)
    dr, dg, db = _DEFAULT_TINT[ch]
    return (min(255, rgb[0] * br // max(1, dr)),
            min(255, rgb[1] * bg // max(1, dg)),
            min(255, rgb[2] * bb // max(1, db)))


def terrain_chunks(world, cx0, cz0, cx1, cz1, cap=450000, step=1, deflate=False, want_bounds=False):
    """Sparse per-chunk terrain for a large area - the data behind the 3D world view.

    Per chunk we return BOTH layers, because they answer different questions:
      surface_y - the true highest block (trees, plants, builds). Used for the fine tiers and to spot
                  sky islands, which sit far above the local ground.
      ground_y  - the terrain surface (vegetation and floating masses skipped). Used for the relief,
                  so a treetop cannot spike the map. Falls back to surface_y for chunks the ground
                  backfill has not reached yet.

    Binary: i16 minCx, i16 minCz, i32 count, then per chunk
            i16 dx, i16 dz, i16 surface_y, i16 ground_y, u8 r, u8 g, u8 b (surface colour).
    """
    step = max(1, int(step))
    conn = db_ro()
    try:
        sql = ("SELECT cx,cz,surface_y,ground_y,surface_block,biome FROM chunks WHERE world=? AND cx>=? "
               "AND cx<=? AND cz>=? AND cz<=? AND surface_y IS NOT NULL")
        args = [world, cx0, cx1, cz0, cz1]
        if step > 1:
            sql += " AND (cx % ?)=0 AND (cz % ?)=0"
            args += [step, step]
        sql += " LIMIT ?"
        args.append(cap)
        rows = conn.execute(sql, args).fetchall()
        # The bounds aggregate scans every chunk in the world. It is only needed by the one cheap
        # probe request the 3D view makes on open - computing it per tile request was costing ~0.5s
        # per tile, which is exactly why zooming felt like it took forever.
        ext = db_world_extent(world) if want_bounds else None
    finally:
        conn.close()
    bc = load_blockcolors()
    n = len(rows)
    rec = np.empty((n, 11), np.uint8)   # dx,dz,surface_y,ground_y (i16 each) + r,g,b
    if n:
        dx = np.fromiter((r[0] - cx0 for r in rows), dtype=">i2", count=n)
        dz = np.fromiter((r[1] - cz0 for r in rows), dtype=">i2", count=n)
        sy = np.fromiter((r[2] for r in rows), dtype=">i2", count=n)
        gy = np.fromiter(((r[2] if r[3] is None else r[3]) for r in rows), dtype=">i2", count=n)
        cols_rgb = np.fromiter(
            (c for r in rows for c in biome_tinted(r[5], r[4], bc.get(r[4], (120, 120, 120)))),
            dtype=np.uint8, count=n * 3)
        rec[:, 0:2] = dx.view(np.uint8).reshape(-1, 2)
        rec[:, 2:4] = dz.view(np.uint8).reshape(-1, 2)
        rec[:, 4:6] = sy.view(np.uint8).reshape(-1, 2)
        rec[:, 6:8] = gy.view(np.uint8).reshape(-1, 2)
        rec[:, 8:11] = cols_rgb.reshape(-1, 3)
    raw = struct.pack(">hhi", cx0, cz0, n) + rec.tobytes()
    out = {"world": world, "cx0": cx0, "cz0": cz0, "count": len(rows), "step": step,
           "bounds": {"minCx": ext[0], "maxCx": ext[1], "minCz": ext[2], "maxCz": ext[3],
                      "chunks": ext[4]} if ext and ext[4] else None}
    if deflate:
        out["deflated"] = True
        out["data"] = base64.b64encode(zlib.compress(raw, 6)).decode("ascii")
    else:
        out["data"] = base64.b64encode(raw).decode("ascii")
    return out



    return sqlite3.connect(f"file:{LOG_DB_PATH}?mode=ro", uri=True)


def db_rw():
    """Writable connection to the map DB (waypoints are the only thing the web writes)."""
    conn = sqlite3.connect(DB_PATH, timeout=10)
    conn.execute("PRAGMA busy_timeout=10000")
    return conn


def waypoints_list(uuid):
    conn = db_ro()
    try:
        try:
            rows = conn.execute(
                "SELECT uuid,name,world,x,y,z,public FROM waypoints WHERE public=1 OR uuid=?",
                (uuid or "",)).fetchall()
        except sqlite3.OperationalError:
            return []
        return [{"uuid": r[0], "name": r[1], "world": r[2], "x": r[3], "y": r[4], "z": r[5],
                 "public": r[6] == 1} for r in rows]
    finally:
        conn.close()


def waypoint_put(uuid, name, world, x, y, z, is_public):
    now = int(time.time())
    conn = db_rw()
    try:
        conn.execute(
            "INSERT INTO waypoints (uuid,name,world,x,y,z,public,created_ts,updated_ts) VALUES (?,?,?,?,?,?,?,?,?) "
            "ON CONFLICT(uuid,name) DO UPDATE SET world=excluded.world, x=excluded.x, y=excluded.y, z=excluded.z, "
            "public=excluded.public, updated_ts=excluded.updated_ts",
            (uuid, name, world, x, y, z, 1 if is_public else 0, now, now))
        conn.commit()
        return True
    except Exception:
        return False
    finally:
        conn.close()


def waypoint_delete(uuid, name):
    conn = db_rw()
    try:
        cur = conn.execute("DELETE FROM waypoints WHERE uuid=? AND name=?", (uuid, name))
        conn.commit()
        return cur.rowcount > 0
    except Exception:
        return False
    finally:
        conn.close()



    return sqlite3.connect(f"file:{LOG_DB_PATH}?mode=ro", uri=True)


REVERTIBLE_ACTIONS = "('block-place','block-break','entity-change-block','fluid-place','fluid-pickup')"


def _actor_clause(actor, alias="pl", col="name"):
    return f" AND ({alias}.{col} LIKE ? OR p.uuid LIKE ?)", ["%" + actor + "%", "%" + actor + "%"]


def admin_heatmap(world=None, actor=None, since_ms=0, until_ms=0, cell=16, cap=400000, source="raw"):
    """Density of player position samples, bucketed into `cell`-sized squares (chunk-aligned by default).
    source='heat' reads the pre-aggregated log_heat table (fast, chunk cells only)."""
    if source == "heat" and cell == 16 and os.path.exists(LOG_DB_PATH):
        conn = _log_conn()
        try:
            where, args = "WHERE 1=1", []
            if world:
                where += " AND h.world=?"; args.append(world)
            if actor:
                where += " AND (pl.name LIKE ? OR h.uuid LIKE ?)"; args += ["%" + actor + "%", "%" + actor + "%"]
            if since_ms:
                where += " AND h.day >= ?"; args.append(since_ms // 86400000)
            if until_ms:
                where += " AND h.day <= ?"; args.append(until_ms // 86400000)
            rows = conn.execute(
                f"SELECT h.gx,h.gz,SUM(h.n) FROM log_heat h LEFT JOIN log_players pl ON pl.uuid=h.uuid "
                f"{where} GROUP BY 1,2", args).fetchall()
            return {"enabled": True, "cell": 16, "source": "heat",
                    "cells": [[r[0], r[1], r[2]] for r in rows]}
        finally:
            conn.close()
    if not os.path.exists(LOG_DB_PATH):
        return {"enabled": False, "cells": []}
    conn = _log_conn()
    try:
        where, args = "WHERE 1=1", []
        if world:
            where += " AND p.world=?"; args.append(world)
        if actor:
            c, a = _actor_clause(actor); where += c; args += a
        if since_ms:
            where += " AND p.ts>=?"; args.append(since_ms)
        if until_ms:
            where += " AND p.ts<=?"; args.append(until_ms)
        rows = conn.execute(
            f"SELECT p.x,p.z FROM log_positions p LEFT JOIN log_players pl ON pl.uuid=p.uuid {where} LIMIT ?",
            args + [cap]).fetchall()
        cells = {}
        for x, z in rows:
            k = (x // cell, z // cell)
            cells[k] = cells.get(k, 0) + 1
        return {"enabled": True, "cell": cell, "capped": len(rows) >= cap,
                "cells": [[gx, gz, n] for (gx, gz), n in cells.items()]}
    finally:
        conn.close()


def admin_track(world=None, actor=None, since_ms=0, until_ms=0, cap=30000):
    """Ordered position samples for one actor (a walkable line)."""
    if not os.path.exists(LOG_DB_PATH):
        return {"points": []}
    conn = _log_conn()
    try:
        where, args = "WHERE 1=1", []
        if world:
            where += " AND p.world=?"; args.append(world)
        if actor:
            c, a = _actor_clause(actor); where += c; args += a
        if since_ms:
            where += " AND p.ts>=?"; args.append(since_ms)
        if until_ms:
            where += " AND p.ts<=?"; args.append(until_ms)
        rows = conn.execute(
            f"SELECT p.x,p.y,p.z,p.ts FROM log_positions p LEFT JOIN log_players pl ON pl.uuid=p.uuid "
            f"{where} ORDER BY p.ts ASC LIMIT ?", args + [cap]).fetchall()
        return {"points": [[r[0], r[1], r[2], r[3]] for r in rows], "capped": len(rows) >= cap}
    finally:
        conn.close()


def admin_rollback_preview(world, actor, minutes, radius=0, x=None, z=None, limit=20000):
    """Same filter semantics as the plugin's findRevertible, so the preview matches what would apply."""
    if not os.path.exists(LOG_DB_PATH):
        return {"count": 0, "sample": [], "blocks": []}
    since = int(time.time() * 1000) - minutes * 60000
    conn = _log_conn()
    try:
        where, args = "WHERE reverted=0 AND action IN " + REVERTIBLE_ACTIONS, []
        if world:
            where += " AND world=?"; args.append(world)
        if actor:
            where += " AND (actor_name LIKE ? OR actor_id LIKE ?)"; args += ["%" + actor + "%", "%" + actor + "%"]
        if radius and x is not None and z is not None:
            where += " AND x>=? AND x<=? AND z>=? AND z<=?"
            args += [x - radius, x + radius, z - radius, z + radius]
        where += " AND ts>=?"; args.append(since)
        total = conn.execute(f"SELECT COUNT(*) FROM log_events {where}", args).fetchone()[0]
        rows = conn.execute(
            f"SELECT ts,action,actor_name,actor_id,x,y,z,target,before FROM log_events {where} "
            f"ORDER BY ts DESC LIMIT ?", args + [limit]).fetchall()
        sample = [{"ts": r[0], "action": r[1], "actor": r[2] or r[3], "x": r[4], "y": r[5], "z": r[6],
                   "target": r[7], "before": r[8]} for r in rows[:25]]
        blocks = [[r[4], r[5], r[6]] for r in rows]
        return {"count": total, "sample": sample, "blocks": blocks, "capped": total > limit}
    finally:
        conn.close()


def admin_audit(limit=100):
    if not os.path.exists(LOG_DB_PATH):
        return {"rows": []}
    conn = _log_conn()
    try:
        rows = conn.execute(
            "SELECT id,ts,actor_name,meta FROM log_events WHERE action IN ('rollback','rollback-undo') "
            "ORDER BY id DESC LIMIT ?", (limit,)).fetchall()
        return {"rows": [{"id": r[0], "ts": r[1], "actor": r[2], "meta": r[3]} for r in rows]}
    finally:
        conn.close()


def admin_containers(world=None, x=None, z=None, radius=0, limit=200):
    if not os.path.exists(LOG_DB_PATH):
        return {"rows": []}
    conn = _log_conn()
    try:
        where, args = "WHERE 1=1", []
        if world:
            where += " AND world=?"; args.append(world)
        if radius and x is not None and z is not None:
            where += " AND x>=? AND x<=? AND z>=? AND z<=?"
            args += [x - radius, x + radius, z - radius, z + radius]
        rows = conn.execute(
            f"SELECT world,x,y,z,kind,contents,updated_ts FROM log_containers {where} "
            f"ORDER BY updated_ts DESC LIMIT ?", args + [limit]).fetchall()
        return {"rows": [{"world": r[0], "x": r[1], "y": r[2], "z": r[3], "kind": r[4],
                          "contents": json.loads(r[5]) if r[5] else [], "updated": r[6]} for r in rows]}
    finally:
        conn.close()


def admin_container(world, x, y, z, limit=100):
    """One container's last-known contents plus the access log (takes/puts/opens/moves) at that spot."""
    if not os.path.exists(LOG_DB_PATH):
        return {"contents": [], "events": []}
    conn = _log_conn()
    try:
        row = conn.execute(
            "SELECT kind,contents,updated_ts FROM log_containers WHERE world=? AND x=? AND y=? AND z=?",
            (world, x, y, z)).fetchone()
        ev = conn.execute(
            "SELECT ts,action,actor_name,target,before,after,meta FROM log_events "
            "WHERE world=? AND x=? AND y=? AND z=? AND action LIKE 'container%' ORDER BY ts DESC LIMIT ?",
            (world, x, y, z, limit)).fetchall()
        return {"kind": row[0] if row else None,
                "contents": json.loads(row[1]) if row and row[1] else [],
                "updated": row[2] if row else None,
                "events": [{"ts": r[0], "action": r[1], "actor": r[2], "target": r[3],
                            "before": r[4], "after": r[5], "meta": r[6]} for r in ev]}
    finally:
        conn.close()


def admin_flow(world=None, x=None, z=None, radius=0, item=None, min_n=1, limit=300):
    """Aggregated container flow (hoppers/droppers): one row per container+item+hour with a count."""
    if not os.path.exists(LOG_DB_PATH):
        return {"rows": []}
    conn = _log_conn()
    try:
        where, args = "WHERE n>=?", [min_n]
        if world:
            where += " AND world=?"; args.append(world)
        if radius and x is not None and z is not None:
            where += " AND x>=? AND x<=? AND z>=? AND z<=?"
            args += [x - radius, x + radius, z - radius, z + radius]
        if item:
            where += " AND item LIKE ?"; args.append("%" + item + "%")
        rows = conn.execute(
            f"SELECT world,x,y,z,item,bucket,n,updated_ts FROM log_container_flow {where} "
            f"ORDER BY n DESC LIMIT ?", args + [limit]).fetchall()
        return {"rows": [{"world": r[0], "x": r[1], "y": r[2], "z": r[3], "item": r[4],
                          "bucket": r[5], "n": r[6], "updated": r[7]} for r in rows]}
    finally:
        conn.close()


def _sum_items(contents):
    d = {}
    for s in contents:
        d[s["id"]] = d.get(s["id"], 0) + s.get("n", 0)
    return d


def admin_inventory_diff(a, b):
    """Diff two inventory snapshots (by id) - 'what changed between these two points'."""
    if not os.path.exists(LOG_DB_PATH):
        return {"error": "no log db"}
    conn = _log_conn()
    try:
        ra = conn.execute("SELECT ts,reason,contents FROM log_inventories WHERE id=?", (a,)).fetchone()
        rb = conn.execute("SELECT ts,reason,contents FROM log_inventories WHERE id=?", (b,)).fetchone()
        if not ra or not rb:
            return {"error": "snapshot not found"}
        da = _sum_items(json.loads(ra[2]) if ra[2] else [])
        db_ = _sum_items(json.loads(rb[2]) if rb[2] else [])
        diff = [{"item": k, "a": da.get(k, 0), "b": db_.get(k, 0), "delta": db_.get(k, 0) - da.get(k, 0)}
                for k in sorted(set(da) | set(db_)) if da.get(k, 0) != db_.get(k, 0)]
        return {"a": {"id": a, "ts": ra[0], "reason": ra[1]},
                "b": {"id": b, "ts": rb[0], "reason": rb[1]}, "diff": diff}
    finally:
        conn.close()


def admin_inventories(uuid=None, limit=100):
    if not os.path.exists(LOG_DB_PATH):
        return {"rows": []}
    conn = _log_conn()
    try:
        where, args = "WHERE 1=1", []
        if uuid:
            where += " AND uuid=?"; args.append(uuid)
        rows = conn.execute(
            f"SELECT id,uuid,ts,reason,contents FROM log_inventories {where} ORDER BY ts DESC LIMIT ?",
            args + [limit]).fetchall()
        return {"rows": [{"id": r[0], "uuid": r[1], "ts": r[2], "reason": r[3],
                          "contents": json.loads(r[4]) if r[4] else []} for r in rows]}
    finally:
        conn.close()


def admin_console(lines=200, grep=None):
    """Tail of the Minecraft server log - so admins can see RCON-issued output (e.g. Sassy) without
    needing the Crafty console."""
    try:
        with open(MC_LOG, "rb") as f:
            f.seek(0, 2)
            size = f.tell()
            back = min(size, 600_000)
            f.seek(size - back)
            data = f.read().decode("utf-8", "replace")
        out = data.splitlines()[-max(1, min(lines, 2000)):]
        if grep:
            out = [ln for ln in out if grep.lower() in ln.lower()]
        return {"lines": out}
    except Exception as e:
        return {"error": str(e), "lines": []}


def log_query(world=None, x=None, z=None, radius=0, since_ms=0, player=None, action=None, limit=200, offset=0):
    """Read-only query over the M-Log event log (groundtruth-log.db), newest first."""
    if not os.path.exists(LOG_DB_PATH):
        return {"enabled": False, "count": 0, "events": []}
    conn = sqlite3.connect(f"file:{LOG_DB_PATH}?mode=ro", uri=True)
    try:
        sql = ("SELECT ts,world,x,y,z,action,actor_kind,actor_id,actor_name,cause_kind,cause_id,"
               "cause_name,target,before,after,meta FROM log_events WHERE 1=1")
        args = []
        if world:
            sql += " AND world=?"; args.append(world)
        if player:
            sql += " AND (actor_name LIKE ? OR actor_id=?)"; args += ["%" + player + "%", player]
        if x is not None and z is not None and radius:
            sql += " AND x>=? AND x<=? AND z>=? AND z<=?"
            args += [x - radius, x + radius, z - radius, z + radius]
        if since_ms:
            sql += " AND ts>=?"; args.append(since_ms)
        if action:
            sql += " AND action=?"; args.append(action)
        sql += " ORDER BY ts DESC LIMIT ?"; args.append(max(1, min(limit, 5000)))
        if offset:
            sql += " OFFSET ?"; args.append(max(0, offset))
        rows = conn.execute(sql, args).fetchall()
        out = [{"ts": r[0], "world": r[1], "x": r[2], "y": r[3], "z": r[4], "action": r[5],
                "actorKind": r[6], "actorId": r[7], "actorName": r[8],
                "causeKind": r[9], "causeId": r[10], "causeName": r[11],
                "target": r[12], "before": r[13], "after": r[14], "meta": r[15]} for r in rows]
        return {"enabled": True, "count": len(out), "events": out}
    finally:
        conn.close()


def chunkinfo(world, cx, cz):
    conn = db_ro()
    try:
        row = conn.execute(
            "SELECT biome,surface_block,surface_y FROM chunks WHERE world=? AND cx=? AND cz=?",
            (world, cx, cz)).fetchone()
        if not row:
            return {"indexed": False}
        return {"indexed": True, "biome": row[0], "surface_block": row[1], "surface_y": row[2]}
    finally:
        conn.close()


def detail(world, cx0, cz0, cx1, cz1, cap=4096):
    """Block-resolution detail: the 16x16 surface colour+height grids for a chunk range, still
    zlib-compressed (the client inflates them with DecompressionStream). Only what's on screen."""
    conn = db_ro()
    try:
        try:
            rows = conn.execute(
                "SELECT cx,cz,rgb,hgt FROM chunk_pixels WHERE world=? AND cx>=? AND cx<=? AND cz>=? AND cz<=? LIMIT ?",
                (world, cx0, cx1, cz0, cz1, cap)).fetchall()
        except sqlite3.OperationalError:
            rows = []  # detail not dumped yet
        out = [{"cx": r[0], "cz": r[1],
                "rgb": base64.b64encode(r[2]).decode(),
                "hgt": base64.b64encode(r[3]).decode()} for r in rows]
        return {"world": world, "count": len(out), "chunks": out}
    finally:
        conn.close()


def biome_counts(world):
    conn = db_ro()
    try:
        rows = conn.execute(
            "SELECT biome,count(*) FROM chunks WHERE world=? AND biome IS NOT NULL GROUP BY biome",
            (world,)).fetchall()
        return {"world": world, "counts": {b: c for b, c in rows}}
    finally:
        conn.close()


def biome_chunks(world, biome, limit=200000):
    conn = db_ro()
    try:
        rows = conn.execute(
            "SELECT cx,cz FROM chunks WHERE world=? AND biome=? LIMIT ?", (world, biome, limit)).fetchall()
        flat = []
        for cx, cz in rows:
            flat.append(cx); flat.append(cz)
        return {"world": world, "biome": biome, "count": len(rows), "chunks": flat}
    finally:
        conn.close()


def _java_slime(seed, cx, cz):
    s = (seed + cx * cx * 4987142 + cx * 5947611 + cz * cz * 4392871 + cz * 2918603) & ((1 << 64) - 1)
    s = (s ^ 0x5DEECE66D) & ((1 << 48) - 1)
    s = (s * 0x5DEECE66D + 0xB) & ((1 << 48) - 1)
    return ((s >> (48 - 31)) % 10) == 0


def slimeclusters(world, min_k=2, max_k=5):
    """Find maximal squares of contiguous slime chunks, over indexed chunks only."""
    if WORLD_SEED is None:
        return {"world": world, "clusters": []}
    conn = db_ro()
    try:
        rows = conn.execute("SELECT cx,cz FROM chunks WHERE world=?", (world,)).fetchall()
    finally:
        conn.close()
    slime = set()
    for cx, cz in rows:
        if _java_slime(WORLD_SEED, cx, cz):
            slime.add((cx, cz))
    anchors = []
    for (cx, cz) in slime:
        k = 0
        while k < max_k:
            n = k + 1
            ok = all((cx + i, cz + j) in slime for i in range(n) for j in range(n))
            if not ok:
                break
            k = n
        if k >= min_k:
            anchors.append((k, cx, cz))
    anchors.sort(reverse=True)
    kept = []
    for k, cx, cz in anchors:
        if any(cx >= o[1] and cz >= o[2] and cx + k <= o[1] + o[0] and cz + k <= o[2] + o[0] for o in kept):
            continue
        kept.append((k, cx, cz))
        if len(kept) >= 200:
            break
    clusters = [{"size": k, "cx": cx, "cz": cz} for k, cx, cz in kept]
    counts = {}
    for k, _, _ in kept:
        counts[str(k)] = counts.get(str(k), 0) + 1
    return {"world": world, "clusters": clusters, "counts": counts}
SKIN_SOURCES = [
    "https://mc-heads.net/avatar/{id}/32",
    "https://minotar.net/helm/{id}/32.png",
    "https://crafatar.com/avatars/{id}?size=32&overlay",
]
# Bedrock (Floodgate) UUIDs are deterministic from the XUID, so services keyed
# off the UUID work. GeyserMC's global API has the real uploaded Bedrock skin;
# Mineatar/mc-heads are generic fallbacks.
BEDROCK_SKIN_SOURCES = [
    "https://api.mineatar.io/face/{full}?scale=2",
    "https://mc-heads.net/avatar/{full}/32",
]
UA = {"User-Agent": "groundtruth-map/1.0"}


def _bedrock_xuid(uuid):
    # Floodgate UUIDs encode the XUID in the low 64 bits (last 16 hex chars).
    h = (uuid or "").replace("-", "")
    if len(h) != 32:
        return None
    try:
        return int(h[-16:], 16)
    except ValueError:
        return None


def _face_from_skin(raw):
    # Crop the front head face (8,8)-(16,16) and composite the hat overlay layer.
    if Image is None:
        return None
    try:
        im = Image.open(io.BytesIO(raw)).convert("RGBA")
        if im.width < 16 or im.height < 16:
            return None
        face = im.crop((8, 8, 16, 16))
        if im.width >= 64 and im.height >= 64:
            face.alpha_composite(im.crop((40, 8, 48, 16)))
        face = face.resize((32, 32), Image.NEAREST)
        out = io.BytesIO()
        face.save(out, "PNG")
        return out.getvalue()
    except Exception:
        return None


def _geyser_face(uuid):
    # GeyserMC global skin API resolves the real Bedrock skin from the XUID.
    xuid = _bedrock_xuid(uuid)
    if xuid is None:
        return None
    try:
        req = urllib.request.Request("https://api.geysermc.org/v2/skin/%d" % xuid, headers=UA)
        with urllib.request.urlopen(req, timeout=8) as resp:
            meta = json.loads(resp.read().decode("utf-8", "replace"))
        val = meta.get("value")
        if not val:
            return None
        prof = json.loads(base64.b64decode(val).decode("utf-8", "replace"))
        url = (prof.get("textures", {}).get("SKIN", {}) or {}).get("url")
        if not url:
            return None
        if url.startswith("http://"):
            url = "https://" + url[7:]
        with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=8) as resp:
            skin = resp.read()
        return _face_from_skin(skin)
    except Exception:
        return None


def skin_bytes(uuid):
    # Floodgate/Bedrock UUIDs have no Mojang skin, but Geyser/Mineatar resolve them.
    if not re.fullmatch(r"[0-9a-fA-F-]{32,36}", uuid or ""):
        return None
    bedrock = uuid.startswith("00000000-0000-0000-")
    safe = uuid.replace("-", "")
    try:
        SKIN_CACHE.mkdir(exist_ok=True)
    except OSError:
        pass
    cache = SKIN_CACHE / (safe + ".png")
    if cache.is_file():
        return cache.read_bytes()
    data = _geyser_face(uuid) if bedrock else None
    if not data:
        for tmpl in (BEDROCK_SKIN_SOURCES if bedrock else SKIN_SOURCES):
            try:
                req = urllib.request.Request(tmpl.format(id=safe, full=uuid), headers=UA)
                with urllib.request.urlopen(req, timeout=8) as resp:
                    data = resp.read()
                if data[:8] == b"\x89PNG\r\n\x1a\n":
                    break
                data = None
            except Exception:
                data = None
    if not data:
        return None
    try:
        cache.write_bytes(data)
    except OSError:
        pass
    return data


# world -> (candidate region dirs, min build Y). First existing dir wins. The dumper is invoked
# on demand to extract voxels for a requested area; results are cached in the chunk_voxels table.
WORLD_REGIONS = {
    "world": (["/opt/minecraft/world/dimensions/minecraft/overworld/region"], -64),
    "world_nether": (["/opt/minecraft/world/dimensions/minecraft/the_nether/region"], 0),
    "world_the_end": (["/opt/minecraft/world/dimensions/minecraft/the_end/region"], 0),
    "world_incendium_incendium_nether": (
        ["/opt/minecraft/world/dimensions/incendium/incendium_nether/region"], 0),
    "bskyblock_world": (["/opt/minecraft/world/dimensions/minecraft/bskyblock_world/region"], -64),
    "parkour_world": (["/opt/minecraft/world/dimensions/parkour_world/region",
                       "/opt/minecraft/world/dimensions/minecraft/parkour_world/region"], -64),
}
DUMPER_JAR = "/tmp/GroundTruthDumper.jar"
DUMPER_USER = "crafty"
VOXEL_MAX_SPAN = 15  # chunks-1 per axis (<= 16x16 chunks per request)


def _region_dir(world):
    ent = WORLD_REGIONS.get(world)
    if not ent:
        return None, None
    for d in ent[0]:
        if Path(d).is_dir():
            return d, ent[1]
    return None, None


def _run_dumper_voxels(world, cx0, cz0, cx1, cz1, lod=0):
    regions, miny = _region_dir(world)
    if not regions:
        return False
    cmd = ["runuser", "-u", DUMPER_USER, "--", "java", "-jar", DUMPER_JAR,
           "--regions", regions, "--world", world, "--min-y", str(miny),
           "--db", DB_PATH, "--skip-structures",
           "--cx0", str(cx0), "--cz0", str(cz0), "--cx1", str(cx1), "--cz1", str(cz1)]
    # lod 0 is the real 1m blocks; higher levels are the decimated ones the renderer scales up
    cmd += ["--voxels"] if lod == 0 else ["--voxel-lods", str(lod)]
    try:
        subprocess.run(cmd, timeout=120, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        return True
    except Exception:
        return False


def assemble_lod_virtual(world, lod, vx, vz):
    """Build one 16x16-cell "virtual chunk" out of the stored per-chunk LOD data.

    A LOD level's stored chunk covers 16/2^lod cells per side, so 2^lod real chunks tile exactly into
    one 16x16 cell block - the shape the existing mesher already understands. Doing the assembly here
    means the renderer needs no LOD awareness at all: it meshes the result as a normal chunk and just
    scales the group by 2^lod.
    """
    k = 1 << lod
    n = 16 >> lod
    rows = db_ro().execute(
        "SELECT cx,cz,data FROM chunk_voxels_lod WHERE world=? AND lod=? AND cx BETWEEN ? AND ? "
        "AND cz BETWEEN ? AND ?", (world, lod, vx * k, vx * k + k - 1, vz * k, vz * k + k - 1)).fetchall()
    if not rows:
        return None
    pal = []
    pal_index = {}
    cols = [[] for _ in range(256)]
    for cx, cz, blob in rows:
        try:
            d = zlib.decompress(blob)
        except Exception:
            continue
        if len(d) < 4 or d[0] != 2:
            continue
        off = 2
        pal_len = struct.unpack_from(">H", d, off)[0]
        off += 2
        src = []
        for _ in range(pal_len):
            ln = struct.unpack_from(">H", d, off)[0]
            off += 2
            src.append(d[off:off + ln].decode("utf-8", "replace"))
            off += ln
        ox = (cx - vx * k) * n
        oz = (cz - vz * k) * n
        for zi in range(n):
            for xi in range(n):
                run_count = struct.unpack_from(">H", d, off)[0]
                off += 2
                target = cols[(oz + zi) * 16 + (ox + xi)]
                for _ in range(run_count):
                    y0, ln, pi = struct.unpack_from(">hhh", d, off)
                    off += 6
                    if pi < 1 or pi > len(src):
                        continue
                    name = src[pi - 1]
                    idx = pal_index.get(name)
                    if idx is None:
                        pal.append(name)
                        idx = len(pal)
                        pal_index[name] = idx
                    target.append((y0, ln, idx))
    if not pal:
        return None
    out = bytearray()
    out += struct.pack(">BH", 1, len(pal))
    for name in pal:
        nb = name.encode("utf-8")
        out += struct.pack(">H", len(nb)) + nb
    for i in range(256):
        runs = cols[i]
        out += struct.pack(">H", len(runs))
        for (y0, ln, idx) in runs:
            out += struct.pack(">hhh", y0, ln, idx)
    return zlib.compress(bytes(out), 6)


def voxels(world, cx0, cz0, cx1, cz1, lod=0):
    if cx1 < cx0:
        cx0, cx1 = cx1, cx0
    if cz1 < cz0:
        cz0, cz1 = cz1, cz0
    cx1 = min(cx1, cx0 + VOXEL_MAX_SPAN)
    cz1 = min(cz1, cz0 + VOXEL_MAX_SPAN)
    if lod > 0:
        # The bbox is in VIRTUAL chunk coordinates: one virtual chunk is 2^lod real chunks, which is
        # exactly the 16x16 cells the mesher expects. Generate the underlying real chunks on demand
        # first if this level has not been dumped for the area.
        k = 1 << lod
        vx0, vz0, vx1, vz1 = cx0, cz0, cx1, cz1
        need = db_ro().execute(
            "SELECT COUNT(*) FROM chunk_voxels_lod WHERE world=? AND lod=? AND cx BETWEEN ? AND ? "
            "AND cz BETWEEN ? AND ?",
            (world, lod, vx0 * k, (vx1 + 1) * k - 1, vz0 * k, (vz1 + 1) * k - 1)).fetchone()[0]
        if need < (vx1 - vx0 + 1) * k * (vz1 - vz0 + 1) * k:
            _run_dumper_voxels(world, vx0 * k, vz0 * k, (vx1 + 1) * k - 1, (vz1 + 1) * k - 1, lod)
        out2 = []
        for vx in range(vx0, vx1 + 1):
            for vz in range(vz0, vz1 + 1):
                blob = assemble_lod_virtual(world, lod, vx, vz)
                if blob:
                    out2.append({"cx": vx, "cz": vz, "biome": None,
                                 "data": base64.b64encode(blob).decode("ascii")})
        return {"world": world, "lod": lod, "k": k, "vx0": vx0, "vz0": vz0,
                "cx0": vx0, "cz0": vz0, "cx1": vx1, "cz1": vz1, "chunks": out2}
    sql = ("SELECT cx,cz,data FROM chunk_voxels WHERE world=? "
           "AND cx BETWEEN ? AND ? AND cz BETWEEN ? AND ?")
    args = (world, cx0, cx1, cz0, cz1)
    try:
        have = db_ro().execute(sql, args).fetchall()
    except sqlite3.OperationalError:
        have = []  # table not created yet - the dumper will make it
    if len(have) < (cx1 - cx0 + 1) * (cz1 - cz0 + 1):
        _run_dumper_voxels(world, cx0, cz0, cx1, cz1, 0)
        have = db_ro().execute(sql, args).fetchall()
    bconn = db_ro()
    biome_map = {(r[0], r[1]): r[2] for r in bconn.execute(
        "SELECT cx,cz,biome FROM chunks WHERE world=? AND cx BETWEEN ? AND ? AND cz BETWEEN ? AND ?",
        (world, cx0, cx1, cz0, cz1)).fetchall()}
    out = []
    for cx, cz, data in have:
        if data is None:
            continue
        out.append({"cx": cx, "cz": cz, "biome": biome_map.get((cx, cz)),
                    "data": base64.b64encode(data).decode("ascii")})
    return {"world": world, "lod": lod, "cx0": cx0, "cz0": cz0, "cx1": cx1, "cz1": cz1, "chunks": out}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass  # keep this quiet; it's a low-traffic LAN tool, not worth journal spam

    def _send_json(self, data):
        body = json.dumps(data).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        parsed = urllib.parse.urlparse(self.path)
        qs = urllib.parse.parse_qs(parsed.query)

        if parsed.path in ("/api/waypoints", "/api/waypoints/delete"):
            payload = verify_token(self.headers.get("X-GT-Code") or (qs.get("code", [""])[0]))
            if not payload:
                self._send_json({"error": "login required"})
                return
            uuid = payload.get("u")
            try:
                if parsed.path.endswith("/delete"):
                    self._send_json({"ok": waypoint_delete(uuid, qs.get("name", [""])[0])})
                else:
                    name = qs.get("name", [""])[0].strip()
                    if not name or len(name) > 40:
                        self._send_json({"error": "name required (max 40 chars)"})
                        return
                    self._send_json({"ok": waypoint_put(
                        uuid, name, qs.get("world", ["world"])[0],
                        int(qs["x"][0]), int(qs.get("y", ["64"])[0]), int(qs["z"][0]),
                        qs.get("public", ["0"])[0] in ("1", "true", "yes"))})
            except (ValueError, KeyError):
                self.send_response(400); self.end_headers()
            return

        if parsed.path in ("/api/admin/rollback/apply", "/api/admin/rollback/undo"):
            ok, why = admin_auth(self, qs, need_rollback=True)
            if not ok:
                body = json.dumps({"error": "Denied."}).encode()
                self.send_response(429 if why == "banned" else 401)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return
            try:
                if parsed.path.endswith("/apply"):
                    actor = qs.get("actor", [""])[0]
                    minutes = int(qs.get("minutes", ["60"])[0])
                    radius = int(qs.get("r", ["0"])[0])
                    if not actor:
                        self.send_response(400); self.end_headers(); return
                    out1 = rcon_cmd(f"groundtruth rollback {actor} {minutes} {radius}")
                    out2 = rcon_cmd("groundtruth rollback confirm")
                    self._send_json({"ok": True, "output": [out1, out2]})
                else:
                    rid = int(qs.get("id", ["0"])[0])
                    self._send_json({"ok": True, "output": rcon_cmd(f"groundtruth rollback undo {rid}")})
            except Exception as e:
                try:
                    self.send_response(500); self.end_headers(); self.wfile.write(str(e).encode())
                except Exception:
                    pass
            return

        if parsed.path == "/upload":
            if qs.get("k", [""])[0] != UPLOAD_KEY:
                self.send_response(403); self.end_headers(); return
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if length <= 0 or length > 30 * 1024 * 1024:
                    self.send_response(413); self.end_headers(); return
                name = self.headers.get("X-Filename", "upload.bin")
                safe = re.sub(r"[^A-Za-z0-9._-]", "_", name)[-80:] or "upload.bin"
                data = self.rfile.read(length)
                INCOMING_DIR.mkdir(parents=True, exist_ok=True)
                out = INCOMING_DIR / (time.strftime("%Y%m%d-%H%M%S") + "-" + safe)
                out.write_bytes(data)
                body = out.name.encode()
                self.send_response(200)
                self.send_header("Content-Type", "text/plain")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
            except Exception as e:
                try:
                    self.send_response(500)
                    self.end_headers()
                    self.wfile.write(str(e).encode())
                except Exception:
                    pass
            return
        self.send_response(404)
        self.end_headers()

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        qs = urllib.parse.parse_qs(parsed.query)

        if parsed.path == "/api/data":
            world = qs.get("world", [None])[0] or _default_world()
            if not world:
                self.send_response(400)
                self.end_headers()
                return
            try:
                self._send_json(query_data(world))
            except Exception as e:
                self.send_response(500)
                self.end_headers()
                self.wfile.write(str(e).encode("utf-8"))
            return

        if parsed.path == "/api/worlds":
            try:
                self._send_json(worlds())
            except Exception as e:
                self.send_response(500)
                self.end_headers()
                self.wfile.write(str(e).encode("utf-8"))
            return

        if parsed.path == "/api/chat":
            if not any_user_authorised(self, qs):
                self._send_json({"error": "login required"})
                return
            try:
                minutes = max(1, int(qs.get("minutes", ["60"])[0]))
                limit = min(1000, max(1, int(qs.get("limit", ["100"])[0])))
            except ValueError:
                self._send_json({"error": "bad minutes/limit"})
                return
            self._send_json(chat_log(minutes, qs.get("player", [None])[0], limit, qs.get("q", [None])[0]))
            return

        if parsed.path == "/api/spawns":
            if not any_user_authorised(self, qs):
                self._send_json({"error": "login required"})
                return
            try:
                minutes = max(1, int(qs.get("minutes", ["30"])[0]))
                radius = max(1, int(qs.get("radius", ["128"])[0]))
                limit = min(200, max(1, int(qs.get("limit", ["20"])[0])))
            except ValueError:
                self._send_json({"error": "bad minutes/radius/limit"})
                return
            self._send_json(mob_spawns(qs.get("player", [None])[0], minutes, radius, limit))
            return

        if parsed.path == "/api/last-attack":
            if not service_authorised(self, qs):
                self._send_json({"error": "service key or admin code required"})
                return
            try:
                minutes = max(1, int(qs.get("minutes", ["10"])[0]))
            except ValueError:
                minutes = 10
            self._send_json(last_attack(minutes))
            return

        if parsed.path in ("/api/player/now", "/api/player/attacks", "/api/player/timeline"):
            if not service_authorised(self, qs):
                self._send_json({"error": "service key or admin code required"})
                return
            name = qs.get("player", [""])[0]
            if not name:
                self._send_json({"error": "player required"})
                return
            try:
                limit = min(500, int(qs.get("limit", ["50"])[0]))
                minutes = max(1, int(qs.get("minutes", ["30"])[0]))
            except ValueError:
                self._send_json({"error": "bad minutes/limit"})
                return
            if parsed.path == "/api/player/now":
                self._send_json(player_now(name))
            elif parsed.path == "/api/player/attacks":
                self._send_json(player_attacks(name, minutes, limit))
            else:
                self._send_json(player_timeline(name, minutes, limit))
            return

        if parsed.path == "/api/players":
            # live player positions: logged-in viewers only
            if not any_user_authorised(self, qs):
                self._send_json({"players": [], "error": "login required"})
                return
            try:
                self._send_json(players())
            except Exception as e:
                self.send_response(500)
                self.end_headers()
                self.wfile.write(str(e).encode("utf-8"))
            return

        if parsed.path == "/api/chunkinfo":
            world = qs.get("world", [None])[0] or _default_world()
            try:
                cx = int(qs.get("cx", ["0"])[0]); cz = int(qs.get("cz", ["0"])[0])
            except ValueError:
                self.send_response(400); self.end_headers(); return
            self._send_json(chunkinfo(world, cx, cz))
            return

        if parsed.path == "/api/nbt":
            # block-entity NBT includes container contents
            if not any_user_authorised(self, qs):
                self._send_json({"error": "login required"})
                return
            world = qs.get("world", [None])[0] or _default_world()
            if not world:
                self.send_response(400); self.end_headers(); return
            try:
                if "y" in qs:  # exact block position
                    self._send_json(nbt_at(world, int(qs["x"][0]), int(qs["y"][0]), int(qs["z"][0])))
                else:          # everything within r blocks (XZ)
                    self._send_json(nbt_near(world, int(qs.get("x", ["0"])[0]), int(qs.get("z", ["0"])[0]),
                                             int(qs.get("r", ["48"])[0]), int(qs.get("limit", ["200"])[0])))
            except ValueError:
                self.send_response(400); self.end_headers(); return
            return

        if parsed.path == "/api/track":
            # a player's OWN session trail - requires their login token, returns only their track
            payload = verify_token(qs.get("code", [""])[0])
            if not payload:
                self._send_json({"error": "login required"})
                return
            hours = int(qs.get("hours", ["12"])[0])
            since = int(time.time() * 1000) - hours * 3600000
            self._send_json(admin_track(qs.get("world", [None])[0], payload.get("u"), since, 0))
            return

        if parsed.path == "/api/pixels":
            try:
                world = qs.get("world", ["world"])[0]
                cx0 = int(qs.get("cx0", ["0"])[0]); cz0 = int(qs.get("cz0", ["0"])[0])
                cx1 = int(qs.get("cx1", ["0"])[0]); cz1 = int(qs.get("cz1", ["0"])[0])
                px = int(qs.get("px", ["1"])[0])
            except (ValueError, KeyError):
                self._send_json({"error": "bad bbox"})
                return
            if cx1 < cx0: cx0, cx1 = cx1, cx0
            if cz1 < cz0: cz0, cz1 = cz1, cz0
            ck = "pixels|" + self.path
            cached = tile_cache_get(ck)
            if cached is not None:
                self._send_json(cached)
                return
            out = pixels_grid(world, cx0, cz0, cx1, cz1, px,
                              deflate=qs.get("deflate", ["0"])[0] in ("1", "true"))
            if not out.get("error") and len(out.get("data", "")) < 500000:
                tile_cache_put(ck, out)
            self._send_json(out)
            return

        if parsed.path == "/api/terrain":
            core = None
            try:
                world = qs.get("world", ["world"])[0]
                cx0 = int(qs.get("cx0", ["0"])[0]); cz0 = int(qs.get("cz0", ["0"])[0])
                cx1 = int(qs.get("cx1", ["0"])[0]); cz1 = int(qs.get("cz1", ["0"])[0])
                want_bounds = any(k in qs for k in ("all", "world_view"))
                if want_bounds:
                    b = db_world_bounds(world)
                    if b:
                        cx0, cx1, cz0, cz1 = b[0], b[1], b[2], b[3]
                        core = {"coreCx0": b[4], "coreCx1": b[5], "coreCz0": b[6], "coreCz1": b[7]}
                step = int(qs.get("step", ["1"])[0])
            except (ValueError, KeyError):
                self._send_json({"error": "bad bbox"})
                return
            if cx1 < cx0: cx0, cx1 = cx1, cx0
            if cz1 < cz0: cz0, cz1 = cz1, cz0
            if step < 1:
                step = 1
            # bound the shape (the row LIMIT in terrain_chunks is the real work cap); a bbox far
            # bigger than any world is simply nonsense
            if (cx1 - cx0 + 1) > 60000 or (cz1 - cz0 + 1) > 60000:
                self._send_json({"error": "area too large"})
                return
            ck = "terrain|" + self.path
            cached = tile_cache_get(ck)
            if cached is not None:
                self._send_json(cached)
                return
            out = terrain_chunks(world, cx0, cz0, cx1, cz1, step=step,
                                 deflate=qs.get("deflate", ["0"])[0] in ("1", "true"),
                                 want_bounds=want_bounds)
            if core and out.get("bounds"):
                out["bounds"].update(core)
            if not out.get("error") and len(out.get("data", "")) < 500000:
                tile_cache_put(ck, out)
            self._send_json(out)
            return

        if parsed.path == "/api/waypoints":
            # public waypoints are visible to anyone; a login also returns the caller's private ones
            payload = verify_token(qs.get("code", [""])[0])
            self._send_json({"waypoints": waypoints_list(payload.get("u") if payload else None)})
            return

        if parsed.path == "/api/auth":
            code = qs.get("code", [""])[0]
            payload = verify_token(code)
            if not payload:
                self._send_json({"error": "Invalid or expired code."})
                return
            # every code is one-shot for LOGGING IN (admin or not) - the browser keeps the token for
            # the session, but the same code can't be used to log in twice.
            if not consume_once(payload):
                self._send_json({"error": "That code has already been used."})
                return
            self._send_json({"name": payload.get("n"), "uuid": payload.get("u"),
                             "admin": payload.get("a") == 1, "rollback": payload.get("p") == 1})
            return

        if parsed.path.startswith("/api/admin/"):
            ok, why = admin_auth(self, qs)
            if not ok:
                code = 429 if why == "banned" else 401
                msg = {"banned": "This IP is banned.", "warn": "Wrong code. 10 failed attempts bans this IP.",
                       "bad": "Wrong code."}.get(why, "Denied.")
                body = json.dumps({"error": msg}).encode()
                self.send_response(code)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return
            try:
                if parsed.path == "/api/admin/health":
                    self._send_json(admin_health())
                elif parsed.path == "/api/admin/summary":
                    self._send_json(admin_summary(qs.get("world", [None])[0],
                                                  int(qs.get("since", ["0"])[0])))
                elif parsed.path == "/api/admin/console":
                    self._send_json(admin_console(int(qs.get("lines", ["200"])[0]),
                                                  qs.get("grep", [None])[0]))
                elif parsed.path == "/api/admin/heatmap":
                    self._send_json(admin_heatmap(
                        qs.get("world", [None])[0], qs.get("actor", [None])[0],
                        int(qs.get("since", ["0"])[0]), int(qs.get("until", ["0"])[0]),
                        int(qs.get("cell", ["16"])[0]), 400000, qs.get("source", ["raw"])[0]))
                elif parsed.path == "/api/admin/track":
                    self._send_json(admin_track(
                        qs.get("world", [None])[0], qs.get("actor", [None])[0],
                        int(qs.get("since", ["0"])[0]), int(qs.get("until", ["0"])[0])))
                elif parsed.path == "/api/admin/rollback/preview":
                    self._send_json(admin_rollback_preview(
                        qs.get("world", [None])[0], qs.get("actor", [None])[0],
                        int(qs.get("minutes", ["60"])[0]), int(qs.get("r", ["0"])[0]),
                        int(qs["x"][0]) if "x" in qs else None,
                        int(qs["z"][0]) if "z" in qs else None))
                elif parsed.path == "/api/admin/audit":
                    self._send_json(admin_audit(int(qs.get("limit", ["100"])[0])))
                elif parsed.path == "/api/admin/containers":
                    self._send_json(admin_containers(
                        qs.get("world", [None])[0],
                        int(qs["x"][0]) if "x" in qs else None,
                        int(qs["z"][0]) if "z" in qs else None,
                        int(qs.get("r", ["0"])[0]), int(qs.get("limit", ["200"])[0])))
                elif parsed.path == "/api/admin/container":
                    self._send_json(admin_container(qs.get("world", [""])[0],
                                                    int(qs["x"][0]), int(qs["y"][0]), int(qs["z"][0])))
                elif parsed.path == "/api/admin/export":
                    kind = qs.get("type", ["events"])[0]
                    fmt = qs.get("format", ["csv"])[0]
                    ex = int(qs["x"][0]) if "x" in qs else None
                    ez = int(qs["z"][0]) if "z" in qs else None
                    er = int(qs.get("r", ["0"])[0])
                    esince = int(qs.get("since", ["0"])[0])
                    elimit = int(qs.get("limit", ["5000"])[0])
                    actor = qs.get("actor", [None])[0]
                    world = qs.get("world", [None])[0]
                    if kind == "flow":
                        rows = admin_flow(world, ex, ez, er, qs.get("item", [None])[0]).get("rows", [])
                        cols = ["world", "x", "y", "z", "item", "bucket", "n", "updated"]
                    elif kind == "inventories":
                        rows = admin_inventories(qs.get("uuid", [None])[0], elimit).get("rows", [])
                        for row in rows:
                            row["contents"] = json.dumps(row.get("contents"))
                        cols = ["id", "uuid", "ts", "reason", "contents"]
                    else:
                        rows = log_query(world, ex, ez, er, esince, actor,
                                         qs.get("action", [None])[0], elimit, 0).get("events", [])
                        cols = ["ts", "world", "x", "y", "z", "action", "actorKind", "actorId", "actorName",
                                "causeName", "target", "before", "after", "meta"]
                    if fmt == "json":
                        body = json.dumps(rows).encode()
                        ctype = "application/json"
                    else:
                        buf = io.StringIO()
                        w = csv.writer(buf)
                        w.writerow(cols)
                        for row in rows:
                            w.writerow([row.get(c, "") for c in cols])
                        body = buf.getvalue().encode()
                        ctype = "text/csv"
                    self.send_response(200)
                    self.send_header("Content-Type", ctype)
                    self.send_header("Content-Disposition", f'attachment; filename="groundtruth-{kind}.{fmt}"')
                    self.send_header("Content-Length", str(len(body)))
                    self.end_headers()
                    self.wfile.write(body)
                elif parsed.path == "/api/admin/flow":
                    self._send_json(admin_flow(
                        qs.get("world", [None])[0],
                        int(qs["x"][0]) if "x" in qs else None,
                        int(qs["z"][0]) if "z" in qs else None,
                        int(qs.get("r", ["0"])[0]), qs.get("item", [None])[0],
                        int(qs.get("min", ["1"])[0]), int(qs.get("limit", ["300"])[0])))
                elif parsed.path == "/api/admin/inventory_diff":
                    self._send_json(admin_inventory_diff(int(qs["a"][0]), int(qs["b"][0])))
                elif parsed.path == "/api/admin/inventories":
                    self._send_json(admin_inventories(qs.get("uuid", [None])[0],
                                                      int(qs.get("limit", ["100"])[0])))
                elif parsed.path == "/api/admin/log":
                    x = int(qs["x"][0]) if "x" in qs else None
                    z = int(qs["z"][0]) if "z" in qs else None
                    self._send_json(log_query(
                        qs.get("world", [None])[0], x, z,
                        int(qs.get("r", ["0"])[0]), int(qs.get("since", ["0"])[0]),
                        qs.get("actor", qs.get("player", [None]))[0], qs.get("action", [None])[0],
                        int(qs.get("limit", ["200"])[0]), int(qs.get("offset", ["0"])[0])))
                else:
                    self.send_response(404); self.end_headers()
            except ValueError:
                self.send_response(400); self.end_headers()
            return

        if parsed.path == "/admin":
            # serve the admin page (static allowlist already handles /admin.html)
            self.path = "/admin.html"

        if parsed.path == "/api/log":
            # the raw event feed is player activity: positions, containers, commands
            if not any_user_authorised(self, qs):
                self._send_json({"error": "login required"})
                return
            try:
                x = int(qs["x"][0]) if "x" in qs else None
                z = int(qs["z"][0]) if "z" in qs else None
                self._send_json(log_query(
                    qs.get("world", [None])[0], x, z,
                    int(qs.get("r", ["0"])[0]), int(qs.get("since", ["0"])[0]),
                    qs.get("player", [None])[0], qs.get("action", [None])[0],
                    int(qs.get("limit", ["200"])[0])))
            except ValueError:
                self.send_response(400); self.end_headers(); return
            return

        if parsed.path == "/api/detail":
            world = qs.get("world", [None])[0] or _default_world()
            try:
                cx0 = int(qs.get("cx0", ["0"])[0]); cz0 = int(qs.get("cz0", ["0"])[0])
                cx1 = int(qs.get("cx1", ["0"])[0]); cz1 = int(qs.get("cz1", ["0"])[0])
            except ValueError:
                self.send_response(400); self.end_headers(); return
            if not world:
                self.send_response(400); self.end_headers(); return
            try:
                self._send_json(detail(world, cx0, cz0, cx1, cz1))
            except Exception as e:
                self.send_response(500); self.end_headers(); self.wfile.write(str(e).encode())
            return

        if parsed.path == "/api/voxels":
            world = qs.get("world", [None])[0] or _default_world()
            try:
                cx0 = int(qs.get("cx0", ["0"])[0]); cz0 = int(qs.get("cz0", ["0"])[0])
                cx1 = int(qs.get("cx1", ["0"])[0]); cz1 = int(qs.get("cz1", ["0"])[0])
            except ValueError:
                self.send_response(400); self.end_headers(); return
            if not world:
                self.send_response(400); self.end_headers(); return
            try:
                lod = int(qs.get("lod", ["0"])[0])
            except ValueError:
                lod = 0
            try:
                self._send_json(voxels(world, cx0, cz0, cx1, cz1, max(0, min(6, lod))))
            except Exception as e:
                self.send_response(500); self.end_headers(); self.wfile.write(str(e).encode())
            return

        if parsed.path == "/api/biome_counts":
            world = qs.get("world", [None])[0] or _default_world()
            if not world:
                self.send_response(400); self.end_headers(); return
            self._send_json(biome_counts(world))
            return

        if parsed.path == "/api/biome_chunks":
            world = qs.get("world", [None])[0] or _default_world()
            biome = qs.get("biome", [""])[0]
            if not world or not biome:
                self.send_response(400); self.end_headers(); return
            self._send_json(biome_chunks(world, biome))
            return

        if parsed.path == "/api/slimeclusters":
            world = qs.get("world", [None])[0] or _default_world()
            try:
                mk = max(2, min(int(qs.get("min", ["2"])[0]), 5))
            except ValueError:
                mk = 2
            self._send_json(slimeclusters(world, mk, 5))
            return

        if parsed.path == "/api/structures":
            world = qs.get("world", [None])[0] or _default_world()
            if not world:
                self.send_response(400); self.end_headers(); return
            try:
                self._send_json(structures_only(world))
            except Exception as e:
                self.send_response(500); self.end_headers(); self.wfile.write(str(e).encode())
            return

        if parsed.path.startswith("/tiles/"):
            rel = parsed.path[len("/tiles/"):]
            path = (TILES_DIR / rel).resolve()
            if TILES_DIR not in path.parents or not path.is_file():
                self.send_response(404)
                self.end_headers()
                return
            ctype = ("image/png" if path.suffix == ".png"
                     else "application/json" if path.suffix == ".json" else "application/octet-stream")
            body = path.read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", ctype)
            self.send_header("Cache-Control", "max-age=3600")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return

        if parsed.path.startswith("/api/skin/"):
            data = skin_bytes(parsed.path.split("/api/skin/", 1)[1])
            if not data:
                self.send_response(404)
                self.end_headers()
                return
            self.send_response(200)
            self.send_header("Content-Type", "image/png")
            self.send_header("Cache-Control", "max-age=86400")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return

        if parsed.path in ("/api/nearby", "/api/find", "/api/where"):
            try:
                world = qs.get("world", [None])[0] or _default_world()
                if not world:
                    self.send_response(400)
                    self.end_headers()
                    return
                x = int(qs.get("x", ["0"])[0])
                z = int(qs.get("z", ["0"])[0])
                radius = max(1, min(int(qs.get("r", ["500"])[0]), 20000))
                limit = max(1, min(int(qs.get("limit", ["25"])[0]), 200))
            except ValueError:
                self.send_response(400)
                self.end_headers()
                return
            try:
                if not any_user_authorised(self, qs):
                    self._send_json({"error": "login required"})
                    return
                if parsed.path == "/api/nearby":
                    self._send_json(nearby(world, x, z, radius, limit))
                elif parsed.path == "/api/find":
                    self._send_json(find(world, qs.get("type", [""])[0], x, z, limit))
                else:
                    player = qs.get("player", [""])[0]
                    if not player:
                        self.send_response(400)
                        self.end_headers()
                        return
                    self._send_json(where(player, radius, limit))
            except Exception as e:
                self.send_response(500)
                self.end_headers()
                self.wfile.write(str(e).encode("utf-8"))
            return

        # Static files (index.html, app.js) - tiny allowlist, this is a LAN tool not a CDN.
        rel = parsed.path.lstrip("/") or "index.html"
        path = (STATIC_DIR / rel).resolve()
        if STATIC_DIR not in path.parents and path != STATIC_DIR:
            self.send_response(403)
            self.end_headers()
            return
        if not path.is_file():
            self.send_response(404)
            self.end_headers()
            return
        content_type = "text/html" if path.suffix == ".html" else \
            "application/javascript" if path.suffix == ".js" else "text/plain"
        body = path.read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        # HTML/JS must revalidate, otherwise Cloudflare serves a stale app after a deploy.
        self.send_header("Cache-Control",
                         "no-cache, must-revalidate" if path.suffix in (".html", ".js") else "max-age=3600")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


if __name__ == "__main__":
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    print(f"GroundTruth viewer on :{PORT}")
    server.serve_forever()
