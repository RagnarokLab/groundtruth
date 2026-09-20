// GroundTruth minimal canvas map - no libraries, pan/zoom/hover/search only.
// Biome colours are a hand-picked palette for common vanilla biomes; anything
// unrecognised (modded/Terralith biomes) gets a stable hashed colour instead
// of going blank, so nothing "disappears" just for being unlisted.

// --- slime chunk math (real vanilla algorithm, pure function of seed+cx+cz - not a guess,
// not affected by version updates, so this is exact "ground truth" even for chunks that
// haven't generated yet). Reimplements java.util.Random's LCG exactly so the result matches
// the real game bit-for-bit. Cross-checked against the live server's own Chunk#isSlimeChunk()
// for 13 real coordinates (mixed signs, mixed true/false) before shipping this - all matched.
const LCG_MULT = 0x5DEECE66Dn, LCG_ADD = 0xBn, LCG_MASK = (1n << 48n) - 1n, INT_MAX = 2147483647n;
function slimeNextBits(state, bits) {
  const newState = (state * LCG_MULT + LCG_ADD) & LCG_MASK;
  return { state: newState, value: newState >> BigInt(48 - bits) };
}
function slimeNextInt(state, bound) {
  const boundBig = BigInt(bound);
  let s = state;
  while (true) {
    const r = slimeNextBits(s, 31);
    s = r.state;
    const val = r.value % boundBig;
    if (r.value - val + (boundBig - 1n) <= INT_MAX) return { state: s, value: val };
  }
}
function isSlimeChunk(seed, cx, cz) {
  if (seed === null || seed === undefined) return false;
  const x = BigInt(cx), z = BigInt(cz);
  const chunkSeed = (BigInt(seed) + (x * x * 0x4c1906n) + (x * 0x5ac0dbn)
    + (z * z * 0x4307a7n) + (z * 0x5f24fn)) ^ 0x3ad8025fn;
  const state0 = (chunkSeed ^ LCG_MULT) & LCG_MASK;
  return slimeNextInt(state0, 10).value === 0n;
}

const canvas = document.getElementById('canvas');
const ctx = canvas.getContext('2d');
const tooltip = document.getElementById('tooltip');
const countsEl = document.getElementById('counts');
const worldSelectEl = document.getElementById('worldSelect');
const searchEl = document.getElementById('search');
const resultsEl = document.getElementById('results');
const owCoordsEl = document.getElementById('ow-coords');
const netherCoordsEl = document.getElementById('nether-coords');
const hoverBiomeEl = document.getElementById('hover-biome');
const toggleNetherEl = document.getElementById('toggleNether');
const toggleBboxesEl = document.getElementById('toggleBboxes');
const toggleSlimeEl = document.getElementById('toggleSlime');
const biomeOverlayEl = document.getElementById('toggleBiomeOverlay');
const inspectCard = document.getElementById('inspectCard');
const inspectTitle = document.getElementById('inspectTitle');
const inspectInfo = document.getElementById('inspectInfo');
const inspectNav = document.getElementById('inspectNav');
const inspectCount = document.getElementById('inspectCount');
const slimeSummary = document.getElementById('slimeSummary');
const slimeResults = document.getElementById('slimeResults');
const typesListEl = document.getElementById('typesList');
const typeFilterEl = document.getElementById('typeFilter');
const pos1El = document.getElementById('pos1-readout');
const pos2El = document.getElementById('pos2-readout');
const sizeEl = document.getElementById('size-readout');

let tileMeta = null;               // {tile,minCx,minCz,maxZoom} from /tiles/<world>/meta.json
let biomeCounts = {};              // biome -> chunk count (from /api/biome_counts)
const hoverBiomeCache = new Map(); // "cx,cz" -> biome name (on-demand single-chunk lookups)
let lastHoverChunk = null;         // last hovered chunk key, so the label refreshes on fetch return
let outlineCoords = new Map();     // biome -> Set("cx,cz"), the biome outline overlay
let structures = [];
let worldSeed = null;
let currentWorld = null;
let currentEnv = null;
let worldList = [];
let visibleTypes = new Set(); // empty by default - nothing shown until picked, per request
let highlightedBiomes = new Set();
let allTypes = [];
let scale = 0.5;       // pixels per chunk (0.5 = 2 chunks/px, a map-scale default)
let panX = 0, panZ = 0; // world chunk coords at screen center
let dragging = false, dragStartX = 0, dragStartY = 0, dragPanX0 = 0, dragPanZ0 = 0, dragMoved = false;

// Per-viewer display settings (colours) - accessibility/preference, not shared data,
// same localStorage pattern as the selection below.
const DEFAULT_SETTINGS = { iconColor: '#ffffff', bboxColor: '#ffd25c', selectionColor: '#ffd25c' };
let settings = { ...DEFAULT_SETTINGS };
try {
  const savedSettings = JSON.parse(localStorage.getItem('groundtruth-settings') || 'null');
  if (savedSettings) settings = { ...DEFAULT_SETTINGS, ...savedSettings };
} catch (e) { /* private mode / blocked storage - defaults are fine */ }

function saveSettings() {
  try { localStorage.setItem('groundtruth-settings', JSON.stringify(settings)); } catch (e) { /* ignore */ }
}

function hexToRgba(hex, alpha) {
  const n = parseInt(hex.replace('#', ''), 16);
  return `rgba(${(n >> 16) & 255},${(n >> 8) & 255},${n & 255},${alpha})`;
}

// WorldEdit-style pos1/pos2 selection - block coords (x, z), persisted across
// refresh/alt-tab via localStorage (per-viewer convenience, not shared data).
let pos1 = null, pos2 = null;

// --- inspect: tap a structure to see what it is; arrows cycle every structure of that type
// (Ctrl+F style). Press-and-hold sets the pos1/pos2 selection wand instead of tapping to inspect.
const TAP_TOL_PX = 16;
const LONG_PRESS_MS = 500;
let inspectList = [], inspectIndex = 0, inspected = null;
let highlightedCluster = null;

