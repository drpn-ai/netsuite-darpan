package netsuite.facade

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import netsuite.reconciliation.orders.NsSuiteQlOrderSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-058. The save path exists so an environment with no shell can create a NetSuite check,
 * which means a form is now the thing standing between an operator and a SuiteQL predicate.
 *
 * <p>These tests are mostly about REFUSAL. A check that saves wrong does not fail — it runs and
 * answers a slightly different question, forever, and reports clean while doing it. So the
 * question each one asks is "does the bad row reach the database", not "does an error appear".</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NsSuiteQlQueryFacadeSupportTests {

    private static final String QUERY_ID = "TEST_NS_CHECK"
    private static final String AUTH_ID = "TEST_NS_AUTH"

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "ns-query-facade")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        // NsSuiteQlSourceQuery.nsAuthConfigId is a real FK. Seeding a credential-free auth row keeps
        // these tests about the save path rather than about NetSuite access.
        ec.artifactExecution.disableAuthz()
        def auth = ec.entity.makeValue("darpan.reconciliation.NsAuthConfig")
        auth.set("nsAuthConfigId", AUTH_ID)
        auth.set("description", "facade test auth")
        auth.set("authType", "NONE")
        auth.set("isActive", "Y")
        auth.createOrUpdate()
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void resetState() {
        ec.message.clearErrors()
        ec.artifactExecution.disableAuthz()
        ec.entity.find("darpan.reconciliation.NsSuiteQlSourceQueryField")
                .condition("nsSuiteQlSourceQueryId", QUERY_ID).disableAuthz().deleteAll()
        def row = ec.entity.find("darpan.reconciliation.NsSuiteQlSourceQuery")
                .condition("nsSuiteQlSourceQueryId", QUERY_ID).disableAuthz().useCache(false).one()
        if (row != null) row.delete()
    }

    private static List<Map<String, Object>> projection() {
        return [[recordFieldName: "internalId", sourceColumn: "id", sequenceNum: 1],
                [recordFieldName: "tranId", sourceColumn: "tranid", sequenceNum: 2]] as List<Map<String, Object>>
    }

    private static Map<String, Object> ctx(Map<String, Object> overrides) {
        Map<String, Object> base = [nsSuiteQlSourceQueryId: QUERY_ID, nsAuthConfigId: AUTH_ID,
                                    description           : "test check", recordType: "SalesOrd",
                                    fromTable             : "transaction", dateColumn: "trandate",
                                    queryTemplate         : "RECORDS", isActive: true,
                                    fields                : projection()] as Map<String, Object>
        base.putAll(overrides)
        return base
    }

    private boolean rowExists() {
        return ec.entity.find("darpan.reconciliation.NsSuiteQlSourceQuery")
                .condition("nsSuiteQlSourceQueryId", QUERY_ID).disableAuthz().useCache(false).one() != null
    }

    @Test
    void savesTheRowAndItsProjection() {
        Map<String, Object> saved = NsSuiteQlQueryFacadeSupport.saveQuery(ec, ctx([:]))

        assertFalse(ec.message.hasError(), ec.message.errorsString)
        assertNotNull(saved)
        assertEquals("SalesOrd", saved.get("recordType"))
        assertEquals(2, ((List) saved.get("fields")).size())
    }

    /**
     * The injection that motivated the identifier whitelist (DAR-BE-053). It reached the extractor
     * as an operator-editable field then; a form makes that field reachable by more people.
     */
    @Test
    void refusesARecordTypeThatWouldWidenTheTypePredicate() {
        NsSuiteQlQueryFacadeSupport.saveQuery(ec, ctx([recordType: "ItemShip' OR '1'='1"]))

        assertTrue(ec.message.hasError())
        assertFalse(rowExists(), "an injected record type must not reach the database")
    }

    @Test
    void refusesAnEmptyProjection() {
        NsSuiteQlQueryFacadeSupport.saveQuery(ec, ctx([fields: []]))

        assertTrue(ec.message.hasError())
        assertFalse(rowExists(), "a check with no columns selects nothing and must not be saved")
    }

    /** Cross-field rules come from normalizeSpec, so the save path inherits them for free. */
    @Test
    void refusesAHalfConfiguredAmountFloor() {
        NsSuiteQlQueryFacadeSupport.saveQuery(ec, ctx([queryTemplate   : "STATE_CONTRADICTION",
                                                       disallowedStates: "F", minAmount: "1"]))

        assertTrue(ec.message.hasError())
        assertTrue(ec.message.errorsString.contains("amountColumn"), ec.message.errorsString)
        assertFalse(rowExists())
    }

    @Test
    void refusesALinkTemplateWithNoChildType() {
        NsSuiteQlQueryFacadeSupport.saveQuery(ec, ctx([queryTemplate: "LINKED_CHILD_ABSENT"]))

        assertTrue(ec.message.hasError())
        assertFalse(rowExists())
    }

    @Test
    void refusesAJoinKeyThatIsNotAMappedField() {
        NsSuiteQlQueryFacadeSupport.saveQuery(ec, ctx([joinKeyFieldName: "orderId"]))

        assertTrue(ec.message.hasError())
        assertFalse(rowExists())
    }

    /**
     * The whole point of the service. A row the form accepted must produce a runnable query —
     * otherwise the failure surfaces mid-run against a client's NetSuite account.
     */
    @Test
    void anythingThatSavesAlsoBuildsAQuery() {
        NsSuiteQlQueryFacadeSupport.saveQuery(ec, ctx([
                queryTemplate          : "STATE_CONTRADICTION", disallowedStates: "A",
                requireParentRecordType: "SalesOrd", recordType: "CustInvc",
                amountColumn           : "foreignamountunpaid", minAmount: "1",
                joinKeyFieldName       : "orderId", originFieldName: "orderOrigin",
                fields                 : projection() + [[recordFieldName: "orderId",
                                                          sourceColumn   : "custbody_hc_order_id",
                                                          sequenceNum    : 3]]]))
        assertFalse(ec.message.hasError(), ec.message.errorsString)

        Map<String, Object> saved = NsSuiteQlQueryFacadeSupport.getQuery(ec, QUERY_ID)
        Map<String, Object> spec = NsSuiteQlOrderSupport.normalizeSpec(
                saved, (List<Map<String, Object>>) saved.get("fields"))
        String sql = NsSuiteQlOrderSupport.buildRecordsQuery(spec, "2026-09-01", "2026-09-02", "2026-09-29")

        assertTrue(sql.contains("t.type = 'CustInvc'"), sql)
        assertTrue(sql.contains("t.foreignamountunpaid >= 1"), sql)
        assertTrue(sql.contains("l0.nextdoc = t.id"), sql)
    }

    /**
     * Rewritten wholesale, not merged. A merge that left a stale row behind would silently add a
     * column to the SELECT — a change to what the check reports that nobody requested.
     */
    @Test
    void replacingTheProjectionLeavesNoStaleColumns() {
        NsSuiteQlQueryFacadeSupport.saveQuery(ec, ctx([:]))
        NsSuiteQlQueryFacadeSupport.saveQuery(ec, ctx([fields: [[recordFieldName: "internalId",
                                                                 sourceColumn   : "id", sequenceNum: 1]]]))

        assertFalse(ec.message.hasError(), ec.message.errorsString)
        Map<String, Object> saved = NsSuiteQlQueryFacadeSupport.getQuery(ec, QUERY_ID)
        assertEquals(1, ((List) saved.get("fields")).size())
    }

    /** Submitted order wins, and the stored sequence is dense — a gap reorders the projection. */
    @Test
    void renumbersTheProjectionDenselyFromOne() {
        NsSuiteQlQueryFacadeSupport.saveQuery(ec, ctx([fields: [
                [recordFieldName: "internalId", sourceColumn: "id", sequenceNum: 40],
                [recordFieldName: "tranId", sourceColumn: "tranid", sequenceNum: 90]]]))

        assertFalse(ec.message.hasError(), ec.message.errorsString)
        List fields = (List) NsSuiteQlQueryFacadeSupport.getQuery(ec, QUERY_ID).get("fields")
        assertEquals([1, 2], fields.collect { (it as Map).get("sequenceNum") as Integer })
        assertEquals(["internalId", "tranId"], fields.collect { (it as Map).get("recordFieldName") })
    }

    @Test
    void getReportsAMissingCheckRatherThanReturningNull() {
        assertNull(NsSuiteQlQueryFacadeSupport.getQuery(ec, "NO_SUCH_CHECK"))

        assertTrue(ec.message.hasError())
    }

    @Test
    void listReportsTheProjectionSize() {
        NsSuiteQlQueryFacadeSupport.saveQuery(ec, ctx([:]))

        Map<String, Object> listed = NsSuiteQlQueryFacadeSupport.listQueries(ec)
                .find { it.get("nsSuiteQlSourceQueryId") == QUERY_ID }
        assertNotNull(listed)
        assertEquals(2, listed.get("fieldCount"))
    }
}
