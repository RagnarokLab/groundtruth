'use strict';
// GroundTruth admin panel (A1). Everything goes through /api/admin/* with the X-GT-Code header.

const $ = (id) => document.getElementById(id);
let CODE = localStorage.getItem('gt_code') || localStorage.getItem('gt_admin_code') || '';
let tab = 'dash';
let evTimer = null, conTimer = null;

function setMsg(el, text, isErr) {
  el.textContent = text || '';
  el.className = isErr ? 'err' : 'muted';
}

async function api(path) {
  const r = await fetch(path, { headers: CODE ? { 'X-GT-Code': CODE } : {} });
  if (r.status === 401 || r.status === 429) {
    let msg = 'unauthorised';
    try { msg = (await r.json()).error || msg; } catch (e) {}
    showLogin(msg);
    throw new Error(msg);
  }
  if (!r.ok) throw new Error('HTTP ' + r.status);
  return r.json();
}

function showLogin(msg) {
  $('loginBar').classList.remove('hide');
  $('who').textContent = '';
  setMsg($('loginMsg'), msg || '', true);
}
function hideLogin() {
  $('loginBar').classList.add('hide');
  $('who').textContent = 'unlocked';
  setMsg($('loginMsg'), '');
}

function fmtBytes(n) {
  if (!n && n !== 0) return '—';
  const u = ['B', 'KB', 'MB', 'GB', 'TB'];
  let i = 0;
  while (n >= 1024 && i < u.length - 1) { n /= 1024; i++; }
  return n.toFixed(i ? 1 : 0) + ' ' + u[i];
}
function fmtTs(ms) {
  if (!ms) return '—';
  return new Date(ms).toLocaleString();
}

// --- tabs -----------------------------------------------------------------------------------
const TABS = ['dash', 'player', 'events', 'rollback', 'containers', 'console'];
document.querySelectorAll('nav button').forEach((b) => {
  b.onclick = () => {
    tab = b.dataset.tab;
    document.querySelectorAll('nav button').forEach((x) => x.classList.toggle('on', x === b));
    TABS.forEach((t) => $('tab-' + t).classList.toggle('hide', t !== tab));
    if (tab === 'dash') loadDash();
    if (tab === 'player') loadPlayer().catch((e) => setMsg($('p_msg'), String(e.message || e), true));
    if (tab === 'events') runEvents();
    if (tab === 'rollback') loadAudit();
    if (tab === 'containers') findContainers().catch(() => {});
    if (tab === 'console') loadConsole();
  };
});

// --- login ----------------------------------------------------------------------------------
$('loginBtn').onclick = () => {
  CODE = $('code').value.trim();
  localStorage.setItem('gt_code', CODE);
  loadDash().then(hideLogin).catch(() => {});
};
$('code').addEventListener('keydown', (e) => { if (e.key === 'Enter') $('loginBtn').click(); });

// --- dashboard ------------------------------------------------------------------------------
async function loadDash() {
  const h = await api('/api/admin/health');
  const log = h.log || {}, srv = h.server || {};
  $('health').innerHTML =
    `<span class="stat"><b>${(log.events || 0).toLocaleString()}</b>events</span>` +
    `<span class="stat"><b>${fmtBytes(log.db_bytes)}</b>log db</span>` +
    `<span class="stat"><b>${fmtBytes((h.map || {}).db_bytes)}</b>map db</span>` +
    `<span class="stat"><b>${(log.log_positions || 0).toLocaleString()}</b>positions</span>` +
    `<span class="stat"><b>${(log.log_containers || 0).toLocaleString()}</b>containers</span>` +
    `<span class="stat"><b>${(log.log_inventories || 0).toLocaleString()}</b>inventories</span>` +
    `<div class="muted" style="margin-top:8px">oldest ${fmtTs(log.oldest)} · newest ${fmtTs(log.newest)}</div>` +
    (srv.logstatus ? `<div class="muted">server: ${escapeHtml(srv.logstatus)}</div>` : '') +
    (log.error ? `<div class="err">${escapeHtml(log.error)}</div>` : '');
  const since = Date.now() - 24 * 3600 * 1000;
  const s = await api('/api/admin/summary?since=' + since);
  const acts = (s.by_action || []).map((x) => `<span class="tag">${escapeHtml(x.action)} ${x.n}</span>`).join(' ');
  const actors = (s.by_actor || []).slice(0, 10).map((x) => `<span class="tag">${escapeHtml(x.actor)} ${x.n}</span>`).join(' ');
  $('summary').innerHTML = `<div><b>actions</b><br>${acts || '—'}</div><div style="margin-top:8px"><b>actors</b><br>${actors || '—'}</div>`;
}

