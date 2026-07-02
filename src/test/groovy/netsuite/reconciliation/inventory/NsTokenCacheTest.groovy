package netsuite.reconciliation.inventory

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull

/**
 * Unit coverage for the JVM-singleton OAuth2 token cache. Pure logic — no Moqui or Spark context
 * required — so it also serves as the smoke test that proves this component's build/test wiring works.
 */
class NsTokenCacheTest {

    private static final String KEY = "auth-config-1"
    private static final String TOKEN_URL = "https://1234567.suitetalk.api.netsuite.com/oauth2/token"

    private static long nowSec() { (long) (System.currentTimeMillis() / 1000L) }

    @BeforeEach
    void reset() { NsTokenCache.clearAll() }

    @Test
    void returnsCachedTokenWhenPresentAndUnexpired() {
        NsTokenCache.put(KEY, TOKEN_URL, "access-abc", nowSec() + 600L)
        assertEquals("access-abc", NsTokenCache.getValidAccessToken(KEY, TOKEN_URL))
        assertEquals(1, NsTokenCache.size())
    }

    @Test
    void returnsNullWhenExpired() {
        NsTokenCache.put(KEY, TOKEN_URL, "access-abc", nowSec() - 1L)
        assertNull(NsTokenCache.getValidAccessToken(KEY, TOKEN_URL))
    }

    @Test
    void returnsNullWhenTokenUrlDiffers() {
        NsTokenCache.put(KEY, TOKEN_URL, "access-abc", nowSec() + 600L)
        assertNull(NsTokenCache.getValidAccessToken(KEY, "https://other.suitetalk.api.netsuite.com/oauth2/token"))
    }

    @Test
    void evictForcesNextLookupToMiss() {
        NsTokenCache.put(KEY, TOKEN_URL, "access-abc", nowSec() + 600L)
        NsTokenCache.evict(KEY)
        assertNull(NsTokenCache.getValidAccessToken(KEY, TOKEN_URL))
        assertEquals(0, NsTokenCache.size())
    }

    @Test
    void nullKeyOrTokenIsNotStored() {
        NsTokenCache.put(null, TOKEN_URL, "access-abc", nowSec() + 600L)
        NsTokenCache.put(KEY, TOKEN_URL, null, nowSec() + 600L)
        assertEquals(0, NsTokenCache.size())
        assertNull(NsTokenCache.getValidAccessToken(null, TOKEN_URL))
    }
}