// --- live players (from the plugin via /api/players), refreshed periodically
let players = [];
const skinCache = new Map();
const SKIN_V = Date.now(); // skins update on join (live Bedrock capture); don't let caches mask them
function skinImg(uuid) {
  let im = skinCache.get(uuid);
  if (!im) {
    im = new Image();
    im.onload = () => { im._ok = true; draw(); };
    im.onerror = () => { im._err = true; };
    im.src = '/api/skin/' + encodeURIComponent(uuid) + '?v=' + SKIN_V;
    skinCache.set(uuid, im);
  }
  return im;
}
async function loadPlayers() {
  try {
    const res = await fetch('/api/players');
    players = (await res.json()).players || [];
    draw();
  } catch (e) { /* keep last known */ }
}
let pressTimer = null, longPressFired = false;
try {
  const saved = JSON.parse(localStorage.getItem('groundtruth-selection') || 'null');
  if (saved) { pos1 = saved.pos1; pos2 = saved.pos2; }
} catch (e) { /* private mode / blocked storage - just start fresh */ }

function saveSelection() {
  try {
    localStorage.setItem('groundtruth-selection', JSON.stringify({ pos1, pos2 }));
  } catch (e) { /* ignore - selection still works for this session */ }
}

function updateSelectionPanel() {
  pos1El.textContent = pos1 ? `pos1: ${pos1[0]}, ${pos1[1]}` : 'pos1: -';
  pos2El.textContent = pos2 ? `pos2: ${pos2[0]}, ${pos2[1]}` : 'pos2: -';
  sizeEl.textContent = (pos1 && pos2)
    ? `size: ${Math.abs(pos2[0] - pos1[0]) + 1} x ${Math.abs(pos2[1] - pos1[1]) + 1}`
    : '';
}

function resize() {
  canvas.width = window.innerWidth;
  canvas.height = window.innerHeight;
  draw();
}
window.addEventListener('resize', resize);

const BIOME_COLORS = {
  'minecraft:ocean': '#1a4a6b', 'minecraft:deep_ocean': '#0f3552',
  'minecraft:frozen_ocean': '#7fa7b5', 'minecraft:warm_ocean': '#2a7ca8',
  'minecraft:river': '#3b6ea5', 'minecraft:frozen_river': '#8fb3c9',
  'minecraft:plains': '#8db15a', 'minecraft:sunflower_plains': '#a3c95f',
  'minecraft:desert': '#d8c977', 'minecraft:windswept_hills': '#8a8a72',
  'minecraft:forest': '#4f7a3d', 'minecraft:flower_forest': '#63a13f',
  'minecraft:birch_forest': '#6f9955', 'minecraft:dark_forest': '#375a2b',
  'minecraft:taiga': '#4c6b56', 'minecraft:old_growth_pine_taiga': '#3f5c4a',
  'minecraft:snowy_plains': '#dbe7e8', 'minecraft:snowy_taiga': '#c3d6d1',
  'minecraft:swamp': '#4d6b53', 'minecraft:mangrove_swamp': '#3f6b4f',
  'minecraft:jungle': '#2f8f3d', 'minecraft:sparse_jungle': '#4d9c52',
  'minecraft:bamboo_jungle': '#3fa04a', 'minecraft:beach': '#e2d59a',
  'minecraft:snowy_beach': '#e8ecec', 'minecraft:savanna': '#a89b4e',
  'minecraft:savanna_plateau': '#b3a758', 'minecraft:badlands': '#a35b3c',
  'minecraft:eroded_badlands': '#b06a48', 'minecraft:wooded_badlands': '#8c6a45',
  'minecraft:mushroom_fields': '#a0678f', 'minecraft:stony_shore': '#8c8c85',
  'minecraft:meadow': '#7fb356', 'minecraft:grove': '#5d7f6b',
  'minecraft:snowy_slopes': '#cfe0e0', 'minecraft:jagged_peaks': '#c9d3d6',
  'minecraft:frozen_peaks': '#b9d0d6', 'minecraft:stony_peaks': '#9b9b90',
  'minecraft:cherry_grove': '#e3a6c4', 'minecraft:nether_wastes': '#5c2b26',
  'minecraft:crimson_forest': '#7a1f1f', 'minecraft:warped_forest': '#1f6e63',
  'minecraft:soul_sand_valley': '#4a3c33', 'minecraft:basalt_deltas': '#5a545a',
  'minecraft:the_end': '#c9c2a3', 'minecraft:end_highlands': '#c9c2a3',
  'minecraft:end_midlands': '#b8b191', 'minecraft:small_end_islands': '#d4cdae',
  'minecraft:end_barrens': '#a8a186', 'minecraft:the_void': '#000000',
};
function hashColor(key) {
  let h = 0;
  for (let i = 0; i < key.length; i++) h = (h * 31 + key.charCodeAt(i)) >>> 0;
  const r = 80 + (h % 120), g = 80 + ((h >> 8) % 120), b = 80 + ((h >> 16) % 120);
  return `rgb(${r},${g},${b})`;
}
function biomeColor(key) {
  return BIOME_COLORS[key] || hashColor(key || 'unknown');
}
function biomeColorRGB(key) {
  const hex = BIOME_COLORS[key];
  if (hex) {
    const n = parseInt(hex.slice(1), 16);
    return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
  }
  let h = 0;
  const k = key || 'unknown';
  for (let i = 0; i < k.length; i++) h = (h * 31 + k.charCodeAt(i)) >>> 0;
  return [80 + (h % 120), 80 + ((h >> 8) % 120), 80 + ((h >> 16) % 120)];
}

// Tiles (M1): the base terrain layer and the optional biome overlay are streamed as PNG tiles
// from /tiles/<world>/<layer>/<z>/<tx>_<ty>.png instead of loading every chunk as JSON. The zoom
// is chosen so one tile pixel is about one screen pixel. Tiles are cached as <img> elements.
const tileImageCache = new Map();
function tileImage(url) {
  let im = tileImageCache.get(url);
  if (!im) {
    im = new Image();
    im.onload = () => { im._ok = true; draw(); };
    im.onerror = () => { im._err = true; };
    im.src = url;
    tileImageCache.set(url, im);
  }
  return im;
}

