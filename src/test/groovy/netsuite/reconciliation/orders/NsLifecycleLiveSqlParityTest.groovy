package netsuite.reconciliation.orders

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-OPS-029 S41a (OC138, from S08's QA pass). A lifecycle stage's places, run as the LIVE SuiteQL the templates build, over an
 * in-memory H2 (Oracle mode) standing in for the transaction and link tables, against every combination of "has the stage" and the
 * stage exception's field (named, another value, empty). SQL's three-valued logic is the point: with the exception compared as
 * BUILTIN.DF(field) IN (...), an empty field made "waived" NULL, so a record with no stage and no exception value was neither
 * missing (NOT (has OR waived) is NULL) nor past the stage, and fell into no place, while the snapshot path found it missing.
 *
 * Every record must be in exactly one of missing (LINKED_CHILD_ABSENT) and past the stage (LINKED_CHILD_PRESENT, which an exception
 * also passes); excused (LINKED_CHILD_EXCUSED) is exactly the records with no stage that an exception names; and the snapshot
 * (NsLifecycleSnapshot.admits) answers each template exactly as the live query does.
 */
class NsLifecycleLiveSqlParityTest {

    private static final String FROM = "2026-10-01"
    private static final String TO = "2026-10-08"
    private static final List WAIVE = [[fieldId: "shipmethod", values: ["Store Pickup"]]]
    /** order id -> [has a shipped fulfillment, shipping method] */
    private static final Map<Long, List> ORDERS = [
            1L: [true, "Store Pickup"], 2L: [true, "Ground"], 3L: [true, null],
            4L: [false, "Store Pickup"], 5L: [false, "Ground"], 6L: [false, null]]

    private Connection db
    private File dir

    /** BUILTIN.DF stands in as the field's display text, which for these columns is the value itself. */
    static String df(String value) { return value }

    @BeforeEach
    void setup() {
        db = DriverManager.getConnection("jdbc:h2:mem:ns-live-${System.nanoTime()};MODE=Oracle;DATABASE_TO_LOWER=TRUE")
        dir = Files.createTempDirectory("ns-live-parity").toFile()
        run("CREATE SCHEMA builtin")
        run("CREATE ALIAS builtin.df FOR '${NsLifecycleLiveSqlParityTest.name}.df'".toString())
        run("CREATE TABLE transaction (id BIGINT, type VARCHAR(20), trandate DATE, status VARCHAR(4), shipmethod VARCHAR(40), tranid VARCHAR(20))")
        run("CREATE TABLE nexttransactionlink (previousdoc BIGINT, nextdoc BIGINT)")
        for (Map.Entry<Long, List> order : ORDERS.entrySet()) {
            long id = order.key as long
            List o = order.value
            insertTransaction(id, "SalesOrd", "B", o[1] as String)
            if (o[0]) {
                insertTransaction(100L + id, "ItemShip", "C", null)
                run("INSERT INTO nexttransactionlink VALUES (${id}, ${100L + id})".toString())
            }
        }
    }

    @AfterEach
    void cleanup() {
        db?.close()
        dir?.deleteDir()
    }

    @Test
    void everyRecordLandsInExactlyOnePlaceAndTheLiveQueryAgreesWithTheSnapshot() {
        Map<String, Set<Long>> live = [
                missing: liveIds(spec("LINKED_CHILD_ABSENT", [absentChildRecordType: "ItemShip"])),
                past   : liveIds(spec("LINKED_CHILD_PRESENT", [presentChildRecordType: "ItemShip"])),
                excused: liveIds(spec("LINKED_CHILD_EXCUSED", [absentChildRecordType: "ItemShip"]))]
        Set<Long> all = ORDERS.keySet()

        ORDERS.each { Long id, List o ->
            int places = [live.missing, live.past].count { it.contains(id) }
            assertEquals(1, places, "order ${id} (has the stage ${o[0]}, exception field ${o[1]}) is in ${places} of missing and past: ${live}")
        }
        assertEquals(all, live.missing + live.past)
        assertEquals([4L] as Set, live.excused, "excused is exactly the order with no stage that the exception names")
        assertTrue(live.past.containsAll(live.excused), "an excused record passes the stage")
        assertEquals([5L, 6L] as Set, live.missing, "an empty exception field does not excuse")

        assertTrue(pagesRun.any { it.contains(" > ") }, "later pages ran as SQL: ${pagesRun}")
        NsLifecycleSnapshot snapshot = NsLifecycleSnapshot.read(spec("RECORDS", [:]), FROM, TO, dir, fetcher, 10, 2)
        try {
            [missing: spec("LINKED_CHILD_ABSENT", [absentChildRecordType: "ItemShip"]),
             past   : spec("LINKED_CHILD_PRESENT", [presentChildRecordType: "ItemShip"]),
             excused: spec("LINKED_CHILD_EXCUSED", [absentChildRecordType: "ItemShip"])].each { String place, Map<String, Object> s ->
                Closure<Boolean> admits = snapshot.admits(s, fetcher)
                assertEquals(live[place], all.findAll { admits(it.toString()) } as Set, "${place}: the snapshot answers as the live query does")
            }
        } finally {
            snapshot.discard()
        }
    }

    /**
     * DAR-OPS-029 S41a (QA D1): the two-column page key as SQL. An order's lines fall either side of every page break at one row a
     * page; each line is read once and in (order, line) order, so the composite condition is exactly "after (order, line)".
     */
    @Test
    void aTwoColumnKeyPagesEveryLineOnceAsSql() {
        run("CREATE TABLE transactionline (id BIGINT, transaction BIGINT, mainline VARCHAR(1))")
        [[1, 11], [1, 12], [1, 13], [2, 21], [4, 41], [4, 42]].each { List<Integer> l ->
            run("INSERT INTO transactionline VALUES (${l[1]}, ${l[0]}, 'F')".toString())
        }
        String query = "SELECT tl.id AS line FROM transaction t JOIN transactionline tl ON tl.transaction = t.id " +
                "WHERE t.type = 'SalesOrd' AND tl.mainline = 'F' ORDER BY t.id, tl.id"
        List<String> lines = []
        [1, 2, 4].each { int pageSize ->
            lines.clear()
            NsSuiteQlOrderSupport.eachPage(query, pageSize, 20, fetcher) { List items -> items.each { lines << ((Map) it).line } }
            assertEquals(["11", "12", "13", "21", "41", "42"], lines, "page size ${pageSize}")
        }
        assertTrue(pagesRun.any { it.contains("(t.id > 1 OR (t.id = 1 AND tl.id > 12))") }, pagesRun.join("\n"))
    }

    // ---- the stand-in NetSuite ----

    private Set<Long> liveIds(Map<String, Object> spec) {
        Set<Long> ids = [] as Set
        NsSuiteQlOrderSupport.eachPage(NsSuiteQlOrderSupport.buildRecordsQuery(spec, FROM, TO), 2, 10, fetcher) { List items ->
            items.each { ids.add(((Map) it).id as Long) }
        }
        return ids
    }

    /** Runs the page's SuiteQL on H2 and answers its first limit rows, saying whether more follow, as SuiteQL does at offset 0. */
    private Closure fetcher = { String query, int limit, int offset ->
        assertEquals(0, offset, "keyset pages never move the offset")
        pagesRun << query
        List<Map> rows = []
        db.createStatement().withCloseable { st ->
            ResultSet rs = st.executeQuery(query)
            int n = rs.metaData.columnCount
            while (rs.next()) {
                Map row = [:]
                for (int i = 1; i <= n; i++) row.put(rs.metaData.getColumnLabel(i).toLowerCase(), rs.getObject(i)?.toString())
                rows << row
            }
        }
        return [items: rows.take(limit), hasMore: rows.size() > limit]
    }

    private List<String> pagesRun = []

    private static Map<String, Object> spec(String template, Map<String, Object> extra) {
        Map<String, Object> query = [nsSuiteQlSourceQueryId: "Q", recordType: "SalesOrd", fromTable: "transaction", dateColumn: "trandate",
                                     queryTemplate: template] + extra
        if (template != "RECORDS" && template != "LINKED_CHILD_PRESENT_ABSENT" && extra.containsKey("absentChildRecordType") || template == "LINKED_CHILD_PRESENT") {
            query.waiveRules = WAIVE
        }
        return NsSuiteQlOrderSupport.normalizeSpec(query, [[recordFieldName: "netsuiteInternalId", sourceColumn: "id", sequenceNum: 1] as Map<String, Object>])
    }

    void insertTransaction(long id, String type, String status, String shipmethod) {
        def ps = db.prepareStatement("INSERT INTO transaction VALUES (?, ?, DATE '2026-10-03', ?, ?, ?)")
        ps.setLong(1, id); ps.setString(2, type); ps.setString(3, status); ps.setString(4, shipmethod); ps.setString(5, "T" + id)
        ps.executeUpdate()
        ps.close()
    }

    void run(String sql) {
        db.createStatement().withCloseable { it.execute(sql) }
    }
}
