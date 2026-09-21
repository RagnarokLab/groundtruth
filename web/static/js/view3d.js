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
  let atlasTex = null, animTex = null, depthBtn = null, worldBtn = null, islandsBtn = null;
  const waterTime = { value: 0 }; // seconds; drives the animated water frames
  let includeUnderground = true;  // toggle: surface-only (false) vs all the way down to bedrock (true)
  let lastCenter = null;
  let worldView = false;          // world mode: coarse whole-map relief instead of full voxels
  let worldStep = 4;              // chunk decimation in world mode (4 -> 64-block cells)
  const WORLD_YSCALE = 1;         // TRUE proportions - GroundTruth shows the world as it actually is
  let worldIslands = true;        // draw sky islands as real floating geometry
  const VOXEL_MAX_VCHUNKS = 70;   // cap on the window, in virtual chunks (7x7 tiles of 10)
  const VOXEL_MAX_REAL_CHUNKS = 256; // cap on the window in real chunks (4096 blocks across)
  const VOXEL_CHUNKS = 12;        // chunks per side of the real-block window
  const TILE_CELLS = 256;         // cells per tile side (~65k cells, still only a few ms to mesh).
                                  // Bigger tiles mean far fewer requests, which matters much more than
                                  // mesh time on a slow link.
  let worldGroup = null;          // THREE.Group holding the terrain tiles for the active level
  let backdropGroup = null;      // at most ONE previous level, kept behind the active one
  let worldLevel = null;          // { key, cell, cx0, cz0, cx1, cz1 } that group covers
  let worldBounds = null;         // world chunk bounds (from a cheap probe request)
  let worldMat = null;
  let worldLoading = false;
  let voxelGroup = null;          // the real-block mesh that takes over once you are close enough
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
    let o = 4;                                            // "GTM1"
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
        b.atile.push(d.getUint8(o) / 255, d.getUint8(o + 1) / 255,
          d.getUint8(o + 2) / 255, d.getUint8(o + 3) / 255); o += 4;
        if (withAanim) { b.aanim.push(d.getFloat32(o, true), d.getFloat32(o + 4, true), d.getFloat32(o + 8, true)); o += 12; }
      }
      return b;
    };
    const opaque = read(false);
    const water = hasWater ? read(true) : { pos: [], uv: [], col: [], nor: [], atile: [], aanim: null };
    return { opaque, water, baseY };
  }

  /** BufferGeometry from a mesher bucket - the same attributes the live mesher emits. */
  function geoFromBucket(b) {
    const g = new THREE.BufferGeometry();
    g.setAttribute('position', new THREE.Float32BufferAttribute(b.pos, 3));
    g.setAttribute('uv', new THREE.Float32BufferAttribute(b.uv, 2));
    g.setAttribute('color', new THREE.Float32BufferAttribute(b.col, 3));
    g.setAttribute('normal', new THREE.Float32BufferAttribute(b.nor, 3));
    g.setAttribute('atile', new THREE.Float32BufferAttribute(b.atile, 4));
    if (b.aanim) g.setAttribute('aanim', new THREE.Float32BufferAttribute(b.aanim, 3));
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

    const makeGeo = (b) => {
      const g = new THREE.BufferGeometry();
      g.setAttribute('position', new THREE.Float32BufferAttribute(b.pos, 3));
      g.setAttribute('uv', new THREE.Float32BufferAttribute(b.uv, 2));
      g.setAttribute('color', new THREE.Float32BufferAttribute(b.col, 3));
      g.setAttribute('normal', new THREE.Float32BufferAttribute(b.nor, 3));
      g.setAttribute('atile', new THREE.Float32BufferAttribute(b.atile, 4));
      if (b.aanim) g.setAttribute('aanim', new THREE.Float32BufferAttribute(b.aanim, 3));
      return g;
    };
    return { geo: makeGeo(opaque), waterGeo: makeGeo(water), baseY };
  }

  /**
   * World mode mesh: one flat quad per decimated chunk (its real surface height + block colour)
   * plus skirts down to any lower neighbour, so a whole 12k-chunk world reads as solid 3D terrain.
   * Absolute block coordinates, so structures/players/waypoints line up without translation.
   */
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
    statusEl.textContent = 'loading voxel terrain\u2026';
    statusEl.style.cssText = 'position:fixed;bottom:12px;left:12px;z-index:101;color:#cfe;font:13px sans-serif;background:rgba(0,0,0,0.6);padding:6px 10px;border-radius:4px;';
    document.body.appendChild(statusEl);

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
      try { await loadWorldView(world, centerCx, centerCz); } catch (e) { statusEl.textContent = '3D world load failed: ' + e.message; }
    } else try {
      const AV = Date.now(); // atlas/biome JSON change when regenerated; bypass the 1h tile cache
      const [atlas, biomeTints, bmodels, vox] = await Promise.all([
        fetch(`/tiles/atlas.json?v=${AV}`).then((r) => r.json()),
        fetch(`/tiles/biome_tints.json?v=${AV}`).then((r) => r.json()).catch(() => ({})),
        fetch(`/tiles/blockmodels.json?v=${AV}`).then((r) => r.json()).catch(() => ({})),
        fetch(`/api/voxels?world=${encodeURIComponent(world)}&cx0=${cx0}&cz0=${cz0}&cx1=${cx1}&cz1=${cz1}`)
          .then((r) => r.json()),
      ]);
      if (!vox.chunks || !vox.chunks.length) {
        statusEl.textContent = 'no voxel data for this area yet';
        animate();
        return;
      }
      for (const c of vox.chunks) {
        const buf = await inflated(c.data);
        const v = parseVoxel(buf);
        c._pal = v.pal; c._cols = v.cols;
      }
      statusEl.textContent = 'meshing\u2026';
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
        shader.fragmentShader = 'varying vec4 vAtile;\n' + shader.fragmentShader;
        shader.fragmentShader = shader.fragmentShader.replace(
          '#include <map_fragment>',
          '#ifdef USE_MAP\n'
          + '  vec2 gtu = vAtile.xy + fract(vUv) * (vAtile.zw - vAtile.xy);\n'
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
      const faces = geo.getAttribute('position').count / 6;
      statusEl.textContent = `${vox.chunks.length} chunks \u00b7 ${N_CHUNKS * 16}\u00b2 blocks \u00b7 ${faces.toLocaleString()} faces \u00b7 drag to orbit`;

      await addMarkers(vox, baseY, cx0, cz0);
    } catch (e) {
      statusEl.textContent = '3D load failed: ' + e.message;
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
   * The LOD ladder, in real blocks the whole way: 1m -> 2m -> 4m -> 8m -> 16m. Every level is the
   * same renderer and the same textures, so zooming out looks like Minecraft getting chunkier, not
   * like a different kind of map.
   */
  function tierFor(spanChunks) {
    // pick the finest level whose window still covers what is on screen, so a ~1100-block view is
    // still 1m blocks and 16m blocks only appear past ~9000 blocks
    let lod = 0, f = 1;
    while (f * VOXEL_MAX_VCHUNKS < spanChunks && lod < 4) { lod++; f *= 2; }
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
    statusEl.textContent = 'loading world terrain\u2026';
    // cheap probe for the world's extent (step 64 returns the bounds plus a couple of samples)
    let probe;
    try {
      probe = await (await fetch(`/api/terrain?world=${encodeURIComponent(world)}&all=1&step=64&deflate=1`)).json();
    } catch (e) { probe = null; }
    if (!probe || probe.error || !probe.bounds) {
      statusEl.textContent = 'no surface data for this world yet';
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
    if (!worldGroup) return;
    for (const m of worldGroup.children) m.geometry.dispose();
    scene.remove(worldGroup);
    worldGroup = null;
  }

  /**
   * Load the atlas/biome/model data the real-block mesher needs, once per session. The polygon offset
   * makes the voxel surface win the depth test against the coarse heightfield still underneath it, so
   * the fine mesh can be laid on top without z-fighting and without punching holes.
   */
  async function ensureVoxelAssets() {
    if (voxMat) return true;
    if (typeof THREE === 'undefined') return false;
    const AV = Date.now();
    const [atlas, tints, bmodels] = await Promise.all([
      fetch(`/tiles/atlas.json?v=${AV}`).then((r) => r.json()),
      fetch(`/tiles/biome_tints.json?v=${AV}`).then((r) => r.json()).catch(() => ({})),
      fetch(`/tiles/blockmodels.json?v=${AV}`).then((r) => r.json()).catch(() => ({})),
    ]);
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
  async function buildVoxelLevel(world, lod, vx0, vz0, vx1, vz1, bg) {
    const f = 1 << lod;
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
        const tx = tileList[i][0], tz = tileList[i][1];
        // lod 0 uses prerendered tiles when they exist (no client meshing); otherwise fall back to
        // the live mesher so brand-new chunks still show immediately.
        if (lod === 0) {
          const gtx = tx / N_CHUNKS, gtz = tz / N_CHUNKS;
          try {
            const resp = await fetch(`/tiles/${encodeURIComponent(world)}/mesh/lod0/${gtx}_${gtz}.gtmesh`);
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
              faces += geo.getAttribute('position').count / 6;
              tiles++;
              loaded.add(tx + ',' + tz);
              if (bg) hideCoveredVoxTiles(bg, loaded, tileBlocks);
              statusEl.textContent = `lod ${lod} (${f}m blocks) \u00b7 ${tiles}/${tileList.length} tiles \u00b7 `
                + `${faces.toLocaleString()} faces`;
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
          const n = r.geo.getAttribute('position').count / 6;
          faces += n;
          tiles++;
          loaded.add(tx + ',' + tz);
          if (bg) hideCoveredVoxTiles(bg, loaded, tileBlocks);
          statusEl.textContent = `lod ${lod} (${f}m blocks) \u00b7 ${tiles}/${tileList.length} tiles \u00b7 `
            + `${faces.toLocaleString()} faces`;
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
   * Build one LOD level over the visible window. Everything here is real blocks - the only thing that
   * changes with zoom is how big a block is - so there is no separate "world map" to fall back to.
   * The previous (coarser) level stays on screen as the backdrop for anything the new level does not
   * cover, and tiles of it are hidden as the finer level covers them.
   */
  async function buildWorldTier(world, cx0, cz0, cx1, cz1, frame) {
    const spanChunks = Math.max(cx1 - cx0, cz1 - cz0);
    const tier = tierFor(spanChunks);
    const lod = tier.lod, f = 1 << lod;
    if (!(await ensureVoxelAssets())) { statusEl.textContent = '3D library not loaded'; return; }

    // Cover what is on screen, capped so an extreme zoom-out cannot ask for an absurd number of tiles.
    // Cap the window in REAL chunks too: the level's own data is what costs, and asking a server to
    // cover thousands of chunks on the fly would stall. 256 real chunks a side is 4096 blocks, which
    // is the far edge we agreed on.
    const winReal = Math.min(spanChunks, VOXEL_MAX_REAL_CHUNKS);
    const winV = Math.max(2, Math.min(Math.ceil(winReal / f), VOXEL_MAX_VCHUNKS));
    const midVx = Math.round(((cx0 + cx1) / 2) / f), midVz = Math.round(((cz0 + cz1) / 2) / f);
    const vx0 = midVx - Math.floor(winV / 2), vz0 = midVz - Math.floor(winV / 2);
    const vx1 = vx0 + winV - 1, vz1 = vz0 + winV - 1;

    const bbox = { cx0: vx0 * f, cz0: vz0 * f, cx1: (vx1 + 1) * f - 1, cz1: (vz1 + 1) * f - 1 };

    if (frame) {
      // frame the middle of the window; zooming out is how you leave, so start where the detail is
      const span = Math.max(cx1 - cx0, cz1 - cz0) * 16;
      const ctr = new THREE.Vector3(((cx0 + cx1) / 2) * 16, 80, ((cz0 + cz1) / 2) * 16);
      scene.fog = new THREE.Fog(WORLD_TINT, span * 1.2, span * 3.5);
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
    const r = await buildVoxelLevel(world, lod, vx0, vz0, vx1, vz1, bg);
    if (!r.tiles) {
      scene.remove(r.group);
      statusEl.textContent = 'no block data here yet \u00b7 lod ' + lod + ' \u00b7 '
        + (r.problem || 'unknown') + (bg ? '' : ' \u00b7 try zooming out');
      return;
    }
    for (const g of r.group.children) {
      if (!g.userData.bbox) {
        g.userData.bbox = { x0: 0, z0: 0, x1: 0, z1: 0 };
      }
    }
    // drop the old level only once the new one is bigger than it (i.e. we zoomed out); when zooming
    // in it stays as the backdrop, otherwise the world would end at the edge of the window
    // when we zoomed out the new level covers the old, so the backdrop is no longer needed at all
    if (backdropGroup && prevLevel) {
      const covers = bbox.cx0 <= prevLevel.cx0 && bbox.cx1 >= prevLevel.cx1
        && bbox.cz0 <= prevLevel.cz0 && bbox.cz1 >= prevLevel.cz1;
      if (covers) { disposeGroup(backdropGroup); backdropGroup = null; }
    }
    worldGroup = r.group;
    worldLevel = { key: tier.key, lod, cx0: bbox.cx0, cz0: bbox.cz0, cx1: bbox.cx1, cz1: bbox.cz1 };
    statusEl.textContent = `lod ${lod} \u00b7 ${f}m blocks \u00b7 ${r.tiles} tiles \u00b7 `
      + `${r.faces.toLocaleString()} faces`;
  }

  /**
   * Rebuild the terrain at the resolution the current zoom calls for.
   *
   * Driven by OrbitControls' 'change' event with a short trailing timer, rather than polled from the
   * render loop: the rebuild is a main-thread job, so we only ever want one, and only once the user
   * has actually stopped moving. Polling tied the timing to the frame rate, which starved on slow
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
    const half = Math.max(4, visBlocks / 32); // real chunks across the view, half going each way

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
      markerPoints = new THREE.Points(
        new THREE.BufferGeometry().setFromPoints(pts.map((p) => p.pos)),
        new THREE.PointsMaterial({ color: 0xffd25c, size: 12, sizeAttenuation: true }));
      markerPoints.userData.markers = pts;
      scene.add(markerPoints);
    }
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
    if (atlasTex) { atlasTex.dispose(); atlasTex = null; }
    if (refineTimer) { clearTimeout(refineTimer); refineTimer = null; }
    disposeGroup(backdropGroup);
    backdropGroup = null;
    disposeWorldGroup();
    disposeVoxelGroup();
    if (worldMat) { worldMat.dispose(); worldMat = null; }
    worldLevel = null; worldBounds = null; refineBusy = false; worldLoading = false;
    if (renderer) renderer.dispose();
    if (controls) controls.dispose();
    if (canvas) canvas.remove();
    if (closeBtn) closeBtn.remove();
    if (depthBtn) { depthBtn.remove(); depthBtn = null; }
    if (worldBtn) { worldBtn.remove(); worldBtn = null; }
    if (islandsBtn) { islandsBtn.remove(); islandsBtn = null; }
    if (statusEl) statusEl.remove();
    if (popupEl) popupEl.remove();
    renderer = scene = camera = controls = canvas = closeBtn = statusEl = markerPoints = popupEl = null;
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
