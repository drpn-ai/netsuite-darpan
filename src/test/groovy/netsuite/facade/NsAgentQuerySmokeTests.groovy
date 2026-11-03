package netsuite.facade

import darpan.agent.AgentQueryLane
import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Timestamp
import java.time.Duration

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-CLI-003 Task 5: fresh SuiteQL through the tenant's own NetSuite connection. No network: the client is a seam. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NsAgentQuerySmokeTests {
    private static final String USER = "TEST_CUSTOMER_USER"
    private static final String TENANT_A = "KREWE"
    private static final String TENANT_B = "GORJANA"
    private static final String TENANT_VIEW = "DOLLSKILL"
    private static final Timestamp FROM = Timestamp.valueOf("2026-04-23 00:00:00")
    private static final String CLIENT_ID = "c" * 64
    private static final String HOST = "7654321-sb1.suitetalk.api.netsuite.com"
    private static final String TOKEN_URL = "https://${HOST}/services/rest/auth/oauth2/v1/token"

    /** Records what the service sent; answers canned pages (or throws). */
    static class FakeClient extends NsSuiteQlClient {
        final List<Map> calls
        final Closure answer
        FakeClient(def auth, List<Map> calls, Closure answer) { super(auth, null); this.calls = calls; this.answer = answer }
        @Override Map queryPage(String sql, int limit, int offset) {
            calls << [sql: sql, limit: limit, offset: offset]
            return (Map) answer.call(sql, limit, offset)
        }
    }

    private ExecutionContext ec
    private final List<Map> calls = []
    private Closure answer = { String sql, int limit, int offset -> [rows: [[id: 1]], hasMore: false] }
    private Closure originalFactory

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "ns-agent-query-smoke")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        ec.entity.makeValue("moqui.security.UserGroup").setAll([userGroupId: TenantAccessSupport.DARPAN_COMPANY_VIEW_ONLY_GROUP_ID,
                description: "View only"]).createOrUpdate()
        seedTenant(TENANT_A, "Krewe", TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID)
        seedTenant(TENANT_B, "Gorjana", TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID)
        seedTenant(TENANT_VIEW, "Dolls Kill", TenantAccessSupport.DARPAN_COMPANY_VIEW_ONLY_GROUP_ID)
        seedAuth("NSA_A", TENANT_A, "Krewe NetSuite", "Y")
        seedAuth("NSA_A_OFF", TENANT_A, "Krewe old", "N")
        seedAuth("NSA_B", TENANT_B, "Gorjana NetSuite", "Y")
        seedAuth("NSA_V", TENANT_VIEW, "Dolls Kill NetSuite", "Y")
        originalFactory = NsAgentQuerySupport.clientFactory
    }

    @AfterAll
    void cleanup() { ReconciliationSmokeTestSupport.cleanupMoqui(ec) }

    @BeforeEach
    void reset() {
        ec.message.clearErrors()
        calls.clear()
        answer = { String sql, int limit, int offset -> [rows: [[id: 1]], hasMore: false] }
        AgentQueryLane.reset()
        NsAgentQuerySupport.clientFactory = { def auth, Duration t -> new FakeClient(auth, calls, { s, l, o -> answer.call(s, l, o) }) }
    }

    @AfterEach
    void restore() { NsAgentQuerySupport.clientFactory = originalFactory }

    @Test
    void "returns rows, hasMore and nextOffset"() {
        answer = { String sql, int limit, int offset -> [rows: (1..limit).collect { [id: offset + it] }, hasMore: true] }
        Map r = query(TENANT_A, "NSA_A", "SELECT id FROM transaction", [limit: 5, offset: 10])
        assertTrue(r.ok as boolean, "${r.errors}")
        assertEquals(5, r.rowCount)
        assertEquals(11, (r.rows as List).first().id)
        assertTrue(r.hasMore as boolean)
        assertEquals(15, r.nextOffset)
        assertEquals([[sql: "SELECT id FROM transaction", limit: 5, offset: 10]], calls)
    }

    @Test
    void "a DELETE never reaches the client"() {
        Map r = query(TENANT_A, "NSA_A", "DELETE FROM transaction")
        assertFalse(r.ok as boolean)
        assertEquals("REFUSED", r.code)
        assertTrue(calls.isEmpty())
        assertEquals(1, logRows("DELETE FROM transaction", "REFUSED").size())
    }

    @Test
    void "a view-only member is refused"() {
        Map r = query(TENANT_VIEW, "NSA_V", "SELECT 1")
        assertFalse(r.ok as boolean)
        assertTrue(calls.isEmpty())
    }

    @Test
    void "another tenant's nsAuthConfigId is refused"() {
        Map r = query(TENANT_A, "NSA_B", "SELECT 1")
        assertFalse(r.ok as boolean, "tenant A reached tenant B's connection")
        assertTrue(calls.isEmpty())
        // positive control: B reaches its own
        assertTrue(query(TENANT_B, "NSA_B", "SELECT 2").ok as boolean)
        assertEquals(1, calls.size())
    }

    @Test
    void "a turned-off connection is refused"() {
        assertFalse(query(TENANT_A, "NSA_A_OFF", "SELECT 1").ok as boolean)
        assertTrue(calls.isEmpty())
    }

    @Test
    void "one log row per call with the outcome"() {
        query(TENANT_A, "NSA_A", "SELECT 11 FROM log_ok")
        answer = { String s, int l, int o -> throw new IllegalStateException("boom") }
        query(TENANT_A, "NSA_A", "SELECT 12 FROM log_err")
        List ok = logRows("SELECT 11 FROM log_ok", "OK")
        List err = logRows("SELECT 12 FROM log_err", "ERROR")
        assertEquals(1, ok.size())
        assertEquals(1, err.size())
        assertEquals(TENANT_A, ok.first().companyUserGroupId)
        assertEquals("NSA_A", ok.first().connectionId)
        assertEquals("NETSUITE", ok.first().systemEnumId)
    }

    @Test
    void "limit 5000 is sent as 2000"() {
        answer = { String sql, int limit, int offset -> [rows: (1..limit).collect { [id: it] }, hasMore: true] }
        Map r = query(TENANT_A, "NSA_A", "SELECT 5000", [limit: 5000])
        assertTrue(r.ok as boolean, "${r.errors}")
        assertEquals(2000, r.rowCount)
        // NetSuite pages at 1000, so the 2000 are two requests that add up to the cap.
        assertEquals([1000, 1000], calls.collect { it.limit })
        assertEquals([0, 1000], calls.collect { it.offset })
        assertEquals(2000, r.nextOffset)
    }

    @Test
    void "an error carrying the account host and client id comes back stripped"() {
        answer = { String s, int l, int o ->
            throw new IllegalStateException("connect to ${HOST} failed (account 7654321-sb1, client ${CLIENT_ID}) via ${TOKEN_URL}".toString())
        }
        Map r = query(TENANT_A, "NSA_A", "SELECT 99 FROM leak")
        assertEquals("ERROR", r.code)
        String text = "${r.error} ${r.errors}"
        assertFalse(text.contains(HOST), text)
        assertFalse(text.contains("7654321"), text)
        assertFalse(text.contains(CLIENT_ID), text)
        assertTrue(text.contains("[hidden]"), text)
    }

    @Test
    void "list agent connections: own only, no credentials, off ones left out"() {
        Map a = list(TENANT_A)
        assertTrue(a.ok as boolean, "${a.errors}")
        assertEquals(["NSA_A"], (a.connections as List).collect { it.nsAuthConfigId })
        Map only = (a.connections as List).first() as Map
        assertEquals(["authType", "description", "nsAuthConfigId"] as Set, only.keySet() as Set)
        assertEquals("OAUTH2_M2M_JWT", only.authType)
        assertEquals(["NSA_B"], (list(TENANT_B).connections as List).collect { it.nsAuthConfigId })
        assertFalse(a.toString().contains(CLIENT_ID) || a.toString().contains(HOST))
    }

    @Test
    void "the client page method sits beside query"() {
        assertNotNull(NsSuiteQlClient.getDeclaredMethod("queryPage", String, int, int))
        assertNull(NsAgentQuerySupport.secretsOf([get: { String k -> null }]).find { it })
    }

    private Map query(String tenant, String authId, String sql, Map extra = [:]) {
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, tenant)
        ec.message.clearErrors()
        return (Map) ec.service.sync().name("facade.NetSuiteQueryFacadeServices.run#NsSuiteQlAgentQuery")
                .parameters([nsAuthConfigId: authId, sql: sql] + extra).disableAuthz().call()
    }

    private Map list(String tenant) {
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, tenant)
        ec.message.clearErrors()
        return (Map) ec.service.sync().name("facade.NetSuiteQueryFacadeServices.list#NsAgentConnections").disableAuthz().call()
    }

    private List logRows(String queryText, String outcome) {
        return ec.entity.find("darpan.agent.AgentQueryLog").condition("queryText", queryText).condition("outcome", outcome)
                .disableAuthz().useCache(false).list() as List
    }

    private void seedAuth(String id, String tenant, String description, String active) {
        ec.entity.makeValue("darpan.reconciliation.NsAuthConfig").setAll([nsAuthConfigId: id, companyUserGroupId: tenant,
                description: description, authType: "OAUTH2_M2M_JWT", tokenUrl: TOKEN_URL, clientId: CLIENT_ID, certId: "k" * 43,
                username: "svc-user", isActive: active]).createOrUpdate()
    }

    private void seedTenant(String tenantId, String label, String permissionGroupId) {
        ec.entity.makeValue("moqui.security.UserGroup").setAll([userGroupId: tenantId, description: label,
                groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID]).createOrUpdate()
        ec.entity.makeValue("moqui.security.UserGroupMember").setAll([userGroupId: tenantId, userId: USER, fromDate: FROM]).createOrUpdate()
        ec.entity.makeValue(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME).setAll([
                tenantUserGroupId: tenantId, userId: USER, permissionUserGroupId: permissionGroupId, fromDate: FROM]).createOrUpdate()
    }
}
