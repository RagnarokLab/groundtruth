/*
 * GroundTruth prerendered 3D tile format.
 *
 * A tile is the output of gt_mesher.buildGeometry (opaque + water + baseY) serialised as compact
 * binary and deflated. The browser loads these prebuilt meshes and uploads them straight to the GPU -
 * no meshing, which is what made the old client slow and memory-hungry.
 *
 * Layout (big-endian-free, little-endian, then deflateSync the whole thing):
 *   u8[4]  magic "GTM1"
 *   u8     flags            (bit0 = has a water mesh)
 *   i32    baseY
 *   then the opaque mesh, then the water mesh (if flagged). Each mesh:
 *     u32  vertexCount V
 *     per vertex (30 bytes):  pos f32*3, uv f32*2, col u8*3, nor i8*3, atile u8*4
 *     water only: per vertex aanim f32*3 (strip row, frame count, fps)
 *   (non-indexed on purpose: the greedy mesher already merges faces, and 6 verts/face keeps the
 *    client loader trivial. Indexing is a later size optimisation.)
 *
 * Quantisation is deliberately visually lossless: colour/normal/atile carry 1/255..1/127 steps.
 */
'use strict';
const zlib = require('zlib');

const MAGIC = 'GTM1';

function i8(n) { n = Math.round(n); return n < -128 ? -128 : n > 127 ? 127 : n; }
function u8(n) { n = Math.round(n); return n < 0 ? 0 : n > 255 ? 255 : n; }

function encodeMesh(mesh, withAanim) {
  const V = mesh.pos.length / 3;
  const stride = 3 * 4 + 2 * 4 + 3 + 3 + 4 + (withAanim ? 3 * 4 : 0);
  const buf = Buffer.alloc(4 + V * stride);
  let o = 0;
  buf.writeUInt32LE(V, o); o += 4;
  for (let i = 0; i < V; i++) {
    buf.writeFloatLE(mesh.pos[i * 3], o); o += 4;
    buf.writeFloatLE(mesh.pos[i * 3 + 1], o); o += 4;
    buf.writeFloatLE(mesh.pos[i * 3 + 2], o); o += 4;
    buf.writeFloatLE(mesh.uv[i * 2], o); o += 4;
    buf.writeFloatLE(mesh.uv[i * 2 + 1], o); o += 4;
    buf.writeUInt8(u8(mesh.col[i * 3] * 255), o); o += 1;
    buf.writeUInt8(u8(mesh.col[i * 3 + 1] * 255), o); o += 1;
    buf.writeUInt8(u8(mesh.col[i * 3 + 2] * 255), o); o += 1;
    buf.writeInt8(i8(mesh.nor[i * 3] * 127), o); o += 1;
    buf.writeInt8(i8(mesh.nor[i * 3 + 1] * 127), o); o += 1;
    buf.writeInt8(i8(mesh.nor[i * 3 + 2] * 127), o); o += 1;
    buf.writeUInt8(u8(mesh.atile[i * 4] * 255), o); o += 1;
    buf.writeUInt8(u8(mesh.atile[i * 4 + 1] * 255), o); o += 1;
    buf.writeUInt8(u8(mesh.atile[i * 4 + 2] * 255), o); o += 1;
    buf.writeUInt8(u8(mesh.atile[i * 4 + 3] * 255), o); o += 1;
    if (withAanim) {
      buf.writeFloatLE(mesh.aanim[i * 3], o); o += 4;
      buf.writeFloatLE(mesh.aanim[i * 3 + 1], o); o += 4;
      buf.writeFloatLE(mesh.aanim[i * 3 + 2], o); o += 4;
    }
  }
  return buf;
}

function encodeTile(mesh) {
  const hasWater = !!(mesh.water && mesh.water.pos && mesh.water.pos.length);
  const header = Buffer.alloc(9);
  header.write(MAGIC, 0, 'ascii');
  header.writeUInt8(hasWater ? 1 : 0, 4);
  header.writeInt32LE(mesh.baseY, 5);
  const opaque = encodeMesh(mesh.opaque, false);
  const water = hasWater ? encodeMesh(mesh.water, true) : Buffer.alloc(4);
  return zlib.deflateSync(Buffer.concat([header, opaque, water]));
}

function decodeMesh(buf, start, withAanim) {
  const V = buf.readUInt32LE(start);
  const stride = 3 * 4 + 2 * 4 + 3 + 3 + 4 + (withAanim ? 3 * 4 : 0);
  const mesh = { pos: [], uv: [], col: [], nor: [], atile: [], aanim: withAanim ? [] : null };
  let o = start + 4;
  for (let i = 0; i < V; i++) {
    mesh.pos.push(buf.readFloatLE(o), buf.readFloatLE(o + 4), buf.readFloatLE(o + 8)); o += 12;
    mesh.uv.push(buf.readFloatLE(o), buf.readFloatLE(o + 4)); o += 8;
    mesh.col.push(buf.readUInt8(o) / 255, buf.readUInt8(o + 1) / 255, buf.readUInt8(o + 2) / 255); o += 3;
    mesh.nor.push(buf.readInt8(o) / 127, buf.readInt8(o + 1) / 127, buf.readInt8(o + 2) / 127); o += 3;
    mesh.atile.push(buf.readUInt8(o) / 255, buf.readUInt8(o + 1) / 255,
      buf.readUInt8(o + 2) / 255, buf.readUInt8(o + 3) / 255); o += 4;
    if (withAanim) {
      mesh.aanim.push(buf.readFloatLE(o), buf.readFloatLE(o + 4), buf.readFloatLE(o + 8)); o += 12;
    }
  }
  return { mesh, end: o };
}

function decodeTile(blob) {
  const buf = zlib.inflateSync(blob);
  if (buf.toString('ascii', 0, 4) !== MAGIC) throw new Error('not a GT mesh tile');
  const hasWater = buf.readUInt8(4) === 1;
  const baseY = buf.readInt32LE(5);
  const opaque = decodeMesh(buf, 9, false);
  const water = hasWater ? decodeMesh(buf, opaque.end, true) : { mesh: { pos: [], uv: [], col: [], nor: [], atile: [], aanim: null }, end: opaque.end };
  return { baseY, opaque: opaque.mesh, water: water.mesh };
}

module.exports = { encodeTile, decodeTile, MAGIC };
