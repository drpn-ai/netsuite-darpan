package netsuite.reconciliation.orders

import groovy.json.JsonSlurper
import org.junit.jupiter.api.Test

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PSSParameterSpec

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * Unit coverage for NetSuite OAuth2 M2M client-assertion minting (DAR-BE-032).
 *
 * These cases exist because NetSuite answers EVERY bad client assertion with the same opaque
 * HTTP 500 {"error":"server_error"} — a wrong algorithm, a mangled PEM and a wrong key are
 * indistinguishable from the response. Anything this layer gets wrong is undiagnosable in
 * production, so it is pinned here instead.
 */
class NsOAuthTokenMinterTest {

    private static final String TOKEN_URL =
            "https://4054670.suitetalk.api.netsuite.com/services/rest/auth/oauth2/v1/token"

    private static String toPkcs8Pem(KeyPair kp) {
        String b64 = Base64.mimeEncoder.encodeToString(kp.private.encoded)
        return "-----BEGIN PRIVATE KEY-----\n${b64}\n-----END PRIVATE KEY-----\n"
    }

    private static KeyPair rsaKey() {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA"); g.initialize(2048); return g.generateKeyPair()
    }

    private static KeyPair ecKey(String curve) {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC"); g.initialize(new ECGenParameterSpec(curve)); return g.generateKeyPair()
    }

    @Test
    void anRsaKeyIsSignedPs256() {
        assertEquals("PS256", NsOAuthTokenMinter.parsePrivateKey(toPkcs8Pem(rsaKey())).alg)
    }

    @Test
    void anEcKeyPicksItsAlgorithmFromTheCurveNotFromPreference() {
        // Signing a P-384 key as ES256 produces a well-formed JWT that NetSuite rejects with the
        // opaque 500 and no hint that the curve is the problem.
        assertEquals("ES256", NsOAuthTokenMinter.parsePrivateKey(toPkcs8Pem(ecKey("secp256r1"))).alg)
        assertEquals("ES384", NsOAuthTokenMinter.parsePrivateKey(toPkcs8Pem(ecKey("secp384r1"))).alg)
    }

    @Test
    void aPemPastedThroughASingleLineFieldStillParses() {
        // A PEM entered into a one-line form field arrives with LITERAL backslash-n rather than real
        // newlines. Left unhandled this surfaces only as "Invalid RSA private key".
        String mangled = toPkcs8Pem(rsaKey()).replace("\n", "\\n")
        assertEquals("PS256", NsOAuthTokenMinter.parsePrivateKey(mangled).alg)
    }

    @Test
    void garbageIsRejectedWithAMessageNamingTheExpectedFormat() {
        def e = assertThrows(IllegalArgumentException) { NsOAuthTokenMinter.parsePrivateKey("not a pem") }
        assertTrue(e.message.toLowerCase().contains("pem"), e.message)
        assertThrows(IllegalArgumentException) { NsOAuthTokenMinter.parsePrivateKey(null) }
    }

    @Test
    void theAssertionCarriesTheIdentifiersNetSuiteMatchesOn() {
        KeyPair kp = rsaKey()
        String jwt = NsOAuthTokenMinter.buildClientAssertion(
                TOKEN_URL, "the-client-id", "the-cert-id", toPkcs8Pem(kp), "rest_webservices")

        String[] parts = jwt.split("\\.")
        assertEquals(3, parts.length, "a JWT is header.claims.signature")
        Map header = (Map) new JsonSlurper().parse(Base64.urlDecoder.decode(parts[0]))
        Map claims = (Map) new JsonSlurper().parse(Base64.urlDecoder.decode(parts[1]))

        // kid is the CERTIFICATE id and iss is the CLIENT id. Swapping them is the single most common
        // NetSuite config slip and authenticates for neither party, so the mapping is pinned here.
        assertEquals("the-cert-id", header.kid)
        assertEquals("PS256", header.alg)
        assertEquals("the-client-id", claims.iss)
        assertEquals(TOKEN_URL, claims.aud, "aud must be the token endpoint itself")
        assertEquals("rest_webservices", claims.scope)
        assertTrue(((Number) claims.exp).longValue() > ((Number) claims.iat).longValue())
    }

    @Test
    void theAssertionSignatureVerifiesAgainstTheMatchingPublicKey() {
        KeyPair kp = rsaKey()
        String jwt = NsOAuthTokenMinter.buildClientAssertion(
                TOKEN_URL, "c", "k", toPkcs8Pem(kp), "rest_webservices")
        String[] parts = jwt.split("\\.")

        Signature v = Signature.getInstance("RSASSA-PSS")
        v.setParameter(new PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1))
        v.initVerify(kp.public)
        v.update("${parts[0]}.${parts[1]}".toString().bytes)
        assertTrue(v.verify(Base64.urlDecoder.decode(parts[2])), "NetSuite must be able to verify this")
    }

    @Test
    void expiryStaysInsideNetSuitesOneHourAssertionLimit() {
        KeyPair kp = rsaKey()
        String jwt = NsOAuthTokenMinter.buildClientAssertion(TOKEN_URL, "c", "k", toPkcs8Pem(kp), "s")
        Map claims = (Map) new JsonSlurper().parse(Base64.urlDecoder.decode(jwt.split("\\.")[1]))
        long ttl = ((Number) claims.exp).longValue() - ((Number) claims.iat).longValue()
        assertTrue(ttl > 0 && ttl <= 3600, "NetSuite rejects an assertion valid for more than an hour; ttl=${ttl}")
    }
}
