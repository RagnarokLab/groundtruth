# GroundTruth

GroundTruth is a Minecraft **world mapping + administration** toolkit that records what a world
**actually generated** rather than what a seed predicts — so modded worldgen, datapacks and version
drift never break the map, and so there is a real, queryable history of what happened on the server.

It is three pieces that share one SQLite schema:

| Component | What it does |
| --- | --- |
| `plugin/` | A **Paper/Spigot plugin**. Indexes newly-generated chunks live, tracks players, and (planned) logs events. |
| `dumper/` | A standalone **Java tool** that reads Anvil region files directly — chunk index, surface detail, full voxels, and block-entity/entity NBT. Runs offline with zero server tick cost. |
| `web/` | A **stdlib-only Python web server** + canvas/WebGL viewer: 2D map, 3D voxel view, JSON API. |

The design principle throughout: **observed data, never prediction**. Everything is read from the
world that actually exists, so it survives modded biomes/structures and Minecraft updates.

## Layout

```
plugin/   Paper plugin (Java, spigot-api)
dumper/   Offline region-file dumper + atlas/model compilers (Java, sqlite-jdbc)
web/      Map server + viewer (Python 3 stdlib + three.js)
```

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

## Running

The dumper writes into the same SQLite database the plugin uses:

```bash
java -cp build/GroundTruthDumper.jar club.footlickers.groundtruth.dumper.Dumper \
  --regions /path/to/world/region --world world --db groundtruth.db \
  --detail --voxels --nbt
```

The web server reads that database (read-only) and serves the map:

```bash
GT_DB=/path/to/groundtruth.db python3 web/server.py   # default port 8095
```

Configuration is via environment variables (`GT_DB`, `GT_PORT`, `GT_SERVER_PROPERTIES`,
`GT_UPLOAD_KEY`, …) — see the top of `web/server.py`. There are **no credentials in this repository**;
supply your own at runtime.

## Dependencies / attribution

- [Three.js](https://threejs.org/) r147 (MIT) — vendored under `web/static/js/` for the 3D view.
- `sqlite-jdbc` and the Spigot/Paper API are **not** vendored; provide them at build time.

## A note on generated assets

The atlas, block colours, biome tints and compiled block models are **generated locally from the
operator's own Minecraft client jar** at runtime. They are Mojang-derived and are **never** committed
or redistributed — `.gitignore` excludes them. Only the code that generates them lives here.

## Licence

MIT - see `LICENSE`.

## Status

Early / alpha. Built for a private server first.
