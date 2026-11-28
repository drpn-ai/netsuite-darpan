package netsuite.reconciliation.inventory

import groovy.transform.CompileStatic

import java.util.concurrent.ConcurrentHashMap

/**
 * Audit H6.1 — JVM-singleton cache for NetSuite OAuth2 M2M JWT access tokens.
 *
 * A cache on a Groovy service script's @Field is reborn on every call (Moqui's Groovy executor makes a new script
 * instance per invocation), so every read would mint a brand-new JWT: extra token-endpoint round-trips, NetSuite-side
 * rate-limit / audit-log noise, and no way to invalidate-and-retry on a 401. Used by the SuiteQL extract
 * (extractNetSuiteOrders), NsSuiteQlClient and NsAgentQuerySupport; the package name is historical (the inventory RESTlet
 * path that first held it was deleted, DAR-OPS-029 S41c).
 *
 * This class hoists the cache to a static JVM-wide map keyed by an opaque authCacheKey
 * (typically the linkedAuthConfigId, falling back to nsConfigId). The script remains responsible
 * for minting tokens; the cache only stores access-token + tokenUrl + expiresAtSec.
 *
 * 401 protocol:
 *   evict(authCacheKey) — call when NetSuite returns 401 (token revoked / rotated on the
 *   NetSuite side) so the very next get() forces a fresh mint via the script's resolveOauthToken
 *   closure, then retry the restlet once.
 */
@CompileStatic
class NsTokenCache {

    private static final ConcurrentHashMap<String, Map<String, Object>> CACHE = new ConcurrentHashMap<>()

    /** Returns the cached token map only if (a) present, (b) tokenUrl matches, (c) not expired. */
    static String getValidAccessToken(String authCacheKey, String tokenUrl) {
        if (!authCacheKey) return null
        Map<String, Object> entry = CACHE.get(authCacheKey)
        if (entry == null) return null
        if (entry.get("tokenUrl") != tokenUrl) return null
        Object expiresAtRaw = entry.get("expiresAtSec")
        if (!(expiresAtRaw instanceof Number)) return null
        long expiresAtSec = ((Number) expiresAtRaw).longValue()
        long nowSec = (long) (System.currentTimeMillis() / 1000L)
        if (expiresAtSec <= nowSec) return null
        Object token = entry.get("accessToken")
        return token == null ? null : token.toString()
    }

    /** Store a freshly-minted token. expiresAtSec is the absolute epoch-second at which to expire. */
    static void put(String authCacheKey, String tokenUrl, String accessToken, long expiresAtSec) {
        if (!authCacheKey || !accessToken) return
        Map<String, Object> entry = [
                tokenUrl    : tokenUrl,
                accessToken : accessToken,
                expiresAtSec: (Object) Long.valueOf(expiresAtSec),
        ] as Map<String, Object>
        CACHE.put(authCacheKey, entry)
    }

    /** Force eviction (call on 401 so the next get() returns null and the caller mints fresh). */
    static void evict(String authCacheKey) {
        if (authCacheKey != null) CACHE.remove(authCacheKey)
    }

    /** Test/diagnostic helper. */
    static int size() {
        return CACHE.size()
    }

    /** Test-only: empties the cache (the DAR-OPS-026 seam name). Idempotent. */
    static void resetForTests() {
        CACHE.clear()
    }
}