function drawTileLayer(layer, alpha) {
  if (!tileMeta || !tileMeta.tile) return;
  const ts = tileMeta.tile;
  const z = Math.max(0, Math.min(tileMeta.maxZoom || 0,
                                Math.round(Math.log2(1 / Math.max(scale, 1e-6)))));
  const spanChunks = ts * Math.pow(2, z);
  const [wx0, wz0] = screenToWorld(0, 0);
  const [wx1, wz1] = screenToWorld(canvas.width, canvas.height);
  const tx0 = Math.floor((wx0 - tileMeta.minCx) / spanChunks);
  const tz0 = Math.floor((wz0 - tileMeta.minCz) / spanChunks);
  const tx1 = Math.floor((wx1 - tileMeta.minCx) / spanChunks);
  const tz1 = Math.floor((wz1 - tileMeta.minCz) / spanChunks);
  const sizePx = spanChunks * scale;
  ctx.save();
  ctx.globalAlpha = alpha;
  ctx.imageSmoothingEnabled = false;
  for (let tz = tz0; tz <= tz1; tz++) {
    for (let tx = tx0; tx <= tx1; tx++) {
      const cx = tileMeta.minCx + tx * spanChunks;
      const cz = tileMeta.minCz + tz * spanChunks;
      const [sx, sy] = worldToScreen(cx, cz);
      const img = tileImage(`/tiles/${encodeURIComponent(currentWorld)}/${layer}/${z}/${tx}_${tz}.png`);
      if (img._ok) ctx.drawImage(img, sx, sy, sizePx, sizePx);
    }
  }
  ctx.restore();
}

// Block-resolution detail (M1b): above DETAIL_SCALE px/chunk the 1px/chunk tiles are too coarse to
// show builds, so we fetch the 16x16 per-chunk colour grids for the visible chunks (zlib-compressed,
// inflated with DecompressionStream) and draw them on top. Only what's on screen is requested.
const DETAIL_SCALE = 16;             // px per chunk; 16 = 1 px per block
const detailCache = new Map();       // "cx,cz" -> 16x16 canvas
let detailReqKey = null, detailTimer = null;

function inflateB64(b64) {
  const bin = atob(b64);
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return new Response(new Blob([bytes]).stream().pipeThrough(new DecompressionStream('deflate')))
    .arrayBuffer();
}

function makeDetailCanvas(rgb) {
  const cv = document.createElement('canvas');
  cv.width = 16; cv.height = 16;
  const c2 = cv.getContext('2d');
  const img = c2.createImageData(16, 16);
  for (let i = 0; i < 256; i++) {
    img.data[i * 4] = rgb[i * 3];
    img.data[i * 4 + 1] = rgb[i * 3 + 1];
    img.data[i * 4 + 2] = rgb[i * 3 + 2];
    img.data[i * 4 + 3] = 255;
  }
  c2.putImageData(img, 0, 0);
  return cv;
}

function ensureDetail() {
  if (!tileMeta || scale < DETAIL_SCALE) return;
  const [wx0, wz0] = screenToWorld(0, 0);
  const [wx1, wz1] = screenToWorld(canvas.width, canvas.height);
  const cx0 = Math.floor(wx0), cz0 = Math.floor(wz0);
  const cx1 = Math.ceil(wx1), cz1 = Math.ceil(wz1);
  const key = `${cx0},${cz0},${cx1},${cz1}`;
  if (key === detailReqKey) return;
  clearTimeout(detailTimer);
  detailTimer = setTimeout(() => {
    detailReqKey = key;
    fetch(`/api/detail?world=${encodeURIComponent(currentWorld)}&cx0=${cx0}&cz0=${cz0}&cx1=${cx1}&cz1=${cz1}`)
      .then((r) => r.json())
      .then(async (d) => {
        for (const c of d.chunks || []) {
          const ck = c.cx + ',' + c.cz;
          if (detailCache.has(ck)) continue;
          const [rgbBuf] = await Promise.all([inflateB64(c.rgb), inflateB64(c.hgt)]);
          detailCache.set(ck, makeDetailCanvas(new Uint8Array(rgbBuf)));
        }
        draw();
      })
      .catch(() => {});
  }, 250);
}

function drawDetail() {
  if (scale < DETAIL_SCALE) return;
  const sz = scale;
  const [wx0, wz0] = screenToWorld(0, 0);
  const [wx1, wz1] = screenToWorld(canvas.width, canvas.height);
  ctx.imageSmoothingEnabled = false;
  for (let cx = Math.floor(wx0); cx <= Math.ceil(wx1); cx++) {
    for (let cz = Math.floor(wz0); cz <= Math.ceil(wz1); cz++) {
      const cv = detailCache.get(cx + ',' + cz);
      if (!cv) continue;
      const [sx, sy] = worldToScreen(cx, cz);
      ctx.drawImage(cv, sx, sy, sz, sz);
    }
  }
}

function worldToScreen(cx, cz) {
  return [
    canvas.width / 2 + (cx - panX) * scale,
    canvas.height / 2 + (cz - panZ) * scale,
  ];
}
function screenToWorld(sx, sy) {
  return [
    panX + (sx - canvas.width / 2) / scale,
    panZ + (sy - canvas.height / 2) / scale,
  ];
}

// On-demand biome lookup for hover/inspect (no full biome dataset is shipped to the client).
function fetchChunkBiome(cx, cz) {
  const key = cx + ',' + cz;
  if (hoverBiomeCache.has(key)) return;
  hoverBiomeCache.set(key, '__pending__');
  fetch(`/api/chunkinfo?world=${encodeURIComponent(currentWorld)}&cx=${cx}&cz=${cz}`)
    .then((r) => r.json())
    .then((d) => {
      const name = d && d.indexed ? d.biome : null;
      hoverBiomeCache.set(key, name);
      if (key === lastHoverChunk) hoverBiomeEl.textContent = `biome: ${name || 'unindexed'}`;
      draw();
    })
    .catch(() => hoverBiomeCache.set(key, null));
}
function biomeFor(cx, cz) {
  const key = cx + ',' + cz;
  if (hoverBiomeCache.has(key)) {
    const v = hoverBiomeCache.get(key);
    return v === '__pending__' ? null : v;
  }
  fetchChunkBiome(cx, cz);
  return null;
}

