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

    private static final String AUTH_ID = "TEST_NS_AUTH"
    private static final String DESCRIPTION = "test check"

    private ExecutionContext ec
    /** DAR-BE-135: the server mints a check's id, so a test learns it from its first save. */
    private String lastId

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
        lastId = null
        ec.entity.find("darpan.reconciliation.NsSuiteQlSourceQuery").condition("description", DESCRIPTION)
                .disableAuthz().useCache(false).list().each { row ->
            ec.entity.find("darpan.reconciliation.NsSuiteQlSourceQueryField")
                    .condition("nsSuiteQlSourceQueryId", row.nsSuiteQlSourceQueryId).disableAuthz().deleteAll()
            row.delete()
        }
    }

    private static List<Map<String, Object>> projection() {
        return [[recordFieldName: "internalId", sourceColumn: "id", sequenceNum: 1],
                [recordFieldName: "tranId", sourceColumn: "tranid", sequenceNum: 2]] as List<Map<String, Object>>
    }

    private Map<String, Object> ctx(Map<String, Object> overrides) {
        Map<String, Object> base = [nsAuthConfigId        : AUTH_ID,
                                    description           : DESCRIPTION, recordType: "SalesOrd",
                                    fromTable             : "transaction", dateColumn: "trandate",
                                    queryTemplate         : "RECORDS", isActive: true,
                                    fields                : projection()] as Map<String, Object>
        if (lastId) base.put("nsSuiteQlSourceQueryId", lastId)
        base.putAll(overrides)
        return base
    }

    private Map<String, Object> save(Map<String, Object> context) {
        Map<String, Object> saved = NsSuiteQlQueryFacadeSupport.saveQuery(ec, context)
        if (saved) lastId = saved.get("nsSuiteQlSourceQueryId") as String
        return saved
    }

    private boolean rowExists() {
        return ec.entity.find("darpan.reconciliation.NsSuiteQlSourceQuery")
                .condition("description", DESCRIPTION).disableAuthz().useCache(false).count() > 0
    }

    @Test
    void savesTheRowAndItsProjection() {
        Map<String, Object> saved = save(ctx([:]))

        assertFalse(ec.message.hasError(), ec.message.errorsString)
        assertNotNull(saved)
        assertEquals("SalesOrd", saved.get("recordType"))
        assertEquals(2, ((List) saved.get("fields")).size())
    }

    @Test
    void aNewCheckTakesAMintedId() {
        Map<String, Object> saved = save(ctx([:]))

        assertFalse(ec.message.hasError(), ec.message.errorsString)
        assertTrue((saved.get("nsSuiteQlSourceQueryId") as String) ==~ /\d+/,
                "DAR-BE-135: a new check takes a minted id, got ${saved.get('nsSuiteQlSourceQueryId')}")
    }

    /** DAR-BE-135: ids span every tenant, so a named id answers the same whether it is missing or another tenant's. */
    @Test
    void aForeignIdAndAMissingIdAnswerAlikeAndNeitherCreatesARow() {
        ec.entity.makeValue("moqui.security.UserGroup").setAll([userGroupId: "GORJANA", description: "Gorjana"]).createOrUpdate()
        def foreign = ec.entity.makeValue("darpan.reconciliation.NsSuiteQlSourceQuery")
        foreign.setAll([nsSuiteQlSourceQueryId: "OTHER_TENANT_CHECK", nsAuthConfigId: AUTH_ID, description: "other tenant",
                        recordType: "SalesOrd", companyUserGroupId: "GORJANA", isActive: "Y"])
        foreign.createOrUpdate()
        try {
            save(ctx([nsSuiteQlSourceQueryId: "OTHER_TENANT_CHECK"]))
            List<String> foreignErrors = new ArrayList<String>(ec.message.errors)
            ec.message.clearErrors()
            save(ctx([nsSuiteQlSourceQueryId: "NO_SUCH_CHECK"]))
            List<String> missingErrors = new ArrayList<String>(ec.message.errors)

            assertEquals(["NetSuite check 'OTHER_TENANT_CHECK' was not found."], foreignErrors)
            assertEquals(["NetSuite check 'NO_SUCH_CHECK' was not found."], missingErrors)
            assertFalse(rowExists(), "a caller-named id must never create a row")
            assertEquals("other tenant", ec.entity.find("darpan.reconciliation.NsSuiteQlSourceQuery")
                    .condition("nsSuiteQlSourceQueryId", "OTHER_TENANT_CHECK").disableAuthz().useCache(false).one().description)
        } finally {
            foreign.delete()
        }
    }

    /**
     * The injection that motivated the identifier whitelist (DAR-BE-053). It reached the extractor
     * as an operator-editable field then; a form makes that field reachable by more people.
     */
    @Test
    void refusesARecordTypeThatWouldWidenTheTypePredicate() {
        save(ctx([recordType: "ItemShip' OR '1'='1"]))

        assertTrue(ec.message.hasError())
        assertFalse(rowExists(), "an injected record type must not reach the database")
    }

    @Test
    void refusesAnEmptyProjection() {
        save(ctx([fields: []]))

        assertTrue(ec.message.hasError())
        assertFalse(rowExists(), "a check with no columns selects nothing and must not be saved")
    }

    /** Cross-field rules come from normalizeSpec, so the save path inherits them for free. */
    @Test
    void refusesAHalfConfiguredAmountFloor() {
        save(ctx([queryTemplate   : "STATE_CONTRADICTION",
                                                       disallowedStates: "F", minAmount: "1"]))

        assertTrue(ec.message.hasError())
        assertTrue(ec.message.errorsString.contains("amountColumn"), ec.message.errorsString)
        assertFalse(rowExists())
    }

    @Test
    void refusesALinkTemplateWithNoChildType() {
        save(ctx([queryTemplate: "LINKED_CHILD_ABSENT"]))

        assertTrue(ec.message.hasError())
        assertFalse(rowExists())
    }

    @Test
    void refusesAJoinKeyThatIsNotAMappedField() {
        save(ctx([joinKeyFieldName: "orderId"]))

        assertTrue(ec.message.hasError())
        assertFalse(rowExists())
    }

    /** DAR-UI-064: a query written in the app says which records it reads; the conditions round-trip as stored JSON. */
    @Test
    void savesMatchRulesAndReadsThemBack() {
        Map<String, Object> saved = save(ctx([queryTemplate: "RECORDS_MATCHING",
                matchRules: [[fieldId: "otherrefnum", op: "STARTS_WITH", values: ["WTY-"]]]]))

        assertFalse(ec.message.hasError(), ec.message.errorsString)
        assertEquals('[{"fieldId":"otherrefnum","op":"STARTS_WITH","values":["WTY-"]}]', saved.get("matchRules"))
        Map<String, Object> again = NsSuiteQlQueryFacadeSupport.getQuery(ec, lastId)
        assertEquals(saved.get("matchRules"), again.get("matchRules"))
    }

    /** A LIKE wildcard in a prefix would widen the population silently, so it never reaches the database. */
    @Test
    void refusesAMatchRuleThatWouldWidenThePopulation() {
        save(ctx([queryTemplate: "RECORDS_MATCHING",
                matchRules: '[{"fieldId":"otherrefnum","op":"STARTS_WITH","values":["%"]}]']))

        assertTrue(ec.message.hasError())
        assertFalse(rowExists())
    }

    @Test
    void refusesAMatchRuleOnAFieldNoQueryMayAskAbout() {
        save(ctx([queryTemplate: "RECORDS_MATCHING",
                matchRules: [[fieldId: "entity) OR (1", op: "NOT_EMPTY"]]]))

        assertTrue(ec.message.hasError())
        assertFalse(rowExists())
    }

    /** The preview refuses exactly what save refuses, before any NetSuite call. */
    @Test
    void previewRefusesWhatSaveRefusesWithoutCallingNetSuite() {
        Map<String, Object> preview = NsSuiteQlQueryFacadeSupport.previewQuery(ec, ctx([recordType: "ItemShip' OR '1'='1"]))

        assertNull(preview)
        assertTrue(ec.message.hasError())
        assertFalse(rowExists(), "a preview stores nothing")
    }

    /** A turned-off NetSuite account is not queried from the preview, as from every other NetSuite read. */
    @Test
    void previewRefusesATurnedOffAccount() {
        def auth = ec.entity.find("darpan.reconciliation.NsAuthConfig").condition("nsAuthConfigId", AUTH_ID)
                .disableAuthz().useCache(false).one()
        // The tenant's own account, so the refusal is "turned off" and not "not found".
        auth.set("companyUserGroupId", "KREWE"); auth.set("isActive", "N"); auth.update()
        try {
            Map<String, Object> preview = NsSuiteQlQueryFacadeSupport.previewQuery(ec, ctx([:]))

            assertNull(preview)
            assertTrue(ec.message.errorsString.contains("is turned off"), ec.message.errorsString)
        } finally {
            auth.set("companyUserGroupId", null); auth.set("isActive", "Y"); auth.update()
        }
    }

    @Test
    void optionsOfferTheMatchFieldsAndOperatorsTheExtractorAccepts() {
        Map<String, Object> options = NsSuiteQlQueryFacadeSupport.queryOptions(ec)

        assertEquals(NsSuiteQlOrderSupport.MATCH_OPS, options.get("matchOps"))
        assertTrue(((List) options.get("matchFields")).contains("status"))
        assertTrue(((List) options.get("templates")).contains("RECORDS_MATCHING"))
    }

    /**
     * The whole point of the service. A row the form accepted must produce a runnable query —
     * otherwise the failure surfaces mid-run against a client's NetSuite account.
     */
    @Test
    void anythingThatSavesAlsoBuildsAQuery() {
        save(ctx([
                queryTemplate          : "STATE_CONTRADICTION", disallowedStates: "A",
                requireParentRecordType: "SalesOrd", recordType: "CustInvc",
                amountColumn           : "foreignamountunpaid", minAmount: "1",
                joinKeyFieldName       : "orderId", originFieldName: "orderOrigin",
                fields                 : projection() + [[recordFieldName: "orderId",
                                                          sourceColumn   : "custbody_hc_order_id",
                                                          sequenceNum    : 3]]]))
        assertFalse(ec.message.hasError(), ec.message.errorsString)

        Map<String, Object> saved = NsSuiteQlQueryFacadeSupport.getQuery(ec, lastId)
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
        save(ctx([:]))
        save(ctx([fields: [[recordFieldName: "internalId",
                                                                 sourceColumn   : "id", sequenceNum: 1]]]))

        assertFalse(ec.message.hasError(), ec.message.errorsString)
        Map<String, Object> saved = NsSuiteQlQueryFacadeSupport.getQuery(ec, lastId)
        assertEquals(1, ((List) saved.get("fields")).size())
    }

    /** Submitted order wins, and the stored sequence is dense — a gap reorders the projection. */
    @Test
    void renumbersTheProjectionDenselyFromOne() {
        save(ctx([fields: [
                [recordFieldName: "internalId", sourceColumn: "id", sequenceNum: 40],
                [recordFieldName: "tranId", sourceColumn: "tranid", sequenceNum: 90]]]))

        assertFalse(ec.message.hasError(), ec.message.errorsString)
        List fields = (List) NsSuiteQlQueryFacadeSupport.getQuery(ec, lastId).get("fields")
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
        save(ctx([:]))

        List rows = (List) NsSuiteQlQueryFacadeSupport.listQueries(ec).get("queries")
        Map<String, Object> listed = rows.find { (it as Map).get("nsSuiteQlSourceQueryId") == lastId } as Map
        assertNotNull(listed)
        assertEquals(2, listed.get("fieldCount"))
    }

    /** Paged like every other list service: a bounded page plus the total, never every row at once. */
    @Test
    void listIsPagedAndReportsTheTotal() {
        save(ctx([:]))

        Map<String, Object> listed = NsSuiteQlQueryFacadeSupport.listQueries(ec, 0, 1)
        Map<String, Object> pagination = (Map<String, Object>) listed.get("pagination")

        assertEquals(1, ((List) listed.get("queries")).size())
        assertEquals(0, pagination.get("pageIndex"))
        assertEquals(1, pagination.get("pageSize"))
        // totalCount counts every check the tenant has, not the page — that is the number a
        // heading or a pager states, and stating the page size there would be a wrong number.
        assertTrue((pagination.get("totalCount") as int) >= 1)
    }
}