// --- events ---------------------------------------------------------------------------------
async function runEvents() {
  const actor = $('f_actor').value.trim();
  const action = $('f_action').value.trim();
  const minutes = parseInt($('f_minutes').value || '1440', 10);
  const limit = Math.min(parseInt($('f_limit').value || '200', 10), 1000);
  const since = Date.now() - minutes * 60000;
  const q = new URLSearchParams({ since: String(since), limit: String(limit) });
  if (actor) q.set('actor', actor);
  if (action) q.set('action', action);
  setMsg($('evMsg'), 'loading…');
  const d = await api('/api/admin/log?' + q.toString());
  const evs = d.events || [];
  const tb = $('evTable').querySelector('tbody');
  tb.innerHTML = evs.map((e) => `<tr>
      <td>${fmtTs(e.ts)}</td>
      <td>${escapeHtml(e.action)}</td>
      <td>${escapeHtml(e.actorName || e.actorId || '')}</td>
      <td>${e.x},${e.y},${e.z}</td>
      <td>${escapeHtml(e.target || '')}</td>
      <td>${escapeHtml((e.before || '') + ' → ' + (e.after || ''))}</td>
      <td>${escapeHtml(e.meta || '')}</td>
    </tr>`).join('');
  setMsg($('evMsg'), evs.length + ' event(s)');
  drawDotmap(evs);
}

function drawDotmap(evs) {
  const c = $('dotmap'), g = c.getContext('2d');
  g.clearRect(0, 0, c.width, c.height);
  if (!evs.length) { g.fillStyle = '#8ba0b6'; g.fillText('no events', 12, 20); return; }
  let minX = 1e9, maxX = -1e9, minZ = 1e9, maxZ = -1e9;
  for (const e of evs) { minX = Math.min(minX, e.x); maxX = Math.max(maxX, e.x); minZ = Math.min(minZ, e.z); maxZ = Math.max(maxZ, e.z); }
  const pad = 20, w = c.width - pad * 2, h = c.height - pad * 2;
  const sx = Math.max(1, maxX - minX), sz = Math.max(1, maxZ - minZ);
  const sc = Math.min(w / sx, h / sz);
  const ox = pad + (w - sx * sc) / 2, oy = pad + (h - sz * sc) / 2;
  const colors = { 'block-break': '#ff8a5c', 'block-place': '#5cd6ff', 'entity-change-block': '#c08cff',
                   'entity-explode': '#ff5c5c', 'container-move': '#8adf6b', 'interact': '#ffd25c' };
  for (const e of evs) {
    const px = ox + (e.x - minX) * sc, py = oy + (maxZ - e.z) * sc;
    g.fillStyle = colors[e.action] || '#dfe9f3';
    g.fillRect(px - 1.5, py - 1.5, 3, 3);
  }
  g.fillStyle = '#8ba0b6';
  g.fillText(`x ${minX}..${maxX}  z ${minZ}..${maxZ}`, 10, c.height - 6);
}

// --- console --------------------------------------------------------------------------------
async function loadConsole() {
  const grep = $('c_grep').value.trim();
  const lines = parseInt($('c_lines').value || '300', 10);
  const q = new URLSearchParams({ lines: String(lines) });
  if (grep) q.set('grep', grep);
  try {
    const out = $('consoleOut');
    // only follow the tail if the user is already at the bottom - otherwise leave their scroll alone
    const atBottom = out.scrollTop + out.clientHeight >= out.scrollHeight - 24;
    const d = await api('/api/admin/console?' + q.toString());
    out.textContent = (d.lines || []).join('\n') || (d.error || '(empty)');
    if (atBottom) out.scrollTop = out.scrollHeight;
    setMsg($('cMsg'), 'updated ' + new Date().toLocaleTimeString());
  } catch (e) { setMsg($('cMsg'), String(e.message || e), true); }
}
$('c_refresh').onclick = loadConsole;