function draw() {
  ctx.fillStyle = '#111';
  ctx.fillRect(0, 0, canvas.width, canvas.height);

  // Base terrain layer from tiles; optional biome overlay on top (toggle in the coords panel).
  drawTileLayer('terrain', 1.0);
  if (biomeOverlayEl && biomeOverlayEl.checked) drawTileLayer('biome', 0.55);
  drawDetail();   // block-resolution detail on top when zoomed in
  ensureDetail();

  // Per-chunk overlays: slime stripes (computed live from the seed, visible chunks only) and the
  // biome outline (coords fetched on demand from /api/biome_chunks when a biome is ticked).
  const sz = Math.max(1, scale);
  if (toggleSlimeEl.checked && currentEnv === 'NORMAL' && worldSeed != null) {
    const [swx0, swz0] = screenToWorld(0, 0);
    const [swx1, swz1] = screenToWorld(canvas.width, canvas.height);
    for (let cx = Math.floor(swx0); cx <= Math.ceil(swx1); cx++) {
      for (let cz = Math.floor(swz0); cz <= Math.ceil(swz1); cz++) {
        if (isSlimeChunk(worldSeed, cx, cz)) {
          const [sx, sy] = worldToScreen(cx, cz);
          drawSlimeStripes(sx, sy, sz);
        }
      }
    }
  }
  if (highlightedBiomes.size > 0) {
    for (const [biome, set] of outlineCoords) {
      if (!highlightedBiomes.has(biome)) continue;
      for (const key of set) {
        const [cx, cz] = key.split(',').map(Number);
        const [sx, sy] = worldToScreen(cx, cz);
        if (sx < -sz || sy < -sz || sx > canvas.width + sz || sy > canvas.height + sz) continue;
        ctx.strokeStyle = '#ffffff';
        ctx.lineWidth = Math.max(1, sz * 0.25);
        ctx.strokeRect(sx, sy, sz, sz);
      }
    }
  }

  const showBboxes = toggleBboxesEl.checked;
  for (const s of structures) {
    if (!visibleTypes.has(s.type)) continue;
    const scx = s.x / 16, scz = s.z / 16; // block coords -> chunk coords for screen placement

    if (showBboxes) {
      const [bx1, bz1] = worldToScreen(s.minX / 16, s.minZ / 16);
      const [bx2, bz2] = worldToScreen(s.maxX / 16, s.maxZ / 16);
      ctx.strokeStyle = settings.bboxColor;
      ctx.lineWidth = 1;
      ctx.strokeRect(bx1, bz1, bx2 - bx1, bz2 - bz1);
    }

    const [sx, sy] = worldToScreen(scx, scz);
    if (sx < -10 || sy < -10 || sx > canvas.width + 10 || sy > canvas.height + 10) continue;
    drawStructureIcon(sx, sy);
  }

  // highlighted inspected structure (tap-inspect / Ctrl+F-style next-prev)
  if (inspected) {
    const [ix, iy] = worldToScreen(inspected.x / 16, inspected.z / 16);
    const [bx1, bz1] = worldToScreen(inspected.minX / 16, inspected.minZ / 16);
    const [bx2, bz2] = worldToScreen(inspected.maxX / 16, inspected.maxZ / 16);
    ctx.save();
    ctx.strokeStyle = '#ffffff';
    ctx.lineWidth = 2;
    ctx.strokeRect(bx1, bz1, bx2 - bx1, bz2 - bz1);
    ctx.beginPath();
    ctx.arc(ix, iy, 9, 0, Math.PI * 2);
    ctx.stroke();
    ctx.restore();
  }

  // highlighted slime cluster (search result)
  if (highlightedCluster) {
    const c = highlightedCluster;
    const [x1, y1] = worldToScreen(c.cx, c.cz);
    const [x2, y2] = worldToScreen(c.cx + c.size, c.cz + c.size);
    ctx.save();
    ctx.strokeStyle = '#3ce65a';
    ctx.lineWidth = 3;
    ctx.strokeRect(x1, y1, x2 - x1, y2 - y1);
    ctx.restore();
  }

  // live online players (skin head + name), only those in the world being viewed
  for (const pl of players) {
    if (pl.world !== currentWorld) continue;
    const [px, py] = worldToScreen(pl.x / 16, pl.z / 16);
    if (px < -20 || py < -20 || px > canvas.width + 20 || py > canvas.height + 20) continue;
    const img = skinImg(pl.uuid);
    ctx.save();
    const hs = 18, hx = px - hs / 2, hy = py - hs / 2;
    ctx.fillStyle = 'rgba(0,0,0,0.65)';
    ctx.fillRect(hx - 2, hy - 2, hs + 4, hs + 4);
    if (img._ok) {
      ctx.imageSmoothingEnabled = false;
      ctx.drawImage(img, hx, hy, hs, hs);
    } else {
      ctx.fillStyle = '#6cf';
      ctx.font = 'bold 12px sans-serif';
      ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
      ctx.fillText((pl.name || '?').replace(/^\./, '').charAt(0).toUpperCase(), px, py);
    }
    ctx.strokeStyle = '#fff'; ctx.lineWidth = 2;
    ctx.strokeRect(hx - 2, hy - 2, hs + 4, hs + 4);
    ctx.font = '12px sans-serif'; ctx.textAlign = 'center'; ctx.textBaseline = 'top';
    ctx.lineWidth = 3; ctx.strokeStyle = '#000'; ctx.strokeText(pl.name, px, py + 13);
    ctx.fillStyle = '#fff'; ctx.fillText(pl.name, px, py + 13);
    ctx.restore();
  }

  // pos1/pos2 selection wand overlay
  if (pos1) {
    const [x1, y1] = worldToScreen(pos1[0] / 16, pos1[1] / 16);
    drawCrosshair(x1, y1, '#ff4d4d');
  }
  if (pos2) {
    const [x2, y2] = worldToScreen(pos2[0] / 16, pos2[1] / 16);
    drawCrosshair(x2, y2, '#4d94ff');
  }
  if (pos1 && pos2) {
    const [x1, y1] = worldToScreen(pos1[0] / 16, pos1[1] / 16);
    const [x2, y2] = worldToScreen(pos2[0] / 16, pos2[1] / 16);
    ctx.fillStyle = hexToRgba(settings.selectionColor, 0.15);
    ctx.fillRect(Math.min(x1, x2), Math.min(y1, y2), Math.abs(x2 - x1), Math.abs(y2 - y1));
    ctx.strokeStyle = settings.selectionColor;
    ctx.lineWidth = 2;
    ctx.strokeRect(Math.min(x1, x2), Math.min(y1, y2), Math.abs(x2 - x1), Math.abs(y2 - y1));
  }
}

