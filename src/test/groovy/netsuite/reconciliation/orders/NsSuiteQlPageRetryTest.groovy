package netsuite.reconciliation.orders

import org.junit.jupiter.api.Test

import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertSame
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-056 — one SuiteQL page is retried for what a retry can fix, and a page that cannot be read says which page
 * and how it failed. Before this, one timed-out page discarded the whole extract (recordCount=0).
 */
class NsSuiteQlPageRetryTest {

    private final List<Long> slept = []
    private final Closure sleeper = { long ms -> slept << ms }

    private static HttpResponse<String> response(int status, String body = "") {
        return [statusCode: { -> status }, body: { -> body }] as HttpResponse<String>
    }

    /** Plays the given outcomes in order: an Integer is a response status, a Throwable is thrown. */
    private static Closure<HttpResponse<String>> server(List outcomes, List<Integer> calls) {
        return { ->
            def next = outcomes[calls.size()]
            calls << calls.size()
            if (next instanceof Throwable) throw (Throwable) next
            return response(next as int, next == 504 ? "gateway timeout" : "{}")
        }
    }

    @Test
    void aTimedOutPageIsRetriedAndTheExtractCarriesOn() {
        List<Integer> calls = []
        List<String> warnings = []
        HttpResponse<String> out = NsSuiteQlPageRetry.send(server([new HttpTimeoutException("request timed out"), 200], calls),
                2000, warnings, sleeper)
        assertEquals(200, out.statusCode())
        assertEquals(2, calls.size())
        assertEquals(["NetSuite SuiteQL page at offset 2000 timed out after 180s; retrying (attempt 2 of 3)."], warnings)
        assertEquals([5000L], slept)
    }

    @Test
    void aDroppedConnectionIsRetriedWithALongerWaitEachTime() {
        List<Integer> calls = []
        List<String> warnings = []
        HttpResponse<String> out = NsSuiteQlPageRetry.send(server([new IOException("Connection reset"), new IOException("Connection reset"), 200], calls),
                0, warnings, sleeper)
        assertEquals(200, out.statusCode())
        assertEquals([5000L, 10000L], slept, "linear backoff")
        assertEquals(2, warnings.size())
        assertTrue(warnings[1].contains("failed: Connection reset; retrying (attempt 3 of 3)"), warnings[1])
    }

    @Test
    void aGatewayThatNeverRecoversFailsNamingThePageAndTheAttempts() {
        List<Integer> calls = []
        List<String> warnings = []
        IllegalStateException e = assertThrows(IllegalStateException) {
            NsSuiteQlPageRetry.send(server([504, 504, 504], calls), 4000, warnings, sleeper)
        }
        assertEquals("NetSuite SuiteQL page at offset 4000 failed after 3 attempts; the last returned status 504: gateway timeout.", e.message)
        assertEquals(3, calls.size())
        assertEquals([5000L, 10000L], slept, "no wait after the last attempt")
        assertEquals(2, warnings.size())
    }

    @Test
    void threeTimeoutsFailWithTheTimeoutAsTheCause() {
        HttpTimeoutException timeout = new HttpTimeoutException("request timed out")
        IllegalStateException e = assertThrows(IllegalStateException) {
            NsSuiteQlPageRetry.send(server([timeout, timeout, timeout], []), 0, [], sleeper)
        }
        assertEquals("NetSuite SuiteQL page at offset 0 failed after 3 attempts; the last timed out after 180s.", e.message)
        assertSame(timeout, e.cause)
    }

    @Test
    void retryableStatusesAreRetriedAndOthersGoStraightBack() {
        [429, 502, 503, 504].each { int status ->
            List<Integer> calls = []
            assertEquals(200, NsSuiteQlPageRetry.send(server([status, 200], calls), 0, [], sleeper).statusCode(), "${status}")
            assertEquals(2, calls.size(), "${status} is retried")
        }
        slept.clear()
        [200, 400, 401, 500].each { int status ->
            List<Integer> calls = []
            List<String> warnings = []
            assertEquals(status, NsSuiteQlPageRetry.send(server([status], calls), 0, warnings, sleeper).statusCode())
            assertEquals([1, 0], [calls.size(), warnings.size()], "${status} goes back to the caller as it is")
        }
        assertEquals([], slept)
    }

    @Test
    void anInterruptDuringTheWaitStopsTheRetry() {
        List<Integer> calls = []
        try {
            IllegalStateException e = assertThrows(IllegalStateException) {
                NsSuiteQlPageRetry.send(server([504, 200], calls), 1000, [], { long ms -> Thread.currentThread().interrupt() })
            }
            assertTrue(e.message.contains("interrupted"), e.message)
            assertEquals(1, calls.size(), "no request after the interrupt")
        } finally {
            assertTrue(Thread.interrupted(), "the interrupt flag is kept for the caller")
        }
    }

    @Test
    void theExtractorSendsEveryPageThroughTheRetryWithTheLongerTimeout() {
        assertEquals(180L, NsSuiteQlPageRetry.REQUEST_TIMEOUT.seconds, "the probe's proven month-scale value")
        String script = Files.readString(componentRoot().resolve("src/main/groovy/netsuite/reconciliation/orders/extractNetSuiteOrders.groovy"))
        assertEquals(2, script.count("NsSuiteQlPageRetry.send("), "the first send and the send after a 401")
        assertTrue(script.contains(".timeout(NsSuiteQlPageRetry.REQUEST_TIMEOUT)"))
        assertFalse(script.contains("Duration.ofSeconds(120)"), "no second timeout value")
        assertTrue(script.contains("+ pageRetryWarnings"), "retry warnings reach the extract's warnings")
    }

    private static Path componentRoot() {
        Path here = Paths.get("").toAbsolutePath()
        for (Path p : [here, here.resolve("runtime/component/netsuite-darpan")]) {
            if (Files.exists(p.resolve("src/main/groovy/netsuite/reconciliation/orders/extractNetSuiteOrders.groovy"))) return p
        }
        throw new IllegalStateException("netsuite-darpan component root not found from ${here}")
    }
}