function escapeHtml(s) {
  return String(s == null ? '' : s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
}

// --- boot / refresh -------------------------------------------------------------------------
if (CODE) { loadDash().then(hideLogin).catch(() => {}); } else { showLogin(''); }
setInterval(() => { if (tab === 'dash' && CODE) loadDash().catch(() => {}); }, 10000);
setInterval(() => { if (tab === 'console' && CODE && $('c_auto').checked) loadConsole(); }, 5000);
// --- push overlays to the parent map (admin panel runs as an iframe over the real map) ----------
function sendOverlay(d) {
  if (window.parent && window.parent !== window) {
    try { window.parent.postMessage(Object.assign({ type: 'gt-overlay' }, d), '*'); } catch (e) {}
  }
}

// close button: remove the overlay iframe in the parent, or go back if opened standalone
$('closeBtn').onclick = () => {
  if (window.parent && window.parent !== window) window.parent.postMessage('gt-admin-close', '*');
  else if (history.length > 1) history.back();
};

$('runBtn').onclick = () => runEvents().catch((e) => setMsg($('evMsg'), String(e.message || e), true));

// --- A2: player heatmap + track -----------------------------------------------------------------
function worldBounds(cells, cell, points) {
  let minX = 1e9, maxX = -1e9, minZ = 1e9, maxZ = -1e9;
  for (const [gx, gz] of cells) {
    minX = Math.min(minX, gx * cell); maxX = Math.max(maxX, (gx + 1) * cell);
    minZ = Math.min(minZ, gz * cell); maxZ = Math.max(maxZ, (gz + 1) * cell);
  }
  for (const p of points) {
    minX = Math.min(minX, p[0]); maxX = Math.max(maxX, p[0]);
    minZ = Math.min(minZ, p[2]); maxZ = Math.max(maxZ, p[2]);
  }
  return { minX, maxX, minZ, maxZ, ok: minX <= maxX };
}

function projector(canvas, b) {
  const pad = 16, w = canvas.width - pad * 2, h = canvas.height - pad * 2;
  const sc = Math.min(w / Math.max(1, b.maxX - b.minX), h / Math.max(1, b.maxZ - b.minZ));
  const ox = pad + (w - (b.maxX - b.minX) * sc) / 2, oy = pad + (h - (b.maxZ - b.minZ) * sc) / 2;
  return { px: (x) => ox + (x - b.minX) * sc, py: (z) => oy + (b.maxZ - z) * sc, sc };
}

// fit [minX,minZ,maxX,maxZ] for a set of [x,y,z,...] points
function fitOf(points) {
  if (!points || !points.length) return null;
  let minX = 1e9, maxX = -1e9, minZ = 1e9, maxZ = -1e9;
  for (const p of points) {
    minX = Math.min(minX, p[0]); maxX = Math.max(maxX, p[0]);
    minZ = Math.min(minZ, p[2]); maxZ = Math.max(maxZ, p[2]);
  }
  return [minX, minZ, maxX, maxZ];
}

async function loadPlayer() {
  const actor = $('p_actor').value.trim();
  const hours = parseInt($('p_hours').value || '24', 10);
  const cell = parseInt($('p_cell').value || '16', 10);
  const since = Date.now() - hours * 3600000;
  const aq = actor ? '&actor=' + encodeURIComponent(actor) : '';
  setMsg($('p_msg'), 'loading…');
  const [heat, track] = await Promise.all([
    api(`/api/admin/heatmap?since=${since}&cell=${cell}${aq}`),
    api(`/api/admin/track?since=${since}${aq}`),
  ]);
  drawPlayer(heat.cells || [], track.points || [], cell);
  setMsg($('p_msg'), `${(heat.cells || []).length} heat cells · ${(track.points || []).length} points`);
  sendOverlay({ cells: heat.cells || [], cell, points: track.points || [], fit: fitOf(track.points) });
  const ev = await api(`/api/admin/log?since=${since}&limit=1000${aq}`);
  const by = {};
  for (const e of ev.events || []) by[e.action] = (by[e.action] || 0) + 1;
  $('p_stats').innerHTML = Object.entries(by).sort((a, b) => b[1] - a[1])
    .map(([k, v]) => `<span class="tag">${escapeHtml(k)} ${v}</span>`).join(' ') || '—';
}

function drawPlayer(cells, points, cell) {
  const c = $('playermap'), g = c.getContext('2d');
  g.clearRect(0, 0, c.width, c.height);
  const b = worldBounds(cells, cell, points);
  if (!b.ok) { g.fillStyle = '#8ba0b6'; g.fillText('no data', 12, 20); return; }
  const pr = projector(c, b);
  let maxN = 1;
  for (const x of cells) maxN = Math.max(maxN, x[2]);
  for (const [gx, gz, n] of cells) {
    g.fillStyle = `rgba(63,208,201,${(0.10 + 0.6 * (n / maxN)).toFixed(2)})`;
    g.fillRect(pr.px(gx * cell), pr.py((gz + 1) * cell), cell * pr.sc, cell * pr.sc);
  }
  if (points.length) {
    g.strokeStyle = '#ffd25c'; g.lineWidth = 1.5; g.beginPath();
    points.forEach((p, i) => { const X = pr.px(p[0]), Y = pr.py(p[2]); i ? g.lineTo(X, Y) : g.moveTo(X, Y); });
    g.stroke();
    const s = points[0], e = points[points.length - 1];
    g.fillStyle = '#8adf6b'; g.beginPath(); g.arc(pr.px(s[0]), pr.py(s[2]), 4, 0, 7); g.fill();
    g.fillStyle = '#ff8a5c'; g.beginPath(); g.arc(pr.px(e[0]), pr.py(e[2]), 4, 0, 7); g.fill();
  }
  g.fillStyle = '#8ba0b6';
  g.fillText(`x ${b.minX}..${b.maxX}   z ${b.minZ}..${b.maxZ}`, 10, c.height - 6);
}

// --- A3: rollback UI ----------------------------------------------------------------------------
function drawBlocks(canvasId, blocks) {
  const c = $(canvasId), g = c.getContext('2d');
  g.clearRect(0, 0, c.width, c.height);
  if (!blocks.length) { g.fillStyle = '#8ba0b6'; g.fillText('nothing to preview', 12, 20); return; }
  const pts = blocks.map((b) => [b[0], 0, b[2]]);
  const b = worldBounds([], 1, pts);
  const pr = projector(c, b);
  g.fillStyle = '#ff6b6b';
  for (const bl of blocks) g.fillRect(pr.px(bl[0]) - 1.5, pr.py(bl[2]) - 1.5, 3, 3);
  g.fillStyle = '#8ba0b6';
  g.fillText(`${blocks.length} block(s)`, 10, c.height - 6);
}

async function previewRollback() {
  const actor = $('r_actor').value.trim();
  const minutes = parseInt($('r_minutes').value || '60', 10);
  const radius = parseInt($('r_radius').value || '0', 10);
  const x = $('r_x').value.trim(), z = $('r_z').value.trim();
  const q = new URLSearchParams({ actor, minutes: String(minutes), r: String(radius) });
  if (x && z) { q.set('x', x); q.set('z', z); }
  setMsg($('r_msg'), 'previewing…');
  const d = await api('/api/admin/rollback/preview?' + q.toString());
  setMsg($('r_msg'), `${d.count} block(s) would change${d.capped ? ' (showing first ' + (d.blocks || []).length + ')' : ''}`);
  drawBlocks('rollmap', d.blocks || []);
  sendOverlay({ blocks: d.blocks || [], fit: fitOf((d.blocks || []).map((b) => [b[0], 0, b[2]])) });
  $('r_sample').innerHTML = (d.sample || []).map((s) =>
    `<div class="muted">${fmtTs(s.ts)} · ${escapeHtml(s.action)} · ${escapeHtml(s.actor || '')} · @${s.x},${s.y},${s.z} · ${escapeHtml(s.target || '')}</div>`).join('');
}

async function applyRollback() {
  const actor = $('r_actor').value.trim();
  const minutes = parseInt($('r_minutes').value || '60', 10);
  const radius = parseInt($('r_radius').value || '0', 10);
  if (!actor) { setMsg($('r_msg'), 'actor required', true); return; }
  if (!confirm(`Apply rollback of "${actor}" (last ${minutes} min${radius ? ', radius ' + radius : ''})?`)) return;
  setMsg($('r_msg'), 'applying…');
  const r = await fetch(`/api/admin/rollback/apply?actor=${encodeURIComponent(actor)}&minutes=${minutes}&r=${radius}`,
    { method: 'POST', headers: CODE ? { 'X-GT-Code': CODE } : {} });
  const d = await r.json();
  setMsg($('r_msg'), (d.output || [d.error || 'done']).join('  |  '));
  loadAudit();
}

async function undoRollback() {
  const id = $('u_id').value.trim();
  if (!id) return;
  setMsg($('u_msg'), 'undoing…');
  const r = await fetch(`/api/admin/rollback/undo?id=${encodeURIComponent(id)}`,
    { method: 'POST', headers: CODE ? { 'X-GT-Code': CODE } : {} });
  const d = await r.json();
  setMsg($('u_msg'), (d.output || [d.error || 'done']).join('  |  '));
  loadAudit();
}

async function loadAudit() {
  try {
    const d = await api('/api/admin/audit?limit=100');
    $('auditTable').querySelector('tbody').innerHTML = (d.rows || []).map((r) =>
      `<tr><td>${r.id}</td><td>${fmtTs(r.ts)}</td><td>${escapeHtml(r.actor || '')}</td><td>${escapeHtml(r.meta || '')}</td></tr>`).join('');
  } catch (e) { /* not on this tab yet */ }
}

// --- A4: containers -----------------------------------------------------------------------------
async function findContainers() {
  const world = $('c_world').value.trim() || 'world';
  const x = $('ct_x').value.trim(), z = $('ct_z').value.trim();
  const r = parseInt($('ct_r').value || '64', 10);
  const q = new URLSearchParams({ world, r: String(r) });
  if (x && z) { q.set('x', x); q.set('z', z); }
  setMsg($('ct_msg'), 'loading…');
  const d = await api('/api/admin/containers?' + q.toString());
  const tb = $('ctTable').querySelector('tbody');
  tb.innerHTML = (d.rows || []).map((c) => {
    const n = (c.contents || []).length;
    return `<tr data-w="${escapeHtml(c.world)}" data-x="${c.x}" data-y="${c.y}" data-z="${c.z}">
      <td>${escapeHtml(c.kind || '')}</td><td>${c.x},${c.y},${c.z}</td><td>${fmtTs(c.updated)}</td><td>${n} stack(s)</td></tr>`;
  }).join('');
  tb.querySelectorAll('tr').forEach((tr) => {
    tr.style.cursor = 'pointer';
    tr.onclick = () => showContainer(tr.dataset.w, +tr.dataset.x, +tr.dataset.y, +tr.dataset.z);
  });
  setMsg($('ct_msg'), (d.rows || []).length + ' container(s)');
}

async function showContainer(world, x, y, z) {
  const d = await api(`/api/admin/container?world=${encodeURIComponent(world)}&x=${x}&y=${y}&z=${z}`);
  const items = (d.contents || []).map((s) => `<span class="tag">slot ${s.s} ${escapeHtml(s.id)} x${s.n}</span>`).join(' ');
  const evs = (d.events || []).map((e) =>
    `<div class="muted">${fmtTs(e.ts)} · ${escapeHtml(e.action)} · ${escapeHtml(e.actor || '')} · ${escapeHtml(e.target || '')} ${escapeHtml(e.before || '')}→${escapeHtml(e.after || '')}</div>`).join('');
  $('ct_detail').innerHTML = `<b>${escapeHtml(d.kind || 'container')} @ ${x},${y},${z}</b> (${fmtTs(d.updated)})<div style="margin:6px 0">${items || 'empty'}</div><b>access log</b>${evs || '<div class="muted">none</div>'}`;
}
$('ct_run').onclick = () => findContainers().catch((e) => setMsg($('ct_msg'), String(e.message || e), true));
$('p_run').onclick = () => loadPlayer().catch((e) => setMsg($('p_msg'), String(e.message || e), true));
$('r_preview').onclick = () => previewRollback().catch((e) => setMsg($('r_msg'), String(e.message || e), true));
$('r_apply').onclick = () => applyRollback().catch((e) => setMsg($('r_msg'), String(e.message || e), true));
$('u_btn').onclick = () => undoRollback().catch((e) => setMsg($('u_msg'), String(e.message || e), true));