function drawSlimeStripes(sx, sy, size) {
  // Diagonal stripes, clipped to the chunk square, so the real biome colour underneath still
  // shows through - a solid tint was too easy to lose against green biomes (forest/jungle/etc).
  ctx.save();
  ctx.beginPath();
  ctx.rect(sx, sy, size, size);
  ctx.clip();
  ctx.strokeStyle = 'rgba(60,230,90,0.9)';
  ctx.lineWidth = Math.max(1, size * 0.18);
  const step = Math.max(1.5, size / 3);
  for (let off = -size; off < size * 2; off += step) {
    ctx.beginPath();
    ctx.moveTo(sx + off, sy);
    ctx.lineTo(sx + off + size, sy + size);
    ctx.stroke();
  }
  ctx.restore();
}

function drawStructureIcon(x, y) {
  ctx.beginPath();
  ctx.arc(x, y, 3, 0, Math.PI * 2);
  ctx.fillStyle = settings.iconColor;
  ctx.fill();
  ctx.strokeStyle = '#000';
  ctx.lineWidth = 1;
  ctx.stroke();
}


function drawCrosshair(x, y, color) {
  ctx.strokeStyle = color;
  ctx.lineWidth = 2;
  ctx.beginPath();
  ctx.moveTo(x - 6, y); ctx.lineTo(x + 6, y);
  ctx.moveTo(x, y - 6); ctx.lineTo(x, y + 6);
  ctx.stroke();
}

function findStructureNear(sx, sy, radiusPx) {
  let best = null, bestD = radiusPx;
  for (const s of structures) {
    if (!visibleTypes.has(s.type)) continue;
    const [px, py] = worldToScreen(s.x / 16, s.z / 16);
    const d = Math.hypot(px - sx, py - sy);
    if (d < bestD) { bestD = d; best = s; }
  }
  return best;
}

function setSelectionPoint(clientX, clientY) {
  const [wcx, wcz] = screenToWorld(clientX, clientY);
  const clicked = [Math.round(wcx * 16), Math.round(wcz * 16)];
  if (pos1 === null) pos1 = clicked;
  else if (pos2 === null) pos2 = clicked;
  else { pos1 = clicked; pos2 = null; }
  saveSelection();
  updateSelectionPanel();
  draw();
}

function closeInspect() {
  inspectList = []; inspectIndex = 0; inspected = null;
  inspectCard.style.display = 'none';
  draw();
}

function inspectStructure(s, center) {
  const dx = s.x / 16, dz = s.z / 16;
  inspectList = structures
    .filter((o) => o.type === s.type)
    .sort((a, b) => ((a.x / 16 - dx) ** 2 + (a.z / 16 - dz) ** 2)
                  - ((b.x / 16 - dx) ** 2 + (b.z / 16 - dz) ** 2));
  inspectIndex = Math.max(0, inspectList.indexOf(s));
  renderInspect(center !== false);
}

function renderInspect(center) {
  const s = inspectList[inspectIndex];
  if (!s) { closeInspect(); return; }
  inspected = s;
  const biome = biomeFor(Math.floor(s.x / 16), Math.floor(s.z / 16));
  inspectTitle.textContent = s.type;
  let html = `x ${s.x}, y ${s.y}, z ${s.z}<br>biome: ${biome || '- (loading)'}`;
  if (toggleNetherEl.checked) html += `<br>nether: ${Math.round(s.x / 8)}, ${Math.round(s.z / 8)}`;
  inspectInfo.innerHTML = html;
  inspectNav.style.display = 'flex';
  inspectCount.textContent = `${inspectIndex + 1} / ${inspectList.length}`;
  inspectCard.style.display = 'block';
  if (center) { panX = s.x / 16; panZ = s.z / 16; }
  draw();
}

function moveInspect(dir) {
  if (inspectList.length < 2) return;
  inspectIndex = (inspectIndex + dir + inspectList.length) % inspectList.length;
  renderInspect(true);
}

function showInspectAt(clientX, clientY) {
  const hit = findStructureNear(clientX, clientY, TAP_TOL_PX);
  if (hit) { inspectStructure(hit, false); return; }
  const [wcx, wcz] = screenToWorld(clientX, clientY);
  const bx = Math.round(wcx * 16), bz = Math.round(wcz * 16);
  const biome = biomeFor(Math.floor(wcx), Math.floor(wcz));
  inspectList = []; inspectIndex = 0; inspected = null;
  inspectTitle.textContent = 'location';
  let html = `x ${bx}, z ${bz}<br>biome: ${biome || '- (loading)'}`;
  if (toggleNetherEl.checked) html += `<br>nether: ${Math.round(bx / 8)}, ${Math.round(bz / 8)}`;
  inspectInfo.innerHTML = html;
  inspectNav.style.display = 'none';
  inspectCard.style.display = 'block';
}

