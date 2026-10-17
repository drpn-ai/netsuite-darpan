package netsuite.reconciliation.orders

import groovy.json.JsonSlurper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import java.nio.file.Files

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-073. A lifecycle's questions answered from one read of the window, with exactly the records each would have
 * found live.
 *
 * <p>The fake account holds six sales orders, one per place a two-stage (Fulfillment, Invoice) lifecycle sorts into,
 * plus the Store Pickup order its exception excuses and an order with no shipping method at all. It answers only the
 * queries the snapshot is meant to send, and counts them.</p>
 */
class NsLifecycleSnapshotTest {

    private static final String FROM = "2026-10-12", TO = "2026-10-15"
    private static final List<Map<String, Object>> FIELDS = [
            [recordFieldName: "internalId", sourceColumn: "id", sequenceNum: 1],
            [recordFieldName: "order", sourceColumn: "tranid", sequenceNum: 2],
            [recordFieldName: "shippingMethod", sourceColumn: "BUILTIN.DF(shipmethod) AS shippingMethod", sequenceNum: 3]]
    private static final List WAIVE = [[fieldId: "shipmethod", values: ["Store Pickup"]]]

    // id -> [tranid, shipmethod, shipped, billed]
    private static final Map<String, List> ORDERS = [
            "1": ["SO1", "Ground", true, true],     // shipped and billed
            "2": ["SO2", "Ground", true, false],    // shipped, never billed
            "3": ["SO3", "Ground", false, true],    // billed, never shipped
            "4": ["SO4", "Ground", false, false],   // never shipped, never billed
            "5": ["SO5", "Store Pickup", false, true], // fulfillment not expected, billed
            "6": ["SO6", null, false, false],       // no shipping method: an exception on it must not excuse it
    ]

    private File dir
    private List<String> sent

    @BeforeEach
    void setUp() {
        dir = Files.createTempDirectory("ns-snapshot-test").toFile()
        sent = []
    }

    @AfterEach
    void tearDown() { dir.deleteDir() }

    private static Map<String, Object> spec(Map<String, Object> overrides, List<Map<String, Object>> fields = FIELDS) {
        return NsSuiteQlOrderSupport.normalizeSpec([nsSuiteQlSourceQueryId: "Q", recordType: "SalesOrd",
                fromTable: "transaction", dateColumn: "trandate"] + overrides, fields)
    }

    private static List<String> ids(Closure<Boolean> keep) { ORDERS.findAll { k, v -> keep(k, v) }.keySet() as List<String> }

    /** The fake NetSuite: the window's rows for the base read, ids for each one-condition query, in pages. */
    private Closure fetcher() {
        Map<String, List<Map>> answers = [:]
        answers[NsSuiteQlOrderSupport.buildRecordsQuery(baseOf(spec([:])), FROM, TO)] =
                ORDERS.collect { k, v -> [id: k, tranid: v[0], shippingMethod: v[1], links: []] as Map }
        Map<String, Object> waived = spec([queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "ItemShip", waiveRules: WAIVE])
        answers[NsSuiteQlOrderSupport.buildConditionIdQuery(waived, "MISSING", "ItemShip", FROM, TO)] = ids { k, v -> !v[2] }.collect { [id: it] as Map }
        answers[NsSuiteQlOrderSupport.buildConditionIdQuery(waived, "MISSING", "CustInvc", FROM, TO)] = ids { k, v -> !v[3] }.collect { [id: it] as Map }
        // SQL: an empty shipping method is not IN ('Store Pickup').
        answers[NsSuiteQlOrderSupport.buildConditionIdQuery(waived, "WAIVED", null, FROM, TO)] = ids { k, v -> v[1] == "Store Pickup" }.collect { [id: it] as Map }
        return { String query, int limit, int offset ->
            sent << query
            List<Map> rows = answers[query]
            if (rows == null) throw new AssertionError("unexpected query: ${query}")
            List<Map> page = rows.drop(offset).take(limit)
            return [items: page, hasMore: offset + page.size() < rows.size()]
        }
    }

    private static Map<String, Object> baseOf(Map<String, Object> s) {
        return s + [queryTemplate: "RECORDS", absentChildRecordType: null, presentChildRecordType: null,
                    waiveRules: [], matchRules: [], stageReach: [:]]
    }

    /** The internalIds a question's extract would hold, through the same writer live pages go through. */
    private static List<String> extract(NsLifecycleSnapshot snapshot, Map<String, Object> s, Closure live) {
        Map served = snapshot.serve(snapshot.admits(s, live))
        StringWriter out = new StringWriter()
        try {
            NsSuiteQlOrderSupport.streamOrdersToWriter(out, [spec: s, fromDate: FROM, toDate: TO], (Closure) served.fetch)
        } finally {
            ((Closure) served.close).call()
        }
        return ((Map) new JsonSlurper().parseText(out.toString())).records.collect { it.internalId as String }
    }

