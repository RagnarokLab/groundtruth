package club.footlickers.groundtruth.dumper;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal NBT reader (enough for Anvil chunk data). Strings via readUTF (ASCII ids are fine). */
public final class Nbt {

    private Nbt() {}

    public static Map<String, Object> parse(InputStream in) throws IOException {
        DataInputStream d = new DataInputStream(in);
        int type = d.readUnsignedByte();
        d.readUTF(); // root name
        Object root = readPayload(d, type);
        if (!(root instanceof Map)) throw new IOException("root is not a compound (tag " + type + ")");
        @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) root;
        return m;
    }

    private static Object readPayload(DataInputStream d, int t) throws IOException {
        switch (t) {
            case 1: return d.readByte();
            case 2: return d.readShort();
            case 3: return d.readInt();
            case 4: return d.readLong();
            case 5: return d.readFloat();
            case 6: return d.readDouble();
            case 7: {
                int n = d.readInt();
                byte[] b = new byte[n];
                d.readFully(b);
                return b;
            }
            case 8: return d.readUTF();
            case 9: {
                int et = d.readUnsignedByte();
                int n = d.readInt();
                List<Object> l = new ArrayList<>(n);
                for (int i = 0; i < n; i++) l.add(readPayload(d, et));
                return l;
            }
            case 10: {
                Map<String, Object> m = new LinkedHashMap<>();
                while (true) {
                    int tt = d.readUnsignedByte();
                    if (tt == 0) break;
                    String name = d.readUTF();
                    m.put(name, readPayload(d, tt));
                }
                return m;
            }
            case 11: {
                int n = d.readInt();
                int[] a = new int[n];
                for (int i = 0; i < n; i++) a[i] = d.readInt();
                return a;
            }
            case 12: {
                int n = d.readInt();
                long[] a = new long[n];
                for (int i = 0; i < n; i++) a[i] = d.readLong();
                return a;
            }
            default:
                throw new EOFException("unsupported NBT tag " + t);
        }
    }

    /** Unpack a modern (non-spanning) packed long array, `bits` per value. */
    public static int[] unpack(long[] longs, int bits, int count) {
        int per = 64 / bits;
        int mask = (1 << bits) - 1;
        int[] out = new int[count];
        for (int i = 0; i < count; i++) {
            long l = longs[i / per];
            out[i] = (int) ((l >>> ((i % per) * bits)) & mask);
        }
        return out;
    }

    /** Serialise a parsed NBT value tree to JSON (byte arrays become base64). */
    public static String toJson(Object v) {
        StringBuilder sb = new StringBuilder();
        writeJson(sb, v);
        return sb.toString();
    }

    private static void writeJson(StringBuilder sb, Object v) {
        if (v == null) { sb.append("null"); return; }
        if (v instanceof byte[] b) { sb.append('"').append(java.util.Base64.getEncoder().encodeToString(b)).append('"'); return; }
        if (v instanceof int[] a) {
            sb.append('[');
            for (int i = 0; i < a.length; i++) { if (i > 0) sb.append(','); sb.append(a[i]); }
            sb.append(']');
            return;
        }
        if (v instanceof long[] a) {
            sb.append('[');
            for (int i = 0; i < a.length; i++) { if (i > 0) sb.append(','); sb.append(a[i]); }
            sb.append(']');
            return;
        }
        if (v instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                esc(sb, String.valueOf(e.getKey()));
                sb.append(':');
                writeJson(sb, e.getValue());
            }
            sb.append('}');
            return;
        }
        if (v instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object o : (List<?>) v) { if (!first) sb.append(','); first = false; writeJson(sb, o); }
            sb.append(']');
            return;
        }
        if (v instanceof String s) { esc(sb, s); return; }
        if (v instanceof Float f) { sb.append(Float.isFinite(f) ? f.toString() : "0"); return; }
        if (v instanceof Double d) { sb.append(Double.isFinite(d) ? d.toString() : "0"); return; }
        if (v instanceof Number) { sb.append(v.toString()); return; }
        esc(sb, String.valueOf(v));
    }

    private static void esc(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20 || c == 0x7f) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }
}
