package netsuite.reconciliation.orders

import darpan.dataset.DatasetRefreshSupport
import darpan.dataset.KeptCopyStore
import darpan.dataset.KeptCopySupport
import darpan.facade.common.DataManagerSupport
import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.automation.SourceSystemConnectorSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import groovy.json.JsonOutput
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ArtifactExecutionInfo
import org.moqui.context.ExecutionContext

import java.sql.Timestamp
import java.time.Instant
import java.time.ZoneId

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-318 plan 3b-1, Task 5: a NetSuite dataset on the seeded NETSUITE_SUITEQL connector row plus this component's own
 * declarations on it (data/NetSuiteConnectorSeedData.xml). No NetSuite account is ever called: the copy is built by a stub
 * extractor, and the real extract#NetSuiteOrders is reached only where it refuses before any request.
 *
 * <ul>
 * <li>Its full read windows on the query's dateColumn and its daily read on darpanStamp, each sent datasetRead, so every
 *     record carries the stamp the copy merges by (rulings 3b1-4, 3b1-5).</li>
 * <li>Review Focus 5 / ruling P3b1-14: a dataset on a template whose records depend on other transactions fails its first
 *     read, by the extractor's own refusal, and never builds a copy. Core's store#Dataset names no system.</li>
 * <li>Ruling P3b1-6: a NetSuite run side reads live, built copy or not.</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NetSuiteKeptCopyTests {

    private static final String A = "KREWE"
    private static final String PREF_KEY = TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY
    private static final String EDITOR_ID = "TEST_CUSTOMER_USER"
    private static final String EDITOR_USERNAME = "test.customer"
    private static final Timestamp FROM_DATE = Timestamp.valueOf("2026-04-21 00:00:00")
    private static final String FACADE_GRANT = "facade.ReconciliationFacadeServices.list#Reconciliations"
    private static final String SYSTEM = "NETSUITE_SUITEQL"
    private static final String EXTRACT = "reconciliation.NetSuiteOrderExtractionServices.extract#NetSuiteOrders"
    private static final String AUTH = "NSA_KC"
    private static final Instant COPY_READ_AT = Instant.parse("2026-11-20T12:00:00Z")
    private static final Timestamp START = Timestamp.from(Instant.parse("2026-11-10T08:00:00Z"))
    private static final Timestamp END = Timestamp.from(Instant.parse("2026-11-11T08:00:00Z"))

    private ExecutionContext ec
    private File dataManagerRoot

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "netsuite-kept-copy")
        dataManagerRoot = DataManagerSupport.resolveDirectoryFile(ec, DataManagerSupport.resolveDataManagerLocation(ec), true)
        assertTrue(dataManagerRoot.path.contains("test-data-manager"), "tests never write the dev datamanager: ${dataManagerRoot}")
        ["datasets", DataManagerSupport.RECONCILIATION_RUNS_PATH].each { new File(dataManagerRoot, it).deleteDir() }

        ec.entity.makeDataLoader().location("component://darpan/data/SecuritySeedData.xml").dummyFks(true).load()
        ReconciliationSmokeTestSupport.loadSeedData(ec, "classpath://data/EntityTypeData.xml",
                "component://moqui-rules/data/RuleSeedData.xml", "component://darpan/data/DarpanSystemSourceSeedData.xml",
                "component://darpan/data/AutomationSeedData.xml", "component://darpan/data/DatasetSeedData.xml")
        ec.entity.makeDataLoader().location("component://darpan/data/SourceSystemConnectorSeedData.xml").dummyFks(true).load()
        // this system's own declarations on its row (ruling P3b1-9)
        ec.entity.makeDataLoader().location("component://netsuite-darpan/data/NetSuiteConnectorSeedData.xml").dummyFks(true).load()
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        ec.entity.makeValue("moqui.security.UserGroupMember").setAll([userGroupId: "DARPAN_USER", userId: EDITOR_ID, fromDate: FROM_DATE]).createOrUpdate()

        ec.entity.makeValue("darpan.reconciliation.NsAuthConfig").setAll([nsAuthConfigId: AUTH, companyUserGroupId: A,
                authType: "OAUTH2_M2M", tokenUrl: "https://1234567.suitetalk.api.netsuite.com/services/rest/auth/oauth2/v1/token",
                clientId: "c", certId: "k", isActive: "Y"]).createOrUpdate()
        query("NS_KC_RECORDS", [:])
        query("NS_KC_SERVE", [:])
        query("NS_KC_LINKED", [queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "ItemShip"])
        query("NS_KC_STATE", [queryTemplate: "STATE_CONTRADICTION", statesExpectingShipment: "D,E,F,G"])
        query("NS_KC_LINES", [queryTemplate: "SALES_LINES", lineKeyColumn: "custcol_hc_order_line_id", joinKeyFieldName: "tranId"])
        query("NS_KC_LINES_BAD", [queryTemplate: "SALES_LINES", lineKeyColumn: "custcol_hc_order_line_id", joinKeyFieldName: "tranId"])
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void actAsTenantA() {
        assertTrue(ec.user.internalLoginUser(EDITOR_USERNAME), "could not log in ${EDITOR_USERNAME}")
        ec.user.setPreference(PREF_KEY, A)
        TenantAccessSupport.syncUserContext(ec)
        ec.message.clearErrors()
    }

    // ----------------------------------------------------------------------------------------------------- the tests

    /** What the row tells every dataset read to send reaches the extractor: Moqui drops a parameter a service does not declare. */
    @Test
    void theConnectorsDatasetReadParametersAreDeclaredByItsExtractor() {
        Map<String, Object> connector = SourceSystemConnectorSupport.resolve(ec, SYSTEM)
        Map<String, Object> sent = DatasetRefreshSupport.datasetReadParameters(connector)
        assertEquals([datasetRead: true], sent)
        Set<String> declared = ec.service.getServiceDefinition(EXTRACT).getInParameterNames()
        assertTrue(declared.containsAll(sent.keySet() + [DatasetRefreshSupport.WINDOW_FIELD_PARAMETER]), "declares ${declared}")
        assertNull(DatasetRefreshSupport.datasetConnectorProblem(ec, SYSTEM))
        // a run side names no window field: the connector row sets none, and the extractor declares no default
        assertNull(connector.windowFieldName)
        assertNull(ec.service.getServiceDefinition(EXTRACT).getInParameter("windowFieldName").attribute("default-value"))
    }

    /**
     * Rulings 3b1-4, 3b1-5, 3b1-6: the first read is FULL on the query's dateColumn, the next one INCREMENTAL on darpanStamp
     * from readThru minus the 2-day margin; each is sent datasetRead and calendar days. A record whose stamp moved replaces
     * its copy record.
     */
    @Test
    void aNetSuiteDatasetReadsInFullThenByWhatChanged() {
        String id = netSuiteDataset("NS_KC_RECORDS")
        List<Map> calls = []
        List<Map> first = [order("101", "2026-11-18", "2026-11-18T10:01:59Z", "B"), order("102", "2026-11-19", "2026-11-19T18:00:00Z", "B")]
        Map res = atCopyClock(0) { refresh(id, stub(calls, first)) }
        assertEquals([], res.errors)
        Map p = calls[0].params
        assertEquals(EXTRACT, calls[0].service)
        assertEquals("trandate", p.windowFieldName, "the full read windows on the query's own dateColumn")
        assertEquals(true, p.datasetRead, "and is told it builds a copy: stamped, through today")
        assertEquals(A, p.companyUserGroupId)
        String zone = TenantAccessSupport.resolveActiveTenantTimeZone(ec)
        assertEquals(COPY_READ_AT.atZone(ZoneId.of(zone)).toLocalDate().toString(), p.windowEnd, "today, as the day it was sent")
        assertEquals(COPY_READ_AT.minusSeconds(62 * 86400L).atZone(ZoneId.of(zone)).toLocalDate().toString(), p.windowStart)
        File dir = DatasetRefreshSupport.datasetDir(ec, A, id)
        assertEquals(2, KeptCopyStore.manifest(dir).recordCount)

        // the next day: order 101's line was edited (its stamp moved), 102 is unchanged
        List<Map> changed = [order("101", "2026-11-18", "2026-11-21T09:00:00Z", "D")]
        List<Map> calls2 = []
        Map again = atCopyClock(1) { refresh(id, stub(calls2, changed)) }
        assertEquals([], again.errors)
        Map p2 = calls2[0].params
        assertEquals("darpanStamp", p2.windowFieldName, "the daily read is by what changed")
        assertEquals(true, p2.datasetRead)
        assertEquals(COPY_READ_AT.minusSeconds(2 * 86400L).atZone(ZoneId.of(zone)).toLocalDate().toString(), p2.windowStart,
                "from readThru less the 2-day margin")
        Map byId = KeptCopyStore.records(dir).collectEntries { [(it.id): it] }
        assertEquals(["101", "102"] as Set, byId.keySet())
        assertEquals("D", byId["101"].record.statusId)
        assertEquals("2026-11-21T09:00:00Z", byId["101"].stamp)
        assertEquals("B", byId["102"].record.statusId)
        def read = ec.entity.find("darpan.dataset.DatasetRead").condition("datasetId", id).orderBy("-readSeqId").disableAuthz().useCache(false).list().first()
        assertEquals("DRK_INCREMENTAL", read.readKindEnumId)
        assertEquals(1, read.changedCount as int)
    }

    /**
     * Review Focus 5 / ruling P3b1-14: core stores the dataset (it names no system), and its first read fails by the real
     * extractor's refusal, before any NetSuite request, with a message naming the template and the two that can be kept.
     * No copy is built, so nothing is ever served.
     */
    @Test
    void aNetSuiteDatasetOnALinkedChildTemplateIsRefused() {
        ["NS_KC_LINKED": "LINKED_CHILD_ABSENT", "NS_KC_STATE": "STATE_CONTRADICTION"].each { String queryId, String template ->
            String id = netSuiteDataset(queryId)
            Map res = atCopyClock(0) { refresh(id, null) }
            String errors = res.errors.toString()
            assertTrue(errors.contains(template) && errors.contains("RECORDS") && errors.contains("SALES_LINES"), errors)
            def read = ec.entity.find("darpan.dataset.DatasetRead").condition("datasetId", id).orderBy("-readSeqId").disableAuthz().useCache(false).list().first()
            assertEquals("DRS_FAILED", read.statusId)
            assertTrue((read.errorText as String).contains(template), read.errorText as String)
            assertEquals("DRK_FULL", read.readKindEnumId, "the first read")
            File dir = DatasetRefreshSupport.datasetDir(ec, A, id)
            assertNull(KeptCopyStore.manifest(dir), "no copy is built")
            def dataset = ec.entity.find("darpan.dataset.Dataset").condition("datasetId", id).disableAuthz().useCache(false).one()
            assertNull(dataset.currentVersion)
        }
    }

    /**
     * Review I1: a SALES_LINES record's lineId is transactionline.id, a line number within its own order, so line 1 of every
     * order shares it. A SALES_LINES dataset keys by its order and its line (tranId,lineId): two orders' line 1 stay two copy
     * records. Keyed by lineId alone the read fails (ruling P3b1-15: one id, different content) rather than merging them.
     */
    @Test
    void aSalesLinesCopyKeepsEveryOrdersLineOne() {
        List<Map> lines = [line("SO1", "1", "01", "2026-11-18T10:01:59Z"), line("SO1", "2", "02", "2026-11-18T10:01:59Z"),
                           line("SO2", "1", "01", "2026-11-19T18:00:00Z")]
        String id = netSuiteDataset("NS_KC_LINES", "tranId,lineId")
        Map res = atCopyClock(0) { refresh(id, stub([], lines)) }
        assertEquals([], res.errors)
        Map byId = KeptCopyStore.records(DatasetRefreshSupport.datasetDir(ec, A, id)).collectEntries { [(it.id): it.record] }
        assertEquals(["SO1|1", "SO1|2", "SO2|1"] as Set, byId.keySet())
        assertEquals("SO2", byId["SO2|1"].tranId)
        assertEquals("SO1", byId["SO1|1"].tranId, "the other order's line 1 did not replace it")

        String bad = netSuiteDataset("NS_KC_LINES_BAD", "lineId")
        Map refused = atCopyClock(0) { refresh(bad, stub([], lines)) }
        assertTrue(refused.errors.toString().contains("repeat a copy id of lineId with different content"), refused.errors.toString())
        assertNull(KeptCopyStore.manifest(DatasetRefreshSupport.datasetDir(ec, A, bad)), "no merged copy is built")
    }

    /**
     * Ruling P3b1-6: a NetSuite run side reads live even with a built copy whose records date their day plainly. The run sends
     * no window field (NO_WINDOW_FIELD); and were the connector row given one, this system's serving check still refuses (the
     * copy's records carry darpanStamp, and derived columns and HotWax statuses read at read time are not tracked by its stamps).
     */
    @Test
    void aNetSuiteRunSideReadsLiveEvenWithABuiltCopy() {
        String id = netSuiteDataset("NS_KC_SERVE")
        Map res = atCopyClock(0) { refresh(id, stub([], [order("201", "2026-11-10", "2026-11-10T19:00:00Z", "B")])) }
        assertEquals([], res.errors)
        assertNotNull(KeptCopyStore.manifest(DatasetRefreshSupport.datasetDir(ec, A, id)))

        Map params = [nsSuiteQlSourceQueryId: "NS_KC_SERVE", windowStart: "2026-11-10", windowEnd: "2026-11-11"]
        Map served = KeptCopySupport.sliceIfServed(ec, KeptCopySupport.side(SourceSystemConnectorSupport.resolve(ec, SYSTEM), params,
                [tenantId: A, windowStart: START, windowEnd: END]))
        assertEquals(false, served.served)
        assertEquals(KeptCopySupport.NO_WINDOW_FIELD, served.reason)

        def row = ec.entity.find("darpan.reconciliation.SourceSystemConnector").condition("systemEnumId", SYSTEM).disableAuthz().useCache(false).forUpdate(true).one()
        row.windowFieldName = "trandate"
        row.update()
        try {
            Map flipped = KeptCopySupport.sliceIfServed(ec, KeptCopySupport.side(SourceSystemConnectorSupport.resolve(ec, SYSTEM),
                    params + [windowFieldName: "trandate"], [tenantId: A, windowStart: START, windowEnd: END]))
            assertEquals(false, flipped.served)
            assertTrue((flipped.reason as String).contains("a NetSuite side reads live"), flipped.reason as String)
        } finally {
            row = ec.entity.find("darpan.reconciliation.SourceSystemConnector").condition("systemEnumId", SYSTEM).disableAuthz().useCache(false).forUpdate(true).one()
            row.windowFieldName = null
            row.update()
        }
    }

    // ----------------------------------------------------------------------------------------------------- helpers

    private void query(String id, Map<String, Object> overrides) {
        ec.entity.makeValue("darpan.reconciliation.NsSuiteQlSourceQuery").setAll([nsSuiteQlSourceQueryId: id, nsAuthConfigId: AUTH,
                companyUserGroupId: A, recordType: "SalesOrd", fromTable: "transaction", dateColumn: "trandate", isActive: "Y"] + overrides)
                .createOrUpdate()
        int seq = 0
        [netsuiteInternalId: "id", tranId: "tranid", trandate: "TO_CHAR(trandate, 'YYYY-MM-DD') AS trandate", statusId: "status"]
                .each { String field, String column ->
                    ec.entity.makeValue("darpan.reconciliation.NsSuiteQlSourceQueryField").setAll([nsSuiteQlSourceQueryId: id,
                            recordFieldName: field, sourceColumn: column, sequenceNum: ++seq]).createOrUpdate()
                }
    }

    private String netSuiteDataset(String queryId, String idField = "netsuiteInternalId") {
        Map stored = underFacadeGrant("dataset.DatasetServices.store#Dataset", [systemEnumId: SYSTEM, sourceConfigId: queryId,
                options: [:], idField: idField, stampField: NsSuiteQlOrderSupport.CHANGE_STAMP_FIELD, windowField: "trandate"])
        assertEquals([], stored.errors)
        return stored.out.datasetId as String
    }

    private static Map order(String id, String day, String stamp, String status) {
        return [netsuiteInternalId: id, tranId: "SO${id}".toString(), trandate: day, statusId: status, darpanStamp: stamp]
    }

    /** One SALES_LINES record as mapRowToRecord writes it (header fields, then the template's line fields, then the stamp). */
    private static Map line(String tranId, String lineId, String orderLineId, String stamp) {
        return [tranId: tranId, trandate: "2026-11-18", orderLineId: orderLineId, lineId: lineId, lineState: "OPEN", darpanStamp: stamp]
    }

    /** A refresh's extractor: the given records, as the live extract writes them. */
    private Closure stub(List<Map> calls, List<Map> records) {
        return { String svc, Map p ->
            calls << [service: svc, params: new LinkedHashMap(p)]
            File out = new File(DataManagerSupport.resolveDirectoryFile(ec, p.outputLocation as String, true), p.fileName as String)
            out.setText(JsonOutput.toJson([records: records, metadata: [windowFrom: p.windowStart, windowThru: p.windowEnd]]), "UTF-8")
            return [fileLocation: out.absolutePath, recordCount: records.size(), dataAvailable: !records.isEmpty()]
        }
    }

    private Map atCopyClock(int plusDays, Closure<Map> work) {
        ec.user.setEffectiveTime(Timestamp.from(COPY_READ_AT.plusSeconds(plusDays * 86400L)))
        try {
            return work.call()
        } finally {
            ec.user.setEffectiveTime(null)
        }
    }

    private Map refresh(String id, Closure extractor) {
        return underFacadeGrant("dataset.DatasetServices.refresh#Dataset", [datasetId: id, extractor: extractor, forceFull: false])
    }

    private Map underFacadeGrant(String serviceName, Map params) {
        def harnessFrame = ec.artifactExecution.peek()
        assertEquals("smokeTests", harnessFrame?.name, "test precondition: unexpected artifact stack")
        ec.artifactExecution.pop(harnessFrame)
        ec.artifactExecution.enableAuthz()
        try {
            assertFalse(ec.artifactExecution.authzDisabled, "test precondition: authz must be ON")
            def aei = ec.artifactExecution.push(FACADE_GRANT, ArtifactExecutionInfo.AT_SERVICE, ArtifactExecutionInfo.AUTHZA_ALL, true)
            try {
                ec.message.clearAll()
                Map out = null
                try {
                    out = ec.service.sync().name(serviceName).parameters(new HashMap(params)).call()
                } catch (Exception e) {
                    ec.message.addError(e.toString())
                }
                List<String> errors = ec.message.errors.collect { it.toString() } +
                        ec.message.validationErrors.collect { it.message?.toString() }
                ec.message.clearAll()
                return [out: errors ? null : out, errors: errors]
            } finally {
                ec.artifactExecution.pop(aei)
            }
        } finally {
            ec.artifactExecution.disableAuthz()
            ec.artifactExecution.push("smokeTests", ArtifactExecutionInfo.AT_OTHER, ArtifactExecutionInfo.AUTHZA_ALL, false)
            ec.artifactExecution.setAnonymousAuthorizedAll()
        }
    }
}
