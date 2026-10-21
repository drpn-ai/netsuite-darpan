package netsuite.reconciliation.orders

import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration

/**
 * DAR-BE-056. One SuiteQL page, retried a bounded number of times for the failures a retry can fix.
 *
 * Before this, a page that timed out threw straight out of the page fetcher and aborted the extraction: every page
 * already fetched was discarded and the extract reported recordCount=0 (the 2026-08 month re-run of DAR-BE-045 died
 * this way after 280s). Retrying the page costs one request and a few seconds; the automation's own retry re-reads
 * the whole window from offset 0. Same shape as darpan-hotwax OmsReturnsSourceSupport.requestReturnsPage.
 *
 * Retried: a request timeout or any other I/O failure of the request (a dropped connection), and HTTP 429, 502, 503,
 * 504 ("momentarily unable", not "the request is wrong"). Not retried: 500 and every 4xx, which are deterministic;
 * a 401 goes back to the caller, which re-mints the token. An interrupt is never retried.
 */
class NsSuiteQlPageRetry {

    /**
     * Per-request timeout. 120s failed a month-scale window that the chain probe (NetSuiteChainIntegrityLiveProbe,
     * 180s) read successfully the same afternoon: the anti-join templates cost scales with the WINDOW, not the page,
     * so every page of a wide window is slower. The extract service runs with transaction="ignore", so no
     * transaction timeout sits under this.
     */
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(180)
    /** Attempts per page, including the first. */
    static final int MAX_ATTEMPTS = 3
    /** Linear backoff: after failed attempt N, wait N x this. A NetSuite that just timed out needs time to drain. */
    static final long BACKOFF_STEP_MILLIS = 5000L
    static final Set<Integer> RETRYABLE_STATUS_CODES = [429, 502, 503, 504].toSet().asImmutable()

    private static final Closure DEFAULT_SLEEPER = { long ms -> sleepQuietly(ms) }

    /**
     * Sends one page through {@code request}, retrying as above. Returns the first response that is not retryable
     * (2xx, 401, 500, other 4xx) for the caller to judge. Throws IllegalStateException naming the page offset and the
     * attempts spent when every attempt failed retryably. Each retry adds one line to {@code warnings}.
     *
     * @param request one HTTP request for the page; called once per attempt
     * @param sleeper test seam: millis -> void
     */
    static HttpResponse<String> send(Closure<HttpResponse<String>> request, int offset, List<String> warnings,
                                     Closure sleeper = DEFAULT_SLEEPER) {
        String lastFailure = null
        IOException lastError = null
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpResponse<String> response = request.call()
                if (!RETRYABLE_STATUS_CODES.contains(response.statusCode())) return response
                lastError = null
                lastFailure = "returned status ${response.statusCode()}".toString() +
                        (response.body() ? ": ${response.body().take(300)}" : "")
            } catch (HttpTimeoutException e) {
                lastError = e
                lastFailure = "timed out after ${REQUEST_TIMEOUT.seconds}s".toString()
            } catch (IOException e) {
                lastError = e
                lastFailure = "failed: ${e.message ?: e.class.simpleName}".toString()
            }
            if (attempt == MAX_ATTEMPTS) break
            warnings?.add("NetSuite SuiteQL page at offset ${offset} ${lastFailure}; retrying (attempt ${attempt + 1} of ${MAX_ATTEMPTS}).".toString())
            sleeper.call(BACKOFF_STEP_MILLIS * attempt)
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("NetSuite SuiteQL page at offset ${offset}: interrupted while waiting to retry.")
            }
        }
        throw new IllegalStateException("NetSuite SuiteQL page at offset ${offset} failed after ${MAX_ATTEMPTS} attempts; the last ${lastFailure}.", lastError)
    }

    /** Restores the interrupt flag rather than swallowing it, so a cancel mid-backoff stays visible. */
    private static void sleepQuietly(long millis) {
        if (millis <= 0L) return
        try {
            Thread.sleep(millis)
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt()
        }
    }
}
