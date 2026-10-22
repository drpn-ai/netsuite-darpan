package netsuite.facade

import darpan.facade.common.SharedConfigAccessSupport
import darpan.facade.settings.SourceConnectionDiagnosticsSupport as Checks
import netsuite.reconciliation.orders.NsOAuthTokenMinter
import netsuite.reconciliation.orders.NsSuiteQlOrderSupport
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.time.Duration

import static darpan.common.ValueSupport.normalize

/**
 * DAR-BE-114. Connection diagnostics for one NetSuite OAuth2 M2M JWT auth profile, in three stages whose
 * failures have three different fixes: the stored key and ids (credential), NetSuite accepting them (token),
 * and the integration role being allowed to run SuiteQL (query).
 *
 * Other auth types are refused, not tested: no tenant reads SuiteQL with them, and a RESTlet call would need an
 * endpoint to aim at. Nothing here writes to ec.message — every outcome, a refused credential included, is a
 * check row, and no response body reaches one (only a status code).
 */
class NsConnectionProbe {
    private static final Logger logger = LoggerFactory.getLogger(NsConnectionProbe.class)

    static final String STAGE_FIRST = Checks.STAGE_FIRST
    static final String STAGE_CREDENTIAL = "credential"
    static final String STAGE_TOKEN = "token"
    static final String STAGE_QUERY = "query"

