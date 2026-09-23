package club.footlickers.groundtruth.mod;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.Inflater;

/**
 * Parses a prerendered 3D mesh tile (".gtmesh", deflated, magic "GTM1" or "GTM2").
 *
 * <p>A tile is a 10x10-chunk area: {@code baseY} plus an opaque and a water mesh of vertices
 * (position, block-unit uv, tint colour, normal, atlas rect). Mirrors {@code gt_tile.js} on the
 * server, so the in-game view draws exactly what the web map does.
 *
 * <p>GTM1 stores the atlas rect as a u8 per component, which quantises a ~0.03-wide rect by half a
 * texel and bleeds the neighbouring atlas tile along every block edge; GTM2 widened it to u16. Both
 * are read here so tiles rendered before the change still load.
 */
public final class GtTile {

    private static final int MAGIC_GTM1 = 0x47544D31; // "GTM1": u8 atlas rect
    private static final int MAGIC_GTM2 = 0x47544D32; // "GTM2": u16 atlas rect

    public int baseY;
    public Mesh opaque;
    public Mesh water;

    public static final class Mesh {
        public float[] pos;   // x, y, z per vertex
        public float[] uv;    // u, v per vertex (block units; the shader fracts these)
        public float[] col;   // r, g, b 0..1 per vertex (tint)
        public float[] nor;   // x, y, z per vertex
        public float[] atile; // u0, v0, u1, v1 per vertex (atlas rect)
        public float[] aanim; // water only: strip row, frame count, fps

        public int verts() {
            return pos.length / 3;
        }
    }

    public static GtTile parse(byte[] deflated) throws Exception {
        ByteBuffer b = ByteBuffer.wrap(inflate(deflated)).order(ByteOrder.LITTLE_ENDIAN);
        int magic = b.getInt();
        if (magic != MAGIC_GTM1 && magic != MAGIC_GTM2) throw new IllegalArgumentException("not a GT mesh tile");
        boolean wideRect = magic == MAGIC_GTM2;
        boolean hasWater = b.get() == 1;
        GtTile t = new GtTile();
        t.baseY = b.getInt();
        t.opaque = readMesh(b, false, wideRect);
        t.water = hasWater ? readMesh(b, true, wideRect) : emptyMesh();
        return t;
    }

    private static Mesh readMesh(ByteBuffer b, boolean withAanim, boolean wideRect) {
        int v = b.getInt();
        Mesh m = new Mesh();
        m.pos = new float[v * 3];
        m.uv = new float[v * 2];
        m.col = new float[v * 3];
        m.nor = new float[v * 3];
        m.atile = new float[v * 4];
        if (withAanim) m.aanim = new float[v * 3];
        for (int i = 0; i < v; i++) {
            m.pos[i * 3] = b.getFloat();
            m.pos[i * 3 + 1] = b.getFloat();
            m.pos[i * 3 + 2] = b.getFloat();
            m.uv[i * 2] = b.getFloat();
            m.uv[i * 2 + 1] = b.getFloat();
            for (int c = 0; c < 3; c++) m.col[i * 3 + c] = (b.get() & 0xFF) / 255f;
            for (int c = 0; c < 3; c++) m.nor[i * 3 + c] = b.get() / 127f;
            if (wideRect) {
                for (int c = 0; c < 4; c++) m.atile[i * 4 + c] = (b.getShort() & 0xFFFF) / 65535f;
            } else {
                for (int c = 0; c < 4; c++) m.atile[i * 4 + c] = (b.get() & 0xFF) / 255f;
            }
            if (withAanim) {
                m.aanim[i * 3] = b.getFloat();
                m.aanim[i * 3 + 1] = b.getFloat();
                m.aanim[i * 3 + 2] = b.getFloat();
            }
        }
        return m;
    }

    private static Mesh emptyMesh() {
        Mesh m = new Mesh();
        m.pos = new float[0];
        m.uv = new float[0];
        m.col = new float[0];
        m.nor = new float[0];
        m.atile = new float[0];
        return m;
    }

    private static byte[] inflate(byte[] in) throws Exception {
        Inflater inf = new Inflater();
        try {
            inf.setInput(in);
            ByteArrayOutputStream out = new ByteArrayOutputStream(in.length * 4);
            byte[] buf = new byte[1 << 16];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0) {
                    if (inf.needsInput() || inf.needsDictionary()) break;
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            inf.end();
        }
    }
}