    @Test
    void everyLifecycleQuestionFindsWhatItsLiveQueryWould() {
        Closure live = fetcher()
        NsLifecycleSnapshot snapshot = NsLifecycleSnapshot.read(spec([:]), FROM, TO, dir, live)
        assertEquals(6, snapshot.baseCount)

        assertEquals(["1", "2", "3", "4", "5", "6"], extract(snapshot, spec([:]), live))
        // "is Fulfillment missing?", Store Pickup excused: NOT (has OR waived)
        assertEquals(["3", "4", "6"], extract(snapshot, spec([queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "ItemShip", waiveRules: WAIVE]), live))
        // "did an exception excuse it?": NOT has AND waived
        assertEquals(["5"], extract(snapshot, spec([queryTemplate: "LINKED_CHILD_EXCUSED", absentChildRecordType: "ItemShip", waiveRules: WAIVE]), live))
        // "has it reached Invoice?"
        assertEquals(["1", "3", "5"], extract(snapshot, spec([queryTemplate: "LINKED_CHILD_PRESENT", presentChildRecordType: "CustInvc"]), live))
        // "has it reached Fulfillment?" with the exception: has OR waived
        assertEquals(["1", "2", "5"], extract(snapshot, spec([queryTemplate: "LINKED_CHILD_PRESENT", presentChildRecordType: "ItemShip", waiveRules: WAIVE]), live))
        assertEquals(["2", "4", "6"], extract(snapshot, spec([queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "CustInvc"]), live))
        assertEquals(["2"], extract(snapshot, spec([queryTemplate: "LINKED_CHILD_PRESENT_ABSENT", presentChildRecordType: "ItemShip", absentChildRecordType: "CustInvc"]), live))
    }

    // The point of it: the window is read once and each condition once, however many questions ask.
    @Test
    void theWindowIsReadOnceAndEachConditionOnce() {
        Closure live = fetcher()
        // Two rows a page, so every read pages: the window's six rows are three pages.
        NsLifecycleSnapshot snapshot = NsLifecycleSnapshot.read(spec([:]), FROM, TO, dir, live, 100, 2)
        assertEquals(3, sent.size())
        sent.clear()
        3.times {
            extract(snapshot, spec([queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "ItemShip", waiveRules: WAIVE]), live)
            extract(snapshot, spec([queryTemplate: "LINKED_CHILD_EXCUSED", absentChildRecordType: "ItemShip", waiveRules: WAIVE]), live)
            extract(snapshot, spec([queryTemplate: "LINKED_CHILD_PRESENT", presentChildRecordType: "CustInvc"]), live)
        }
        // missing Fulfillment: 4 ids, 2 pages; Store Pickup: 1 page; missing Invoice: 3 ids, 2 pages.
        assertEquals(5, sent.size(), sent.join("\n"))
        assertEquals(3, sent.unique(false).size())
        assertEquals([("MISSING|ItemShip|"): 4, ("WAIVED|" + groovy.json.JsonOutput.toJson(WAIVE)): 1, ("MISSING|CustInvc|"): 3],
                snapshot.conditionCounts)
    }

    // Exact or not at all: a shape the snapshot cannot reproduce exactly is asked live.
    @Test
    void onlyShapesItCanAnswerExactlyAreAnswered() {
        assertTrue(NsLifecycleSnapshot.canAnswer(spec([:])))
        assertTrue(NsLifecycleSnapshot.canAnswer(spec([queryTemplate: "RECORDS_MATCHING", matchRules: [[fieldId: "status", values: ["Closed"]]]])))
        assertFalse(NsLifecycleSnapshot.canAnswer(spec([queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "ItemShip", requireFulfillableOpenLine: "Y"])))
        assertFalse(NsLifecycleSnapshot.canAnswer(spec([queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "ItemShip", requireOverdueShipDate: "Y"])))
        assertFalse(NsLifecycleSnapshot.canAnswer(spec([queryTemplate: "STATE_CONTRADICTION", pendingStates: "B"])))
        // Without the id column a row cannot be matched to its conditions.
        assertFalse(NsLifecycleSnapshot.canAnswer(spec([:], [[recordFieldName: "order", sourceColumn: "tranid", sequenceNum: 1]])))

        NsLifecycleSnapshot snapshot = NsLifecycleSnapshot.read(spec([:]), FROM, TO, dir, fetcher())
        assertTrue(snapshot.covers(spec([queryTemplate: "LINKED_CHILD_PRESENT", presentChildRecordType: "CustInvc"], FIELDS.take(2))))
        // A column the window was not read with cannot be served.
        assertFalse(snapshot.covers(spec([:], FIELDS + [[recordFieldName: "memo", sourceColumn: "memo", sequenceNum: 4]])))
    }

    // Snapshots are kept per exactly what was read: another window, record type or account is another snapshot.
    @Test
    void theKeyNamesEverythingTheReadDependsOn() {
        String k = NsLifecycleSnapshot.keyFor(spec([:]), FROM, TO, "AUTH1|GORJANA")
        assertEquals(k, NsLifecycleSnapshot.keyFor(spec([queryTemplate: "LINKED_CHILD_PRESENT", presentChildRecordType: "CustInvc"]), FROM, TO, "AUTH1|GORJANA"))
        assertFalse(k == NsLifecycleSnapshot.keyFor(spec([:]), FROM, "2026-10-16", "AUTH1|GORJANA"))
        assertFalse(k == NsLifecycleSnapshot.keyFor(spec([:]), FROM, TO, "AUTH2|GORJANA"))
        assertFalse(k == NsLifecycleSnapshot.keyFor(spec([recordType: "RtnAuth"]), FROM, TO, "AUTH1|GORJANA"))
    }

    // A window larger than the page ceiling would leave every question short; it fails rather than truncate.
    @Test
    void aReadPastThePageCeilingFailsRatherThanTruncate() {
        assertThrows(IllegalStateException) { NsLifecycleSnapshot.read(spec([:]), FROM, TO, dir, fetcher(), 2, 2) }
    }
}
