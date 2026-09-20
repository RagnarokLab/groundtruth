#!/usr/bin/env python3
"""
GroundTruth minimal web viewer - stdlib only (no pip installs on this host).
Read-only against the plugin's own sqlite db; never touches the world files
or any other plugin's data. Serves a static canvas map + a JSON data API.
"""
import base64
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


def players():
    raw = rcon_cmd("groundtruth players")
    payload = raw.split("GTPLAYERS|", 1)[-1]
    envs = {w["world"]: w["env"] for w in (worlds().get("worlds") or [])}
    out = []
    for seg in payload.split(";"):
        seg = seg.strip()
        if seg.count(",") < 5:
            continue
        name, uuid, x, y, z, world = seg.split(",", 5)
        name = name.lstrip(".")  # Floodgate bedrock prefix
        try:
            out.append({"name": name, "uuid": uuid, "x": float(x), "y": float(y),
                        "z": float(z), "world": world, "env": envs.get(world)})
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


def log_query(world=None, x=None, z=None, radius=0, since_ms=0, player=None, action=None, limit=200):
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


def _run_dumper_voxels(world, cx0, cz0, cx1, cz1):
    regions, miny = _region_dir(world)
    if not regions:
        return False
    cmd = ["runuser", "-u", DUMPER_USER, "--", "java", "-jar", DUMPER_JAR,
           "--regions", regions, "--world", world, "--min-y", str(miny),
           "--db", DB_PATH, "--skip-structures", "--voxels",
           "--cx0", str(cx0), "--cz0", str(cz0), "--cx1", str(cx1), "--cz1", str(cz1)]
    try:
        subprocess.run(cmd, timeout=120, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        return True
    except Exception:
        return False


def voxels(world, cx0, cz0, cx1, cz1):
    if cx1 < cx0:
        cx0, cx1 = cx1, cx0
    if cz1 < cz0:
        cz0, cz1 = cz1, cz0
    cx1 = min(cx1, cx0 + VOXEL_MAX_SPAN)
    cz1 = min(cz1, cz0 + VOXEL_MAX_SPAN)
    sql = ("SELECT cx,cz,data FROM chunk_voxels WHERE world=? AND cx BETWEEN ? AND ? AND cz BETWEEN ? AND ?")
    args = (world, cx0, cx1, cz0, cz1)
    try:
        have = db_ro().execute(sql, args).fetchall()
    except sqlite3.OperationalError:
        have = []  # table not created yet - the dumper will make it
    if len(have) < (cx1 - cx0 + 1) * (cz1 - cz0 + 1):
        _run_dumper_voxels(world, cx0, cz0, cx1, cz1)
        have = db_ro().execute(sql, args).fetchall()
    bconn = db_ro()
    biome_map = {(r[0], r[1]): r[2] for r in bconn.execute(
        "SELECT cx,cz,biome FROM chunks WHERE world=? AND cx BETWEEN ? AND ? AND cz BETWEEN ? AND ?",
        args).fetchall()}
    out = []
    for cx, cz, data in have:
        if data is None:
            continue
        out.append({"cx": cx, "cz": cz, "biome": biome_map.get((cx, cz)),
                    "data": base64.b64encode(data).decode("ascii")})
    return {"world": world, "cx0": cx0, "cz0": cz0, "cx1": cx1, "cz1": cz1, "chunks": out}


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

        if parsed.path == "/api/players":
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

        if parsed.path == "/api/log":
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
                self._send_json(voxels(world, cx0, cz0, cx1, cz1))
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
