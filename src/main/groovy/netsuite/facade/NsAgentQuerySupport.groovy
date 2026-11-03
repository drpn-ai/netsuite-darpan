package netsuite.facade

import darpan.agent.AgentQuerySupport
import darpan.facade.common.TenantAccessSupport
import netsuite.reconciliation.inventory.NsTokenCache

import java.time.Duration

/**
 * DAR-CLI-003. A tenant user's agent sends one fresh, read-only SuiteQL query through a NetSuite connection the
 * tenant already stores; Darpan holds the credentials. Gated as {@link NsSuiteQlQueryFacadeSupport#previewQuery} is
 * (run access on the active tenant, then an account the tenant owns or was granted and has not turned off); the
 * query text is checked, clamped, run with a 45 s HTTP timeout, logged and stripped of connection secrets by
 * {@link AgentQuerySupport#run}.
 */
class NsAgentQuerySupport {

    static final int NS_PAGE_MAX = NsSuiteQlClient.PAGE_LIMIT
    static final Duration HTTP_TIMEOUT = Duration.ofSeconds(45)

    /** Test seam: (authConfig, requestTimeout) -> client. */
    static Closure clientFactory = { def auth, Duration timeout -> new NsSuiteQlClient(auth, timeout) }

    /** The AgentQuerySupport.run answer, or null with an error on ec when the caller may not read through this account. */
    static Map runAgentQuery(def ec, Map context) {
        String authId = context.get("nsAuthConfigId")?.toString()?.trim()
        String sql = context.get("sql")?.toString()
        Map meta = [systemEnumId: "NETSUITE", connectionId: authId, queryText: sql ?: ""]
        if (!TenantAccessSupport.requireActiveTenantRunAccess(
                ec, "Your active tenant only has view access, so it cannot read NetSuite.")) {
            return null
        }
        String problem = AgentQuerySupport.checkSuiteQl(sql)
        if (problem) return AgentQuerySupport.refuse(ec, meta, problem)
        int limit
        int offset
        try {
            limit = AgentQuerySupport.clampLimit(context.get("limit"))
            offset = offsetOf(context.get("offset"))
        } catch (AgentQuerySupport.AgentQueryRefused e) {
            return AgentQuerySupport.refuse(ec, meta, e.message)
        }
        def auth = NsLifecycleOptionsSupport.accessibleAccount(ec, authId)
        if (auth == null) return null

        // Everything that needs ec is resolved here: the work closure runs on a worker thread.
        NsSuiteQlClient client
        try {
            client = (NsSuiteQlClient) clientFactory.call(auth, HTTP_TIMEOUT)
        } catch (Exception e) {
            return AgentQuerySupport.refuse(ec, meta, "NetSuite account '${authId}' cannot be used: ${e.message}".toString())
        }
        meta.secrets = secretsOf(auth)
        final String query = sql
        final int wanted = limit
        final int start = offset
        return AgentQuerySupport.run(ec, meta, {
            List<Map> rows = []
            boolean hasMore = false
            int at = start
            while (rows.size() < wanted) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("The query was cancelled.")
                int ask = Math.min(NS_PAGE_MAX, wanted - rows.size())
                Map page = client.queryPage(query, ask, at)
                List<Map> got = (page.rows ?: []) as List<Map>
                rows.addAll(got)
                at += got.size()
                hasMore = page.hasMore == true
                if (!hasMore || got.isEmpty()) break
            }
            return [rows: rows, hasMore: hasMore, nextOffset: hasMore ? at : null, offset: start] as Map
        } as Closure<Map>)
    }

    private static int offsetOf(Object raw) {
        if (raw == null || raw.toString().trim() == "") return 0
        try {
            long n = raw instanceof Number ? ((Number) raw).longValue() : Long.parseLong(raw.toString().trim())
            if (n < 0 || n > Integer.MAX_VALUE) throw new NumberFormatException()
            return (int) n
        } catch (NumberFormatException ignored) {
            throw new AgentQuerySupport.AgentQueryRefused("The offset must be a whole number from 0.")
        }
    }

    /** Everything about the connection that must never reach the agent or the log. */
    static List<String> secretsOf(def auth) {
        List<String> out = []
        ["clientId", "certId", "privateKeyPem", "password", "apiToken", "username", "tokenUrl"].each { String k ->
            String v = auth.get(k)?.toString()?.trim()
            if (v) out.add(v)
        }
        String tokenUrl = auth.get("tokenUrl")?.toString()?.trim()
        if (tokenUrl) {
            try {
                String host = URI.create(tokenUrl).host
                if (host) {
                    out.add(host)
                    String account = host.split("\\.")[0]
                    if (account.length() >= 3) out.add(account)
                }
            } catch (Exception ignored) { }
            try {
                String cached = NsTokenCache.getValidAccessToken(auth.get("nsAuthConfigId") as String, tokenUrl)
                if (cached) out.add(cached)
            } catch (Exception ignored) { }
        }
        return out.unique()
    }
}
