package netsuite.reconciliation.orders

import darpan.facade.reconciliation.RunVerificationSupport
import darpan.peak.NetSuiteReadGate
import darpan.peak.PeakReadPolicy
import darpan.peak.ReadLimits
import darpan.reconciliation.automation.SourceSystemConnectorSupport
import darpan.reconciliation.conclusion.ConclusionKeySupport
import darpan.reconciliation.conclusion.ExcludedRecordsSidecar
import darpan.reconciliation.conclusion.RunConclusionSupport
import darpan.reconciliation.source.KeepArtifacts
import darpan.reconciliation.source.KeepLineTranslator
import darpan.reconciliation.source.KeepPlanSupport
import darpan.reconciliation.source.KeepStepSupport
import darpan.reconciliation.source.SourceFilterSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import netsuite.reconciliation.inventory.NsTokenCache
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext
import org.moqui.service.ServiceCallback

import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
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
        // this system's own declarations on its row (ruling P3b1-9; keepLinesJson, ruling 3b2b-12)
        ec.entity.makeDataLoader().location("component://netsuite-darpan/data/NetSuiteConnectorSeedData.xml").dummyFks(true).load()
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
        // DAR-BE-318 (plan 3b-2b, Ruling 3b2b-23): the row still names the filter slot (it means "this endpoint takes Keep"),
        // and the service no longer declares it, so the shared gate keeps a NetSuite side in Spark: the read filters nothing.
        assertFalse(declared.contains((String) connector.filterParameterName),
                "a NetSuite side keeps in Spark only once the extract stops declaring ${connector.filterParameterName}")
        assertTrue(KeepPlanSupport.sparkKeepsSide(connector, definition))
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

    // ---- DAR-BE-318 (plan 3b-2b, Task 20): NetSuite leaves Keep to Spark -------------------------------------------

    /** The derived field a SALES_LINES line with no HotWax line id is written with; the seed's Keep line reads it. */
    private static final String UNKEYED = "nsUnkeyedLine"
    private static final String KEEP_TENANT = "NS_KEEP"
    private static final String KEEP_AUTH = "NS_KEEP_AUTH"
    private static final String KEEP_LINES = "NS_KEEP_LINES"
    private static final String KEEP_RECORDS = "NS_KEEP_RECORDS"
    private static final List<Map<String, Object>> KEEP_FIELDS = [
            [recordFieldName: "orderId", sourceColumn: "custbody_hc_order_id", sequenceNum: 1],
            [recordFieldName: "netsuiteOrderId", sourceColumn: "tranid", sequenceNum: 2]]
    private static final Map<String, Object> LINES_QUERY = [recordType: "SalesOrd", fromTable: "transaction", dateColumn: "trandate",
            queryTemplate: "SALES_LINES", lineKeyColumn: "custcol_hc_order_line_id", joinKeyFieldName: "orderId", originFieldName: "orderOrigin"]
    private static final Map<String, Object> RECORDS_QUERY = [recordType: "SalesOrd", fromTable: "transaction", dateColumn: "trandate",
            joinKeyFieldName: "orderId", originFieldName: "orderOrigin"]

    /**
     * SALES_LINES rows as SuiteQL returns them (a null column is left out of the row). Two payment lines carry no HotWax line
     * id (one absent, one blank); the first would also fail the tenant's quantityShipped rule, so the built-in must own it, as
     * it did when the extractor set unkeyed lines aside before any rule. lineState "shipped " passes an INCLUDE_IN on SHIPPED
     * only through today's trim and upper-case; line 22 has no lineState (FIELD_ABSENT); 24.00 keeps its text.
     */
    private static final List<Map> LINE_ROWS = [
            [custbody_hc_order_id: "M1", tranid: "SO1", order_line_id: "01", quantity_shipped: "1", quantity_billed: "1", line_state: "BILLED",
             item_type: "InvtPart", line_id: "11", item_name: "2311-104-67-G", line_amount: "24.00"],
            [custbody_hc_order_id: "M1", tranid: "SO1", order_line_id: "02", quantity_shipped: "0", quantity_billed: "0", line_state: "OPEN",
             item_type: "InvtPart", line_id: "12", line_amount: "10"],
            [custbody_hc_order_id: "M1", tranid: "SO1", quantity_shipped: "0", line_state: "OPEN", item_type: "Payment", line_id: "13",
             item_name: "Store Credit - Redemption", line_amount: "-89.3"],
            [custbody_hc_order_id: "M2", tranid: "SO2", order_line_id: "01", quantity_shipped: "2", line_state: "shipped ", item_type: "InvtPart", line_id: "21"],
            [custbody_hc_order_id: "M2", tranid: "SO2", order_line_id: "02", quantity_shipped: "1", item_type: "InvtPart", line_id: "22"],
            [custbody_hc_order_id: "M2", tranid: "SO2", order_line_id: "   ", quantity_shipped: "1", line_state: "BILLED", item_type: "Payment",
             line_id: "23", item_name: "Gift Card", line_amount: "-5"],
            [custbody_hc_order_id: "M3", tranid: "SO3", order_line_id: "01", quantity_shipped: "1", line_state: "CLOSED", item_type: "InvtPart", line_id: "31"],
            [custbody_hc_order_id: "M3", tranid: "SO3", order_line_id: "02", quantity_shipped: "3", line_state: "BILLED", item_type: "InvtPart", line_id: "32"]]

    private static final List<Map> RECORD_ROWS = [[custbody_hc_order_id: "M1", tranid: "SO1"], [tranid: "SO9"],
                                                  [custbody_hc_order_id: "M2", tranid: "SO2"], [tranid: "SO8"]]

    private static final List<String> LINE_KEYS = ["orderId", "orderLineId"]

    /** [name, query id, query, rows, the tenant's filter rows (getter shape), key fields] */
    private static final List<Map> KEEP_SCENARIOS = [
            [name: "lines-two-rules", queryId: KEEP_LINES, query: LINES_QUERY, rows: LINE_ROWS, keyFields: LINE_KEYS,
             filters: [[sequenceNum: 1, fieldExpression: "quantityShipped", operator: "EXCLUDE_IN", filterValues: "0"],
                       [sequenceNum: 2, fieldExpression: "lineState", operator: "INCLUDE_IN", filterValues: "BILLED,SHIPPED"]]],
            [name: "lines-no-rules", queryId: KEEP_LINES, query: LINES_QUERY, rows: LINE_ROWS, keyFields: LINE_KEYS, filters: []],
            [name: "lines-nothing-set-aside", queryId: KEEP_LINES, query: LINES_QUERY, rows: [LINE_ROWS[0], LINE_ROWS[3], LINE_ROWS[7]],
             keyFields: LINE_KEYS, filters: [[sequenceNum: 1, fieldExpression: "lineState", operator: "EXCLUDE_IN", filterValues: "CANCELLED"]]],
            [name: "records-origin-rule", queryId: KEEP_RECORDS, query: RECORDS_QUERY, rows: RECORD_ROWS, keyFields: ["netsuiteOrderId"],
             filters: [[sequenceNum: 1, fieldExpression: "orderOrigin", operator: "EXCLUDE_IN", filterValues: "NETSUITE_NATIVE"]]]]

    private void keepFixtures() {
        ec.artifactExecution.disableAuthz()
        try {
            ec.entity.makeValue("moqui.security.UserGroup").setAll([userGroupId: KEEP_TENANT, description: KEEP_TENANT]).createOrUpdate()
            ec.entity.makeValue("darpan.reconciliation.NsAuthConfig").setAll([nsAuthConfigId: KEEP_AUTH, companyUserGroupId: KEEP_TENANT,
                    description: "Keep parity account", authType: "OAUTH2_M2M_JWT", tokenUrl: PEAK_TOKEN_URL,
                    clientId: "c" * 64, certId: "k" * 43, isActive: "Y"]).createOrUpdate()
            [(KEEP_LINES): LINES_QUERY, (KEEP_RECORDS): RECORDS_QUERY].each { String queryId, Map query ->
                ec.entity.makeValue("darpan.reconciliation.NsSuiteQlSourceQuery").setAll([nsSuiteQlSourceQueryId: queryId,
                        nsAuthConfigId: KEEP_AUTH, companyUserGroupId: KEEP_TENANT, description: queryId, isActive: "Y"] + query).createOrUpdate()
                KEEP_FIELDS.each { Map field ->
                    ec.entity.makeValue("darpan.reconciliation.NsSuiteQlSourceQueryField").setAll([nsSuiteQlSourceQueryId: queryId] + field).createOrUpdate()
                }
            }
        } finally {
            ec.artifactExecution.enableAuthz()
        }
    }

    /** The real extract#NetSuiteOrders over these rows, three to a page; returns the extract file. */
    private File extractThroughTheService(Map scenario) {
        List<Map> rows = (List<Map>) scenario.rows
        NsSuiteQlPageRetry.requestForTests = { int offset ->
            suiteQlPage(rows.subList(Math.min(offset, rows.size()), Math.min(offset + 3, rows.size())), offset + 3 < rows.size())
        } as Closure<HttpResponse<String>>
        NsTokenCache.put(KEEP_AUTH, PEAK_TOKEN_URL, "test-token", (long) (System.currentTimeMillis() / 1000L) + 3600L)
        try {
            Map result = (Map) ec.service.sync().name("reconciliation.NetSuiteOrderExtractionServices.extract#NetSuiteOrders")
                    .parameters([nsSuiteQlSourceQueryId: scenario.queryId, companyUserGroupId: KEEP_TENANT, pageSize: 3,
                                 windowStart: "2026-10-31", windowEnd: "2026-11-01", fileName: "${scenario.name}.json".toString()])
                    .disableAuthz().call()
            assertEquals(null, result.errors ? result.errors : null, "${result.errors}")
            File extract = RunVerificationSupport.resolveLocationFile(ec, (String) result.fileLocation)
            assertTrue(extract?.isFile(), "the extract wrote its file: ${result.fileLocation}")
            return extract
        } finally {
            NsSuiteQlPageRetry.requestForTests = null
            NsTokenCache.evict(KEEP_AUTH)
            ec.message.clearErrors()
        }
    }

    private static Map<String, Object> specOf(Map scenario) {
        return NsSuiteQlOrderSupport.normalizeSpec([nsSuiteQlSourceQueryId: scenario.queryId] + (Map) scenario.query, KEEP_FIELDS)
    }

    /**
     * Today's read, the reference: what streamOrdersToWriter and the extract script did before Keep moved into Spark, over the
     * same mapped records. A SALES_LINES line with no HotWax line id is set aside first, as "0:orderLineId" and outside every
     * tenant count; then the first rejecting rule (SourceFilterSupport.evaluate) owns a record; the rest are written. The side
     * file holds what was set aside, whole; the metadata carries the per-rule counts only when rules are configured.
     * Checked against streamOrdersToWriter as it stood before Task 20 on every scenario below (same records, metadata counts
     * and side file) before that code was deleted.
     */
    private static Map todaysRead(File extract, Map<String, Object> spec, List<Map> rows, List<Map> filterRows) {
        List<Map<String, Object>> rules = SourceFilterSupport.parseRules(filterRows)
        Map collector = ExcludedRecordsSidecar.newCollector()
        Map<Integer, Integer> excludedBy = [:], absentBy = [:]
        int excluded = 0, unkeyed = 0
        List<Map> kept = []
        rows.each { Map row ->
            Map<String, Object> record = NsSuiteQlOrderSupport.mapRowToRecord(spec, row)
            if (spec.queryTemplate == "SALES_LINES" && !record.get("orderLineId")?.toString()?.trim()) {
                unkeyed++
                ExcludedRecordsSidecar.collect(collector, record, [sequenceNum: 0, fieldExpression: "orderLineId"])
                return
            }
            Map<String, Object> verdict = SourceFilterSupport.evaluate(record, rules)
            if (verdict != null) {
                Integer seq = (Integer) ((Map) verdict.rule).sequenceNum
                Map<Integer, Integer> bucket = SourceFilterSupport.REASON_FIELD_ABSENT == verdict.reason ? absentBy : excludedBy
                bucket.put(seq, (bucket.get(seq) ?: 0) + 1)
                excluded++
                ExcludedRecordsSidecar.collect(collector, record, (Map) verdict.rule)
                return
            }
            kept << record
        }
        Map metadata = [recordCount: kept.size(), excludedCount: excluded]
        if (unkeyed) metadata.unkeyedLinesSetAside = unkeyed
        if (rules) {
            metadata.configuredExclusions = rules.collect { Map rule ->
                [sequenceNum: rule.sequenceNum, fieldExpression: rule.fieldExpression, operator: rule.operator,
                 values: new ArrayList((List) rule.values), excludedCount: excludedBy.get(rule.sequenceNum) ?: 0,
                 fieldAbsentCount: absentBy.get(rule.sequenceNum) ?: 0]
            }
        }
        extract.text = JsonOutput.toJson([records: kept, metadata: metadata])
        if (collector.total) {
            ExcludedRecordsSidecar.write(new File(extract.parentFile, ExcludedRecordsSidecar.fileNameFor(extract.name)), collector, extract.name)
        } else {
            ExcludedRecordsSidecar.discard(collector)
        }
        return metadata
    }

    /** What the conclude pass indexes of one side (RunConclusionSupport.indexSide), for every key and first key field. */
    private static Map conclusionIndex(File extract, List<String> keyFields, Set<String> keys, Set<String> firsts) {
        java.lang.reflect.Method index = RunConclusionSupport.getDeclaredMethod("indexSide", Map, Set, Set)
        index.setAccessible(true)
        return (Map) index.invoke(null, [extractFile: extract, keyFields: keyFields, idNormalizer: null, keyReliable: true], keys, firsts)
    }

    /** The derived field, taken out where it is the only difference the move makes (a set-aside payment line carries it). */
    private static Object withoutUnkeyedMark(Object value) {
        if (value instanceof Map) {
            return ((Map) value).findAll { k, v -> k != UNKEYED }.collectEntries { k, v -> [k, withoutUnkeyedMark(v)] }
        }
        if (value instanceof Collection) return ((Collection) value).collect { withoutUnkeyedMark(it) }
        return value
    }

    private static Object json(Object value) {
        return new JsonSlurper().parseText(JsonOutput.toJson(value))
    }

    @Test
    void theSeedDeclaresTheUnkeyedPaymentLineAsABuiltInKeepLine() {
        def row = ec.entity.find(SourceSystemConnectorSupport.ENTITY_NAME).condition("systemEnumId", "NETSUITE_SUITEQL").useCache(false).one()
        List<Map> rules = KeepLineTranslator.declared(row.keepLinesJson, [:])
        assertEquals(["BUILT_IN:0"], rules*.id)
        assertEquals([0], rules*.sequenceNum)
        assertEquals(["orderLineId"], rules*.label, "the side file reads _excludedBy 0:orderLineId, as it did")
        assertEquals(["BUILT_IN"], rules*.source)
        assertEquals([[kind: "compare", field: UNKEYED, text: "FACT", op: "EQ", values: ["Y"],
                       valueKind: "TEXT", fold: "NONE"]], rules.collect { ((List<Map>) it.tests)[0].test })
        assertEquals("Y", row.excludedRecordsSidecar, "Spark writes the side file where the extractor did")
    }

    /**
     * Parity (Review Focus 1): the same NetSuite rows, read through the real extract#NetSuiteOrders and kept in Spark by the
     * shared gate's plan, give exactly what today's read-time filtering gave: the same kept records, the same set-aside records
     * in the side file (the payment lines now also carry nsUnkeyedLine "Y"), the same counts, and the same conclusion index
     * (kept and excluded by key and first key field, and whether an absent key is unknown).
     */
    @Test
    void aNetSuiteSideKeptInSparkMatchesTodaysReadTimeFiltering() {
        keepFixtures()
        resetPeak()
        Map<String, Object> connector = SourceSystemConnectorSupport.resolve(ec, "NETSUITE_SUITEQL")
        def definition = ec.service.getServiceDefinition((String) connector.extractServiceName)
        File todayDir = Files.createTempDirectory("ns-keep-today").toFile()
        try {
            KEEP_SCENARIOS.each { Map scenario ->
                String name = scenario.name
                List<String> keyFields = (List<String>) scenario.keyFields
                File extract = extractThroughTheService(scenario)
                assertFalse(new File(extract.parentFile, ExcludedRecordsSidecar.fileNameFor(extract.name)).exists(),
                        "${name}: the extractor writes no side file of its own")

                Map sideKeep = KeepPlanSupport.effectiveSideKeep(ec, (List<Map<String, Object>>) scenario.filters, connector, [:], definition,
                        null, keyFields.collect { "\$.records[*].${it}".toString() }, true)
                assertEquals(KeepPlanSupport.SPARK, sideKeep.placement, name)
                assertEquals([], sideKeep.readFilters, "${name}: the read gets no filters")
                assertEquals(["BUILT_IN:0"] + ((List) scenario.filters).collect { "A:${it.sequenceNum}".toString() }, ((List<Map>) sideKeep.rules)*.id, name)
                KeepStepSupport.keptValues(ec, extract, sideKeep, keyFields[0])

                File today = new File(todayDir, extract.name)
                Map todayMetadata = todaysRead(today, specOf(scenario), (List<Map>) scenario.rows, (List<Map>) scenario.filters)

                // kept records
                List<Map> keptNow = []
                KeepArtifacts.forEachKeptRecord(extract) { Map record -> keptNow << record }
                assertEquals(new JsonSlurper().parse(today).records, json(keptNow), "${name}: kept records")
                assertTrue(keptNow.every { !it.containsKey(UNKEYED) }, name)

                // set-aside records, in the side file
                Map sideToday = ExcludedRecordsSidecar.read(today)
                Map sideNow = ExcludedRecordsSidecar.read(extract)
                assertEquals(sideToday.present, sideNow.present, "${name}: side file present")
                assertEquals(sideToday.truncated, sideNow.truncated, name)
                assertEquals(json(sideToday.records), json(withoutUnkeyedMark(sideNow.records)), "${name}: set-aside records")
                ((List<Map>) sideNow.records).each { Map record ->
                    assertEquals(record._excludedBy == "0:orderLineId" ? "Y" : null, record.get(UNKEYED), "${name}: ${record}")
                }

                // counts
                Map summary = KeepArtifacts.readSummary(extract)
                long builtIn = ((List<Map>) summary.builtIn).sum { ((it.setAsideCount ?: 0) as long) } as long
                assertEquals(todayMetadata.recordCount as long, summary.keptCount as long, "${name}: kept count")
                assertEquals((todayMetadata.unkeyedLinesSetAside ?: 0) as long, builtIn, "${name}: unkeyed lines set aside")
                assertEquals(todayMetadata.excludedCount as long, (summary.setAsideCount as long) - builtIn, "${name}: tenant exclusions")
                assertEquals(json(todayMetadata.configuredExclusions ?: []), json(summary.configuredExclusions), "${name}: per-rule counts")

                // what the conclude pass reads
                Set<String> keys = [] as Set, firsts = [] as Set
                [today, extract].each { File f ->
                    List<Map> all = (List<Map>) new JsonSlurper().parse(f).records + (List<Map>) ExcludedRecordsSidecar.read(f).records
                    all.each { Map record ->
                        String key = ConclusionKeySupport.compareKey(record, keyFields, null)
                        if (key) keys << key
                        String first = ConclusionKeySupport.compareKey(record, [keyFields[0]], null)
                        if (first) firsts << first
                    }
                }
                assertEquals(json(conclusionIndex(today, keyFields, keys, firsts)),
                        json(withoutUnkeyedMark(conclusionIndex(extract, keyFields, keys, firsts))), "${name}: conclusion index")
            }
        } finally {
            todayDir.deleteDir()
            resetPeak()
        }
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

    /**
     * DAR-BE-320. An audit's read by change of a HotWax-enriched lines query (gorjana R10/R11/R12): with no
     * omsChangedStatusIds it is refused in the words darpan reads as "read the whole window"; with them HotWax is asked for
     * the orders it changed before NetSuite is, and a failed ask fails the read (not a refusal: a whole-window read after it
     * would ask the same failing HotWax for every order of the month). Neither sends NetSuite a request.
     */
    @Test
    void aReadByChangeAsksHotwaxFirstAndNeitherARefusalNorAFailedAskReadsNetsuite() {
        String t = "NS_BE320"
        peakFixtures(t, t, t, [queryTemplate: "SALES_LINES", absentChildRecordType: null, dateColumn: "createddate",
                               lineKeyColumn: "custcol_hc_order_line_id", joinKeyFieldName: "orderId",
                               omsStatusConfigId: "NS_BE320_NO_OMS", omsStatusKeyField: "orderId", omsStatusLineKeyField: "orderLineId"])
        ec.artifactExecution.disableAuthz()
        try {
            ec.entity.makeValue("darpan.reconciliation.NsSuiteQlSourceQueryField").setAll([nsSuiteQlSourceQueryId: "${t}_Q".toString(),
                    recordFieldName: "orderId", sourceColumn: "custbody_hc_order_id", sequenceNum: 2]).createOrUpdate()
        } finally {
            ec.artifactExecution.enableAuthz()
        }
        resetPeak()
        int[] requests = [0]
        NsSuiteQlPageRetry.requestForTests = { int offset -> requests[0]++; suiteQlPage([], false) } as Closure<HttpResponse<String>>
        Closure<Map> readByChange = { ->
            (Map) ec.service.sync().name("reconciliation.NetSuiteOrderExtractionServices.extract#NetSuiteOrders")
                    .parameters([nsSuiteQlSourceQueryId: "${t}_Q".toString(), companyUserGroupId: t, windowStart: "2026-11-01",
                                 windowEnd: "2026-11-26", changedSince: java.sql.Timestamp.valueOf("2026-11-25 00:00:00")])
                    .disableAuthz().call()
        }
        // an earlier test's message error would stop the service from running at all
        ec.message.clearErrors()
        try {
            Map refused = readByChange()
            assertTrue(((List) refused.errors)?.any { it.toString().startsWith("Not read by what changed") && it.toString().contains("omsChangedStatusIds") },
                    "${refused.errors}")
            ec.message.clearErrors()

            ec.artifactExecution.disableAuthz()
            try {
                ec.entity.find("darpan.reconciliation.NsSuiteQlSourceQuery").condition("nsSuiteQlSourceQueryId", "${t}_Q".toString()).one()
                        .setAll([omsChangedStatusIds: "ITEM_CANCELLED", liveLineStates: "OPEN"]).update()
            } finally {
                ec.artifactExecution.enableAuthz()
            }
            Map failed = readByChange()
            List errors = (List) failed.errors
            assertTrue(errors?.any { it.toString().contains("HotWax changed orders read failed") }, "${errors}")
            assertTrue(errors.every { !it.toString().startsWith("Not read by what changed") }, "a failed ask is no refusal: ${errors}")
            assertEquals(0, requests[0], "NetSuite was never asked")
        } finally {
            ec.message.clearErrors()
            resetPeak()
        }
    }
}
