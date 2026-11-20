package netsuite.reconciliation.orders

import darpan.peak.NetSuiteReadGate
import darpan.peak.PeakReadPolicy
import darpan.peak.ReadLimits
import darpan.reconciliation.automation.SourceSystemConnectorSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
    /**
     * DAR-BE-328: whoever calls the extract (an automation side, a read by key, an interactive run, a lookup), a tenant in a
     * "NetSuite one at a time" peak period reads only in its turn: the extract waits while another of the tenant's NetSuite
     * reads holds it, and reads once it is let go. The read itself fails here on the missing private key, after the wait and before any request.
     */
    @Test
    void inASerialPeakPeriodTheExtractWaitsForTheTenantsTurn() {
        String tenant = "NS_PEAK_GATE"
        ec.artifactExecution.disableAuthz()
        try {
            ec.entity.makeValue("moqui.security.UserGroup").setAll([userGroupId: tenant, description: "Peak gate tenant"]).createOrUpdate()
            ec.entity.makeValue("darpan.reconciliation.NsAuthConfig").setAll([nsAuthConfigId: "NS_PEAK_GATE_AUTH", companyUserGroupId: tenant,
                    description: "Peak gate account", authType: "OAUTH2_M2M_JWT", tokenUrl: "https://7654321-sb1.suitetalk.api.netsuite.com/services/rest/auth/oauth2/v1/token",
                    clientId: "c" * 64, certId: "k" * 43, isActive: "Y"]).createOrUpdate()
            ec.entity.makeValue("darpan.reconciliation.NsSuiteQlSourceQuery").setAll([nsSuiteQlSourceQueryId: "NS_PEAK_GATE_Q",
                    nsAuthConfigId: "NS_PEAK_GATE_AUTH", companyUserGroupId: tenant, description: "Peak gate query",
                    recordType: "SalesOrd", fromTable: "transaction", dateColumn: "trandate",
                    queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "ItemShip", isActive: "Y"]).createOrUpdate()
            ec.entity.makeValue("darpan.reconciliation.NsSuiteQlSourceQueryField").setAll([nsSuiteQlSourceQueryId: "NS_PEAK_GATE_Q",
                    recordFieldName: "internalId", sourceColumn: "id", sequenceNum: 1]).createOrUpdate()
        } finally {
            ec.artifactExecution.enableAuthz()
        }
        Closure<Map> extract = { ->
            (Map) ec.service.sync().name("reconciliation.NetSuiteOrderExtractionServices.extract#NetSuiteOrders")
                    .parameters([nsSuiteQlSourceQueryId: "NS_PEAK_GATE_Q", companyUserGroupId: tenant,
                                 windowStart           : "2026-08-01", windowEnd: "2026-09-01"])
                    .disableAuthz().call()
        }
        PeakReadPolicy.resetForTests()
        NetSuiteReadGate.resetForTests()
        CountDownLatch holding = new CountDownLatch(1)
        long releasedAt = 0L
        Thread holder = null
        try {
            extract.call()   // warm: the script compiles outside the timed part
            ec.message.clearErrors()
            PeakReadPolicy.limitsLoader = { def e, String t -> t == tenant ? new ReadLimits(null, true) : ReadLimits.NONE }
            holder = Thread.start {
                NetSuiteReadGate.run(tenant) { -> holding.countDown(); Thread.sleep(1_500); releasedAt = System.currentTimeMillis() }
            }
            assertTrue(holding.await(5, TimeUnit.SECONDS))
            Map result = extract.call()
            long finishedAt = System.currentTimeMillis()
            assertTrue(releasedAt > 0L && finishedAt >= releasedAt,
                    "the extract must read only after the tenant's other NetSuite read let go (released ${releasedAt}, finished ${finishedAt}): ${result.errors}")
            // It got past the query and account checks to the read itself, which the fake credential fails.
            assertEquals(false, result.dataAvailable)
            List errors = (List) result.errors
            assertTrue(errors && !errors.any { it.toString().contains("not found") || it.toString().contains("inactive") },
                    "the extract must fail at the read, not before the gate: ${errors}")
        } finally {
            holder?.join(5_000)
            ec.message.clearErrors()
            PeakReadPolicy.resetForTests()
            NetSuiteReadGate.resetForTests()
        }
    }
}
