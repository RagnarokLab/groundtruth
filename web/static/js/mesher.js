/*
 * GroundTruth voxel mesher - shared by the browser 3D view and the Node prerenderer.
 * Produces the same geometry arrays in both: opaque/water = { pos, uv, col, nor, atile, aanim }.
 * Extracted mechanically from gt_view3d.js so the two stay identical.
 */
'use strict';

  const N_CHUNKS = 10;      // voxel area side length in chunks (160 blocks)
  const DYE_COLOURS = {
    white: [0.99, 0.99, 0.99], orange: [0.98, 0.50, 0.11], magenta: [0.78, 0.31, 0.74],
    light_blue: [0.23, 0.70, 0.85], yellow: [1.00, 0.85, 0.24], lime: [0.50, 0.78, 0.12],
    pink: [0.95, 0.55, 0.67], gray: [0.28, 0.31, 0.32], light_gray: [0.62, 0.62, 0.59],
    cyan: [0.09, 0.61, 0.61], purple: [0.54, 0.20, 0.72], blue: [0.24, 0.27, 0.67],
    brown: [0.51, 0.33, 0.20], green: [0.37, 0.49, 0.09], red: [0.69, 0.18, 0.15],
    black: [0.11, 0.11, 0.13],
  };
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
  function buildGeometry(data, atlas, biomeTints, minY, cx0, cz0, bmodels, includeUnderground) {
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

    return { opaque, water, baseY };
  }

module.exports = { buildGeometry, N_CHUNKS, blockInfo, shapeOf, ruleIsSimple };
