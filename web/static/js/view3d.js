/*
 * GroundTruth 3D view - voxel mesher.
 *
 * Fetches real per-block voxel data (/api/voxels, extracted on demand by the offline dumper from the
 * still-anvil region files), greedy-meshes it, and textures it with a block atlas built from the
 * user's own vanilla client jar. Grass/leaves/water are tinted per biome using biome_tints.json, so
 * a lavender valley really is lavender and a sakura grove is pink.
 *
 * three.js r147 UMD + OrbitControls are vendored (MIT). Kept separate so the 2D map is unaffected:
 * 3D is opt-in, and potato-PC users simply stay on the 2D canvas.
 */
(function () {
  'use strict';

  const N_CHUNKS = 10;      // voxel area side length in chunks (160 blocks)
  // Hard ceiling on faces per level. With the compact geometry ~3M faces is ~350 MB, so this is a
  // runaway guard (far tiles get dropped) rather than a normal limit.
  const MAX_LEVEL_FACES = 3500000;   // the ring gets MAX - CORE, i.e. ~2.5M for the lod 1 backdrop
  const TILE = 16;
  // the 16 dye colours (RGB 0..1) - banner flags are tinted by their base colour
  const DYE_COLOURS = {
    white: [0.99, 0.99, 0.99], orange: [0.98, 0.50, 0.11], magenta: [0.78, 0.31, 0.74],
    light_blue: [0.23, 0.70, 0.85], yellow: [1.00, 0.85, 0.24], lime: [0.50, 0.78, 0.12],
    pink: [0.95, 0.55, 0.67], gray: [0.28, 0.31, 0.32], light_gray: [0.62, 0.62, 0.59],
    cyan: [0.09, 0.61, 0.61], purple: [0.54, 0.20, 0.72], blue: [0.24, 0.27, 0.67],
    brown: [0.51, 0.33, 0.20], green: [0.37, 0.49, 0.09], red: [0.69, 0.18, 0.15],
    black: [0.11, 0.11, 0.13],
  };

  let renderer = null, scene = null, camera = null, controls = null;
  let raf = null, canvas = null, closeBtn = null, statusEl = null;
  let markerPoints = null, popupEl = null, downXY = null;
  let overlayGroup = null;        // structure outlines etc: rebuilt per level, disposed with it
  let overlaySeq = 0;             // guards async overlay builds: a newer refresh wins
  let panelEl = null, bboxToggle = null, bboxColor = null, lastVoxWindow = null;
  let slimeToggle = null;
  let atlasTex = null, animTex = null, depthBtn = null, worldBtn = null, islandsBtn = null;
  const waterTime = { value: 0 }; // seconds; drives the animated water frames
  let includeUnderground = true;  // toggle: surface-only (false) vs all the way down to bedrock (true)
  let lastCenter = null;
  let worldView = false;          // world mode: coarse whole-map relief instead of full voxels
  let worldStep = 4;              // chunk decimation in world mode (4 -> 64-block cells)
  const WORLD_YSCALE = 1;         // TRUE proportions - GroundTruth shows the world as it actually is
  let worldIslands = true;        // draw sky islands as real floating geometry
  const SLIME_MAX_CHUNKS = 104;  // /api/pixels refuses a request over 12000 chunks, so the slime
                                 // overlay covers a centred square inside that; slime chunks are 1 in
                                 // 10 and scattered, so nothing about the edge reads as a boundary
  const VOXEL_MAX_VCHUNKS = 70;   // cap on the window, in virtual chunks (7x7 tiles of 10) - also the
                                  // span at which the tier switches from 1m to 2m blocks
  const VOXEL_MAX_VCHUNKS_LOD1 = 128; // lod 1's own cap: its tiles cover 4x the area, so reaching the
                                      // same 256 real chunks costs ~1/4 the tiles and can fill the screen
  const VOXEL_MAX_REAL_CHUNKS = 256; // cap on the window in real chunks (4096 blocks across)
  const CORE_MAX_CHUNKS = 30;        // fine detail island at coarse tiers: up to 3x3 lod-0 tiles
  const CORE_DROP_SPAN = 700;        // ...dropped once the view spans more chunks than this
  const CORE_FACE_BUDGET = 1000000;  // ...and never allowed to eat more than this many faces
  const CORE_SPAN_FRACTION = 0.4;    // ...sized to this share of the visible span
  const VOXEL_CHUNKS = 12;        // chunks per side of the real-block window
  const TILE_CELLS = 256;         // cells per tile side (~65k cells, still only a few ms to mesh).
                                  // Bigger tiles mean far fewer requests, which matters much more than
                                  // mesh time on a slow link.
  let worldGroup = null;          // THREE.Group holding the terrain tiles for the active level
  let backdropGroup = null;      // at most ONE previous level, kept behind the active one
  let coreGroup = null;           // fine (lod 0) detail island kept in the middle at coarse tiers
  let coreLevel = null;           // { cx0, cz0, cx1, cz1 } that core covers, in real chunks
  let lastBiomeStats = null;      // HUD readout: biome coverage and tint-table size of the last build
  let worldLevel = null;          // { key, cell, cx0, cz0, cx1, cz1 } that group covers
  let worldBounds = null;         // world chunk bounds (from a cheap probe request)
  let worldMat = null;
  let worldLoading = false;
  let voxelGroup = null;          // the real-block mesh that takes over once you are close enough
  // cache-buster for the prerendered tiles: they are rewritten in place by the prerenderer, and with
  // no version the browser keeps serving the old bytes (the atlas already does this).
  const TILES_V = Date.now();
  let voxAtlas = null, voxTints = null, voxModels = null, voxMat = null, voxWaterMat = null;
  let voxMatBg = null, voxWaterMatBg = null;   // pushed-back copies for the level that is now backdrop
  let worldMinY = -64;
  let initialWin = 30;      // window (in chunks) the 3D view opens at; the detail setting drives this
  let refineBusy = false, refineTimer = null, refineChanges = 0, refineLastErr = '';
  let refineTries = 0, refineDone = 0, refineWhy = 'none';
  const ISLAND_GAP = 20;         // blocks the surface must sit above the ground to be a sky island
  const SLAB = 10;                // plate thickness drawn for a floating island (data carries heights, not thickness)
  const WORLD_TINT = 0x0b1622;

  /** Decode an /api/terrain payload (base64, optionally deflated) into bytes. */
  function inflated(b64) {
    const bin = atob(b64);
    const bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    return new Response(new Blob([bytes]).stream().pipeThrough(new DecompressionStream('deflate')))
      .arrayBuffer();
  }

  function parseVoxel(buf) {
    const d = new DataView(buf);
    let o = 0;
    d.getUint8(o); o += 1;                       // version
    const plen = d.getUint16(o); o += 2;
    const dec = new TextDecoder();
    const pal = [];
    for (let i = 0; i < plen; i++) {
      const n = d.getUint16(o); o += 2;
      pal.push(dec.decode(new Uint8Array(buf, o, n)));
      o += n;
    }
    const cols = new Array(256);
    for (let c = 0; c < 256; c++) {
      const rc = d.getUint16(o); o += 2;
      const runs = new Array(rc);
      for (let r = 0; r < rc; r++) {
        // palette indices in the stored format are 1-based (0 means air); the mesher indexes a JS
        // array with them, so convert once here. Without this every block rendered as the NEXT
        // palette entry, which is why surfaces looked subtly wrong and LOD palettes blew up.
        runs[r] = [d.getInt16(o), d.getUint16(o + 2), Math.max(0, d.getUint16(o + 4) - 1)];
        o += 6;
      }
      cols[c] = runs;
    }
    return { pal, cols };
  }

  /** Inflate a deflated byte payload (DecompressionStream, like the voxel path). */
  async function inflateRaw(bytes) {
    const ds = new DecompressionStream('deflate');
    const ab = await new Response(new Blob([bytes]).stream().pipeThrough(ds)).arrayBuffer();
    return new Uint8Array(ab);
  }

  /** Parse a prerendered .gtmesh tile (already inflated). Mirrors gt_tile.js's on-disk layout. */
  function parseTile(buf) {
    const d = new DataView(buf.buffer, buf.byteOffset, buf.byteLength);
    // GTM1 stored the atlas rect as a u8, which quantised it by ~0.5 texel and bled the neighbouring
    // atlas tile along every block edge; GTM2 uses u16. Accept both so a re-render can run live.
    const atile16 = d.getUint8(3) === 0x32;               // '2'
    let o = 4;
    const hasWater = d.getUint8(o) === 1; o += 1;
    const baseY = d.getInt32(o, true); o += 4;
    const read = (withAanim) => {
      const V = d.getUint32(o, true); o += 4;
      const b = { pos: [], uv: [], col: [], nor: [], atile: [], aanim: withAanim ? [] : null };
      for (let i = 0; i < V; i++) {
        b.pos.push(d.getFloat32(o, true), d.getFloat32(o + 4, true), d.getFloat32(o + 8, true)); o += 12;
        b.uv.push(d.getFloat32(o, true), d.getFloat32(o + 4, true)); o += 8;
        b.col.push(d.getUint8(o) / 255, d.getUint8(o + 1) / 255, d.getUint8(o + 2) / 255); o += 3;
        b.nor.push(d.getInt8(o) / 127, d.getInt8(o + 1) / 127, d.getInt8(o + 2) / 127); o += 3;
        if (atile16) {
          b.atile.push(d.getUint16(o, true) / 65535, d.getUint16(o + 2, true) / 65535,
            d.getUint16(o + 4, true) / 65535, d.getUint16(o + 6, true) / 65535); o += 8;
        } else {
          b.atile.push(d.getUint8(o) / 255, d.getUint8(o + 1) / 255,
            d.getUint8(o + 2) / 255, d.getUint8(o + 3) / 255); o += 4;
        }
        if (withAanim) { b.aanim.push(d.getFloat32(o, true), d.getFloat32(o + 4, true), d.getFloat32(o + 8, true)); o += 12; }
      }
      return b;
    };
    const opaque = read(false);
    const water = hasWater ? read(true) : { pos: [], uv: [], col: [], nor: [], atile: [], aanim: null };
    return { opaque, water, baseY };
  }

  /**
   * BufferGeometry from a mesher bucket, in the smallest form three.js accepts. The mesher emits 6
   * vertices per face (two triangles); we index it down to the quad's 4 corners, and store colour,
   * normal and atlas-rect as compact normalised integers instead of floats. Both are visually
   * lossless, but a full lod-0 window was ~3 GB of plain JS number arrays + float attributes before
   * this, which is what killed phone browsers.
   */
  function geoFromBucket(b) {
    const faces = Math.floor(b.pos.length / 18);        // 6 verts x 3 floats per face
    const g = new THREE.BufferGeometry();
    if (!faces) return g;
    const n = faces * 4;
    const pos = new Float32Array(n * 3);
    const uv = new Float32Array(n * 2);
    const col = new Uint8Array(n * 3);
    const nor = new Int8Array(n * 3);
    const atile = new Uint16Array(n * 4);         // u16 (1/65535): a u8 quantises a 0.03-wide atlas
                                                  // rect by ~2 texels and bleeds the neighbouring tile
    const aanim = b.aanim ? new Float32Array(n * 3) : null;
    const index = n > 65535 ? new Uint32Array(faces * 6) : new Uint16Array(faces * 6);
    let v = 0, ix = 0;
    for (let f = 0; f < faces; f++) {
      const s = f * 6;
      const corners = [s, s + 1, s + 2, s + 5];         // v0,v1,v2,v5 are the quad's unique corners
      for (let k = 0; k < 4; k++) {
        const i = corners[k], o = v * 3, o2 = v * 2, o4 = v * 4;
        pos[o] = b.pos[i * 3]; pos[o + 1] = b.pos[i * 3 + 1]; pos[o + 2] = b.pos[i * 3 + 2];
        uv[o2] = b.uv[i * 2]; uv[o2 + 1] = b.uv[i * 2 + 1];
        col[o] = b.col[i * 3] * 255; col[o + 1] = b.col[i * 3 + 1] * 255; col[o + 2] = b.col[i * 3 + 2] * 255;
        nor[o] = b.nor[i * 3] * 127; nor[o + 1] = b.nor[i * 3 + 1] * 127; nor[o + 2] = b.nor[i * 3 + 2] * 127;
        atile[o4] = b.atile[i * 4] * 65535; atile[o4 + 1] = b.atile[i * 4 + 1] * 65535;
        atile[o4 + 2] = b.atile[i * 4 + 2] * 65535; atile[o4 + 3] = b.atile[i * 4 + 3] * 65535;
        if (aanim) {
          aanim[o] = b.aanim[i * 3]; aanim[o + 1] = b.aanim[i * 3 + 1]; aanim[o + 2] = b.aanim[i * 3 + 2];
        }
        v++;
      }
      index[ix++] = v - 4; index[ix++] = v - 3; index[ix++] = v - 2;
      index[ix++] = v - 4; index[ix++] = v - 2; index[ix++] = v - 1;
    }
    g.setAttribute('position', new THREE.BufferAttribute(pos, 3));
    g.setAttribute('uv', new THREE.BufferAttribute(uv, 2));
    g.setAttribute('color', new THREE.BufferAttribute(col, 3, true));
    g.setAttribute('normal', new THREE.BufferAttribute(nor, 3, true));
    g.setAttribute('atile', new THREE.BufferAttribute(atile, 4, true));
    if (aanim) g.setAttribute('aanim', new THREE.BufferAttribute(aanim, 3));
    g.setIndex(new THREE.BufferAttribute(index, 1));
    return g;
  }

  // Resolve a block name to atlas info: exact, then "minecraft:<base>" (handles modded blocks whose
  // texture we don't have), then the base material of a slab/stairs/fence/etc.
  const SUFFIXES = ['_slab', '_stairs', '_fence_gate', '_fence', '_wall', '_door', '_trapdoor',
    '_button', '_pressure_plate', '_carpet', '_bricks', '_brick', '_block', '_top', '_side'];
  function blockInfo(atlas, name) {
    const brace = name.indexOf('[');
    if (brace >= 0) name = name.slice(0, brace); // strip block-state properties
    let b = atlas.blocks[name];
    if (b) return b;
    const base = name.includes(':') ? name.slice(name.indexOf(':') + 1) : name;
    b = atlas.blocks['minecraft:' + base];
    if (b) return b;
    const noWall = base.replace('_wall_', '_').replace(/^wall_/, ''); // wall_torch -> torch
    if (noWall !== base) { b = atlas.blocks['minecraft:' + noWall]; if (b) return b; }
    for (const sfx of SUFFIXES) {
      if (base.endsWith(sfx)) {
        b = atlas.blocks['minecraft:' + base.slice(0, -sfx.length)];
        if (b) return b;
      }
    }
    return null;
  }

  // Plant/ground-cover shapes. These render as crosses / flat quads / thin posts instead of cubes,
  // so a flower isn't a transparent box you can see through.
  // Vanilla "crop" model = 4 axis-aligned planes in a # arrangement (wheat/carrots/beetroot/stems),
  // distinct from the diagonal X of flowers.
  const CROP_RE = /^(wheat|carrots|potatoes|beetroots|nether_wart)$|_crop$|_stem$/;
  const CROSS_RE = /(_sapling|_flower|_tulip|_mushroom|_roots|_sprouts|_grass|_fern|_bush|_berries|_dripleaf|_orchid|_bluet|_daisy|dandelion|poppy|allium|cornflower|wither_rose|sunflower|lilac|rose_bush|peony|pitcher_plant|dead_bush|sugar_cane|seagrass|kelp|sweet_berry_bush|sea_pickle|vine|glow_lichen|sculk_vein|hanging_roots|cave_vines|weeping_vines|twisting_vines)$/;
  const FLAT_RE = /(_carpet|leaf_litter|pink_petals|lily_pad|_petals|(^|_)rail$|_wire$|_pressure_plate$|_tripwire$|_torchflower)$/;
  const POST_RE = /(torch|end_rod|candle)$/;
  function shapeOf(short) {
    if (short.endsWith('_slab')) return 'slab';
    if (short.endsWith('_stairs')) return 'stairs';
    if (CROP_RE.test(short)) return 'crop';
    if (CROSS_RE.test(short)) return 'cross';
    if (FLAT_RE.test(short)) return 'flat';
    if (POST_RE.test(short)) return 'post';
    return null;
  }

  // --- vanilla blockstate/model resolution ---
  function propsOf(name) {
    const b = name.indexOf('[');
    if (b < 0) return {};
    const out = {};
    name.slice(b + 1, name.lastIndexOf(']')).split(',').forEach((kv) => {
      const e = kv.indexOf('=');
      if (e > 0) out[kv.slice(0, e)] = kv.slice(e + 1);
    });
    return out;
  }
  function matchWhen(when, props) {
    if (!when) return true;
    if (when.OR) return when.OR.some((w) => matchWhen(w, props));
    if (when.AND) return when.AND.every((w) => matchWhen(w, props));
    for (const k in when) {
      if (k === 'OR' || k === 'AND') continue;
      const v = when[k];
      if (v && typeof v === 'object' && Array.isArray(v.OR)) {
        if (v.OR.indexOf(props[k]) < 0) return false;
      } else if (props[k] !== String(v)) return false;
    }
    return true;
  }
  function matchVariantKey(key, props) {
    if (key === '') return true;
    return key.split(',').every((kv) => {
      const e = kv.indexOf('=');
      return e > 0 && props[kv.slice(0, e)] === kv.slice(e + 1);
    });
  }
  function resolveRule(rule, props) {
    const applies = [];
    if (rule.variants) {
      for (const key in rule.variants) {
        if (!matchVariantKey(key, props)) continue;
        let v = rule.variants[key];
        if (Array.isArray(v)) v = v[0];
        applies.push(v);
        break;
      }
    } else if (rule.multipart) {
      for (const part of rule.multipart) {
        if (!matchWhen(part.when, props)) continue;
        const a = part.apply;
        if (Array.isArray(a)) applies.push(...a); else applies.push(a);
      }
    }
    return applies;
  }
  function isFullCubeModel(m) {
    if (!m || m.length !== 6) return false;
    for (const f of m) for (const c of f.c) {
      if ((c[0] !== 0 && c[0] !== 16) || (c[1] !== 0 && c[1] !== 16) || (c[2] !== 0 && c[2] !== 16)) return false;
    }
    return true;
  }
  function ruleIsSimple(rule, models) {
    // A rotated full cube is still a full cube (e.g. logs with axis=x/y/z) - keep those on the fast
    // greedy path; only genuinely non-cube shapes should go through the model renderer.
    const ok = (a) => isFullCubeModel(models[a.model]);
    if (rule.variants) return Object.keys(rule.variants).every((k) => {
      const arr = Array.isArray(rule.variants[k]) ? rule.variants[k] : [rule.variants[k]];
      return arr.every(ok);
    });
    if (rule.multipart) return rule.multipart.every((p) => {
      const arr = Array.isArray(p.apply) ? p.apply : [p.apply];
      return arr.every(ok);
    });
    return false;
  }

  function buildGeometry(data, atlas, biomeTints, minY, cx0, cz0, bmodels) {
    const AX = N_CHUNKS * 16, AZ = N_CHUNKS * 16;
    // Vertical window: keep everything down to a bit below the lowest terrain surface, so we render
    // a terrain diorama instead of a solid stone column all the way to bedrock.
    let top = minY + 16, minTop = Infinity, roofed = false;
    for (const c of data.chunks) {
      for (const runs of c._cols) {
        let colTop = minY, colName = null;
        for (const r of runs) {
          const e = r[0] + r[1];
          if (e > top) top = e;
          if (e > colTop) { colTop = e; colName = c._pal[r[2]]; }
        }
        if (colTop > minY) minTop = Math.min(minTop, colTop);
        if (colName) {
          const b = colName.indexOf('[') >= 0 ? colName.slice(0, colName.indexOf('[')) : colName;
          if (b.endsWith(':bedrock') && colTop >= 100) roofed = true; // nether-style bedrock roof
        }
      }
    }
    // In a roofed dimension (nether) cut the bedrock ceiling so you can see the terrain below; there
    // the "top solid" would otherwise be the roof and clip the view to just beneath it.
    const skipAbove = roofed ? 120 : Infinity;
    const baseY = includeUnderground ? minY
      : (roofed ? minY : (minTop === Infinity ? minY : minTop));
    if (roofed) top = Math.min(top, skipAbove);
    const AY = top - baseY + 1;
    const grid = new Uint16Array(AX * AY * AZ);   // 0 = air, else global palette id (1-based)
    const gpal = [''];                             // 1-based name list
    const gid = new Map();
    const biome2d = new Array(AX * AZ).fill(null);
    lastBiomeStats = { chunks: data.chunks.length, withBiome: 0,
                       tintKeys: biomeTints ? Object.keys(biomeTints).length : -1 };
    const infoCache = new Map();
    const specials = []; // fallback shapes for blocks with no compiled model
    const MODELS = (bmodels && bmodels.models) || {};
    const BLOCKS = (bmodels && bmodels.blocks) || {};
    const simpleCache = new Map();
    const simpleOf = (id) => {
      let s = simpleCache.get(id);
      if (s === undefined) { const r = BLOCKS[id]; s = r ? ruleIsSimple(r, MODELS) : false; simpleCache.set(id, s); }
      return s;
    };
    const modelBlocks = []; // blocks rendered from their real vanilla model
    // Submerged blocks carry water in-game (waterlogged, seagrass/kelp/coral). Render a water cell in
    // their block too, otherwise the plant sits in an air pocket.
    let WATER_ID = null;
    const ensureWaterId = () => {
      if (WATER_ID === null) {
        WATER_ID = gid.get('minecraft:water');
        if (WATER_ID === undefined) {
          WATER_ID = gpal.length; gpal.push('minecraft:water'); gid.set('minecraft:water', WATER_ID);
        }
      }
      return WATER_ID;
    };
    const isWaterlogged = (name, short) => /waterlogged=true/.test(name)
      || /^(seagrass|kelp|kelp_plant|bubble_column|.*_coral|.*_coral_fan|.*_coral_block|.*_coral_wall_fan)$/.test(short);
    const fillWaterCells = (wx, yStart, yEnd, wz) => {
      const wid = ensureWaterId();
      const yTop = Math.min(yEnd, skipAbove - baseY);
      for (let y = yStart; y < yTop; y++) grid[(y * AZ + wz) * AX + wx] = wid;
    };

    for (const c of data.chunks) {
      const bx = (c.cx - cx0) * 16, bz = (c.cz - cz0) * 16;
      if (bx < 0 || bz < 0 || bx + 15 >= AX || bz + 15 >= AZ) continue;
      const biome = c.biome;
      if (biome) lastBiomeStats.withBiome++;
      for (let z = 0; z < 16; z++) for (let x = 0; x < 16; x++) biome2d[(bz + z) * AX + (bx + x)] = biome;
      const pal = c._pal;
      for (let zi = 0; zi < 16; zi++) {
        for (let xi = 0; xi < 16; xi++) {
          const wx = bx + xi, wz = bz + zi;
          for (const [y0, len, pi] of c._cols[zi * 16 + xi]) {
            const name = pal[pi];
            const brace = name.indexOf('[');
            const base0 = brace >= 0 ? name.slice(0, brace) : name;
            const short = base0.includes(':') ? base0.slice(base0.indexOf(':') + 1) : base0;
            const yStart = Math.max(0, y0 - baseY), yEnd = Math.min(AY, y0 + len - baseY);
            const isW = short.indexOf('water') >= 0;
            const wl = !isW && isWaterlogged(name, short);
            if (!isW && BLOCKS[base0] && !simpleOf(base0)) { // real model (fence/door/rail/...)
              for (let y = yStart; y < yEnd; y++) modelBlocks.push([wx, y, wz, name]);
              if (wl) fillWaterCells(wx, yStart, yEnd, wz);
              continue;
            }
            if (!isW && !BLOCKS[base0] && shapeOf(short)) { // no model available: simple fallback shape
              for (let y = yStart; y < yEnd; y++) specials.push([wx, y, wz, name]);
              if (wl) fillWaterCells(wx, yStart, yEnd, wz);
              continue;
            }
            let id = gid.get(name);
            if (id === undefined) { id = gpal.length; gpal.push(name); gid.set(name, id); }
            const yTop = Math.min(yEnd, skipAbove - baseY); // drop the bedrock roof
            for (let y = yStart; y < yTop; y++) grid[(y * AZ + wz) * AX + wx] = id;
          }
        }
      }
    }

    const at = (x, y, z) => (x < 0 || y < 0 || z < 0 || x >= AX || y >= AY || z >= AZ)
      ? 0 : grid[(y * AZ + z) * AX + x];
    const waterCache = new Map();
    const isWaterId = (v) => {
      if (!v) return false;
      let w = waterCache.get(v);
      if (w === undefined) { w = gpal[v].indexOf('water') >= 0 && gpal[v].indexOf('waterlogged') < 0; waterCache.set(v, w); }
      return w;
    };
    // Surface height (0..1) of a water cell, or null if the cell isn't water. Falling water (level 8)
    // is a full block; otherwise the surface sits below the block top by the flowing level (the same
    // formula the mesher lowers faces with). Used to slope the exposed water surface between blocks
    // of different levels, so a stream ramps diagonally instead of stepping.
    const waterSurfaceH = (v) => {
      if (!isWaterId(v)) return null;
      const lm = gpal[v].match(/level=(\d+)/);
      const lvl = lm ? +lm[1] : 0;
      return lvl >= 8 ? 1 : Math.max(0.15, 0.875 - lvl * 0.11);
    };
    const waterCellH = (x, y, z) => {
      const v = at(x, y, z);
      if (!isWaterId(v)) return null;
      if (isWaterId(at(x, y + 1, z))) return 1; // water continues upward -> the column is full here
      return waterSurfaceH(v);
    };
    // Fluid surface height at the shared grid corner (xc,zc), averaged over the up-to-four water
    // cells that meet there at height yb (vanilla averages neighbour heights the same way).
    const cornerH = (xc, zc, yb, fallback) => {
      let s = 0, n = 0;
      for (let dx = -1; dx <= 0; dx++) {
        for (let dz = -1; dz <= 0; dz++) {
          const h = waterCellH(xc + dx, yb, zc + dz);
          if (h != null) { s += h; n++; }
        }
      }
      return n ? s / n : fallback;
    };
    // Top height of a *side* face at a corner. If the column continues upward at (cellX,cellZ) the
    // surface is not here, so the face must stay full height - averaging it down to a lower neighbour
    // left a slit of air under the block above (the "air gaps" in falling water). Otherwise use the
    // averaged corner height so the side edge meets the sloped top surface exactly.
    const sideTop = (cellX, cellZ, yb, cornerX, cornerZ, self) =>
      isWaterId(at(cellX, yb + 1, cellZ)) ? 1 : cornerH(cornerX, cornerZ, yb, self);
    // See-through blocks must not cull their neighbours (otherwise a leaf hides the log behind it and
    // you see straight through the transparent pixels). Water/leaves/glass/ice are all see-through.
    const transCache = new Map();
    const isTransparent = (v) => {
      if (!v) return false;
      let t = transCache.get(v);
      if (t === undefined) {
        const n = gpal[v];
        const brace = n.indexOf('[');              // palette names carry state, e.g. water[level=0]
        const b = brace >= 0 ? n.slice(0, brace) : n;
        // waterlogged blocks are submerged, so they must not cull the surrounding water
        const wl = /waterlogged=true/.test(n);
        t = wl || (/:(water|bubble_column|ice|slime_block|honey_block)$/.test(b)
              || /_leaves$/.test(b) || b.indexOf('glass') >= 0);
        transCache.set(v, t);
      }
      return t;
    };
    const infoOf = (id) => {
      let i = infoCache.get(id);
      if (i === undefined) { i = blockInfo(atlas, gpal[id]); infoCache.set(id, i); }
      return i;
    };
    const tintOf = (biome, channel) => {
      if (channel === 'none') return null;
      const t = biomeTints[biome];
      if (!t) return null;
      const hex = t[channel] || t.foliage;
      return [parseInt(hex.slice(1, 3), 16) / 255, parseInt(hex.slice(3, 5), 16) / 255,
        parseInt(hex.slice(5, 7), 16) / 255];
    };

    const opaque = { pos: [], uv: [], col: [], nor: [], atile: [] };
    const water = { pos: [], uv: [], col: [], nor: [], atile: [], aanim: [] };
    let bucket = opaque;
    let curAnim = null; // [stripRow, frameCount, fps] while emitting a water quad
    const cx = AX / 2, cz = AZ / 2;
    const tileRect = (tile) => {
      const cols = atlas.cols, rows = atlas.rows, tc = tile % cols, tr = Math.floor(tile / cols);
      return [tc / cols, tr / rows, (tc + 1) / cols, (tr + 1) / rows];
    };
    const push = (p, t, c, n, a) => {
      bucket.pos.push(p[0] - cx, p[1], p[2] - cz);
      bucket.uv.push(t[0], t[1]);
      bucket.col.push(c[0], c[1], c[2]);
      bucket.nor.push(n[0], n[1], n[2]);
      bucket.atile.push(a[0], a[1], a[2], a[3]);
      // water carries its animation strip + frame count + fps; the water shader advances the frame
      if (bucket.aanim) bucket.aanim.push(curAnim ? curAnim[0] : 0, curAnim ? curAnim[1] : 1, curAnim ? curAnim[2] : 0);
    };
    // uv is in *block* units (0..w, 0..h) and the shader fract()s it inside the tile rect, so a
    // greedy-merged run of N blocks repeats the texture N times instead of stretching it once.
    // UVs are derived from world position so the texture's "up" always matches world +Y on side
    // faces (the naive per-axis assignment rotated X faces 90 degrees). The shader fract()s the uv
    // in block units, so each block still shows one full tile.
    const quad = (p, dup, dvp, tile, c, n, tileGrid, w, h, d, ys) => {
      const cols = tileGrid.cols, rows = tileGrid.rows;
      const tc = tile % cols, tr = Math.floor(tile / cols);
      const a = [tc / cols, tr / rows, (tc + 1) / cols, (tr + 1) / rows];
      const p1 = [p[0] + dup[0], p[1] + dup[1], p[2] + dup[2]];
      const p2 = [p[0] + dup[0] + dvp[0], p[1] + dup[1] + dvp[1], p[2] + dup[2] + dvp[2]];
      const p3 = [p[0] + dvp[0], p[1] + dvp[1], p[2] + dvp[2]];
      if (ys) { p[1] = ys[0]; p1[1] = ys[1]; p2[1] = ys[2]; p3[1] = ys[3]; }
      const topY = Math.max(p[1], p1[1], p2[1], p3[1]);
      const uvOf = (pt) => (d === 1) ? [pt[0], pt[2]] : (d === 0) ? [pt[2], topY - pt[1]] : [pt[0], topY - pt[1]];
      const u0 = uvOf(p), u1 = uvOf(p1), u2 = uvOf(p2), u3 = uvOf(p3);
      push(p, u0, c, n, a); push(p1, u1, c, n, a); push(p2, u2, c, n, a);
      push(p, u0, c, n, a); push(p2, u2, c, n, a); push(p3, u3, c, n, a);
    };
    // Partial-block helpers: emit an axis-aligned box (0..1 local units) with the block's tiles.
    const UEDGE = 0.9999; // uv just under 1 so the tiling shader doesn't wrap a seam at the edge
    const face = (a, b, c2, d, tile, color, n, ext) => {
      const cols = atlas.cols, rows = atlas.rows, tc = tile % cols, tr = Math.floor(tile / cols);
      const a4 = [tc / cols, tr / rows, (tc + 1) / cols, (tr + 1) / rows];
      const X0 = ext[0], Y0 = ext[1], Z0 = ext[2], X1 = ext[3], Y1 = ext[4], Z1 = ext[5];
      const uvOf = (pt) => {
        const nx = (X1 > X0 ? (pt[0] - X0) / (X1 - X0) : 0) * UEDGE;
        const ny = (Y1 > Y0 ? (pt[1] - Y0) / (Y1 - Y0) : 0) * UEDGE;
        const nz = (Z1 > Z0 ? (pt[2] - Z0) / (Z1 - Z0) : 0) * UEDGE;
        if (Math.abs(n[1]) > 0.5) return [nx, nz];
        if (Math.abs(n[0]) > 0.5) return [nz, 1 - ny];
        return [nx, 1 - ny];
      };
      const ua = uvOf(a), ub = uvOf(b), uc = uvOf(c2), ud = uvOf(d);
      push(a, ua, color, n, a4); push(b, ub, color, n, a4); push(c2, uc, color, n, a4);
      push(a, ua, color, n, a4); push(c2, uc, color, n, a4); push(d, ud, color, n, a4);
    };
    const emitBox = (x, y, z, bx, inf, colorTop, colorSide) => {
      const X0 = x + bx[0], Y0 = y + bx[1], Z0 = z + bx[2];
      const X1 = x + bx[3], Y1 = y + bx[4], Z1 = z + bx[5];
      const ext = [X0, Y0, Z0, X1, Y1, Z1];
      const top = inf ? inf.top : 0, side = inf ? inf.side : 0;
      const bottom = inf ? (inf.bottom != null ? inf.bottom : inf.side) : 0;
      face([X0, Y1, Z0], [X1, Y1, Z0], [X1, Y1, Z1], [X0, Y1, Z1], top, colorTop, [0, 1, 0], ext);
      face([X0, Y0, Z1], [X1, Y0, Z1], [X1, Y0, Z0], [X0, Y0, Z0], bottom, colorSide, [0, -1, 0], ext);
      face([X0, Y0, Z0], [X1, Y0, Z0], [X1, Y1, Z0], [X0, Y1, Z0], side, colorSide, [0, 0, -1], ext);
      face([X0, Y0, Z1], [X0, Y1, Z1], [X1, Y1, Z1], [X1, Y0, Z1], side, colorSide, [0, 0, 1], ext);
      face([X0, Y0, Z0], [X0, Y1, Z0], [X0, Y1, Z1], [X0, Y0, Z1], side, colorSide, [-1, 0, 0], ext);
      face([X1, Y0, Z0], [X1, Y0, Z1], [X1, Y1, Z1], [X1, Y1, Z0], side, colorSide, [1, 0, 0], ext);
    };

    const dims = [AX, AY, AZ];
    const x = [0, 0, 0], q = [0, 0, 0];
    for (let d = 0; d < 3; d++) {
      const u = (d + 1) % 3, v = (d + 2) % 3;
      q[0] = q[1] = q[2] = 0; q[d] = 1;
      const du = dims[u], dv = dims[v];
      const mask = new Int32Array(du * dv);
      for (x[d] = -1; x[d] < dims[d];) {
        let n = 0;
        for (x[v] = 0; x[v] < dims[v]; x[v]++) {
          for (x[u] = 0; x[u] < dims[u]; x[u]++, n++) {
            const a = at(x[0], x[1], x[2]);
            const b = at(x[0] + q[0], x[1] + q[1], x[2] + q[2]);
            // Water is see-through: it must NOT cull the terrain behind/below it (so the seabed
            // still gets faces), and it draws its own surface against air.
            const atr = isTransparent(a), btr = isTransparent(b);
            const aw = isWaterId(a), bw = isWaterId(b);
            mask[n] = (a && !atr && (!b || btr)) ? a
              : (b && !btr && (!a || atr)) ? -b
              : (atr && !b) ? a
              : (btr && !a) ? -b
              : (aw && b && !bw) ? a        // water surface against glass/other transparent (not water)
              : (bw && a && !aw) ? -b : 0;
          }
        }
        x[d]++;
        n = 0;
        for (let j = 0; j < dv; j++) {
          for (let i = 0; i < du;) {
            const c = mask[n];
            if (!c) { i++; n++; continue; }
            let w = 1;
            while (i + w < du && mask[n + w] === c) w++;
            let h = 1, done = false;
            while (j + h < dv) {
              for (let k = 0; k < w; k++) if (mask[n + k + h * du] !== c) { done = true; break; }
              if (done) break;
              h++;
            }
            x[u] = i; x[v] = j;
            const id = c > 0 ? c : -c;
            const inf = infoOf(id);
            const sign = c > 0 ? 1 : -1;
            const bp = [x[0], x[1], x[2]];
            const name = gpal[id];
            const isWater = name.indexOf('water') >= 0 && name.indexOf('waterlogged') < 0;
            const dup = [0, 0, 0]; dup[u] = w;
            const dvp = [0, 0, 0]; dvp[v] = h;
            const planeY = bp[1];
            let ys = null;
            if (isWater) {
              // Water is a partial block: the flowing surface sits just below the block top. Falling
              // water (level=8) is full height in-game, so it must NOT be lowered. Instead of a flat
              // step at each level we slope the exposed surface: every corner of the (possibly merged)
              // top face - and the top edge of each side face - takes the averaged height of the water
              // cells meeting there, so a change of flowing level ramps diagonally. Uniform pools are
              // unaffected (all corners equal).
              const lm = name.match(/level=(\d+)/);
              const lvl = lm ? +lm[1] : 0;
              const off = lvl >= 8 ? 0 : 1 - Math.max(0.15, 0.875 - lvl * 0.11);
              const self = 1 - off;
              const runY = d === 0 ? w : h; // vertical extent of a side run, before lowering
              if (d === 1) {
                if (sign > 0) { // exposed top surface
                  const yb = planeY - 1;
                  ys = [yb + cornerH(bp[0], bp[2], yb, self),
                        yb + cornerH(bp[0] + dup[0], bp[2] + dup[2], yb, self),
                        yb + cornerH(bp[0] + dup[0] + dvp[0], bp[2] + dup[2] + dvp[2], yb, self),
                        yb + cornerH(bp[0] + dvp[0], bp[2] + dvp[2], yb, self)];
                }
              } else if (d === 0) { // X-facing side: slope its two top corners to match the surface
                dup[1] -= off;
                const ybTop = planeY + runY - 1;
                const bx = sign > 0 ? bp[0] - 1 : bp[0]; // the water block's own column
                ys = [bp[1],
                      ybTop + sideTop(bx, bp[2], ybTop, bp[0], bp[2], self),
                      ybTop + sideTop(bx, bp[2] + dvp[2] - 1, ybTop, bp[0], bp[2] + dvp[2], self),
                      bp[1]];
              } else {               // Z-facing side
                dvp[1] -= off;
                const ybTop = planeY + runY - 1;
                const bz = sign > 0 ? bp[2] - 1 : bp[2];
                ys = [bp[1], bp[1],
                      ybTop + sideTop(bp[0] + dup[0] - 1, bz, ybTop, bp[0] + dup[0], bp[2], self),
                      ybTop + sideTop(bp[0], bz, ybTop, bp[0], bp[2], self)];
              }
            }
            const normal = [0, 0, 0]; normal[d] = sign;
            let tile = 0, color = [1, 1, 1];
            if (inf) {
              const face = (d === 1) ? (sign > 0 ? 'top' : 'bottom') : 'side';
              tile = inf[face] != null ? inf[face] : inf.top;
              const ch = (d === 1 && sign > 0) ? (inf.tint || 'none') : (inf.tintSide || inf.tint || 'none');
              const t = tintOf(biome2d[x[2] * AX + x[0]], ch);
              if (t) color = t;
            } else {
              const raw = atlas.blocks[gpal[id]];
              if (raw && raw.color) {
                const h = raw.color;
                color = [parseInt(h.slice(1, 3), 16) / 255, parseInt(h.slice(3, 5), 16) / 255,
                  parseInt(h.slice(5, 7), 16) / 255];
              } else { color = [0.35, 0.35, 0.35]; }
              // neutral tile for unknown blocks - NOT tile 0 (which is a leaves texture)
              tile = (atlas.tiles && atlas.tiles.stone != null) ? atlas.tiles.stone : 0;
            }
            bucket = isWater ? water : opaque; // water gets its own translucent material
            curAnim = null;
            if (isWater) {
              // top/bottom = still water (gentle shimmer), sides = flowing water (falls/streams)
              const anim = atlas.anims && atlas.anims[d === 1 ? 'water_still' : 'water_flow'];
              if (anim) curAnim = [anim.row, anim.frames, 20 / (anim.frametime || 1)];
            }
            quad(bp, dup, dvp, tile, color, normal, atlas, w, h, d, ys);
            curAnim = null;
            for (let l = 0; l < h; l++) for (let k = 0; k < w; k++) mask[n + k + l * du] = 0;
            i += w; n += w;
          }
        }
      }
    }

    // One upright double-sided quad from two bottom corners (upright uv: v=0 at top).
    const vplane = (ax, az, bx, bz, y, a4, c) => {
      const n = [0, 1, 0];
      push([ax, y, az], [0, 1], c, n, a4); push([bx, y, bz], [1, 1], c, n, a4); push([bx, y + 1, bz], [1, 0], c, n, a4);
      push([ax, y, az], [0, 1], c, n, a4); push([bx, y + 1, bz], [1, 0], c, n, a4); push([ax, y + 1, az], [0, 0], c, n, a4);
    };
    const crossQuad = (x, y, z, tile, c) => { // diagonal X (flowers)
      const a4 = tileRect(tile);
      vplane(x, z, x + 1, z + 1, y, a4, c);
      vplane(x + 1, z, x, z + 1, y, a4, c);
    };
    const cropQuad = (x, y, z, tile, c) => { // # (wheat/carrots/stems): planes 4px in from each edge
      const a4 = tileRect(tile);
      vplane(x + 0.25, z, x + 0.25, z + 1, y, a4, c);
      vplane(x + 0.75, z, x + 0.75, z + 1, y, a4, c);
      vplane(x, z + 0.25, x + 1, z + 0.25, y, a4, c);
      vplane(x, z + 0.75, x + 1, z + 0.75, y, a4, c);
    };
    const flatQuad = (x, y, z, tile, c) => {
      const a4 = tileRect(tile), n = [0, 1, 0], yy = y + 0.0625;
      push([x, yy, z], [0, 1], c, n, a4); push([x + 1, yy, z], [1, 1], c, n, a4); push([x + 1, yy, z + 1], [1, 0], c, n, a4);
      push([x, yy, z], [0, 1], c, n, a4); push([x + 1, yy, z + 1], [1, 0], c, n, a4); push([x, yy, z + 1], [0, 0], c, n, a4);
    };

    for (const [x2, y2, z2, id] of specials) {
      const inf = blockInfo(atlas, id);
      const biome = biome2d[z2 * AX + x2];
      const wtop = tintOf(biome, inf ? (inf.tint || 'none') : 'none');
      const wside = tintOf(biome, inf ? (inf.tintSide || inf.tint || 'none') : 'none');
      const colorTop = wtop || (inf ? [1, 1, 1] : [0.35, 0.35, 0.35]);
      const colorSide = wside || colorTop;
      bucket = opaque;
      const brace = id.indexOf('[');
      const base = brace >= 0 ? id.slice(0, brace) : id;
      const short = base.includes(':') ? base.slice(base.indexOf(':') + 1) : base;
      const shape = shapeOf(short);
      if (shape === 'crop') {
        cropQuad(x2, y2, z2, inf ? inf.side : 0, colorSide);
        continue;
      }
      if (shape === 'cross') {
        crossQuad(x2, y2, z2, inf ? inf.side : 0, colorSide);
        continue;
      }
      if (shape === 'flat') {
        flatQuad(x2, y2, z2, inf ? inf.top : 0, colorTop);
        continue;
      }
      if (shape === 'post') {
        let bx = [0.4375, 0, 0.4375, 0.5625, 0.625, 0.5625]; // floor torch: small centred post
        const mf = id.match(/facing=(north|south|east|west)/);
        if (short.indexOf('wall_torch') >= 0 && mf) {          // wall torch: raised, against the wall
          const f = mf[1];
          bx = f === 'north' ? [0.4375, 0.1875, 0, 0.5625, 0.8125, 0.125]
            : f === 'south' ? [0.4375, 0.1875, 0.875, 0.5625, 0.8125, 1]
            : f === 'west' ? [0, 0.1875, 0.4375, 0.125, 0.8125, 0.5625]
            : [0.875, 0.1875, 0.4375, 1, 0.8125, 0.5625];
        }
        emitBox(x2, y2, z2, bx, inf, colorTop, colorSide);
        continue;
      }
      if (short.endsWith('_slab')) {
        const topHalf = id.indexOf('half=top') >= 0;
        emitBox(x2, y2, z2, topHalf ? [0, 0.5, 0, 1, 1, 1] : [0, 0, 0, 1, 0.5, 1], inf, colorTop, colorSide);
      } else {
        // facing = the side the full/tall half is on (verified against the vanilla stair model).
        const topHalf = id.indexOf('half=top') >= 0;
        const m = id.match(/facing=(north|south|east|west)/);
        const f = m ? m[1] : 'north';
        const tall = f === 'north' ? [0, 0.5, 0, 1, 1, 0.5]
          : f === 'south' ? [0, 0.5, 0.5, 1, 1, 1]
          : f === 'west' ? [0, 0.5, 0, 0.5, 1, 1] : [0.5, 0.5, 0, 1, 1, 1];
        if (!topHalf) {
          emitBox(x2, y2, z2, [0, 0, 0, 1, 0.5, 1], inf, colorTop, colorSide); // lower base slab
          emitBox(x2, y2, z2, tall, inf, colorTop, colorSide);                  // full-height step
        } else {
          emitBox(x2, y2, z2, [0, 0.5, 0, 1, 1, 1], inf, colorTop, colorSide);  // upper base slab
          emitBox(x2, y2, z2, [tall[0], 0, tall[2], tall[3], 0.5, tall[5]], inf, colorTop, colorSide);
        }
      }
    }

    // Render a block from its compiled vanilla model (blockstate rule -> model(s) -> faces).
    const DIRV = { down: [0, -1, 0], up: [0, 1, 0], north: [0, 0, -1], south: [0, 0, 1], west: [-1, 0, 0], east: [1, 0, 0] };
    const rotXYZ = (x, y, z, rx, ry) => {
      const cy = Math.cos(rx), sy = Math.sin(rx);
      const y1 = y * cy - z * sy, z1 = y * sy + z * cy;
      const cx = Math.cos(ry), sx = Math.sin(ry);
      return [x * cx - z1 * sx, y1, x * sx + z1 * cx];
    };
    const emitTri = (a, ua, b, ub, c, uc, color, n, a4) => {
      push(a, ua, color, n, a4); push(b, ub, color, n, a4); push(c, uc, color, n, a4);
    };
    const renderModel = (x, y, z, idName) => {
      const brace = idName.indexOf('[');
      const baseId = brace >= 0 ? idName.slice(0, brace) : idName;
      const rule = BLOCKS[baseId];
      if (!rule) return;
      const props = propsOf(idName);
      const applies = resolveRule(rule, props);
      const inf = blockInfo(atlas, baseId);
      const biome = biome2d[z * AX + x];
      for (const a of applies) {
        const model = MODELS[a.model];
        if (!model) continue;
        const rx = a.x ? (a.x * Math.PI) / 180 : 0, ry = a.y ? (a.y * Math.PI) / 180 : 0;
        for (const f of model) {
          if (f.cf) { const n = DIRV[f.cf]; const nb = at(x + n[0], y + n[1], z + n[2]); if (nb && !isTransparent(nb)) continue; }
          const tname = f.t ? (f.t.indexOf('/') >= 0 ? f.t.slice(f.t.lastIndexOf('/') + 1) : f.t) : null;
          // Entity textures (chests/banners) live in the atlas too, but at native size (64px) and in
          // their own region - a box model's UVs are in that 0..size space, not 0..16 tile space.
          const ent = f.t && atlas.entities && atlas.entities[f.t];
          const a4 = ent ? [ent.u0, ent.v0, ent.u1, ent.v1]
            : tileRect((tname && atlas.tiles[tname] != null) ? atlas.tiles[tname]
              : (atlas.tiles.stone != null ? atlas.tiles.stone : 0));
          const texSize = ent ? (ent.size || 64) : 16;
          let color = [1, 1, 1];
          if (!ent && f.ti != null && inf) { const t = tintOf(biome, inf.tint || 'none'); if (t) color = t; }
          // banners: the flag faces are dyed by the banner's base colour (from the block name)
          if (f.dye) {
            const m = baseId.match(/^(?:minecraft:)?([a-z_]+?)_(?:wall_)?banner$/);
            const dye = m && DYE_COLOURS[m[1]];
            if (dye) color = dye;
          }
          const uv0 = f.uv;
          // UVs are indexed by vertex exactly as vanilla's CuboidFace.UVs.getVertexU/V: corners
          // 0,1 take minU and 2,3 take maxU; corners 0,3 take minV and 1,2 take maxV. (Swapping the
          // old order for this one is what rotated the beds' top face 90 degrees.)
          const cu = [[uv0[0], uv0[1]], [uv0[0], uv0[3]], [uv0[2], uv0[3]], [uv0[2], uv0[1]]];
          const pts = [], uvs = [];
          for (let i = 0; i < 4; i++) {
            const c = f.c[i];
            const r = rotXYZ(c[0] - 8, c[1] - 8, c[2] - 8, rx, ry);
            pts.push([x + (r[0] + 8) / 16, y + (r[1] + 8) / 16, z + (r[2] + 8) / 16]);
            uvs.push([(cu[i][0] / texSize) * 0.9999, (cu[i][1] / texSize) * 0.9999]);
          }
          const ax = pts[1][0] - pts[0][0], ay = pts[1][1] - pts[0][1], az = pts[1][2] - pts[0][2];
          const bx = pts[2][0] - pts[0][0], by = pts[2][1] - pts[0][1], bz = pts[2][2] - pts[0][2];
          let nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
          const len = Math.hypot(nx, ny, nz) || 1; nx /= len; ny /= len; nz /= len;
          emitTri(pts[0], uvs[0], pts[1], uvs[1], pts[2], uvs[2], color, [nx, ny, nz], a4);
          emitTri(pts[0], uvs[0], pts[2], uvs[2], pts[3], uvs[3], color, [nx, ny, nz], a4);
        }
      }
    };
    for (const [mx, my, mz, idName] of modelBlocks) { bucket = opaque; renderModel(mx, my, mz, idName); }

    return { geo: geoFromBucket(opaque), waterGeo: geoFromBucket(water), baseY };
  }

  /**
   * World mode mesh: one flat quad per decimated chunk (its real surface height + block colour)
   * plus skirts down to any lower neighbour, so a whole 12k-chunk world reads as solid 3D terrain.
   * Absolute block coordinates, so structures/players/waypoints line up without translation.
   */
  /** One-time stylesheet for the status line's spinner and action button. */
  function ensureStatusStyles() {
    if (document.getElementById('gt3d-status-style')) return;
    const s = document.createElement('style');
    s.id = 'gt3d-status-style';
    s.textContent = '@keyframes gt3d-spin{to{transform:rotate(360deg)}}'
      + '#gt3d-status{display:flex;align-items:center;gap:8px}'
      + '#gt3d-status .gt3d-spin{width:11px;height:11px;border:2px solid rgba(207,238,255,0.25);'
      + 'border-top-color:#cfe;border-radius:50%;animation:gt3d-spin 0.8s linear infinite}'
      + '#gt3d-status .gt3d-act{padding:2px 8px;font-size:12px;cursor:pointer;background:#222;color:#eee;'
      + 'border:1px solid #666;border-radius:4px}';
    document.head.appendChild(s);
  }

  /**
   * The status line, in the three states that ask different things of the reader:
   *   busy  - a load is running, so it carries a spinner and never reads as frozen
   *   empty - there is nothing to draw here yet, so it carries the way out
   *   error - what broke, and a retry
   * Anything else is plain information and gets neither.
   */
  function setStatus(kind, text, actionLabel, action) {
    if (!statusEl) return;
    ensureStatusStyles();
    statusEl.id = 'gt3d-status';
    statusEl.style.cssText = 'position:fixed;bottom:12px;left:12px;z-index:101;font:13px sans-serif;'
      + 'background:rgba(0,0,0,0.6);padding:6px 10px;border-radius:4px;color:#cfe;';
    statusEl.textContent = '';
    if (kind === 'busy') {
      const sp = document.createElement('span');
      sp.className = 'gt3d-spin';
      statusEl.appendChild(sp);
    } else if (kind === 'error') {
      statusEl.style.color = '#ffb4b4';
    }
    const t = document.createElement('span');
    t.textContent = text;
    statusEl.appendChild(t);
    if (actionLabel && action) {
      const b = document.createElement('button');
      b.className = 'gt3d-act';
      b.textContent = actionLabel;
      b.onclick = action;
      statusEl.appendChild(b);
    }
  }

  /** Re-open the same spot: the retry path after a load has failed. */
  function retryOpen() {
    const c = lastCenter;
    if (!c) return;
    close();
    open(c.world, c.cx, c.cz);
  }

  /** Step the camera back out and rebuild at the resolution that implies. */
  function zoomOutStep() {
    if (!camera || !controls) return;
    const d = camera.position.clone().sub(controls.target);
    if (d.lengthSq() > 1) camera.position.copy(controls.target).addScaledVector(d, 2.2);
    controls.update();
    refineWorldNow();
  }

  async function open(world, centerCx, centerCz, opts) {
    if (canvas) return;
    if (typeof THREE === 'undefined') { alert('3D library not loaded'); return; }

    let minY = -64;
    try {
      const m = await (await fetch(`/tiles/${encodeURIComponent(world)}/meta.json`)).json();
      if (m && m.minY != null) minY = m.minY;
    } catch (e) { /* default */ }
    lastCenter = { world, cx: centerCx, cz: centerCz };
    worldMinY = minY;
    if (opts && opts.win) initialWin = opts.win;
    // ONE view: the ladder always runs, from whole-world relief down to real blocks. There is no
    // separate "detail mode" any more - zooming in is how you get detail.
    worldView = true;
    if (opts && opts.step) worldStep = opts.step;
    const half = Math.floor(N_CHUNKS / 2);
    const cx0 = Math.floor(centerCx) - half, cz0 = Math.floor(centerCz) - half;
    const cx1 = cx0 + N_CHUNKS - 1, cz1 = cz0 + N_CHUNKS - 1;

    canvas = document.createElement('canvas');
    canvas.style.cssText = 'position:fixed;inset:0;z-index:100;background:#0b1622;display:block;';
    document.body.appendChild(canvas);
    closeBtn = document.createElement('button');
    closeBtn.textContent = '\u2715 2D map';
    closeBtn.style.cssText = 'position:fixed;top:12px;right:12px;z-index:101;padding:8px 12px;font-size:14px;cursor:pointer;background:#222;color:#eee;border:1px solid #666;border-radius:4px;';
    closeBtn.onclick = close;
    document.body.appendChild(closeBtn);
    depthBtn = document.createElement('button');
    depthBtn.textContent = includeUnderground ? 'underground: on' : 'underground: off';
    depthBtn.style.cssText = 'position:fixed;top:12px;right:110px;z-index:101;padding:8px 12px;font-size:14px;cursor:pointer;background:#222;color:#eee;border:1px solid #666;border-radius:4px;';
    depthBtn.onclick = () => {
      const c = lastCenter;
      includeUnderground = !includeUnderground;
      if (c) { close(); open(c.world, c.cx, c.cz); }
    };
    document.body.appendChild(depthBtn);
    depthBtn.style.display = worldView ? 'none' : '';
    worldBtn = document.createElement('button');
    worldBtn.textContent = '\u25c9 one view';
    worldBtn.title = 'One continuous view: the ladder runs from the whole world down to real blocks';
    worldBtn.style.display = 'none';
    worldBtn.style.cssText = 'position:fixed;top:12px;right:268px;z-index:101;padding:8px 12px;font-size:14px;cursor:pointer;background:#222;color:#eee;border:1px solid #666;border-radius:4px;';
    worldBtn.onclick = () => {
      const c = lastCenter;
      close();
      if (c) open(c.world, c.cx, c.cz, { worldView: !worldView });
    };
    document.body.appendChild(worldBtn);
    islandsBtn = document.createElement('button');
    islandsBtn.textContent = worldIslands ? 'islands: on' : 'islands: off';
    islandsBtn.title = 'Sky islands are ~200 blocks above the ground here; show them as floating slabs';
    islandsBtn.style.cssText = 'position:fixed;top:12px;right:110px;z-index:101;padding:8px 12px;font-size:14px;cursor:pointer;background:#222;color:#eee;border:1px solid #666;border-radius:4px;';
    islandsBtn.style.display = 'none'; // islands are real blocks at every level now
    islandsBtn.onclick = () => {
      const c = lastCenter;
      worldIslands = !worldIslands;
      close();
      if (c) open(c.world, c.cx, c.cz, { worldView: true, step: worldStep });
    };
    document.body.appendChild(islandsBtn);
    statusEl = document.createElement('div');
    document.body.appendChild(statusEl);
    setStatus('busy', 'loading terrain\u2026');

    // Controls for the 3D view itself. The layer switches are the 2D map's own (read and written
    // through window.GT, so the two views cannot disagree), and the view-only switches live here
    // rather than floating separately over the map.
    panelEl = document.createElement('div');
    panelEl.style.cssText = 'position:fixed;top:12px;left:12px;z-index:101;display:flex;flex-direction:column;'
      + 'gap:6px;background:rgba(0,0,0,0.6);border:1px solid #666;border-radius:6px;padding:8px 10px;'
      + 'font:13px sans-serif;color:#eee;';
    const panelTitle = document.createElement('div');
    panelTitle.textContent = 'layers & view';
    panelTitle.style.cssText = 'font-weight:bold;opacity:0.75;';
    panelEl.appendChild(panelTitle);
    const bboxRow = document.createElement('label');
    bboxRow.style.cssText = 'display:flex;align-items:center;gap:6px;cursor:pointer;';
    bboxToggle = document.createElement('input');
    bboxToggle.type = 'checkbox';
    bboxToggle.checked = !!(window.GT && window.GT.showBboxes && window.GT.showBboxes());
    bboxToggle.onchange = () => {
      if (window.GT && window.GT.setShowBboxes) window.GT.setShowBboxes(bboxToggle.checked);
      refreshOverlays();
    };
    bboxRow.appendChild(bboxToggle);
    bboxRow.appendChild(document.createTextNode('structure boxes'));
    panelEl.appendChild(bboxRow);
    const colRow = document.createElement('label');
    colRow.style.cssText = 'display:flex;align-items:center;gap:6px;cursor:pointer;';
    bboxColor = document.createElement('input');
    bboxColor.type = 'color';
    bboxColor.value = (window.GT && window.GT.bboxColor && window.GT.bboxColor()) || '#ffd25c';
    bboxColor.style.cssText = 'width:30px;height:18px;padding:0;border:1px solid #666;background:none;cursor:pointer;';
    bboxColor.oninput = () => {
      if (window.GT && window.GT.setBboxColor) window.GT.setBboxColor(bboxColor.value);
      refreshOverlays();
    };
    colRow.appendChild(bboxColor);
    colRow.appendChild(document.createTextNode('box colour'));
    panelEl.appendChild(colRow);
    const slimeRow = document.createElement('label');
    slimeRow.style.cssText = 'display:flex;align-items:center;gap:6px;cursor:pointer;color:#7ee98a;';
    slimeToggle = document.createElement('input');
    slimeToggle.type = 'checkbox';
    slimeToggle.checked = !!(window.GT && window.GT.showSlime && window.GT.showSlime());
    if (window.GT && window.GT.canShowSlime && !window.GT.canShowSlime()) {
      slimeToggle.disabled = true;
      slimeRow.style.opacity = '0.5';
      slimeRow.title = 'slime chunks exist in overworld-type dimensions only';
    }
    slimeToggle.onchange = () => {
      if (window.GT && window.GT.setShowSlime) window.GT.setShowSlime(slimeToggle.checked);
      refreshOverlays();
    };
    slimeRow.appendChild(slimeToggle);
    slimeRow.appendChild(document.createTextNode('slime chunks'));
    panelEl.appendChild(slimeRow);
    for (const b of [depthBtn, worldBtn, islandsBtn]) {
      const hidden = b.style.display === 'none';
      b.style.cssText = 'padding:5px 8px;font-size:13px;cursor:pointer;background:#222;color:#eee;'
        + 'border:1px solid #666;border-radius:4px;text-align:left;';
      if (hidden) b.style.display = 'none';
      panelEl.appendChild(b);
    }
    document.body.appendChild(panelEl);

    renderer = new THREE.WebGLRenderer({ canvas, antialias: true, logarithmicDepthBuffer: worldView });
    renderer.setPixelRatio(Math.min(devicePixelRatio || 1, 2));
    renderer.setSize(innerWidth, innerHeight);
    scene = new THREE.Scene();
    scene.background = new THREE.Color(WORLD_TINT);
    camera = new THREE.PerspectiveCamera(55, innerWidth / innerHeight, worldView ? 2 : 0.5, worldView ? 400000 : 6000);
    controls = new THREE.OrbitControls(camera, canvas);
    controls.maxPolarAngle = Math.PI * 0.495;
    // PC: left-drag moves the map (pan), right-drag moves the view (orbit). Mobile: one finger
    // pans the location, two fingers orbit + pinch-zoom.
    controls.mouseButtons = { LEFT: THREE.MOUSE.PAN, MIDDLE: THREE.MOUSE.DOLLY, RIGHT: THREE.MOUSE.ROTATE };
    controls.touches = { ONE: THREE.TOUCH.PAN, TWO: THREE.TOUCH.DOLLY_ROTATE };
    // much calmer mouse feel
    controls.addEventListener('change', scheduleWorldRefine);
    controls.zoomSpeed = 0.8;  // 0.95^0.8 ~ 4% per wheel notch (was ~1%: painfully slow)
    controls.panSpeed = 0.25;
    controls.rotateSpeed = 0.4;
    scene.add(new THREE.AmbientLight(0xffffff, 0.55));
    const dir = new THREE.DirectionalLight(0xffffff, 0.45); // sums to 1.0 on top faces, so flat ground shows true colour
    dir.position.set(1, 2, 0.6);
    scene.add(dir);

    if (worldView) {
      try { await loadWorldView(world, centerCx, centerCz); } catch (e) { setStatus('error', 'terrain load failed: ' + e.message, 'retry', retryOpen); }
    } else try {
      const AV = Date.now(); // atlas/biome JSON change when regenerated; bypass the 1h tile cache
      // Small mesher assets FIRST (cached + retried), then the heavy voxel response: the tint table
      // must never lose a race against a whole-area voxels fetch, or the whole load comes out grey.
      const { atlas, tints: biomeTints, bmodels } = await loadVoxAssets();
      const vox = await fetch(`/api/voxels?world=${encodeURIComponent(world)}&cx0=${cx0}&cz0=${cz0}&cx1=${cx1}&cz1=${cz1}`)
        .then((r) => r.json());
      if (!vox.chunks || !vox.chunks.length) {
        setStatus('empty', 'no block data for this area yet', 'back to 2D', close);
        animate();
        return;
      }
      for (const c of vox.chunks) {
        const buf = await inflated(c.data);
        const v = parseVoxel(buf);
        c._pal = v.pal; c._cols = v.cols;
      }
      setStatus('busy', 'meshing\u2026');
      await new Promise((r) => setTimeout(r, 0));

      atlasTex = await new THREE.TextureLoader().loadAsync(`/tiles/atlas.png?v=${AV}`);
      atlasTex.flipY = false;
      atlasTex.magFilter = THREE.NearestFilter;
      atlasTex.minFilter = THREE.NearestFilter;
      atlasTex.generateMipmaps = false;

      // anim.png holds the full water frame strips (see AtlasBuilder). Optional: if it is missing we
      // fall back to the static first-frame atlas tile for water.
      try {
        animTex = await new THREE.TextureLoader().loadAsync(`/tiles/anim.png?v=${AV}`);
        animTex.flipY = false;
        animTex.magFilter = THREE.NearestFilter;
        animTex.minFilter = THREE.NearestFilter;
        animTex.generateMipmaps = false;
        animTex.wrapS = THREE.ClampToEdgeWrapping;
        animTex.wrapT = THREE.ClampToEdgeWrapping;
      } catch (e) { animTex = null; }

      const { geo, waterGeo, baseY } = buildGeometry(vox, atlas, biomeTints, minY, cx0, cz0, bmodels);
      // Tile the atlas sprite once per block: uv is in block units, atile is the tile's UV rect.
      const patch = (shader) => {
        shader.vertexShader = 'attribute vec4 atile;\nvarying vec4 vAtile;\n' + shader.vertexShader;
        shader.vertexShader = shader.vertexShader.replace(
          '#include <uv_vertex>', '#include <uv_vertex>\n  vAtile = atile;');
        shader.uniforms.uAtileTexel = { value: new THREE.Vector2(1 / (atlas.cols * 16), 1 / (atlas.rows * 16)) };
        shader.fragmentShader = 'varying vec4 vAtile;\nuniform vec2 uAtileTexel;\n' + shader.fragmentShader;
        shader.fragmentShader = shader.fragmentShader.replace(
          '#include <map_fragment>',
          '#ifdef USE_MAP\n'
          // inset half a texel so a block edge can never sample the neighbouring atlas tile
          + '  vec2 gtu = vAtile.xy + uAtileTexel * 0.5 + fract(vUv) * (vAtile.zw - vAtile.xy - uAtileTexel);\n'
          + '  vec4 sampledDiffuseColor = texture2D(map, gtu);\n'
          + '  diffuseColor *= sampledDiffuseColor;\n'
          + '#endif');
      };
      // alphaTest clears the black background on cut-out textures (flowers, saplings, grass).
      const mat = new THREE.MeshLambertMaterial({
        map: atlasTex, vertexColors: true, side: THREE.DoubleSide, alphaTest: 0.25,
      });
      mat.onBeforeCompile = patch;
      scene.add(new THREE.Mesh(geo, mat));
      // Water: separate translucent pass so you can see the bottom through it. It samples the
      // animated frames from anim.png (advancing over time) instead of the static atlas tile.
      if (waterGeo.getAttribute('position').count) {
        const wmat = new THREE.MeshLambertMaterial({
          map: atlasTex, vertexColors: true, side: THREE.DoubleSide,
          transparent: true, opacity: 0.9, depthWrite: true,
        });
        if (animTex) {
          const animGrid = new THREE.Vector2(atlas.animCols || 1, atlas.animRows || 1);
          wmat.onBeforeCompile = (shader) => {
            shader.uniforms.animMap = { value: animTex };
            shader.uniforms.uTime = waterTime;
            shader.uniforms.uAnimGrid = { value: animGrid };
            shader.vertexShader = 'attribute vec3 aanim;\nvarying vec3 vAnim;\n' + shader.vertexShader;
            shader.vertexShader = shader.vertexShader.replace(
              '#include <uv_vertex>', '#include <uv_vertex>\n  vAnim = aanim;');
            shader.fragmentShader = 'uniform sampler2D animMap;\nuniform float uTime;\nuniform vec2 uAnimGrid;\nvarying vec3 vAnim;\n' + shader.fragmentShader;
            shader.fragmentShader = shader.fragmentShader.replace('#include <map_fragment>',
              '#ifdef USE_MAP\n'
              + '  float afN = max(vAnim.y, 1.0);\n'
              + '  float afI = mod(floor(uTime * vAnim.z), afN);\n'
              + '  vec2 aUv = vec2((afI + fract(vUv.x)) / uAnimGrid.x, (vAnim.x + fract(vUv.y)) / uAnimGrid.y);\n'
              + '  diffuseColor *= texture2D(animMap, aUv);\n'
              + '#endif');
          };
        } else {
          wmat.onBeforeCompile = patch;
        }
        scene.add(new THREE.Mesh(waterGeo, wmat));
      }

      geo.computeBoundingBox();
      const bb = geo.boundingBox, ctr = bb.getCenter(new THREE.Vector3()), sz = bb.getSize(new THREE.Vector3());
      const maxDim = Math.max(sz.x, sz.z, sz.y);
      const fitDist = (maxDim * 0.5) / Math.tan((camera.fov / 2) * Math.PI / 180);
      camera.position.copy(ctr).addScaledVector(new THREE.Vector3(0.6, 0.55, 0.6).normalize(), fitDist * 1.2);
      controls.target.copy(ctr);
      controls.minDistance = maxDim * 0.1;
      controls.maxDistance = fitDist * 6;
      controls.update();
      const faces = (geo.index ? geo.index.count : geo.getAttribute('position').count) / 6;
      setStatus('info', `${vox.chunks.length} chunks \u00b7 ${N_CHUNKS * 16}\u00b2 blocks \u00b7 ${faces.toLocaleString()} faces \u00b7 drag to orbit`);

      await addMarkers(vox, baseY, cx0, cz0);
    } catch (e) {
      setStatus('error', '3D load failed: ' + e.message, 'retry', retryOpen);
    }

    window.addEventListener('resize', onResize);
    window.addEventListener('keydown', onKey);
    canvas.addEventListener('pointerdown', (e) => { downXY = [e.clientX, e.clientY]; });
    canvas.addEventListener('pointerup', (e) => {
      if (!downXY || Math.abs(e.clientX - downXY[0]) > 4 || Math.abs(e.clientY - downXY[1]) > 4) return;
      if (!markerPoints) return;
      const rc = new THREE.Raycaster();
      rc.params.Points.threshold = 8;
      rc.setFromCamera(new THREE.Vector2((e.clientX / innerWidth) * 2 - 1, -(e.clientY / innerHeight) * 2 + 1), camera);
      const hit = rc.intersectObject(markerPoints)[0];
      if (!hit) { if (popupEl) popupEl.style.display = 'none'; return; }
      const m = markerPoints.userData.markers[hit.index];
      if (!popupEl) {
        popupEl = document.createElement('div');
        popupEl.style.cssText = 'position:fixed;z-index:102;background:rgba(0,0,0,0.85);color:#eee;font:13px sans-serif;padding:8px 10px;border-radius:6px;max-width:280px;pointer-events:none;';
        document.body.appendChild(popupEl);
      }
      popupEl.innerHTML = `<b>${m.s.type}</b><br>x ${m.s.x}, y ${m.s.y}, z ${m.s.z}`
        + (m.underground ? '<br><i>underground structure</i>' : '');
      popupEl.style.display = 'block';
      popupEl.style.left = Math.min(e.clientX + 14, innerWidth - 300) + 'px';
      popupEl.style.top = (e.clientY + 14) + 'px';
    });
    animate();
  }

  /** Which tier covers a span of N chunks, and at what resolution. */
  /**
   * Two stages, in real blocks: 1m for the detail layer and 2m for the low-res one around it. Every
   * level is the same renderer and the same textures, so zooming out looks like Minecraft getting
   * chunkier, not like a different kind of map. 4m and beyond is not used: at that cell size narrow
   * structures (towers, masts, thin walls) collapse into a block or two.
   */
  function tierFor(spanChunks) {
    // pick the finest level whose window still covers what is on screen, so a ~1100-block view is
    // still 1m blocks and 2m blocks only appear past that
    let lod = 0, f = 1;
    while (f * VOXEL_MAX_VCHUNKS < spanChunks && lod < 1) { lod++; f *= 2; }
    return { kind: 'vox', lod, key: 'v' + lod };
  }

  /**
   * Mesh a dense block-level heightfield from /api/pixels. The server has already median-filtered it,
   * so this is plain terrain: a top face per cell plus skirts to lower neighbours.
   */
  /**
   * World view = a ladder of terrain meshes, not one fixed level. We always build a single mesh
   * covering what you're looking at, and rebuild it at a finer resolution when you zoom in:
   *   whole world -> 64m chunk cells -> 16m chunk cells -> n-metre block cells (from chunk_pixels)
   * Rebuilding one mesh - rather than stitching LOD tiles - means there are no seams to manage.
   */
  async function loadWorldView(world, centerCx, centerCz) {
    setStatus('busy', 'loading terrain\u2026');
    // cheap probe for the world's extent (step 64 returns the bounds plus a couple of samples)
    let probe;
    try {
      probe = await (await fetch(`/api/terrain?world=${encodeURIComponent(world)}&all=1&step=64&deflate=1`)).json();
    } catch (e) { probe = null; }
    if (!probe || probe.error || !probe.bounds) {
      setStatus('empty', 'no surface data for this world yet', '2D map', close);
      return;
    }
    worldBounds = probe.bounds;
    worldMat = new THREE.MeshLambertMaterial({ vertexColors: true });
    // Open where the detail is: one full-detail window around the requested spot. Zooming OUT walks
    // up the ladder from here; there is no separate world view to land in.
    const c0x = Math.round(centerCx != null ? centerCx : 0);
    const c0z = Math.round(centerCz != null ? centerCz : 0);
    const w0 = initialWin || 30;
    await buildWorldTier(world, c0x - w0 / 2, c0z - w0 / 2, c0x + w0 / 2, c0z + w0 / 2, true);
    addWorldMarkers();
  }

  /** Load one terrain tile, meshing it small enough that no frame is ever blocked. */
  /**
   * Hide any tile of the previous level that the new level now fully covers. Tiles sit on a global
   * grid, so this is just index arithmetic - no per-vertex work.
   */
  /** Free a level's geometry (a Group of tile Groups, each holding opaque/water meshes). */
  function disposeGroup(g) {
    if (!g) return;
    g.traverse((o) => { if (o.geometry) o.geometry.dispose(); });
    scene.remove(g);
  }

  function disposeVoxelGroup() {
    if (!voxelGroup) return;
    for (const m of voxelGroup.children) m.geometry.dispose();
    scene.remove(voxelGroup);
    voxelGroup = null;
  }

  function disposeWorldGroup() {
    if (coreGroup) { disposeGroup(coreGroup); coreGroup = null; coreLevel = null; }
    if (worldGroup) { disposeGroup(worldGroup); worldGroup = null; }
  }

  /**
   * Load the atlas/biome/model data the real-block mesher needs, once per session. The polygon offset
   * makes the voxel surface win the depth test against the coarse heightfield still underneath it, so
   * the fine mesh can be laid on top without z-fighting and without punching holes.
   */
  /**
   * Fetch + cache the small mesher assets (atlas index, biome tints, block models) ONCE per page
   * load, with retries, and load them BEFORE the heavy requests.
   *
   * Why: these files are tiny, but they used to be re-fetched on every 3D open inside a Promise.all
   * racing the very large responses (a whole-area /api/voxels, atlas.png, multi-MB .gtmesh tiles)
   * for the plugin's web thread pool. When the small fetch lost that race it rejected, and
   * `.catch(() => ({}))` silently substituted an EMPTY tint table - so grass/leaves/water (whose
   * textures are greyscale) rendered uncoloured for that entire load. Fetching them once, with
   * retries, and before the heavy requests removes the race.
   */
  let voxAssetsPromise = null;
  function fetchJsonRetry(url, tries) {
    return fetch(url).then((r) => {
      if (!r.ok) throw new Error(url + ' -> ' + r.status);
      return r.json();
    }).catch((e) => {
      if (tries <= 1) throw e;
      return new Promise((res) => setTimeout(res, 200 * (5 - tries)))
        .then(() => fetchJsonRetry(url, tries - 1));
    });
  }
  function loadVoxAssets() {
    if (!voxAssetsPromise) {
      const AV = Date.now();
      voxAssetsPromise = Promise.all([
        fetchJsonRetry(`/tiles/atlas.json?v=${AV}`, 4),
        fetchJsonRetry(`/tiles/biome_tints.json?v=${AV}`, 4),
        fetchJsonRetry(`/tiles/blockmodels.json?v=${AV}`, 3).catch(() => ({})),
      ]).then(([atlas, tints, bmodels]) => ({ atlas, tints, bmodels }))
        .catch((e) => { voxAssetsPromise = null; throw e; });   // let a later call retry from scratch
    }
    return voxAssetsPromise;
  }

  async function ensureVoxelAssets() {
    if (voxMat) return true;
    if (typeof THREE === 'undefined') return false;
    const AV = Date.now();
    const { atlas, tints, bmodels } = await loadVoxAssets();
    const tex = await new THREE.TextureLoader().loadAsync(`/tiles/atlas.png?v=${AV}`);
    tex.flipY = false;
    tex.magFilter = THREE.NearestFilter;
    tex.minFilter = THREE.NearestFilter;
    tex.generateMipmaps = false;
    let anim = null;
    try {
      anim = await new THREE.TextureLoader().loadAsync(`/tiles/anim.png?v=${AV}`);
      anim.flipY = false;
      anim.magFilter = THREE.NearestFilter;
      anim.minFilter = THREE.NearestFilter;
      anim.generateMipmaps = false;
      anim.wrapS = THREE.ClampToEdgeWrapping;
      anim.wrapT = THREE.ClampToEdgeWrapping;
    } catch (e) { anim = null; }
    // Tile the atlas sprite once per block: uv is in block units, atile is the tile's UV rect.
    const patch = (shader) => {
      shader.vertexShader = 'attribute vec4 atile;\nvarying vec4 vAtile;\n' + shader.vertexShader;
      shader.vertexShader = shader.vertexShader.replace(
        '#include <uv_vertex>', '#include <uv_vertex>\n  vAtile = atile;');
      shader.fragmentShader = 'varying vec4 vAtile;\n' + shader.fragmentShader;
      shader.fragmentShader = shader.fragmentShader.replace(
        '#include <map_fragment>',
        '#ifdef USE_MAP\n'
        + '  vec2 gtu = vAtile.xy + fract(vUv) * (vAtile.zw - vAtile.xy);\n'
        + '  vec4 sampledDiffuseColor = texture2D(map, gtu);\n'
        + '  diffuseColor *= sampledDiffuseColor;\n'
        + '#endif');
    };
    const mat = new THREE.MeshLambertMaterial({
      map: tex, vertexColors: true, side: THREE.DoubleSide, alphaTest: 0.25,
    });
    mat.onBeforeCompile = patch;
    let wmat = null;
    if (anim) {
      const animGrid = new THREE.Vector2(atlas.animCols || 1, atlas.animRows || 1);
      wmat = new THREE.MeshLambertMaterial({
        map: tex, vertexColors: true, side: THREE.DoubleSide,
        transparent: true, opacity: 0.9, depthWrite: true,
      });
      wmat.onBeforeCompile = (shader) => {
        shader.uniforms.animMap = { value: anim };
        shader.uniforms.uTime = waterTime;
        shader.uniforms.uAnimGrid = { value: animGrid };
        shader.vertexShader = 'attribute vec3 aanim;\nvarying vec3 vAnim;\n' + shader.vertexShader;
        shader.vertexShader = shader.vertexShader.replace(
          '#include <uv_vertex>', '#include <uv_vertex>\n  vAnim = aanim;');
        shader.fragmentShader = 'uniform sampler2D animMap;\nuniform float uTime;\nuniform vec2 uAnimGrid;\nvarying vec3 vAnim;\n' + shader.fragmentShader;
        shader.fragmentShader = shader.fragmentShader.replace('#include <map_fragment>',
          '#ifdef USE_MAP\n'
          + '  float afN = max(vAnim.y, 1.0);\n'
          + '  float afI = mod(floor(uTime * vAnim.z), afN);\n'
          + '  vec2 aUv = vec2((afI + fract(vUv.x)) / uAnimGrid.x, (vAnim.x + fract(vUv.y)) / uAnimGrid.y);\n'
          + '  diffuseColor *= texture2D(animMap, aUv);\n'
          + '#endif');
      };
    }
    voxAtlas = atlas; voxTints = tints; voxModels = bmodels;
    voxMat = mat; voxWaterMat = wmat;
    voxMatBg = null;
    voxWaterMatBg = null;
    atlasTex = tex; animTex = anim;
    return true;
  }

  /**
   * Build one LOD level over a window, as tiles of 10x10 *virtual* chunks. A virtual chunk is 2^lod
   * real chunks and is already assembled server-side into the mesher's native 16x16-cell shape, so
   * each tile is meshed by the normal voxel mesher and the whole group is scaled by 2^lod. Nothing in
   * the mesher has to know about LOD.
   */
  async function buildVoxelLevel(world, lod, vx0, vz0, vx1, vz1, bg, exclude, faceBudget) {
    const f = 1 << lod;
    const budget = faceBudget || MAX_LEVEL_FACES;
    if (lod === 0) {
      // Prerendered tiles sit on a fixed N_CHUNKS grid; snap the window onto it so we can fetch them.
      vx0 = Math.floor(vx0 / N_CHUNKS) * N_CHUNKS;
      vz0 = Math.floor(vz0 / N_CHUNKS) * N_CHUNKS;
      vx1 = (Math.floor(vx1 / N_CHUNKS) + 1) * N_CHUNKS - 1;
      vz1 = (Math.floor(vz1 / N_CHUNKS) + 1) * N_CHUNKS - 1;
    }
    const group = new THREE.Group();
    scene.add(group);
    const tileList = [];
    for (let tx = vx0; tx <= vx1; tx += N_CHUNKS) {
      for (let tz = vz0; tz <= vz1; tz += N_CHUNKS) {
        tileList.push([tx, tz]);
      }
    }
    // Nearest tiles first, so if the face budget binds it drops the far edge rather than the ground
    // under the camera.
    const midTx = (vx0 + vx1) / 2, midTz = (vz0 + vz1) / 2;
    tileList.sort((a, b) => (Math.abs(a[0] - midTx) + Math.abs(a[1] - midTz))
                          - (Math.abs(b[0] - midTx) + Math.abs(b[1] - midTz)));
    const loaded = new Set();
    let lastProblem = '';
    const tileCells = N_CHUNKS * 16;              // 160 cells per tile side
    const tileBlocks = tileCells * f;             // ...which is this many blocks at this level
    let cells = 0, faces = 0, tiles = 0;
    let next = 0;
    const workers = Math.max(1, Math.min(3, tileList.length));
    const runWorker = async () => {
      for (;;) {
        const i = next++;
        if (i >= tileList.length) return;
        if (faces >= budget) { lastProblem = 'face budget reached'; return; }
        const tx = tileList[i][0], tz = tileList[i][1];
        // Coarse levels are a RING around the finer level still on screen: never draw over it, or the
        // near field is covered with low-detail geometry that carries no per-chunk biome tint.
        if (exclude) {
          const rx0 = tx * 16 * f, rz0 = tz * 16 * f;
          const rx1 = (tx + N_CHUNKS) * 16 * f, rz1 = (tz + N_CHUNKS) * 16 * f;
          if (rx0 >= exclude.cx0 * 16 && rx1 <= (exclude.cx1 + 1) * 16
              && rz0 >= exclude.cz0 * 16 && rz1 <= (exclude.cz1 + 1) * 16) continue;
        }
        // lod 0 uses prerendered tiles when they exist (no client meshing); otherwise fall back to
        // the live mesher so brand-new chunks still show immediately.
        if (lod === 0) {
          const gtx = tx / N_CHUNKS, gtz = tz / N_CHUNKS;
          try {
            const resp = await fetch(`/tiles/${encodeURIComponent(world)}/mesh/lod0/${gtx}_${gtz}.gtmesh?v=${TILES_V}`);
            if (resp.ok) {
              const bytes = await inflateRaw(new Uint8Array(await resp.arrayBuffer()));
              const t = parseTile(bytes);
              const geo = geoFromBucket(t.opaque);
              const g = new THREE.Group();
              g.add(new THREE.Mesh(geo, voxMat));
              if (t.water.pos.length && voxWaterMat) g.add(new THREE.Mesh(geoFromBucket(t.water), voxWaterMat));
              g.scale.setScalar(f);
              g.position.set((tx + N_CHUNKS / 2) * 16 * f, t.baseY * f, (tz + N_CHUNKS / 2) * 16 * f);
              g.userData.bbox = { x0: tx * 16 * f, z0: tz * 16 * f,
                                  x1: (tx + N_CHUNKS) * 16 * f, z1: (tz + N_CHUNKS) * 16 * f };
              group.add(g);
              faces += (geo.index ? geo.index.count : geo.getAttribute('position').count) / 6;
              tiles++;
              loaded.add(tx + ',' + tz);
              if (bg) hideCoveredVoxTiles(bg, loaded, tileBlocks);
              setStatus('busy', `lod ${lod} (${f}m blocks) \u00b7 ${tiles}/${tileList.length} tiles \u00b7 `
                + `${faces.toLocaleString()} faces`);
              continue;
            }
          } catch (e) { /* fall back to the live mesher below */ }
        }
        const tx1 = tx + N_CHUNKS - 1, tz1 = tz + N_CHUNKS - 1;
        let res = null;
        try {
          res = await (await fetch(`/api/voxels?world=${encodeURIComponent(world)}&lod=${lod}`
            + `&cx0=${tx}&cz0=${tz}&cx1=${tx1}&cz1=${tz1}`)).json();
        } catch (e) { res = null; lastProblem = String(e && e.message || e); }
        if (!res) { if (!lastProblem) lastProblem = 'request failed'; }
        else if (res.error) { lastProblem = String(res.error); }
        else if (!res.chunks || !res.chunks.length) { lastProblem = 'server has no ' + f + 'm blocks here'; }
        if (res && res.chunks && res.chunks.length) {
          for (const c of res.chunks) {
            const buf = await inflated(c.data);
            const v = parseVoxel(buf);
            c._pal = v.pal;
            c._cols = v.cols;
          }
          const r = buildGeometry(res, voxAtlas, voxTints, worldMinY >> lod, tx, tz, voxModels);
          const g = new THREE.Group();
          g.add(new THREE.Mesh(r.geo, voxMat));
          if (r.waterGeo.getAttribute('position').count && voxWaterMat) {
            g.add(new THREE.Mesh(r.waterGeo, voxWaterMat));
          }
          // the mesher lays the tile out in cells and centres it on the area; scale up to blocks
          g.scale.setScalar(f);
          g.position.set((tx + N_CHUNKS / 2) * 16 * f, r.baseY * f, (tz + N_CHUNKS / 2) * 16 * f);
          g.userData.bbox = { x0: tx * 16 * f, z0: tz * 16 * f,
                              x1: (tx + N_CHUNKS) * 16 * f, z1: (tz + N_CHUNKS) * 16 * f };
          group.add(g);
          const n = (r.geo.index ? r.geo.index.count : r.geo.getAttribute('position').count) / 6;
          faces += n;
          tiles++;
          loaded.add(tx + ',' + tz);
          if (bg) hideCoveredVoxTiles(bg, loaded, tileBlocks);
          setStatus('busy', `lod ${lod} (${f}m blocks) \u00b7 ${tiles}/${tileList.length} tiles \u00b7 `
            + `${faces.toLocaleString()} faces`);
        }
      }
    };
    await Promise.all(Array.from({ length: workers }, runWorker));
    return { group, tiles, faces, tileBlocks, problem: lastProblem };
  }

  /** Hide background tiles the new level now fully covers (same grid arithmetic as the heightfield). */
  function hideCoveredVoxTiles(bg, loaded, fineBlocks) {
    for (const m of bg.children) {
      if (m.visible === false) continue;
      const b = m.userData && m.userData.bbox;
      if (!b) continue;
      let covered = true;
      for (let tx = Math.floor(b.x0 / fineBlocks); tx <= Math.floor((b.x1 - 1) / fineBlocks) && covered; tx++) {
        for (let tz = Math.floor(b.z0 / fineBlocks); tz <= Math.floor((b.z1 - 1) / fineBlocks); tz++) {
          if (!loaded.has(tx + ',' + tz)) { covered = false; break; }
        }
      }
      if (covered) m.visible = false;
    }
  }

  /**
   * Build the terrain for the current zoom: a fine lod-0 island in the middle plus one coarser
   * backdrop level filling the distance. Everything is real blocks - the only
   * thing that changes with zoom is how big a block is - so there is no separate "world map" to fall
   * back to. The previous level stays on screen as the backdrop, and its tiles are hidden where the
   * new level covers them.
   */
  async function buildWorldTier(world, cx0, cz0, cx1, cz1, frame) {
    const spanChunks = Math.max(cx1 - cx0, cz1 - cz0);
    const tier = tierFor(spanChunks);
    const lod = tier.lod, f = 1 << lod;
    if (!(await ensureVoxelAssets())) { setStatus('error', '3D library not loaded', 'retry', retryOpen); return; }

    // Cover what is on screen, capped so an extreme zoom-out cannot ask for an absurd number of tiles.
    // Cap the window in REAL chunks too: the level's own data is what costs, and asking a server to
    // cover thousands of chunks on the fly would stall. 256 real chunks a side is 4096 blocks, which
    // is the far edge of the loaded view.
    const winReal = Math.min(spanChunks, VOXEL_MAX_REAL_CHUNKS);
    // lod 1 gets a wider window than lod 0: its tiles are 4x the area, so it can reach the same 256
    // real chunks with ~1/4 the tiles - which is what lets the low-res ring fill the screen.
    const winCap = lod === 0 ? VOXEL_MAX_VCHUNKS : VOXEL_MAX_VCHUNKS_LOD1;
    const winV = Math.max(2, Math.min(Math.ceil(winReal / f), winCap));
    const midVx = Math.round(((cx0 + cx1) / 2) / f), midVz = Math.round(((cz0 + cz1) / 2) / f);
    const vx0 = midVx - Math.floor(winV / 2), vz0 = midVz - Math.floor(winV / 2);
    const vx1 = vx0 + winV - 1, vz1 = vz0 + winV - 1;

    const bbox = { cx0: vx0 * f, cz0: vz0 * f, cx1: (vx1 + 1) * f - 1, cz1: (vz1 + 1) * f - 1 };

    // Fine island in the middle: at a coarse tier the tier's own level is only
    // a backdrop for the distance, and real blocks stay where you are actually looking. Sized to a
    // share of the view and face-capped, so a wide view drops it once fine blocks would cover too
    // little of the screen to justify their cost.
    let coreWin = null;
    if (lod > 0 && spanChunks <= CORE_DROP_SPAN) {
      const n = Math.max(N_CHUNKS, Math.min(CORE_MAX_CHUNKS, Math.round(spanChunks * CORE_SPAN_FRACTION)));
      const midCx = (cx0 + cx1) / 2, midCz = (cz0 + cz1) / 2;
      const cc0 = Math.floor((midCx - n / 2) / N_CHUNKS) * N_CHUNKS;
      const cd0 = Math.floor((midCz - n / 2) / N_CHUNKS) * N_CHUNKS;
      coreWin = { cx0: cc0, cz0: cd0, cx1: cc0 + N_CHUNKS - 1, cz1: cd0 + N_CHUNKS - 1 };
    }
    const coreMoved = coreWin
      ? (!coreLevel || coreLevel.cx0 !== coreWin.cx0 || coreLevel.cz0 !== coreWin.cz0)
      : !!coreGroup;
    if (coreMoved) {
      if (coreWin) {
        const cr = await buildVoxelLevel(world, 0, coreWin.cx0, coreWin.cz0, coreWin.cx1, coreWin.cz1,
                                         null, null, CORE_FACE_BUDGET);
        if (cr.tiles) {
          disposeGroup(coreGroup);
          coreGroup = cr.group;
          coreLevel = { cx0: coreWin.cx0, cz0: coreWin.cz0, cx1: coreWin.cx1, cz1: coreWin.cz1 };
        } else {
          disposeGroup(cr.group);
        }
      } else {
        disposeGroup(coreGroup); coreGroup = null; coreLevel = null;
      }
    }

    if (frame) {
      // frame the middle of the window; zooming out is how you leave, so start where the detail is
      const span = Math.max(cx1 - cx0, cz1 - cz0) * 16;
      const ctr = new THREE.Vector3(((cx0 + cx1) / 2) * 16, 80, ((cz0 + cz1) / 2) * 16);
      // No scene fog: it was set once from the opening span and never updated, so zooming out closed
      // it over the whole world and dimmed everything. Colour stays true at every distance instead.
      const fitDist = (span * 0.5) / Math.tan((camera.fov / 2) * Math.PI / 180);
      camera.position.copy(ctr).addScaledVector(new THREE.Vector3(0.32, 0.55, 0.32).normalize(), fitDist * 0.8);
      controls.target.copy(ctr);
      controls.minDistance = 8;
      controls.maxDistance = 400000;
      controls.update();
    }

    const prevLevel = worldLevel;
    // Demote the active level to the backdrop, dropping any older one EVERY time. Keeping them
    // around leaked a whole level of geometry per zoom step until the tab ran out of memory.
    if (worldGroup) {
      disposeGroup(backdropGroup);
      backdropGroup = worldGroup;
      backdropGroup.position.y = -1;   // one block lower so coincident surfaces cannot z-fight
    }
    const bg = backdropGroup;
    // The coarse level is a backdrop RING: skip whatever the fine middle already covers.
    const exclude = coreLevel
      ? { cx0: coreLevel.cx0, cz0: coreLevel.cz0, cx1: coreLevel.cx1, cz1: coreLevel.cz1 }
      : null;
    const r = await buildVoxelLevel(world, lod, vx0, vz0, vx1, vz1, bg, exclude,
                                    coreLevel ? MAX_LEVEL_FACES - CORE_FACE_BUDGET : undefined);
    if (!r.tiles) {
      scene.remove(r.group);
      clearOverlays();   // nothing new to outline, so drop last level's
      const why = 'no block data here yet \u00b7 lod ' + lod + ' \u00b7 ' + (r.problem || 'unknown');
      // the way out depends on whether a coarser level is left to try
      if (lod === 0) setStatus('empty', why, 'zoom out', zoomOutStep);
      else setStatus('empty', why, '2D map', close);
      return;
    }
    for (const g of r.group.children) {
      if (!g.userData.bbox) {
        g.userData.bbox = { x0: 0, z0: 0, x1: 0, z1: 0 };
      }
    }
    // The new level plus the fine core cover the visible window, so the previous level is redundant:
    // drop it now. Keeping it is what left a coarse lod 1 hanging around when zooming in.
    if (backdropGroup) { disposeGroup(backdropGroup); backdropGroup = null; }
    worldGroup = r.group;
    worldLevel = { key: tier.key, lod, cx0: bbox.cx0, cz0: bbox.cz0, cx1: bbox.cx1, cz1: bbox.cz1 };
    setStatus('info', `lod ${lod} \u00b7 ${f}m blocks \u00b7 ${r.tiles} tiles \u00b7 `
      + `${r.faces.toLocaleString()} faces \u00b7 biomes `
      + (lastBiomeStats ? `${lastBiomeStats.withBiome}/${lastBiomeStats.chunks}` : '?')
      + ` \u00b7 tints ${lastBiomeStats ? lastBiomeStats.tintKeys : '?'}`);
    // Overlays track the level that is actually on screen, so they are rebuilt here: this runs for
    // both the first load and every zoom-driven rebuild.
    refreshOverlays();
  }

  /**
   * Rebuild the terrain at the resolution the current zoom calls for.
   *
   * Driven by OrbitControls' 'change' event with a short trailing timer, rather than polled from the
   * render loop: the rebuild is a main-thread job, so only one runs at a time, and only once the
   * camera has stopped moving. Polling tied the timing to the frame rate, which starved on slow
   * machines (and a mid-gesture rebuild was what made the map hitch).
   */
  function scheduleWorldRefine() {
    if (!worldView) return;
    refineChanges++;
    clearTimeout(refineTimer);
    refineTimer = setTimeout(refineWorldNow, 450);
  }

  async function refineWorldNow() {
    refineTries++;
    if (!worldView) { refineWhy = 'notworld'; return; }
    if (refineBusy) { refineWhy = 'busy'; return; }
    if (worldLoading) { refineWhy = 'loading'; return; }
    if (!worldGroup) { refineWhy = 'nogroup'; return; }
    if (!worldBounds) { refineWhy = 'nobounds'; return; }
    if (!camera || !controls) { refineWhy = 'nocam'; return; }

    const dist = camera.position.distanceTo(controls.target);
    const visBlocks = 2 * dist * Math.tan((camera.fov / 2) * Math.PI / 180) * 1.4;
    const tier = tierFor(Math.max(8, visBlocks / 16));
    // chunks the mesh should span at this resolution so the window covers the view
    // Load a margin beyond the view: with none, the window edge sits exactly at the screen edge, so new
    // terrain only started loading once it was already a large part of the screen.
    const half = Math.max(6, (visBlocks / 32) * 1.6); // real chunks across the view, half going each way

    const tx = controls.target.x / 16, tz = controls.target.z / 16;
    const b = worldBounds;
    const hx = Math.min(half, (b.maxCx - b.minCx) / 2), hz = Math.min(half, (b.maxCz - b.minCz) / 2);
    const cxc = Math.min(Math.max(tx, b.minCx + hx), b.maxCx - hx);
    const czc = Math.min(Math.max(tz, b.minCz + hz), b.maxCz - hz);
    const cx0 = Math.round(cxc - hx), cx1 = Math.round(cxc + hx);
    const cz0 = Math.round(czc - hz), cz1 = Math.round(czc + hz);

    // nothing to do when the resolution is unchanged and the current mesh still covers the view
    const same = worldLevel && worldLevel.key === tier.key;
    const covers = worldLevel && cx0 >= worldLevel.cx0 && cx1 <= worldLevel.cx1
      && cz0 >= worldLevel.cz0 && cz1 <= worldLevel.cz1;
    if (same && covers) { refineWhy = 'uptodate'; window.GT3D_READY = true; return; }
    refineWhy = 'rebuild ' + tier.key;

    refineBusy = true;
    worldLoading = true;
    try {
      await buildWorldTier(lastCenter.world, cx0, cz0, cx1, cz1, false);
    } catch (e) { refineLastErr = String(e && e.message || e); }
    worldLoading = false;
    refineBusy = false;
    refineDone++;
    window.GT3D_READY = true;
  }

  /** Structures + waypoints + live players, in absolute block coordinates (matches the world mesh). */
  function addWorldMarkers() {
    const pts = [];
    if (window.GT && window.GT.getStructures) {
      for (const s of window.GT.getStructures()) {
        pts.push({ x: s.x, y: s.y, z: s.z, kind: 'structure', label: s.type });
      }
    }
    if (window.GT && window.GT.getWaypoints) {
      for (const w of window.GT.getWaypoints()) {
        pts.push({ x: w.x, y: w.y, z: w.z, kind: 'waypoint', label: w.name + (w.public ? ' (public)' : '') });
      }
    }
    if (window.GT && window.GT.getPlayers) {
      for (const p of window.GT.getPlayers()) {
        if (p.world !== lastCenter.world) continue;
        pts.push({ x: p.x, y: (p.y || 64), z: p.z, kind: 'player', label: p.name });
      }
    }
    if (!pts.length) return;
    // drop the previous markers first: rebuilding them on every open without this leaked a whole
    // Points geometry + material per view
    if (markerPoints) { scene.remove(markerPoints); markerPoints.geometry.dispose(); markerPoints.material.dispose(); }
    markerPoints = new THREE.Points(
      new THREE.BufferGeometry().setFromPoints(
        pts.map((p) => new THREE.Vector3(p.x, p.y * WORLD_YSCALE + 3, p.z))),
      new THREE.PointsMaterial({ color: 0xffd25c, size: 8, sizeAttenuation: false, depthTest: true }));
    markerPoints.userData.markers = pts.map((p) => ({
      s: { type: p.label, x: Math.round(p.x), y: Math.round(p.y), z: Math.round(p.z) },
      underground: false, kind: p.kind,
    }));
    scene.add(markerPoints);
  }

  /**
   * Slime chunks as translucent green plates laid on the surface, outlined so they still read over
   * grass. A slime chunk is a pure function of the world seed, so no data is stored for it; only the
   * surface height comes from the server, as one sample per chunk from /api/pixels.
   */
  async function addSlimePlates(cx0, cz0, cx1, cz1, seq) {
    if (!window.GT || !window.GT.isSlime) return;
    // Never past the level's own window: that is where terrain exists, and a plate with no ground
    // under it would hang in the background. Inside that, cap the request to the server's cell limit,
    // centred, so the plates cover what is being looked at.
    const half = SLIME_MAX_CHUNKS >> 1;
    const mx = (cx0 + cx1) >> 1, mz = (cz0 + cz1) >> 1;
    const ax0 = Math.max(cx0, mx - half), ax1 = Math.min(cx1, mx + half - 1);
    const az0 = Math.max(cz0, mz - half), az1 = Math.min(cz1, mz + half - 1);
    let buf;
    try {
      const j = await (await fetch(`/api/pixels?world=${encodeURIComponent(lastCenter.world)}`
        + `&cx0=${ax0}&cz0=${az0}&cx1=${ax1}&cz1=${az1}&px=16&deflate=1`)).json();
      if (!j || !j.data) return;
      buf = await inflated(j.data);
    } catch (e) { return; }
    if (seq !== overlaySeq) return;                 // a newer refresh has already taken over
    const d = new DataView(buf);
    const ox = d.getInt16(0), oz = d.getInt16(2), cols = d.getInt32(6), rows = d.getInt32(10);
    const verts = [], edges = [];
    for (let r = 0; r < rows; r++) {
      for (let c = 0; c < cols; c++) {
        const cell = 14 + (r * cols + c) * 7;
        const y = d.getInt16(cell);
        if (y === -32768) continue;                 // server has no surface for that chunk
        const cx = ox + c, cz = oz + r;             // at one sample per chunk a cell IS a chunk
        if (!window.GT.isSlime(cx, cz)) continue;
        const x0 = cx * 16, z0 = cz * 16, x1 = x0 + 16, z1 = z0 + 16, yy = y + 1;
        verts.push(x0, yy, z0, x1, yy, z0, x1, yy, z1, x0, yy, z0, x1, yy, z1, x0, yy, z1);
        edges.push(x0, yy, z0, x1, yy, z0, x1, yy, z0, x1, yy, z1,
                   x1, yy, z1, x0, yy, z1, x0, yy, z1, x0, yy, z0);
      }
    }
    if (!verts.length) return;
    if (!overlayGroup) { overlayGroup = new THREE.Group(); scene.add(overlayGroup); }
    const fill = new THREE.BufferGeometry();
    fill.setAttribute('position', new THREE.Float32BufferAttribute(verts, 3));
    overlayGroup.add(new THREE.Mesh(fill, new THREE.MeshBasicMaterial({
      color: 0x3ce65a, transparent: true, opacity: 0.28, depthWrite: false, side: THREE.DoubleSide,
    })));
    const outline = new THREE.BufferGeometry();
    outline.setAttribute('position', new THREE.Float32BufferAttribute(edges, 3));
    overlayGroup.add(new THREE.LineSegments(outline, new THREE.LineBasicMaterial({
      color: 0x3ce65a, transparent: true, opacity: 0.85,
    })));
  }

  /**
   * Rebuild the runtime overlays for whatever window is currently on screen.
   *
   * Async because the slime plates need surface heights from the server: overlaySeq makes the newest
   * refresh the only one allowed to add geometry, so a slow response cannot land on a later view.
   */
  async function refreshOverlays() {
    const seq = ++overlaySeq;
    clearOverlays();
    if (worldLevel) {
      addStructureBoxes(worldLevel.cx0, worldLevel.cz0, worldLevel.cx1, worldLevel.cz1);
      if (window.GT && window.GT.showSlime && window.GT.showSlime()
          && window.GT.canShowSlime && window.GT.canShowSlime()) {
        await addSlimePlates(worldLevel.cx0, worldLevel.cz0, worldLevel.cx1, worldLevel.cz1, seq)
          .catch(() => {});   // a bad payload just means no plates, never a broken view
      }
    } else if (lastVoxWindow) {
      addStructureBoxes(lastVoxWindow.cx0, lastVoxWindow.cz0,
                        lastVoxWindow.cx0 + VOXEL_CHUNKS - 1, lastVoxWindow.cz0 + VOXEL_CHUNKS - 1);
    }
  }

  /** Drop the previous overlays' geometry; they are rebuilt per level. */
  function clearOverlays() {
    if (!overlayGroup) return;
    for (const c of overlayGroup.children.slice()) {
      overlayGroup.remove(c);
      if (c.geometry) c.geometry.dispose();
      if (c.material) c.material.dispose();
    }
  }

  /**
   * Structure outlines for the visible window, as line geometry - the same boxes the 2D map strokes,
   * following the same toggle and colour. Drawn at runtime rather than baked into the tiles: a
   * structure can be discovered at any time, and a baked box would go stale the moment it is.
   */
  function addStructureBoxes(cx0, cz0, cx1, cz1) {
    if (!window.GT || !window.GT.getStructures || !window.GT.showBboxes || !window.GT.showBboxes()) return;
    const structs = window.GT.getStructures() || [];
    if (!structs.length) return;
    if (!overlayGroup) { overlayGroup = new THREE.Group(); scene.add(overlayGroup); }
    const verts = [];
    const EDGES = [[0,1],[1,2],[2,3],[3,0],[4,5],[5,6],[6,7],[7,4],[0,4],[1,5],[2,6],[3,7]];
    for (const s of structs) {
      if (!s || s.minX === undefined) continue;                   // nearest-N shape: no box to draw
      if (s.maxX < cx0 * 16 || s.minX > (cx1 + 1) * 16) continue;  // outside the visible window
      if (s.maxZ < cz0 * 16 || s.minZ > (cz1 + 1) * 16) continue;
      const x0 = s.minX, x1 = s.maxX + 1, y0 = s.minY, y1 = s.maxY + 1;
      const z0 = s.minZ, z1 = s.maxZ + 1;
      const c = [
        [x0, y0, z0], [x1, y0, z0], [x1, y0, z1], [x0, y0, z1],
        [x0, y1, z0], [x1, y1, z0], [x1, y1, z1], [x0, y1, z1],
      ];
      for (const [a, b] of EDGES) verts.push(...c[a], ...c[b]);
    }
    if (!verts.length) return;
    const geo = new THREE.BufferGeometry();
    geo.setAttribute('position', new THREE.Float32BufferAttribute(verts, 3));
    overlayGroup.add(new THREE.LineSegments(geo, new THREE.LineBasicMaterial({
      color: new THREE.Color(window.GT.bboxColor ? window.GT.bboxColor() : '#ffd25c'),
      transparent: true, opacity: 0.6,
    })));
  }

  async function addMarkers(vox, baseY, cx0, cz0) {
    const structs = (window.GT && window.GT.getStructures) ? window.GT.getStructures() : [];
    const bbox = new Map();
    for (const c of vox.chunks) bbox.set(c.cx + ',' + c.cz, c);
    const pts = [];
    for (const s of structs) {
      const scx = Math.floor(s.x / 16), scz = Math.floor(s.z / 16);
      const lx = (scx - cx0) * 16 + 8, lz = (scz - cz0) * 16 + 8;
      if (lx < 0 || lz < 0 || lx >= N_CHUNKS * 16 || lz >= N_CHUNKS * 16) continue;
      // pin to the local surface: highest solid block near the structure centre
      let surf = s.y;
      const c = bbox.get(scx + ',' + scz);
      if (c) {
        let hi = baseY;
        for (const [y0, len] of c._cols[(lz % 16) * 16 + (lx % 16)]) hi = Math.max(hi, y0 + len - 1);
        surf = hi;
      }
      pts.push({ pos: new THREE.Vector3(lx - N_CHUNKS * 8, surf - baseY, lz - N_CHUNKS * 8), s, underground: s.y < surf - 3 });
    }
    if (pts.length) {
      if (markerPoints) { scene.remove(markerPoints); markerPoints.geometry.dispose(); markerPoints.material.dispose(); }
      markerPoints = new THREE.Points(
        new THREE.BufferGeometry().setFromPoints(pts.map((p) => p.pos)),
        new THREE.PointsMaterial({ color: 0xffd25c, size: 12, sizeAttenuation: true }));
      markerPoints.userData.markers = pts;
      scene.add(markerPoints);
    }
    lastVoxWindow = { cx0, cz0 };
    refreshOverlays();
  }

  // Arrow keys / WASD move the view in the horizontal plane (Q/E for up-down). Speed scales with
  // how far the camera is from its target so it feels right at any zoom.
  function onKey(e) {
    if (!controls || !camera) return;
    const tag = (e.target && e.target.tagName) || '';
    if (tag === 'INPUT' || tag === 'TEXTAREA') return;
    const step = camera.position.distanceTo(controls.target) * (e.shiftKey ? 0.15 : 0.045);
    const fwd = new THREE.Vector3().subVectors(controls.target, camera.position);
    fwd.y = 0;
    if (fwd.lengthSq() < 1e-6) fwd.set(0, 0, -1);
    fwd.normalize();
    const right = new THREE.Vector3().crossVectors(fwd, new THREE.Vector3(0, 1, 0)).normalize();
    const delta = new THREE.Vector3();
    switch (e.key) {
      case 'ArrowUp': case 'w': case 'W': delta.addScaledVector(fwd, step); break;
      case 'ArrowDown': case 's': case 'S': delta.addScaledVector(fwd, -step); break;
      case 'ArrowLeft': case 'a': case 'A': delta.addScaledVector(right, -step); break;
      case 'ArrowRight': case 'd': case 'D': delta.addScaledVector(right, step); break;
      case 'e': case 'E': case 'PageUp': delta.y += step; break;
      case 'q': case 'Q': case 'PageDown': delta.y -= step; break;
      default: return;
    }
    e.preventDefault();
    camera.position.add(delta);
    controls.target.add(delta);
    controls.update();
  }

  function onResize() {
    if (!renderer) return;
    camera.aspect = innerWidth / innerHeight;
    camera.updateProjectionMatrix();
    renderer.setSize(innerWidth, innerHeight);
  }

  function animate() {
    raf = requestAnimationFrame(animate);
    waterTime.value = performance.now() / 1000;
    if (controls) controls.update();
    if (renderer && scene && camera) renderer.render(scene, camera);
  }

  function close() {
    if (raf) cancelAnimationFrame(raf);
    raf = null;
    window.removeEventListener('resize', onResize);
    window.removeEventListener('keydown', onKey);
    if (refineTimer) { clearTimeout(refineTimer); refineTimer = null; }
    // Take the 3D layer down FIRST so the 2D map always comes back, even if a three.js/WebGL
    // dispose throws underneath us. (The canvas used to come off at the very end, so one throwing
    // dispose left the 3D view stuck on screen and the "2D map" button looking dead.)
    if (canvas) { canvas.remove(); canvas = null; }
    if (closeBtn) { closeBtn.remove(); closeBtn = null; }
    if (depthBtn) { depthBtn.remove(); depthBtn = null; }
    if (worldBtn) { worldBtn.remove(); worldBtn = null; }
    if (islandsBtn) { islandsBtn.remove(); islandsBtn = null; }
    if (statusEl) { statusEl.remove(); statusEl = null; }
    if (popupEl) { popupEl.remove(); popupEl = null; }
    if (panelEl) { panelEl.remove(); panelEl = null; }
    bboxToggle = null; bboxColor = null; lastVoxWindow = null; slimeToggle = null;
    try {
      if (atlasTex) { atlasTex.dispose(); atlasTex = null; }
      if (animTex) { animTex.dispose(); animTex = null; }
      disposeGroup(backdropGroup);
      backdropGroup = null;
      disposeWorldGroup();
      disposeVoxelGroup();
      if (overlayGroup) { disposeGroup(overlayGroup); overlayGroup = null; }
      if (worldMat) { worldMat.dispose(); worldMat = null; }
      if (renderer) renderer.dispose();
      if (controls) controls.dispose();
    } catch (e) {
      console.warn('GT3D close: dispose failed (UI already removed)', e);
    }
    worldLevel = null; worldBounds = null; refineBusy = false; worldLoading = false;
    renderer = scene = camera = controls = markerPoints = null;
    downXY = null;
    // tell the 2D map it can repaint now that the 3D view is gone
    try { if (window.GT && window.GT.on3DClose) window.GT.on3DClose(); } catch (e) { /* ignore */ }
  }

  window.GT3D = {
    open, close,
    isOpen: () => !!canvas,
    markerCount: () => (markerPoints ? markerPoints.userData.markers.length : 0),
    popupOpen: () => !!popupEl && popupEl.style.display === 'block',
    // test/tuning hook: place the camera at a given distance from its target (fires 'change', so
    // the LOD refine runs exactly as it would after a real zoom gesture)
    setDistance: (d) => {
      if (!camera || !controls) return;
      const dir = new THREE.Vector3().subVectors(camera.position, controls.target).normalize();
      camera.position.copy(controls.target).addScaledVector(dir, d);
      controls.update();
    },
    // Aim the camera at a world point for automation/screenshots: {x,y,z,dist,yaw,pitch}
    setView: (v) => {
      if (!camera || !controls) return false;
      const t = new THREE.Vector3(v.x || 0, v.y || 64, v.z || 0);
      controls.target.copy(t);
      const d = Math.max(1, v.dist || 300);
      const yaw = ((v.yaw != null ? v.yaw : 45) * Math.PI) / 180;
      const pitch = ((v.pitch != null ? v.pitch : 30) * Math.PI) / 180;
      camera.position.copy(t).add(new THREE.Vector3(
        Math.sin(yaw) * Math.cos(pitch), Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch)
      ).multiplyScalar(d));
      controls.update();
      refineWorldNow();
      return true;
    },
    // run the LOD refine immediately (tuning/testing hook - normally it is debounced after a gesture)
    refine: () => refineWorldNow(),
    // lightweight introspection (used by the deploy smoke test and for tuning the LOD ladder)
    debug: () => ({
      dist: (camera && controls) ? Math.round(camera.position.distanceTo(controls.target)) : null,
      level: worldLevel ? worldLevel.key : null,
      worldView,
      tries: refineTries, done: refineDone, busy: refineBusy, why: refineWhy,
      changes: refineChanges, group: !!worldGroup, vox: !!voxelGroup, err: refineLastErr,
    }),
  };

  // URL-driven 3D view for automation/screenshots (point a browser here, then capture the canvas):
  //   /?view3d=1&world=world&x=<blockX>&y=<blockY>&z=<blockZ>&dist=<blocks>&yaw=<deg>&pitch=<deg>
  // window.GT3D_READY flips true once the LOD ladder has settled - poll it before screenshotting.
  (function () {
    const sp = new URLSearchParams(location.search);
    if (!sp.get('view3d')) return;
    const bx = parseFloat(sp.get('x') || '0'), bz = parseFloat(sp.get('z') || '0');
    const view = {
      x: bx, y: parseFloat(sp.get('y') || '70'), z: bz,
      dist: parseFloat(sp.get('dist') || '300'),
      yaw: parseFloat(sp.get('yaw') || '45'),
      pitch: parseFloat(sp.get('pitch') || '30'),
    };
    const world = sp.get('world') || 'world';
    window.GT3D_READY = false;
    setTimeout(async () => {
      try {
        await GT3D.open(world, Math.floor(bx / 16), Math.floor(bz / 16));
        GT3D.setView(view);
      } catch (e) { /* leave the default view */ }
    }, 400);
  })();
})();
