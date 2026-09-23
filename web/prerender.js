/*
 * GroundTruth prerenderer: turn stored per-chunk voxel data into prebuilt 3D mesh tiles.
 *
 *   node prerender.js --world world --cx0 0 --cz0 -80 --cx1 20 --cz1 -60 --out /opt/groundtruth-web/tiles --host http://127.0.0.1:8096
 *
 * Tiles are aligned to a fixed 10x10-chunk grid (the mesher's native window): tile (tx,tz) covers
 * chunks [tx*10 .. tx*10+9] x [tz*10 .. tz*10+9]. Output: <out>/<world>/mesh/lod0/<tx>_<tz>.gtmesh
 *
 * Reads voxel data over the plugin's own HTTP API (the same JSON the viewer uses, biome included),
 * so it needs no SQLite driver and can run anywhere that can reach the API. Throttle externally
 * with `nice -n 19 ionice -c3`.
 */
'use strict';
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');
const mesher = require('./gt_mesher.js');
const { encodeTile } = require('./gt_tile.js');

const N = mesher.N_CHUNKS || 10;

function arg(name, def) {
  const i = process.argv.indexOf('--' + name);
  return i >= 0 ? process.argv[i + 1] : def;
}

function parseVoxel(buf) {
  const d = new DataView(buf.buffer, buf.byteOffset, buf.byteLength);
  let o = 0; d.getUint8(o); o++;
  const plen = d.getUint16(o); o += 2;
  const dec = new TextDecoder(); const pal = [];
  for (let i = 0; i < plen; i++) { const n = d.getUint16(o); o += 2; pal.push(dec.decode(new Uint8Array(buf.buffer, buf.byteOffset + o, n))); o += n; }
  const cols = new Array(256);
  for (let c = 0; c < 256; c++) { const rc = d.getUint16(o); o += 2; const runs = new Array(rc);
    for (let r = 0; r < rc; r++) { runs[r] = [d.getInt16(o), d.getUint16(o + 2), Math.max(0, d.getUint16(o + 4) - 1)]; o += 6; } cols[c] = runs; }
  return { pal, cols };
}

async function fetchJSON(host, url, tries) {
  tries = tries || 6;
  try {
    const r = await fetch(host + url);
    if (!r.ok) throw new Error(url + ' -> ' + r.status);
    return r.json();
  } catch (e) {
    // A long backfill must survive a transient failure (e.g. the server restarting to apply a plugin
    // change) instead of dying a few hundred tiles in.
    if (tries <= 1) throw e;
    await new Promise((res) => setTimeout(res, 1500 * (7 - tries)));
    return fetchJSON(host, url, tries - 1);
  }
}

async function main() {
  const world = arg('world', 'world');
  const host = arg('host', 'http://127.0.0.1:8096');
  const out = arg('out', '/opt/groundtruth-web/tiles');
  const cx0 = parseInt(arg('cx0', '0'), 10), cz0 = parseInt(arg('cz0', '0'), 10);
  const cx1 = parseInt(arg('cx1', '0'), 10), cz1 = parseInt(arg('cz1', '0'), 10);
  const minY = parseInt(arg('min-y', '-64'), 10);

  const [atlas, biomeTints, bmodels] = await Promise.all([
    fetchJSON(host, '/tiles/atlas.json'),
    fetchJSON(host, '/tiles/biome_tints.json').catch(() => ({})),
    fetchJSON(host, '/tiles/blockmodels.json').catch(() => ({})),
  ]);

  let tileList = [];
  const tilesFile = arg('tiles', null);
  if (tilesFile) {
    // explicit tile list ("tx,tz" per line) - used when the inhabited area is scattered
    tileList = fs.readFileSync(tilesFile, 'utf8').split('\n')
      .map((s) => s.trim()).filter(Boolean)
      .map((s) => { const p = s.split(','); return [parseInt(p[0], 10), parseInt(p[1], 10)]; });
  } else {
    const tx0 = Math.floor(cx0 / N), tz0 = Math.floor(cz0 / N);
    const tx1 = Math.floor(cx1 / N), tz1 = Math.floor(cz1 / N);
    for (let tx = tx0; tx <= tx1; tx++) for (let tz = tz0; tz <= tz1; tz++) tileList.push([tx, tz]);
  }
  const total = tileList.length;
  let done = 0, wrote = 0, skipped = 0;

  for (const pair of tileList) {
    {
      const tx = pair[0], tz = pair[1];
      const c0 = tx * N, z0 = tz * N;
      const vox = await fetchJSON(host,
        `/api/voxels?world=${encodeURIComponent(world)}&lod=0&cx0=${c0}&cz0=${z0}&cx1=${c0 + N - 1}&cz1=${z0 + N - 1}`);
      done++;
      if (!vox.chunks || !vox.chunks.length) {
        skipped++;
        process.stdout.write(`\r${done}/${total} tile ${tx},${tz} empty       `);
        continue;
      }
      for (const c of vox.chunks) {
        const buf = zlib.inflateSync(Buffer.from(c.data, 'base64'));
        const v = parseVoxel(buf);
        c._pal = v.pal; c._cols = v.cols;
      }
      const built = mesher.buildGeometry(vox, atlas, biomeTints, minY, c0, z0, bmodels, true);
      const blob = encodeTile(built);
      const dir = path.join(out, world, 'mesh', 'lod0');
      fs.mkdirSync(dir, { recursive: true });
      fs.writeFileSync(path.join(dir, `${tx}_${tz}.gtmesh`), blob);
      wrote++;
      const faces = Math.round(built.opaque.pos.length / 18);
      process.stdout.write(`\r${done}/${total} tile ${tx},${tz}: ${faces} faces, ${(blob.length / 1048576).toFixed(2)} MB   `);
    }
  }
  console.log(`\nprerender done: ${wrote} written, ${skipped} empty, ${done} total`);
}

main().catch(e => { console.error('FAIL', e); process.exit(1); });
