package netsuite.reconciliation.orders

import darpan.peak.NetSuiteReadGate
import darpan.peak.PeakReadPolicy
import darpan.peak.ReadLimits
import darpan.reconciliation.automation.SourceSystemConnectorSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import groovy.json.JsonOutput
import netsuite.reconciliation.inventory.NsTokenCache
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext
import org.moqui.service.ServiceCallback

import java.net.http.HttpResponse
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * Wiring guard for DAR-BE-032/DAR-BE-044: the NETSUITE_SUITEQL registry row names an extract service, and
 * dispatch will call it by that name with a fixed set of parameters. Nothing else in the suite
 * proves that service actually LOADS — a bad XSD attribute, a mistyped script location or a
 * parameter the registry sends but the service does not declare all fail only at run time, on a
 * scheduled automation, with the window already consumed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NetSuiteOrderExtractionServiceContractTests {

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "netsuite-orders-contract")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/SourceSystemConnectorSeedData.xml")
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @Test
    void theConnectorsExtractServiceIsLoadableByTheNameTheRegistryStores() {
        Map<String, Object> connector = SourceSystemConnectorSupport.resolve(ec, "NETSUITE_SUITEQL")
        assertNotNull(connector, "NETSUITE_SUITEQL connector row must be seeded")

        def definition = ec.service.getServiceDefinition((String) connector.extractServiceName)
        assertNotNull(definition,
                "registry names '${connector.extractServiceName}' but Moqui cannot load it — dispatch " +
                        "would fail at run time on a scheduled automation")
    }

    @Test
    void theServiceDeclaresEveryParameterDispatchSends() {
        Map<String, Object> connector = SourceSystemConnectorSupport.resolve(ec, "NETSUITE_SUITEQL")
        def definition = ec.service.getServiceDefinition((String) connector.extractServiceName)
        assertNotNull(definition)
        Set<String> declared = definition.getInParameterNames()

        // Each of these is sent by the registry-driven dispatcher because the row declares the slot;
        // a parameter the service does not declare is dropped silently rather than erroring.
        assertTrue(declared.contains((String) connector.configParameterName),
                "dispatch sends the config id as '${connector.configParameterName}'; service declares ${declared}")
        assertTrue(declared.contains((String) connector.dateFromParameterName),
                "dispatch sends the window start as '${connector.dateFromParameterName}'; declares ${declared}")
        assertTrue(declared.contains((String) connector.dateToParameterName),
                "dispatch sends the window end as '${connector.dateToParameterName}'; declares ${declared}")
        // The row declares NO projection slot since DAR-BE-044 — the query's field mapping is the
        // projection — so dispatch must not be sending one. Asserting the absence matters: the
        // previous version of this test read connector.keepFieldsParameterName and would have passed
        // a null straight into contains(), quietly proving nothing.
        assertNull(connector.keepFieldsParameterName,
                "projection belongs to NsSuiteQlSourceQuery, not the registry row")
        assertTrue(declared.contains((String) connector.filterParameterName),
                "the row declares an exclusion slot, and without it a configured rule would never run")
        assertTrue(declared.contains("companyUserGroupId"),
                "without it a scheduled run resolves config against the wrong tenant; declares ${declared}")
    }

    @Test
    void theServiceReturnsTheOutputsTheAutomationEdgeReads() {
        Map<String, Object> connector = SourceSystemConnectorSupport.resolve(ec, "NETSUITE_SUITEQL")
        def definition = ec.service.getServiceDefinition((String) connector.extractServiceName)
        Set<String> out = definition.getOutParameterNames()
        // The automation edge reads these by name off every getter; a missing one reads as "no data"
        // rather than as a wiring error.
        ["dataAvailable", "fileLocation", "fileName", "recordCount", "warnings", "errors"].each {
            assertTrue(out.contains(it), "extract#NetSuiteOrders must return '${it}'; returns ${out}")
        }
    }

    @Test
    void aMissingSourceQueryIsRefusedWithoutTouchingTheNetwork() {
        // The failure path has to be safe to reach: a bad config id must come back as an error, not an
        // outbound call or an exception that leaves a .partial file behind. Since DAR-BE-044 the id
        // dispatch sends is the QUERY, and it is resolved before the credential — so this also proves
        // an unresolvable query cannot reach the token mint.
        Map result = ec.service.sync().name("reconciliation.NetSuiteOrderExtractionServices.extract#NetSuiteOrders")
                .parameters([nsSuiteQlSourceQueryId: "NO_SUCH_QUERY",
                             windowStart           : "2026-08-01", windowEnd: "2026-09-01"])
                .disableAuthz().call()

        assertEquals(false, result.dataAvailable)
        assertTrue(((List) result.errors).any { it.toString().contains("NO_SUCH_QUERY") },
                "the error must name the query that was not usable: ${result.errors}")
    }

    // ---- DAR-BE-328: NetSuite one at a time in a peak period ------------------------------------------------------

    private static final String PEAK_TOKEN_URL = "https://7654321-sb1.suitetalk.api.netsuite.com/services/rest/auth/oauth2/v1/token"
    private static final String LOOKUP_SERVICE = "reconciliation.HotWaxOmsExtractionServices.lookup#HotWaxOmsOrderStatuses"

    /**
     * The NetSuite account (auth config) belongs to {@code owner}; the query row reading through it belongs to {@code reader},
     * which holds a share grant when it is another tenant.
     */
    private void peakFixtures(String prefix, String owner, String reader, Map<String, Object> queryExtra = [:]) {
        ec.artifactExecution.disableAuthz()
        try {
            [owner, reader].unique().each { String tenant ->
                ec.entity.makeValue("moqui.security.UserGroup").setAll([userGroupId: tenant, description: tenant]).createOrUpdate()
            }
            ec.entity.makeValue("darpan.reconciliation.NsAuthConfig").setAll([nsAuthConfigId: "${prefix}_AUTH".toString(),
                    companyUserGroupId: owner, description: "Peak gate account", authType: "OAUTH2_M2M_JWT", tokenUrl: PEAK_TOKEN_URL,
                    clientId: "c" * 64, certId: "k" * 43, isActive: "Y"]).createOrUpdate()
            if (reader != owner) {
                ec.entity.makeValue("moqui.basic.EnumerationType").setAll([enumTypeId: "DarpanSharedConfigType",
                        description: "Darpan API source config types that support cross-tenant sharing"]).createOrUpdate()
                ec.entity.makeValue("moqui.basic.Enumeration").setAll([enumId: "SCFG_NS_AUTH", enumTypeId: "DarpanSharedConfigType",
                        description: "NetSuite auth config"]).createOrUpdate()
                ec.entity.makeValue("darpan.auth.ConfigTenantAccess").setAll([configTypeEnumId: "SCFG_NS_AUTH",
                        configId: "${prefix}_AUTH".toString(), tenantUserGroupId: reader,
                        fromDate: new java.sql.Timestamp(System.currentTimeMillis() - 60_000L)]).createOrUpdate()
            }
            ec.entity.makeValue("darpan.reconciliation.NsSuiteQlSourceQuery").setAll([nsSuiteQlSourceQueryId: "${prefix}_Q".toString(),
                    nsAuthConfigId: "${prefix}_AUTH".toString(), companyUserGroupId: reader, description: "Peak gate query",
                    recordType: "SalesOrd", fromTable: "transaction", dateColumn: "trandate",
                    queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "ItemShip", isActive: "Y"] + queryExtra).createOrUpdate()
            ec.entity.makeValue("darpan.reconciliation.NsSuiteQlSourceQueryField").setAll([nsSuiteQlSourceQueryId: "${prefix}_Q".toString(),
                    recordFieldName: "internalId", sourceColumn: "id", sourceColumnAlias: "nsid", sequenceNum: 1]).createOrUpdate()
        } finally {
            ec.artifactExecution.enableAuthz()
        }
    }

    private Map peakExtract(String prefix, String reader) {
        return (Map) ec.service.sync().name("reconciliation.NetSuiteOrderExtractionServices.extract#NetSuiteOrders")
                .parameters([nsSuiteQlSourceQueryId: "${prefix}_Q".toString(), companyUserGroupId: reader,
                             windowStart           : "2026-08-01", windowEnd: "2026-09-01"])
                .disableAuthz().call()
    }

    /** The account owner, and only the owner, is in a "NetSuite one at a time" peak period. */
    private static void serialFor(String owner) {
        PeakReadPolicy.resetForTests()
        NetSuiteReadGate.resetForTests()
        PeakReadPolicy.limitsLoader = { def e, String t -> t == owner ? new ReadLimits(null, true) : ReadLimits.NONE }
    }

    private static void resetPeak() {
        NsSuiteQlPageRetry.requestForTests = null
        PeakReadPolicy.resetForTests()
        NetSuiteReadGate.resetForTests()
    }

    private static HttpResponse<String> suiteQlPage(List<Map> items, boolean hasMore) {
        String body = JsonOutput.toJson([items: items, hasMore: hasMore])
        return [statusCode: { -> 200 }, body: { -> body }] as HttpResponse<String>
    }

    /**
     * DAR-BE-328 / M5: whoever calls the extract (an automation side, a read by key, an interactive run, a lookup), a NetSuite
     * account in a "NetSuite one at a time" peak period is read only in its turn, and the turn is the ACCOUNT OWNER's (the
     * auth config's tenant), the same key the agent query uses, not the tenant whose query row reads through a shared
     * account. The extract waits while another of the owner's NetSuite reads holds the turn and reads once it is let go.
     * The read itself fails here on the missing private key, after the wait and before any request.
     */
    @Test
    void inASerialPeakPeriodTheExtractWaitsForTheAccountOwnersTurn() {
        String owner = "NS_PEAK_GATE_OWNER"
        String reader = "NS_PEAK_GATE"
        peakFixtures("NS_PEAK_GATE", owner, reader)
        resetPeak()
        CountDownLatch holding = new CountDownLatch(1)
        long releasedAt = 0L
        Thread holder = null
        try {
            peakExtract("NS_PEAK_GATE", reader)   // warm: the script compiles outside the timed part
            ec.message.clearErrors()
            serialFor(owner)
            holder = Thread.start {
                NetSuiteReadGate.run(owner) { -> holding.countDown(); Thread.sleep(1_500); releasedAt = System.currentTimeMillis() }
            }
            assertTrue(holding.await(5, TimeUnit.SECONDS))
            Map result = peakExtract("NS_PEAK_GATE", reader)
            long finishedAt = System.currentTimeMillis()
            assertTrue(releasedAt > 0L && finishedAt >= releasedAt,
                    "the extract must read only after the account owner's other NetSuite read let go (released ${releasedAt}, finished ${finishedAt}): ${result.errors}")
            // It got past the query and account checks (the shared account included) to the read itself, which the fake credential fails.
            assertEquals(false, result.dataAvailable)
            List errors = (List) result.errors
            assertTrue(errors && !errors.any { it.toString().contains("not found") || it.toString().contains("inactive") },
                    "the extract must fail at the read, not before the gate: ${errors}")
        } finally {
            holder?.join(5_000)
            ec.message.clearErrors()
            resetPeak()
        }
    }

    /**
     * DAR-BE-328 / I1: the turn is held for one NetSuite request, not for the whole extract. While page 1 is being read,
     * another of the owner's NetSuite reads waits; it gets its turn between page 1 and page 2, and page 2 is read after it.
     */
    @Test
    void inASerialPeakPeriodAnotherNetSuiteReadGetsInBetweenTwoPagesOfTheExtract() {
        String owner = "NS_PEAK_PAGES"
        peakFixtures("NS_PEAK_PAGES", owner, owner)
        resetPeak()
        NsTokenCache.put("NS_PEAK_PAGES_AUTH", PEAK_TOKEN_URL, "test-token", (long) (System.currentTimeMillis() / 1000L) + 3600L)
        AtomicInteger pages = new AtomicInteger()
        CountDownLatch waiterRan = new CountDownLatch(1)
        Thread[] waiter = new Thread[1]
        boolean[] waiterHeldOffDuringPage1 = [false]
        boolean[] waiterRanBeforePage2 = [false]
        try {
            serialFor(owner)
            NsSuiteQlPageRetry.requestForTests = { int offset ->
                if (pages.incrementAndGet() == 1) {
                    waiter[0] = Thread.start { NetSuiteReadGate.run(owner) { -> waiterRan.countDown() } }
                    // Queued behind page 1's turn: it does not run while this request is out.
                    waiterHeldOffDuringPage1[0] = !waiterRan.await(500, TimeUnit.MILLISECONDS)
                    return suiteQlPage([[nsid: "1001"]], true)
                }
                // Page 2's request is out only once the waiter has had its turn (it cannot get in during this one).
                waiterRanBeforePage2[0] = waiterRan.await(3, TimeUnit.SECONDS)
                return suiteQlPage([[nsid: "1002"]], false)
            } as Closure<HttpResponse<String>>
            Map result = peakExtract("NS_PEAK_PAGES", owner)
            assertEquals(null, result.errors ? result.errors : null, "${result.errors}")
            assertEquals(2, pages.get())
            assertEquals(2, result.recordCount)
            assertTrue(waiterHeldOffDuringPage1[0], "page 1's request holds the owner's turn")
            assertTrue(waiterRanBeforePage2[0], "the turn is let go between pages: the waiting read ran before page 2")
        } finally {
            waiterRan.countDown()
            waiter[0]?.join(5_000)
            NsTokenCache.evict("NS_PEAK_PAGES_AUTH")
            ec.message.clearErrors()
            resetPeak()
        }
    }

    /**
     * DAR-BE-328 / I1: a page's HotWax enrichment (the OMS status lookup) runs outside the NetSuite turn, so a slow HotWax
     * read never holds the owner's other NetSuite reads back. Probed from inside the lookup itself (a service callback):
     * another of the owner's NetSuite reads gets its turn while the lookup runs. The lookup fails here (no such OMS config),
     * which fails the extract after the probe.
     */
    @Test
    void theHotWaxEnrichmentOfAPageDoesNotHoldTheNetSuiteTurn() {
        String owner = "NS_PEAK_ENRICH"
        peakFixtures("NS_PEAK_ENRICH", owner, owner, [omsStatusConfigId: "NS_PEAK_NO_OMS", omsStatusKeyField: "internalId"])
        resetPeak()
        NsTokenCache.put("NS_PEAK_ENRICH_AUTH", PEAK_TOKEN_URL, "test-token", (long) (System.currentTimeMillis() / 1000L) + 3600L)
        AtomicBoolean armed = new AtomicBoolean(false)
        Boolean[] turnFreeDuringLookup = [null]
        Thread[] probe = new Thread[1]
        Closure check = { ->
            if (!armed.getAndSet(false)) return
            CountDownLatch got = new CountDownLatch(1)
            probe[0] = Thread.start { NetSuiteReadGate.run(owner) { -> got.countDown() } }
            turnFreeDuringLookup[0] = got.await(3, TimeUnit.SECONDS)
        }
        // A registered callback cannot be removed; disarmed, it does nothing for later tests.
        ec.service.registerCallback(ec.service.getServiceDefinition(LOOKUP_SERVICE).serviceName, new ServiceCallback() {
            @Override boolean isEnabled() { return true }
            @Override void receiveEvent(Map<String, Object> context, Map<String, Object> result) { check.call() }
            @Override void receiveEvent(Map<String, Object> context, Throwable t) { check.call() }
        })
        try {
            serialFor(owner)
            NsSuiteQlPageRetry.requestForTests = { int offset -> suiteQlPage([[nsid: "1001"]], false) } as Closure<HttpResponse<String>>
            armed.set(true)
            Map result = peakExtract("NS_PEAK_ENRICH", owner)
            assertTrue(((List) result.errors)?.any { it.toString().contains("HotWax order status lookup failed") },
                    "the page was enriched from HotWax (and the lookup failed): ${result.errors}")
            assertEquals(true, turnFreeDuringLookup[0], "another NetSuite read of the owner got its turn while the HotWax lookup ran")
        } finally {
            armed.set(false)
            probe[0]?.join(5_000)
            NsTokenCache.evict("NS_PEAK_ENRICH_AUTH")
            ec.message.clearErrors()
            resetPeak()
        }
    }
}
