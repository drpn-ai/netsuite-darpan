package netsuite.facade

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import netsuite.reconciliation.inventory.NsTokenCache
import netsuite.reconciliation.orders.NsOAuthTokenMinter
import netsuite.reconciliation.orders.NsSuiteQlOrderSupport

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * DAR-UI-050. One-page SuiteQL for small aggregate reads (the lifecycle builder's options), on the extractor's
 * own token path: NsOAuthTokenMinter, NsTokenCache, the shipped SuiteQL URL derivation with its SSRF check,
 * `Prefer: transient`, and one retry after a 401. The extract script keeps its own copy, untouched.
 */
class NsSuiteQlClient {

    static final int PAGE_LIMIT = 1000

    private final def authConfig
    private final String authId
    private final String tokenUrl
    private final String suiteQlUrl
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()

    NsSuiteQlClient(def authConfig) {
        this.authConfig = authConfig
        this.authId = authConfig.get("nsAuthConfigId") as String
        this.tokenUrl = authConfig.get("tokenUrl")?.toString()?.trim()
        if (!tokenUrl) throw new IllegalStateException("NetSuite account ${authId} has no token URL.")
        this.suiteQlUrl = NsSuiteQlOrderSupport.suiteQlUrlFromTokenUrl(tokenUrl)
    }

    /**
     * The rows of one page. An aggregate that would need a second page is a caller bug, so it is refused unless the
     * caller asked for the first page only (a value list sorted most common first).
     */
    List<Map> query(String sql, boolean firstPageOnly = false) {
        String token = NsTokenCache.getValidAccessToken(authId, tokenUrl) ?: mintToken()
        HttpResponse<String> resp = send(token, sql)
        if (resp.statusCode() == 401) {
            NsTokenCache.evict(authId)
            resp = send(mintToken(), sql)
        }
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("NetSuite SuiteQL returned ${resp.statusCode()}: ${resp.body()?.take(300)}")
        }
        Map parsed = new JsonSlurper().parseText(resp.body()) as Map
        if (parsed.hasMore == true && !firstPageOnly) throw new IllegalStateException("NetSuite returned more than ${PAGE_LIMIT} rows for a summary query.")
        return (parsed.items ?: []) as List<Map>
    }

    private HttpResponse<String> send(String token, String sql) {
        return http.send(HttpRequest.newBuilder(URI.create("${suiteQlUrl}?limit=${PAGE_LIMIT}&offset=0"))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Prefer", "transient")
                .header("Authorization", "Bearer ${token}")
                .POST(HttpRequest.BodyPublishers.ofString(JsonOutput.toJson([q: sql]), StandardCharsets.UTF_8))
                .build(), HttpResponse.BodyHandlers.ofString())
    }

    private String mintToken() {
        String scope = authConfig.get("scope")?.toString()?.trim() ?: "restlets rest_webservices"
        String assertion = NsOAuthTokenMinter.buildClientAssertion(tokenUrl, authConfig.get("clientId")?.toString()?.trim(),
                authConfig.get("certId")?.toString()?.trim(), authConfig.get("privateKeyPem")?.toString(), scope)
        String form = "grant_type=" + enc("client_credentials") +
                "&client_assertion_type=" + enc("urn:ietf:params:oauth:client-assertion-type:jwt-bearer") +
                "&client_assertion=" + enc(assertion)
        HttpResponse<String> resp = http.send(HttpRequest.newBuilder(URI.create(tokenUrl))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("NetSuite token endpoint returned ${resp.statusCode()}. Check that the account's " +
                    "Client ID (64 hex) and Certificate ID (43-char base64url) are not swapped.")
        }
        Map parsed = new JsonSlurper().parseText(resp.body()) as Map
        String token = parsed.access_token?.toString()
        long expiresAtSec = (long) (System.currentTimeMillis() / 1000L) + ((parsed.expires_in ?: 3600) as long) - 60L
        NsTokenCache.put(authId, tokenUrl, token, expiresAtSec)
        return token
    }

    private static String enc(String v) { URLEncoder.encode(v, StandardCharsets.UTF_8) }
}
