package netsuite.facade

import org.junit.jupiter.api.Test

import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-114. NetSuite connection diagnostics must name the layer that failed: an unreadable key, a refused
 * token and a role that cannot run SuiteQL are three different fixes. No network — both calls are seams.
 */
class NsConnectionProbeTests {

    private static final String TOKEN_URL = "https://1234.suitetalk.api.netsuite.com/services/rest/auth/oauth2/v1/token"

    private static String pem() {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC")
        g.initialize(new ECGenParameterSpec("secp256r1"))
        String b64 = Base64.mimeEncoder.encodeToString(g.generateKeyPair().private.encoded)
        return "-----BEGIN PRIVATE KEY-----\n${b64}\n-----END PRIVATE KEY-----\n"
    }

    private static Map<String, Object> config(Map overrides = [:]) {
        return ([
                nsAuthConfigId: "rails_prod",
                authType      : "OAUTH2_M2M_JWT",
                tokenUrl      : TOKEN_URL,
                clientId      : "c" * 64,
                certId        : "k" * 43,
                privateKeyPem : pem(),
                scope         : "restlets rest_webservices",
        ] + overrides) as Map<String, Object>
    }

    private static Map<String, Object> seams(Map overrides = [:]) {
        return ([
                mintToken: { -> "token" },
                query    : { String sql -> [[id: 1]] },
        ] + overrides) as Map<String, Object>
    }

    private static Map<String, Object> runAll(Map<String, Object> config, Map<String, Object> seams) {
        List checks = []
        String stage = NsConnectionProbe.STAGE_FIRST
        while (stage) {
            Map<String, Object> result = NsConnectionProbe.probeStage(config, stage, seams)
            checks.addAll(result.checks as List)
            stage = result.nextStage as String
        }
        return [checks: checks]
    }

    @Test
    void aWorkingProfilePassesEveryStageInOrder() {
        List<String> asked = []
        Map result = runAll(config(), seams(query: { String sql -> asked << sql; [[id: 7]] }))

        assertEquals(["credential", "token", "query"], result.checks*.key)
        assertTrue(result.checks.every { it.status == "PASS" }, "${result.checks}")
        assertEquals([NsConnectionProbe.PROBE_QUERY], asked)
    }

    @Test
    void stagesHandOnToTheNextAndTheLastEndsTheWalk() {
        assertEquals("token", NsConnectionProbe.probeStage(config(), NsConnectionProbe.STAGE_FIRST, seams()).nextStage)
        assertEquals("query", NsConnectionProbe.probeStage(config(), "token", seams()).nextStage)
        assertNull(NsConnectionProbe.probeStage(config(), "query", seams()).nextStage)
    }

    @Test
    void anythingButOauthIsRefusedWithoutTouchingTheNetwork() {
        boolean called = false
        Map result = runAll(config(authType: "BASIC"), seams(mintToken: { -> called = true; "t" }))

        // The stages a failure makes meaningless are still listed, as SKIP, so the popup shows the whole walk.
        assertEquals(["FAIL", "SKIP", "SKIP"], result.checks*.status)
        assertTrue((result.checks[0].detail as String).contains("OAuth2"))
        assertFalse(called)
    }

    @Test
    void missingOauthFieldsAreNamed() {
        Map result = runAll(config(clientId: "", privateKeyPem: null), seams())

        assertEquals(["FAIL", "SKIP", "SKIP"], result.checks*.status)
        assertTrue((result.checks[0].detail as String).contains("Client ID"))
        assertTrue((result.checks[0].detail as String).contains("private key"))
    }

    @Test
    void anUnreadableKeyStopsBeforeAnyCall() {
        boolean called = false
        Map result = runAll(config(privateKeyPem: "-----BEGIN PRIVATE KEY-----\nnot-a-key\n-----END PRIVATE KEY-----"),
                seams(mintToken: { -> called = true; "t" }))

        assertEquals(["credential", "token", "query"], result.checks*.key)
        assertEquals(["FAIL", "SKIP", "SKIP"], result.checks*.status)
        assertFalse(called)
    }

    @Test
    void aTokenUrlOffNetSuiteIsRefusedBeforeAnyCall() {
        boolean called = false
        Map result = runAll(config(tokenUrl: "https://attacker.example.com/token"), seams(mintToken: { -> called = true; "t" }))

        assertEquals("FAIL", result.checks[0].status)
        assertFalse(called)
    }

    @Test
    void aRefusedTokenNamesTheStatusAndTheSwappedIdTrap() {
        Map result = runAll(config(), seams(mintToken: { -> throw new IllegalStateException("NetSuite token endpoint returned 500. Check ...") }))

        assertEquals(["PASS", "FAIL", "SKIP"], result.checks*.status)
        Map token = result.checks[1] as Map
        assertEquals("FAIL", token.status)
        assertTrue((token.detail as String).contains("500"))
        assertTrue((token.detail as String).contains("Certificate ID"))
    }

    @Test
    void aRefusedQueryNamesTheRolePermissionWithoutEchoingTheResponseBody() {
        Map result = runAll(config(), seams(query: { String sql ->
            throw new IllegalStateException("NetSuite SuiteQL returned 403: {\"o:errorDetails\":[{\"detail\":\"secret body\"}]}")
        }))

        Map query = result.checks[2] as Map
        assertEquals("FAIL", query.status)
        assertTrue((query.detail as String).contains("403"))
        assertTrue((query.detail as String).contains("SuiteQL"))
        assertFalse((query.detail as String).contains("secret body"))
    }

    @Test
    void everyRowCarriesItsOwnTiming() {
        Map result = runAll(config(), seams())
        assertTrue(result.checks.every { it.durationMillis instanceof Long }, "${result.checks}")
    }
}
