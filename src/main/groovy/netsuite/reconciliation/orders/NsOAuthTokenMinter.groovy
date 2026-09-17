package netsuite.reconciliation.orders

import groovy.json.JsonOutput
import groovy.transform.CompileStatic

import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.RSAPrivateKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.PSSParameterSpec

/**
 * Builds the OAuth 2.0 M2M client assertion NetSuite's token endpoint expects (DAR-BE-032).
 *
 * Mirrors the proven behaviour of netsuite-darpan's fetchNsInventoryAdjustmentsBulk.groovy, lifted
 * into a class so the SuiteQL orders path can reuse it and so the parts that are undiagnosable in
 * production can be unit-tested. NetSuite answers every bad assertion with the same opaque
 * HTTP 500 {"error":"server_error"}, so a wrong algorithm, a mangled PEM and swapped identifiers all
 * look identical from the outside.
 *
 * Token caching is deliberately NOT done here — NsTokenCache is the JVM-singleton that owns it.
 */
@CompileStatic
class NsOAuthTokenMinter {

    /** NetSuite rejects a client assertion valid for more than an hour; stay inside it with margin. */
    private static final long ASSERTION_TTL_SECONDS = 3300L

    /**
     * Parses a PKCS#8 PEM and reports the JOSE algorithm the key requires.
     *
     * Two details are load-bearing and neither is obvious. A PEM pasted through a single-line form
     * field arrives with LITERAL backslash-n instead of newlines. And for an EC key the algorithm is
     * fixed by the CURVE (P-256 => ES256, P-384 => ES384, P-521 => ES512), not by preference —
     * signing a P-384 key as ES256 yields a well-formed JWT that NetSuite silently refuses.
     *
     * @return [privateKey: PrivateKey, alg: String]
     */
    static Map<String, Object> parsePrivateKey(String pemText) {
        String normalized = pemText?.replace("\\n", "\n")?.trim()
        if (!normalized) throw new IllegalArgumentException("Private key PEM is required")

        String b64 = normalized
                .replaceAll("-----BEGIN [A-Z ]+-----", "")
                .replaceAll("-----END [A-Z ]+-----", "")
                .replaceAll("\\s", "")
        byte[] der
        try {
            der = Base64.decoder.decode(b64)
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid PEM format for private key: ${e.message}")
        }

        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der)
        try {
            PrivateKey k = KeyFactory.getInstance("RSA").generatePrivate(spec)
            return [privateKey: k, alg: "PS256", keyKind: "RSA",
                    keyBits   : ((RSAPrivateKey) k).modulus.bitLength()] as Map<String, Object>
        } catch (Exception ignored) { }
        try {
            PrivateKey k = KeyFactory.getInstance("EC").generatePrivate(spec)
            int bits = ((ECPrivateKey) k).params.order.bitLength()
            String alg = bits > 384 ? "ES512" : (bits > 256 ? "ES384" : "ES256")
            return [privateKey: k, alg: alg, keyKind: "EC", keyBits: bits] as Map<String, Object>
        } catch (Exception ignored) { }

        throw new IllegalArgumentException("Private key must be a PKCS#8 RSA or EC PEM " +
                "(decoded ${der.length} DER bytes)")
    }

    /**
     * `iss` is the integration's CLIENT ID and `kid` the CERTIFICATE ID from the OAuth 2.0 Client
     * Credentials (M2M) mapping. They are long opaque strings typed into adjacent fields and are
     * routinely entered swapped, which authenticates for neither party — hence the explicit naming
     * here and the test that pins it.
     */
    static String buildClientAssertion(String tokenUrl, String clientId, String certId,
                                       String privateKeyPem, String scope) {
        Map<String, Object> parsed = parsePrivateKey(privateKeyPem)
        PrivateKey key = (PrivateKey) parsed.get("privateKey")
        String alg = (String) parsed.get("alg")

        long now = (long) (System.currentTimeMillis() / 1000L)
        String header = JsonOutput.toJson([alg: alg, typ: "JWT", kid: certId])
        String claims = JsonOutput.toJson([
                iss  : clientId,
                scope: scope,
                aud  : tokenUrl,
                iat  : now,
                exp  : now + ASSERTION_TTL_SECONDS,
                jti  : UUID.randomUUID().toString(),
        ])
        String signingInput = b64url(header.getBytes(StandardCharsets.UTF_8)) + "." +
                b64url(claims.getBytes(StandardCharsets.UTF_8))
        return signingInput + "." + b64url(sign(signingInput, key, alg))
    }

    private static byte[] sign(String signingInput, PrivateKey key, String alg) {
        byte[] input = signingInput.getBytes(StandardCharsets.UTF_8)
        if (alg == "PS256") {
            Signature sig = Signature.getInstance("RSASSA-PSS")
            sig.setParameter(new PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1))
            sig.initSign(key)
            sig.update(input)
            return sig.sign()
        }
        String shaName = alg == "ES512" ? "SHA512withECDSA" : (alg == "ES384" ? "SHA384withECDSA" : "SHA256withECDSA")
        int joseLen = alg == "ES512" ? 132 : (alg == "ES384" ? 96 : 64)
        Signature sig = Signature.getInstance(shaName)
        sig.initSign(key)
        sig.update(input)
        return derToJose(sig.sign(), joseLen)
    }

    /** ECDSA signs to DER; JOSE wants fixed-width R||S. */
    private static byte[] derToJose(byte[] der, int outLen) {
        int off = der[1] == (byte) 0x81 ? 3 : 2
        if (der[off++] != (byte) 0x02) throw new IllegalStateException("bad DER R marker")
        int rLen = der[off++] & 0xFF
        byte[] r = Arrays.copyOfRange(der, off, off + rLen); off += rLen
        if (der[off++] != (byte) 0x02) throw new IllegalStateException("bad DER S marker")
        int sLen = der[off++] & 0xFF
        byte[] s = Arrays.copyOfRange(der, off, off + sLen)

        int part = (int) (outLen / 2)
        byte[] jose = new byte[outLen]
        System.arraycopy(fixedWidth(r, part), 0, jose, 0, part)
        System.arraycopy(fixedWidth(s, part), 0, jose, part, part)
        return jose
    }

    /** DER trims leading zeros and may add a sign byte; JOSE needs exactly `len` bytes. */
    private static byte[] fixedWidth(byte[] v, int len) {
        if (v.length == len) return v
        if (v.length > len) return Arrays.copyOfRange(v, v.length - len, v.length)
        byte[] out = new byte[len]
        System.arraycopy(v, 0, out, len - v.length, v.length)
        return out
    }

    private static String b64url(byte[] raw) { Base64.urlEncoder.withoutPadding().encodeToString(raw) }
}