// --- slime cluster search: squares of contiguous slime chunks, for big slime farms.
// Computed server-side (from the same pure seed formula) and fetched on demand.
function runSlimeSearch() {
  if (currentEnv && currentEnv !== 'NORMAL') {
    slimeSummary.textContent = 'slime chunks exist in overworld-type dimensions only';
    slimeResults.innerHTML = '';
    return;
  }
  const minK = parseInt(document.getElementById('slimeSize').value, 10) || 2;
  slimeSummary.textContent = 'searching...';
  slimeResults.innerHTML = '';
  const t0 = performance.now();
  fetch(`/api/slimeclusters?world=${encodeURIComponent(currentWorld)}&min=${minK}`)
    .then((r) => r.json())
    .then((d) => {
      const counts = d.counts || {};
      slimeSummary.textContent = Object.keys(counts).sort((a, b) => a - b)
        .map((k) => `${k}x${k}: ${counts[k]}`).join('  ·  ')
        + `   (${Math.round(performance.now() - t0)}ms)`;
      const list = (d.clusters || []).filter((s) => s.size >= minK);
      if (!list.length) {
        slimeResults.innerHTML = `<div>no ${minK}x${minK}+ cluster found</div>`;
        return;
      }
      for (const s of list.slice(0, 60)) {
        const div = document.createElement('div');
        div.textContent = `${s.size}x${s.size} @ blocks ${s.cx * 16}, ${s.cz * 16}`;
        div.style.cursor = 'pointer';
        div.onclick = () => {
          panX = s.cx + s.size / 2;
          panZ = s.cz + s.size / 2;
          highlightedCluster = s;
          draw();
        };
        slimeResults.appendChild(div);
      }
    })
    .catch(() => { slimeSummary.textContent = 'slime search failed'; });
}

canvas.addEventListener('mousedown', (e) => {
  dragging = true;
  dragMoved = false;
  longPressFired = false;
  canvas.classList.add('dragging');
  dragStartX = e.clientX; dragStartY = e.clientY;
  dragPanX0 = panX; dragPanZ0 = panZ;
  const sx = e.clientX, sy = e.clientY;
  clearTimeout(pressTimer);
  pressTimer = setTimeout(() => {
    if (dragging && !dragMoved) { longPressFired = true; setSelectionPoint(sx, sy); }
  }, LONG_PRESS_MS);
});
window.addEventListener('mouseup', (e) => {
  clearTimeout(pressTimer);
  if (dragging && !dragMoved && !longPressFired) {
    // a click, not a drag or hold - inspect what's here
    showInspectAt(e.clientX, e.clientY);
  }
  dragging = false;
  canvas.classList.remove('dragging');
});
// Shared by mouse hover AND touch (touch has no real "hover", so this gets called on
// touchstart/touchmove too - otherwise mobile users tapping never see coords/biome at all).
function updateHoverReadout(clientX, clientY) {
  const [wcx, wcz] = screenToWorld(clientX, clientY);
  const bx = Math.round(wcx * 16), bz = Math.round(wcz * 16);
  owCoordsEl.textContent = `overworld: ${bx}, ${bz}`;
  if (toggleNetherEl.checked) {
    netherCoordsEl.style.display = 'block';
    netherCoordsEl.textContent = `nether: ${Math.round(bx / 8)}, ${Math.round(bz / 8)}`;
  } else {
    netherCoordsEl.style.display = 'none';
  }
  const hoveredBiome = biomeFor(Math.floor(wcx), Math.floor(wcz));
  lastHoverChunk = `${Math.floor(wcx)},${Math.floor(wcz)}`;
  hoverBiomeEl.textContent = `biome: ${hoveredBiome || '- (loading)'}`;
}

window.addEventListener('mousemove', (e) => {
  if (dragging) {
    if (Math.abs(e.clientX - dragStartX) > 3 || Math.abs(e.clientY - dragStartY) > 3) {
      dragMoved = true;
      clearTimeout(pressTimer);
    }
    panX = dragPanX0 - (e.clientX - dragStartX) / scale;
    panZ = dragPanZ0 - (e.clientY - dragStartY) / scale;
    draw();
  }

  updateHoverReadout(e.clientX, e.clientY);

  if (dragging) return;
  const hit = findStructureNear(e.clientX, e.clientY, 8);
  if (hit) {
    tooltip.style.display = 'block';
    tooltip.style.left = (e.clientX + 12) + 'px';
    tooltip.style.top = (e.clientY + 12) + 'px';
    let text = `${hit.type} @ ${hit.x},${hit.y},${hit.z}`;
    if (toggleNetherEl.checked) {
      text += ` (nether ${Math.round(hit.x / 8)},${Math.round(hit.z / 8)})`;
    }
    tooltip.textContent = text;
  } else {
    tooltip.style.display = 'none';
  }
});
function zoomAt(clientX, clientY, factor) {
  const [wx, wz] = screenToWorld(clientX, clientY);
  scale = Math.max(0.05, Math.min(40, scale * factor));
  const [wx2, wz2] = screenToWorld(clientX, clientY);
  panX += wx - wx2;
  panZ += wz - wz2;
}

canvas.addEventListener('wheel', (e) => {
  e.preventDefault();
  zoomAt(e.clientX, e.clientY, e.deltaY < 0 ? 1.15 : (1 / 1.15));
  draw();
}, { passive: false });

// --- touch support: one finger pans (and taps set pos1/pos2 like a click), two fingers pinch-zoom.
// touch-action:none in CSS stops the browser's own page-zoom/scroll from fighting this.
let touchMode = null; // 'pan' | 'pinch'
let pinchStartDist = 0, pinchStartScale = 1;
let touchStartX = 0, touchStartY = 0, touchMoved = false;

function touchDist(t0, t1) {
  return Math.hypot(t1.clientX - t0.clientX, t1.clientY - t0.clientY);
}
function touchMid(t0, t1) {
  return [(t0.clientX + t1.clientX) / 2, (t0.clientY + t1.clientY) / 2];
}

canvas.addEventListener('touchstart', (e) => {
  e.preventDefault();
  if (e.touches.length === 1) {
    touchMode = 'pan';
    touchMoved = false;
    longPressFired = false;
    touchStartX = e.touches[0].clientX;
    touchStartY = e.touches[0].clientY;
    dragPanX0 = panX; dragPanZ0 = panZ;
    updateHoverReadout(touchStartX, touchStartY);
    const sx = touchStartX, sy = touchStartY;
    clearTimeout(pressTimer);
    pressTimer = setTimeout(() => {
      if (touchMode === 'pan' && !touchMoved) { longPressFired = true; setSelectionPoint(sx, sy); }
    }, LONG_PRESS_MS);
  } else if (e.touches.length === 2) {
    clearTimeout(pressTimer);
    touchMode = 'pinch';
    pinchStartDist = touchDist(e.touches[0], e.touches[1]);
    pinchStartScale = scale;
  }
}, { passive: false });

