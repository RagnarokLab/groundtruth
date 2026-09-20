package club.footlickers.groundtruth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
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