    /** One id, the cheapest read every integration role that runs Darpan's checks already has. */
    static final String PROBE_QUERY = "SELECT TOP 1 id FROM transaction"
    /** Short enough that all three stages answer well inside one request. */
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15)

    private static final Map<String, String> LABELS = [
            (STAGE_CREDENTIAL): "Credential readable",
            (STAGE_TOKEN)     : "Token issued",
            (STAGE_QUERY)     : "SuiteQL answers",
    ]

    static Map<String, Object> probeConnection(def ec, Object rawConfigId, Object stage = null) {
        String configId = normalize(rawConfigId)
        def row = configId ? SharedConfigAccessSupport.listAccessibleConfigRows(ec, SharedConfigAccessSupport.CONFIG_TYPE_NS_AUTH)
                .find { it.get("nsAuthConfigId") == configId } : null
        if (row == null) {
            return [checks   : [Checks.check(STAGE_CREDENTIAL, LABELS[STAGE_CREDENTIAL], Checks.STATUS_FAIL,
                    "The auth profile could not be loaded.")] + skippedAfter(STAGE_CREDENTIAL),
                    nextStage: null]
        }

        Map<String, Object> config
        try {
            config = [
                    nsAuthConfigId: row.get("nsAuthConfigId"),
                    authType      : row.get("authType"),
                    tokenUrl      : row.get("tokenUrl"),
                    clientId      : row.get("clientId"),
                    certId        : row.get("certId"),
                    privateKeyPem : row.get("privateKeyPem"),
                    scope         : row.get("scope"),
            ] as Map<String, Object>
        } catch (Throwable t) {
            logger.warn("NetSuite probe could not read the stored key for ${configId}: ${t.class.simpleName}")
            config = [authType: row.get("authType"), credentialError: "The stored private key could not be decrypted."]
        }

        // Built on first use, so a profile that fails the credential stage never constructs a client.
        NsSuiteQlClient client = null
        Closure<NsSuiteQlClient> clientOf = { -> client = client ?: new NsSuiteQlClient(config, REQUEST_TIMEOUT) }
        Map<String, Object> seams = [
                mintToken: { -> clientOf().mintToken() },
                query    : { String sql -> clientOf().query(sql, true) },
        ]

        String wanted = normalize(stage)
        if (wanted) return probeStage(config, wanted, seams)
        List checks = []
        String next = STAGE_FIRST
        while (next) {
            Map<String, Object> result = probeStage(config, next, seams)
            checks.addAll(result.checks as List)
            next = normalize(result.nextStage)
        }
        return [checks: checks, nextStage: null]
    }

    /** One stage: its row (plus SKIP rows for what a failure makes meaningless) and the stage to run next. */
    static Map<String, Object> probeStage(Map<String, Object> config, String stage, Map<String, Object> seams) {
        switch (normalize(stage)) {
            case STAGE_FIRST:
            case STAGE_CREDENTIAL: return credentialStage(config)
            case STAGE_TOKEN: return networkStage(STAGE_TOKEN, STAGE_QUERY) { (seams.mintToken as Closure).call() }
            case STAGE_QUERY: return networkStage(STAGE_QUERY, null) { (seams.query as Closure).call(PROBE_QUERY) }
            default: return [checks: [], nextStage: null]
        }
    }

    /** Entirely local, so a bad key or a mistyped id is never misreported as NetSuite refusing the call. */
    private static Map<String, Object> credentialStage(Map<String, Object> config) {
        long startedAt = System.currentTimeMillis()
        String failure = credentialFailure(config)
        long took = System.currentTimeMillis() - startedAt
        if (failure) {
            return [checks   : [Checks.check(STAGE_CREDENTIAL, LABELS[STAGE_CREDENTIAL], Checks.STATUS_FAIL, failure, took)] +
                    skippedAfter(STAGE_CREDENTIAL),
                    nextStage: null]
        }
        return [checks: [Checks.check(STAGE_CREDENTIAL, LABELS[STAGE_CREDENTIAL], Checks.STATUS_PASS, null, took)],
                nextStage: STAGE_TOKEN]
    }

    private static String credentialFailure(Map<String, Object> config) {
        if (normalize(config.authType)?.toUpperCase() != "OAUTH2_M2M_JWT") {
            return "Diagnostics test OAuth2 M2M JWT profiles only."
        }
        if (normalize(config.credentialError)) return normalize(config.credentialError)

        List<String> missing = []
        if (!normalize(config.tokenUrl)) missing << "token URL"
        if (!normalize(config.clientId)) missing << "Client ID"
        if (!normalize(config.certId)) missing << "Certificate ID"
        if (!normalize(config.privateKeyPem)) missing << "private key"
        if (missing) return "Missing: ${missing.join(', ')}.".toString()

        try {
            NsSuiteQlOrderSupport.suiteQlUrlFromTokenUrl(normalize(config.tokenUrl))
        } catch (IllegalArgumentException ignored) {
            return "The token URL is not a NetSuite address."
        }
        try {
            NsOAuthTokenMinter.parsePrivateKey(config.privateKeyPem as String)
        } catch (Exception ignored) {
            return "The private key could not be read. Paste the whole PEM, header and footer included."
        }
        return null
    }

    private static Map<String, Object> networkStage(String stage, String nextStage, Closure call) {
        long startedAt = System.currentTimeMillis()
        try {
            call.call()
            return [checks   : [Checks.check(stage, LABELS[stage], Checks.STATUS_PASS, null, System.currentTimeMillis() - startedAt)],
                    nextStage: nextStage]
        } catch (Throwable t) {
            logger.warn("NetSuite probe stage ${stage} failed: ${t.class.simpleName}")
            return [checks   : [Checks.check(stage, LABELS[stage], Checks.STATUS_FAIL, failureDetail(stage, t),
                    System.currentTimeMillis() - startedAt)] + skippedAfter(stage),
                    nextStage: null]
        }
    }

    /** A status code and the usual cause behind it; never the response body, which can echo request context. */
    private static String failureDetail(String stage, Throwable t) {
        String status = ((t.message ?: "") =~ /returned (\d{3})/).with { it.find() ? it.group(1) : null }
        if (stage == STAGE_TOKEN) {
            return status
                    ? "NetSuite refused the token request (HTTP ${status}). The usual cause is a Client ID and Certificate ID pasted into each other's fields.".toString()
                    : "NetSuite's token endpoint did not answer."
        }
        return status
                ? "NetSuite refused the SuiteQL query (HTTP ${status}). The integration role may lack SuiteQL (REST Web Services) permission.".toString()
                : "NetSuite's SuiteQL endpoint did not answer."
    }

    private static List<Map<String, Object>> skippedAfter(String stage) {
        List<String> order = [STAGE_CREDENTIAL, STAGE_TOKEN, STAGE_QUERY]
        return order.drop(order.indexOf(stage) + 1).collect { String later ->
            Checks.check(later, LABELS[later], Checks.STATUS_SKIP, "Not attempted.")
        }
    }
}