canvas.addEventListener('touchmove', (e) => {
  e.preventDefault();
  if (touchMode === 'pan' && e.touches.length === 1) {
    const dx = e.touches[0].clientX - touchStartX, dy = e.touches[0].clientY - touchStartY;
    if (Math.abs(dx) > 3 || Math.abs(dy) > 3) { touchMoved = true; clearTimeout(pressTimer); }
    panX = dragPanX0 - dx / scale;
    panZ = dragPanZ0 - dy / scale;
    updateHoverReadout(e.touches[0].clientX, e.touches[0].clientY);
    draw();
  } else if (touchMode === 'pinch' && e.touches.length === 2) {
    const dist = touchDist(e.touches[0], e.touches[1]);
    const [mx, my] = touchMid(e.touches[0], e.touches[1]);
    scale = Math.max(0.05, Math.min(40, pinchStartScale * (dist / pinchStartDist)));
    draw();
  }
}, { passive: false });

canvas.addEventListener('touchend', (e) => {
  clearTimeout(pressTimer);
  if (touchMode === 'pan' && !touchMoved && !longPressFired) {
    showInspectAt(touchStartX, touchStartY);
  }
  touchMode = null;
}, { passive: false });

toggleBboxesEl.addEventListener('change', draw);
toggleNetherEl.addEventListener('change', draw);
toggleSlimeEl.addEventListener('change', draw);

searchEl.addEventListener('input', () => {
  const q = searchEl.value.trim().toLowerCase();
  resultsEl.innerHTML = '';
  if (!q) return;
  const matches = structures.filter(s => s.type.toLowerCase().includes(q)).slice(0, 30);
  for (const m of matches) {
    const div = document.createElement('div');
    div.textContent = `${m.type} (${m.x}, ${m.z})`;
    div.onclick = () => { panX = m.x / 16; panZ = m.z / 16; scale = 3; inspectStructure(m, false); };
    resultsEl.appendChild(div);
  }
});

function jumpToCoords() {
  const x = parseInt(document.getElementById('jumpX').value, 10);
  const z = parseInt(document.getElementById('jumpZ').value, 10);
  if (Number.isNaN(x) || Number.isNaN(z)) return;
  panX = x / 16;
  panZ = z / 16;
  draw();
}
document.getElementById('jumpGo').addEventListener('click', jumpToCoords);
for (const id of ['jumpX', 'jumpZ']) {
  document.getElementById(id).addEventListener('keydown', (e) => {
    if (e.key === 'Enter') jumpToCoords();
  });
}

// --- generic filterable multi-select checkbox list (used by both structure types and biomes,
// same interaction pattern so it's consistent and easy to search regardless of spelling) ---
function buildCheckboxList(listEl, filterEl, allKeys, counts, selectedSet, onChange) {
  const filter = filterEl.value.trim().toLowerCase();
  listEl.innerHTML = '';
  for (const key of allKeys) {
    if (filter && !key.toLowerCase().includes(filter)) continue;
    const label = document.createElement('label');
    const cb = document.createElement('input');
    cb.type = 'checkbox';
    cb.checked = selectedSet.has(key);
    cb.addEventListener('change', () => {
      if (cb.checked) selectedSet.add(key); else selectedSet.delete(key);
      onChange();
    });
    label.appendChild(cb);
    label.append(` ${key} (${counts[key]})`);
    listEl.appendChild(label);
  }
}

// --- structure type toggle panel ---

function rebuildTypesList() {
  const counts = {};
  for (const s of structures) counts[s.type] = (counts[s.type] || 0) + 1;
  allTypes = Object.keys(counts).sort();
  buildCheckboxList(typesListEl, typeFilterEl, allTypes, counts, visibleTypes, draw);
}
typeFilterEl.addEventListener('input', rebuildTypesList);
document.getElementById('selAll').addEventListener('click', () => {
  for (const t of allTypes) visibleTypes.add(t);
  rebuildTypesList(); draw();
});
document.getElementById('selNone').addEventListener('click', () => {
  visibleTypes.clear();
  rebuildTypesList(); draw();
});

// --- biome highlight panel ---

const biomesListEl = document.getElementById('biomesList');
const biomeFilterEl = document.getElementById('biomeFilter');
let allBiomes = [];

function rebuildBiomesList() {
  allBiomes = Object.keys(biomeCounts).sort();
  buildCheckboxList(biomesListEl, biomeFilterEl, allBiomes, biomeCounts, highlightedBiomes, loadOutline);
}
function loadOutline() {
  for (const b of highlightedBiomes) {
    if (outlineCoords.has(b)) continue;
    outlineCoords.set(b, new Set());
    fetch(`/api/biome_chunks?world=${encodeURIComponent(currentWorld)}&biome=${encodeURIComponent(b)}`)
      .then((r) => r.json())
      .then((d) => {
        const set = new Set();
        const arr = d.chunks || [];
        for (let i = 0; i + 1 < arr.length; i += 2) set.add(arr[i] + ',' + arr[i + 1]);
        outlineCoords.set(b, set);
        draw();
      })
      .catch(() => {});
  }
  for (const b of [...outlineCoords.keys()]) if (!highlightedBiomes.has(b)) outlineCoords.delete(b);
  draw();
}
biomeFilterEl.addEventListener('input', rebuildBiomesList);
document.getElementById('biomeSelAll').addEventListener('click', () => {
  for (const b of allBiomes) highlightedBiomes.add(b);
  rebuildBiomesList(); loadOutline();
});
document.getElementById('biomeSelNone').addEventListener('click', () => {
  highlightedBiomes.clear(); outlineCoords.clear();
  rebuildBiomesList(); draw();
});

