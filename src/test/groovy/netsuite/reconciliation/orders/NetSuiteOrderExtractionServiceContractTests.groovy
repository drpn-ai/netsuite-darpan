package netsuite.reconciliation.orders

import darpan.reconciliation.automation.SourceSystemConnectorSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path

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
}
