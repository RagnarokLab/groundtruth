package club.footlickers.groundtruth.mod;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.zip.Inflater;

/**
 * Builds a per-block image from an /api/detail response.
 *
 * <p>Each chunk in the response carries 16x16 block colours (the true block colour, no shading baked
 * in) and 16x16 heights, both deflated. The tile pyramid bottoms out at one pixel per chunk, so this
 * is what makes a close-in view show real blocks. The height layer is applied as a darken-only relief
 * shade - brightening must never happen or high ground washes out - matching the web viewer so the two
 * look the same.
 */
final class GtDetail {

    private GtDetail() {
    }

    /**
     * Compose the chunks into one image, one pixel per block, positioned by chunk coordinate.
     * Returns null when the response has nothing usable.
     */
    static NativeImage compose(String json, int cx0, int cz0, int cx1, int cz1) {
        if (json == null) return null;
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
        JsonElement chunksEl = root.get("chunks");
        if (chunksEl == null || !chunksEl.isJsonArray()) return null;
        JsonArray chunks = chunksEl.getAsJsonArray();
        if (chunks.isEmpty()) return null;

        int w = (cx1 - cx0 + 1) * 16, h = (cz1 - cz0 + 1) * 16;
        if (w <= 0 || h <= 0) return null;
        NativeImage img = new NativeImage(w, h, false);
        boolean any = false;
        for (JsonElement e : chunks) {
            JsonObject c = e.getAsJsonObject();
            int cx = c.get("cx").getAsInt(), cz = c.get("cz").getAsInt();
            byte[] rgb, hgt;
            try {
                rgb = inflate(Base64.getDecoder().decode(c.get("rgb").getAsString()));
                hgt = inflate(Base64.getDecoder().decode(c.get("hgt").getAsString()));
            } catch (Exception ex) {
                continue;
            }
            if (rgb.length < 768 || hgt.length < 512) continue;
            int ox = (cx - cx0) * 16, oz = (cz - cz0) * 16;
            if (ox < 0 || oz < 0 || ox + 16 > w || oz + 16 > h) continue;

            // one shade range per chunk, so a cliff does not flatten the chunk next to it
            int minY = 0, maxY = 0;
            for (int i = 0; i < 256; i++) {
                int v = (hgt[i * 2] & 0xFF) | ((hgt[i * 2 + 1] & 0xFF) << 8);
                if (i == 0 || v < minY) minY = v;
                if (i == 0 || v > maxY) maxY = v;
            }
            int span = Math.max(8, maxY - minY);
            for (int i = 0; i < 256; i++) {
                int v = (hgt[i * 2] & 0xFF) | ((hgt[i * 2 + 1] & 0xFF) << 8);
                float t = Math.max(0f, Math.min(1f, (v - minY) / (float) span));
                float sh = 0.78f + 0.22f * t;
                int r = Math.min(255, Math.round((rgb[i * 3] & 0xFF) * sh));
                int g = Math.min(255, Math.round((rgb[i * 3 + 1] & 0xFF) * sh));
                int b = Math.min(255, Math.round((rgb[i * 3 + 2] & 0xFF) * sh));
                img.setPixelABGR(ox + (i % 16), oz + (i / 16), 0xFF000000 | (b << 16) | (g << 8) | r);
            }
            any = true;
        }
        if (!any) {
            img.close();
            return null;
        }
        return img;
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