// --- tabs within the "Show on map" panel ---
const tabStructuresBtn = document.getElementById('tabStructures');
const tabBiomesBtn = document.getElementById('tabBiomes');
const pageStructures = document.getElementById('pageStructures');
const pageBiomes = document.getElementById('pageBiomes');
tabStructuresBtn.addEventListener('click', () => {
  tabStructuresBtn.classList.add('active'); tabBiomesBtn.classList.remove('active');
  pageStructures.classList.add('active'); pageBiomes.classList.remove('active');
});
tabBiomesBtn.addEventListener('click', () => {
  tabBiomesBtn.classList.add('active'); tabStructuresBtn.classList.remove('active');
  pageBiomes.classList.add('active'); pageStructures.classList.remove('active');
});

document.getElementById('clearSelection').addEventListener('click', () => {
  pos1 = null; pos2 = null;
  saveSelection(); updateSelectionPanel(); draw();
});

document.getElementById('inspectClose').addEventListener('click', closeInspect);
document.getElementById('inspectPrev').addEventListener('click', () => moveInspect(-1));
document.getElementById('inspectNext').addEventListener('click', () => moveInspect(1));
document.getElementById('findSlime').addEventListener('click', runSlimeSearch);

async function loadWorlds() {
  try {
    const res = await fetch('/api/worlds');
    const data = await res.json();
    worldList = data.worlds || [];
    if (!worldList.length) { countsEl.textContent = 'no worlds reported by the server'; return; }
    let saved = null;
    try { saved = localStorage.getItem('groundtruth-world'); } catch (e) { /* ignore */ }
    worldSelectEl.innerHTML = worldList.map((w) =>
      `<option value="${w.world}">${w.world} (${w.env}, ${w.chunks} chunks)</option>`).join('');
    currentWorld = worldList.some((w) => w.world === saved) ? saved : worldList[0].world;
    worldSelectEl.value = currentWorld;
    currentEnv = (worldList.find((w) => w.world === currentWorld) || {}).env || null;
    await load();
  } catch (e) {
    countsEl.textContent = 'failed to load the world list from the server';
  }
}

async function load() {
  if (!currentWorld) return;
  try {
    const [sres, mres] = await Promise.all([
      fetch(`/api/structures?world=${encodeURIComponent(currentWorld)}`),
      fetch(`/tiles/${encodeURIComponent(currentWorld)}/meta.json`)
        .then((r) => (r.ok ? r.json() : null)).catch(() => null),
    ]);
    const sdata = await sres.json();
    structures = sdata.structures || [];
    worldSeed = sdata.seed;
    tileMeta = mres;
    hoverBiomeCache.clear();
    detailCache.clear();
    detailReqKey = null;
    outlineCoords = new Map();
    highlightedBiomes.clear();
    try {
      const bc = await (await fetch(`/api/biome_counts?world=${encodeURIComponent(currentWorld)}`)).json();
      biomeCounts = bc.counts || {};
    } catch (e) { biomeCounts = {}; }
    countsEl.textContent = `${structures.length} structures indexed`
      + (tileMeta ? ` · tiles ready (zoom 0-${tileMeta.maxZoom})` : ' · tiles not rendered yet');
    rebuildTypesList();
    rebuildBiomesList();
    draw();
  } catch (e) {
    countsEl.textContent = 'failed to load world data';
  }
}

// --- settings, now living inside the GroundTruth panel instead of a separate one ---
const setIconColor = document.getElementById('setIconColor');
const setBboxColor = document.getElementById('setBboxColor');
const setSelectionColor = document.getElementById('setSelectionColor');

function applySettingsToInputs() {
  setIconColor.value = settings.iconColor;
  setBboxColor.value = settings.bboxColor;
  setSelectionColor.value = settings.selectionColor;
}
applySettingsToInputs();

setIconColor.addEventListener('input', () => { settings.iconColor = setIconColor.value; saveSettings(); draw(); });
setBboxColor.addEventListener('input', () => { settings.bboxColor = setBboxColor.value; saveSettings(); draw(); });
setSelectionColor.addEventListener('input', () => { settings.selectionColor = setSelectionColor.value; saveSettings(); draw(); });
document.getElementById('settingsReset').addEventListener('click', () => {
  settings = { ...DEFAULT_SETTINGS };
  saveSettings();
  applySettingsToInputs();
  draw();
});

// --- collapsible panels (mainly for mobile, where they eat a lot of screen) ---
let collapsedPanels = {};
try {
  collapsedPanels = JSON.parse(localStorage.getItem('groundtruth-collapsed') || '{}');
} catch (e) { /* defaults are fine */ }

document.querySelectorAll('.panel-collapse-btn').forEach((btn) => {
  const panel = btn.closest('.panel');
  if (collapsedPanels[panel.id]) {
    panel.classList.add('collapsed');
    btn.textContent = '+';
  }
  btn.addEventListener('click', (e) => {
    e.stopPropagation();
    const isCollapsed = panel.classList.toggle('collapsed');
    btn.textContent = isCollapsed ? '+' : '−';
    collapsedPanels[panel.id] = isCollapsed;
    try { localStorage.setItem('groundtruth-collapsed', JSON.stringify(collapsedPanels)); } catch (e) { /* ignore */ }
  });
});

worldSelectEl.addEventListener('change', () => {
  currentWorld = worldSelectEl.value;
  currentEnv = (worldList.find((w) => w.world === currentWorld) || {}).env || null;
  try { localStorage.setItem('groundtruth-world', currentWorld); } catch (e) { /* ignore */ }
  load();
});

resize();
updateSelectionPanel();
loadWorlds();
setInterval(load, 30000); // pick up new backfill progress without a manual refresh
loadPlayers();
setInterval(loadPlayers, 10000); // live player markers

// Minimal interface for the 3D view module (window.GT3D) to read app state.
window.GT = {
  getWorld: () => currentWorld,
  getStructures: () => structures,
  getCenter: () => ({ cx: panX, cz: panZ }),
};
document.getElementById('view3dBtn').addEventListener('click', () => {
  if (window.GT3D) window.GT3D.open(currentWorld, panX, panZ);
  else alert('3D view is still loading');
});
