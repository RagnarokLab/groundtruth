package club.footlickers.groundtruth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Mints the one login code that everyone uses. The code is a signed, self-describing token:
 *
 *   base64url(payload) . base64url(HMAC-SHA256(secret, base64url(payload)))
 *
 * payload = {"u":uuid,"n":name,"a":0|1,"e":expiryMillis,"j":randomId}
 *
 * The web verifies it with the same secret (no shared database needed). Player codes are one-shot -
 * the web marks the jti as used; admin codes are reusable so an admin stays logged in.
 */
public final class Auth {

    private static final SecureRandom RNG = new SecureRandom();
    private final SecretKeySpec key;

    public Auth(String secret) {
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    public static String randomSecret() {
        byte[] b = new byte[32];
        RNG.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public static String randomId() {
        byte[] b = new byte[9];
        RNG.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** @param ttlMs how long the code stays valid (players short, admins per config). */
    public String token(String uuid, String name, boolean admin, boolean rollback, long ttlMs) {
        long exp = System.currentTimeMillis() + ttlMs;
        String payload = "{\"u\":\"" + esc(uuid) + "\",\"n\":\"" + esc(name) + "\",\"a\":" + (admin ? 1 : 0)
                + ",\"p\":" + (rollback ? 1 : 0)
                + ",\"e\":" + exp + ",\"j\":\"" + randomId() + "\"}";
        String p = b64(payload.getBytes(StandardCharsets.UTF_8));
        return p + "." + b64(hmac(p));
    }

    /** A verified caller. */
    public static final class Id {
        public final String uuid, name;
        public final boolean admin, rollback;
        Id(String uuid, String name, boolean admin, boolean rollback) {
            this.uuid = uuid; this.name = name; this.admin = admin; this.rollback = rollback;
        }
    }

    /**
     * Verify a token that this server (or the web) minted. Returns the identity, or null when the
     * signature, the shape or the expiry is wrong - never throws, callers just refuse.
     */
    public Id verify(String code) {
        if (code == null) return null;
        int dot = code.lastIndexOf('.');
        if (dot <= 0) return null;
        String payload = code.substring(0, dot);
        String sig = code.substring(dot + 1);
        String expect = b64(hmac(payload));
        if (!MessageDigest.isEqual(sig.getBytes(StandardCharsets.UTF_8),
                                   expect.getBytes(StandardCharsets.UTF_8))) return null;
        try {
            String json = new String(Base64.getUrlDecoder().decode(pad(payload)), StandardCharsets.UTF_8);
            String uuid = field(json, "u"), name = field(json, "n");
            boolean admin = "1".equals(field(json, "a"));
            boolean rollback = "1".equals(field(json, "p"));
            long exp = Long.parseLong(field(json, "e"));
            if (exp < System.currentTimeMillis()) return null;
            return new Id(uuid, name, admin, rollback);
        } catch (Exception e) {
            return null;
        }
    }

    private static String pad(String b64url) {
        int pad = (4 - b64url.length() % 4) % 4;
        return b64url + "=".repeat(pad);
    }

    /** Pull one flat field out of the payload we wrote ourselves. */
    private static String field(String json, String key) {
        String needle = "\"" + key + "\"";
        int i = json.indexOf(needle);
        if (i < 0) return null;
        i = json.indexOf(':', i + needle.length());
        if (i < 0) return null;
        i++;
        while (i < json.length() && json.charAt(i) == ' ') i++;
        if (i >= json.length()) return null;
        if (json.charAt(i) == '"') {
            StringBuilder sb = new StringBuilder();
            for (int k = i + 1; k < json.length(); k++) {
                char c = json.charAt(k);
                if (c == '\\' && k + 1 < json.length()) { sb.append(json.charAt(++k)); continue; }
                if (c == '"') break;
                sb.append(c);
            }
            return sb.toString();
        }
        int k = i;
        while (k < json.length() && ",}".indexOf(json.charAt(k)) < 0) k++;
        return json.substring(i, k).trim();
    }

    private byte[] hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("hmac failed", e);
        }
    }

    private static String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
