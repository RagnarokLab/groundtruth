# GroundTruth

GroundTruth is a Minecraft **world mapping + administration** toolkit that records what a world
**actually generated** rather than what a seed predicts — so modded worldgen, datapacks and version
drift never break the map, and so there is a real, queryable history of what happened on the server.

The design principle throughout: **observed data, never prediction**. Everything is read from the
world that actually exists, so it survives modded biomes/structures and Minecraft updates.

## Components

| Component | What it does |
| --- | --- |
| `plugin/` | A **Paper plugin**. Serves the map + JSON API itself (no external web server needed), indexes newly-generated chunks live, records block/entity/player events, and re-indexes chunks when players build. |
| `dumper/` | A standalone **Java tool** that reads Anvil region files directly — chunk index, surface detail, full voxels, LOD levels, and block-entity/entity NBT. Runs offline in its own JVM with zero server tick cost. Used to backfill an existing world. |
| `web/` | The viewer (HTML/JS + three.js) served by the plugin, plus the **Node.js prerenderer** that turns stored voxels into prebuilt 3D mesh tiles. |

```
plugin/   Paper plugin (Java) — live indexing + the web server
dumper/   Offline region-file dumper + atlas/model compilers (Java, sqlite-jdbc)
web/      Viewer static files + the Node.js 3D prerenderer
```

## Requirements

- **Java 21+** — for the plugin (Paper 1.21+) and the dumper.
- **Node.js 18+** — for the 3D prerenderer (`web/prerender.js`). Only needed for the 3D view's
  prebuilt tiles; the 2D map and live indexing work without it.
- The **Spigot/Paper API jar** and **sqlite-jdbc** to build (not vendored — see Building).

## Quick start

1. Build (or obtain) `GroundTruth.jar` and drop it into your server's `plugins/` folder.
2. Start the server. The plugin opens its database and serves the map on port **8096**
   (`http://<server>:8096/`).
3. New chunks are indexed live as players explore, so a fresh world needs nothing else.

To map a world that **already exists**, backfill it with the offline dumper (no tick cost — it runs
in its own process):

```
/groundtruth dump <world>
```

which reads region files directly and writes into the same database.

## 3D view & the prerenderer

The 3D view renders real per-block geometry. Building that mesh in the browser is slow and
memory-hungry, so tiles are **prerendered server-side** into compact deflated mesh files and served
statically; the browser only loads finished geometry.

```
node web/prerender.js --world world --cx0 0 --cz0 -80 --cx1 160 --cz1 80 \
  --out /path/to/tiles --host http://127.0.0.1:8096
```

Tiles land in `<tiles>/<world>/mesh/lod0/<tx>_<tz>.gtmesh` on a fixed 10×10-chunk grid. Any tile that
hasn't been prerendered yet falls back to live client-side meshing, so nothing ever looks broken while
tiles are being built. Run it throttled (`nice -n 19 ionice -c3`) so it never competes with the server.

## Configuration

Config lives in `plugins/GroundTruth/config.yml`. The settings most worth knowing:

| Key | Default | Meaning |
| --- | --- | --- |
| `web-enabled` / `web-port` | `true` / `8096` | The map + API server. `0` disables it. |
| `render-only-visited` | `true` | **Only show what players have actually been to.** Scopes both the 2D map and the 3D view to visited chunks. Set to `false` to render the whole world (this gets big on a large world). |
| `render-visited-radius` | `15` | Chunk buffer drawn around visited chunks, so the edges look representative rather than clipped. |
| `detail-indexing` | `true` | Write the per-block layers (2D pixels, voxels, LOD) live for new chunks, so a plugin-only server needs no dumper. |
| `detail-lod-levels` | `1,2,3,4` | Which decimated 3D levels to build. |
| `service-key` | *(empty)* | Shared key for local assistants. Never committed. |
| `proxy-to` | *(empty)* | Optional fallback URL while migrating off an external service. |

## Building

**Plugin** (`plugin/`) — needs a Spigot/Paper API jar on the classpath:

```bash
cd plugin
SPIGOT_API=/path/to/spigot-api.jar ./build.sh
```

**Dumper** (`dumper/`) — needs `sqlite-jdbc.jar`:

```bash
cd dumper
SQLITE_JDBC=/path/to/sqlite-jdbc.jar ./build.sh
```

## Dependencies / attribution

- [Three.js](https://threejs.org/) r147 (MIT) — vendored under `web/static/js/` for the 3D view.
- [Node.js](https://nodejs.org/) 18+ — required by the 3D prerenderer (uses the built-in `fetch` and
  `zlib`, no npm packages).
- `sqlite-jdbc` and the Spigot/Paper API are **not** vendored; provide them at build time.

## A note on generated assets

The atlas, block colours, biome tints and compiled block models are **generated locally from the
operator's own Minecraft client jar** at runtime. They are Mojang-derived and are **never** committed
or redistributed — `.gitignore` excludes them. Only the code that generates them lives here.

## Licence

Proprietary / source-available - see `LICENSE`. You may view the source, but redistribution,
modification and commercial use are not permitted, except that the client-side mod may be included
unmodified in a non-commercial modpack.

## Status

Early / alpha. Built for a private server first.
