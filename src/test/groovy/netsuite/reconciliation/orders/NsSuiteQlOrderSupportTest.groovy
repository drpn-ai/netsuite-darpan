package netsuite.reconciliation.orders

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * Unit coverage for the SuiteQL extraction logic (DAR-BE-032, generalized by DAR-BE-044). Pure logic
 * only — spec validation, query construction, row mapping and the on-disk envelope — so it runs
 * without Moqui, Spark or a live NetSuite account. The HTTP exchange itself is proven by
 * tools/live-probes.
 *
 * The sales-order spec below is the SAME shape that used to be hardcoded in the support class. Every
 * behavioural test still asserts against it, which is what makes this file evidence that DAR-BE-044
 * moved the values without changing what they produce.
 */
class NsSuiteQlOrderSupportTest {

    private static final String TOKEN_URL =
            "https://4054670.suitetalk.api.netsuite.com/services/rest/auth/oauth2/v1/token"

    /** The gorjana OMS order-presence pair, expressed as configuration rather than constants. */
    private static Map<String, Object> salesOrderSpec() {
        return NsSuiteQlOrderSupport.normalizeSpec(
                [nsSuiteQlSourceQueryId: "NS_SALES_ORDERS", recordType: "SalesOrd", fromTable: "transaction",
                 dateColumn            : "trandate", joinKeyFieldName: "orderId", originFieldName: "orderOrigin"],
                [[recordFieldName: "orderId", sourceColumn: "custbody_hc_order_id", sequenceNum: 1],
                 [recordFieldName: "orderName", sourceColumn: "otherrefnum", sequenceNum: 2],
                 [recordFieldName: "externalId", sourceColumn: "custbody_hc_shopify_order_id", sequenceNum: 3],
                 [recordFieldName: "netsuiteOrderId", sourceColumn: "tranid", sequenceNum: 4],
                 [recordFieldName: "netsuiteInternalId", sourceColumn: "id", sequenceNum: 5],
                 [recordFieldName: "orderDate", sourceColumn: "TO_CHAR(trandate, 'YYYY-MM-DD') AS trandate",
                  sourceColumnAlias: "trandate", sequenceNum: 6],
                 [recordFieldName: "statusId", sourceColumn: "status", sequenceNum: 7]])
    }

    // ------------------------------------------------------------------ endpoint derivation

    @Test
    void suiteQlUrlIsDerivedFromTheAuthProfilesTokenUrl() {
        // The account id must never be configured twice. Deriving the query endpoint from the token
        // endpoint's host keeps them in lockstep and keeps the URL inside OutboundHttpPolicy's
        // allow-list by construction rather than by a second validation.
        assertEquals("https://4054670.suitetalk.api.netsuite.com/services/rest/query/v1/suiteql",
                NsSuiteQlOrderSupport.suiteQlUrlFromTokenUrl(TOKEN_URL))
    }

    @Test
    void suiteQlUrlRejectsAHostOutsideNetSuite() {
        // A NsAuthConfig row mutated out-of-band could otherwise point the extractor at an
        // attacker-chosen host with a valid bearer token attached.
        assertThrows(IllegalArgumentException) {
            NsSuiteQlOrderSupport.suiteQlUrlFromTokenUrl("https://evil.example.com/token")
        }
        assertThrows(IllegalArgumentException) {
            NsSuiteQlOrderSupport.suiteQlUrlFromTokenUrl("http://169.254.169.254/token")
        }
    }

    // ------------------------------------------------------------------ spec validation (DAR-BE-044)

    @Test
    void theSelectListIsExactlyTheMappedColumns() {
        // One source of truth: a column cannot be fetched without being projected, or projected
        // without being fetched. When they were two separate constants they could drift, and the
        // failure mode was a silently null record field rather than an error.
        List<String> columns = NsSuiteQlOrderSupport.selectColumns(salesOrderSpec())
        assertEquals(["custbody_hc_order_id", "otherrefnum", "custbody_hc_shopify_order_id", "tranid",
                      "id", "TO_CHAR(trandate, 'YYYY-MM-DD') AS trandate", "status"], columns)
    }

    @Test
    void aConfiguredFragmentCannotSmuggleSqlIntoTheQuery() {
        // THE REASON THIS TEST EXISTS. The window bounds are interpolated into SuiteQL text rather
        // than bound as parameters, so requireIsoDate has always guarded them. While the table, type
        // and columns were compile-time constants they were trivially safe; reading them from
        // operator-editable config puts them on the SAME interpolation path. Each one is checked
        // before it can reach the query.
        assertThrows(IllegalArgumentException) {
            NsSuiteQlOrderSupport.normalizeSpec([recordType: "SalesOrd'; DROP TABLE transaction--"],
                    [[recordFieldName: "orderId", sourceColumn: "custbody_hc_order_id"]])
        }
        assertThrows(IllegalArgumentException) {
            NsSuiteQlOrderSupport.normalizeSpec([recordType: "SalesOrd", fromTable: "transaction; DELETE FROM x"],
                    [[recordFieldName: "orderId", sourceColumn: "custbody_hc_order_id"]])
        }
        assertThrows(IllegalArgumentException) {
            NsSuiteQlOrderSupport.normalizeSpec([recordType: "SalesOrd"],
                    [[recordFieldName: "orderId", sourceColumn: "(SELECT id FROM transaction)"]])
        }
        assertThrows(IllegalArgumentException) {
            NsSuiteQlOrderSupport.normalizeSpec([recordType: "SalesOrd", dateColumn: "trandate) OR 1=1--"],
                    [[recordFieldName: "orderId", sourceColumn: "custbody_hc_order_id"]])
        }
    }

    @Test
    void aLegitimateFormattingExpressionIsStillAccepted() {
        // The guard must not be so tight that it blocks the one expression this connector REQUIRES:
        // trandate has to be formatted server-side (see below), and that is a function call.
        Map spec = NsSuiteQlOrderSupport.normalizeSpec([recordType: "SalesOrd"],
                [[recordFieldName: "orderDate", sourceColumn: "TO_CHAR(trandate, 'YYYY-MM-DD') AS trandate"]])
        assertEquals(["TO_CHAR(trandate, 'YYYY-MM-DD') AS trandate"], NsSuiteQlOrderSupport.selectColumns(spec))
    }

    @Test
    void anAliasedExpressionIsReadBackUnderItsAliasNotItsText() {
        // The row comes back keyed by alias. Reading it by the expression text would find nothing and
        // produce a silently null field — the exact failure this mapping is meant to make impossible.
        Map spec = NsSuiteQlOrderSupport.normalizeSpec([recordType: "SalesOrd"],
                [[recordFieldName: "orderDate", sourceColumn: "TO_CHAR(trandate, 'YYYY-MM-DD') AS trandate"]])
        Map record = NsSuiteQlOrderSupport.mapRowToRecord(spec, [trandate: "2026-08-14"])
        assertEquals("2026-08-14", record.orderDate)
    }

    @Test
    void aQueryWithNoFieldMappingsIsRefused() {
        // It would select nothing and project nothing, and the run would report a clean empty result.
        assertThrows(IllegalArgumentException) {
            NsSuiteQlOrderSupport.normalizeSpec([recordType: "SalesOrd"], [])
        }
    }

    @Test
    void aJoinKeyThatNamesAnUnmappedFieldIsRefused() {
        // Otherwise the join key silently resolves to nothing and every record looks NETSUITE_NATIVE.
        assertThrows(IllegalArgumentException) {
            NsSuiteQlOrderSupport.normalizeSpec([recordType: "SalesOrd", joinKeyFieldName: "orderId"],
                    [[recordFieldName: "netsuiteOrderId", sourceColumn: "tranid"]])
        }
    }

    @Test
    void anOriginFieldWithoutAJoinKeyIsRefused() {
        // Origin means "was the join key's column populated". With no join key the question has no
        // answer, and defaulting it would label every record with a value nobody can rely on.
        assertThrows(IllegalArgumentException) {
            NsSuiteQlOrderSupport.normalizeSpec([recordType: "SalesOrd", originFieldName: "orderOrigin"],
                    [[recordFieldName: "netsuiteOrderId", sourceColumn: "tranid"]])
        }
    }

    // ------------------------------------------------------------------ query construction

    @Test
    void ordersQueryBoundsTheDateColumnHalfOpen() {
        String q = NsSuiteQlOrderSupport.buildRecordsQuery(salesOrderSpec(), "2026-08-01", "2026-09-01")
        // Half-open [from, to) matches the OMS connector's orderDateFrom/orderDateThru semantics, so
        // consecutive windows cannot double-count an order sitting exactly on the boundary.
        assertTrue(q.contains("trandate >= TO_DATE('2026-08-01', 'YYYY-MM-DD')"), q)
        assertTrue(q.contains("trandate < TO_DATE('2026-09-01', 'YYYY-MM-DD')"), q)
        assertTrue(q.contains("BETWEEN") == false, "BETWEEN is inclusive on both ends: ${q}")
    }

    @Test
    void ordersQuerySelectsTheJoinKeyAndOperatorIdentityColumns() {
        String q = NsSuiteQlOrderSupport.buildRecordsQuery(salesOrderSpec(), "2026-08-01", "2026-09-01")
        assertTrue(q.contains("custbody_hc_order_id"), "the join key must be selected: ${q}")
        assertTrue(q.contains("tranid"), "operators find the order in NetSuite by document number")
        assertTrue(q.contains("otherrefnum"), "carries the OMS order name")
        assertTrue(q.contains("type = 'SalesOrd'"), "presence is a sales-order question: ${q}")
        // Deterministic ordering is what makes offset paging safe; without it NetSuite may return
        // overlapping or skipped rows across pages.
        assertTrue(q.contains("ORDER BY id"), "offset paging needs a total order: ${q}")
    }

    @Test
    void ordersQueryAsksForIsoDatesRatherThanTheAccountsDisplayFormat() {
        // A live run returned trandate as "8/14/2026" — NetSuite renders it in the ACCOUNT's date
        // format, which is a display preference, not a contract. The OMS side emits ISO, so an
        // operator comparing orderDate across the two sides would see every row mismatch, and an
        // account set to D/M/YYYY would silently swap day and month on top of that. Ask the database
        // to format it instead of guessing at parse time, where 8/9/2026 is genuinely ambiguous.
        String q = NsSuiteQlOrderSupport.buildRecordsQuery(salesOrderSpec(), "2026-08-01", "2026-09-01")
        assertTrue(q.contains("TO_CHAR(trandate, 'YYYY-MM-DD')"),
                "trandate must be formatted server-side to ISO: ${q}")
    }

    @Test
    void ordersQueryRejectsMalformedDates() {
        // The dates are interpolated into SuiteQL text, so anything but yyyy-MM-dd is refused rather
        // than concatenated.
        Map spec = salesOrderSpec()
        assertThrows(IllegalArgumentException) {
            NsSuiteQlOrderSupport.buildRecordsQuery(spec, "2026-8-1", "2026-09-01")
        }
        assertThrows(IllegalArgumentException) {
            NsSuiteQlOrderSupport.buildRecordsQuery(spec, "2026-08-01", "') OR 1=1--")
        }
    }

    @Test
    void aDifferentRecordTypeIsConfigurationRatherThanANewConnector() {
        // THE POINT OF DAR-BE-044. Four within-NetSuite pairs (DAR-BE-045..047) were going to mean
        // four more systemEnumIds, connector rows and extract services. A different type and
        // projection now produce a correct query through the same code path.
        Map spec = NsSuiteQlOrderSupport.normalizeSpec(
                [recordType: "ItemFulfillment", fromTable: "transaction", dateColumn: "trandate",
                 joinKeyFieldName: "fulfillmentId"],
                [[recordFieldName: "fulfillmentId", sourceColumn: "tranid", sequenceNum: 1],
                 [recordFieldName: "createdFrom", sourceColumn: "createdfrom", sequenceNum: 2]])
        String q = NsSuiteQlOrderSupport.buildRecordsQuery(spec, "2026-08-01", "2026-09-01")
        assertTrue(q.contains("type = 'ItemFulfillment'"), q)
        assertTrue(q.contains("SELECT tranid, createdfrom FROM transaction"), q)
        assertTrue(q.contains("ORDER BY id"), "paging safety is not negotiable per pair: ${q}")
    }

    // ------------------------------------------------------------------ row mapping

    @Test
    void rowMapsCustbodyHcOrderIdOntoOrderId() {
        Map record = NsSuiteQlOrderSupport.mapRowToRecord(salesOrderSpec(), [
                id                          : "1234567",
                tranid                      : "SO7117992",
                custbody_hc_order_id        : "M942074",
                custbody_hc_shopify_order_id: "7156795932803",
                otherrefnum                 : "#GOR197201194",
                trandate                    : "2026-08-14",
                status                      : "SalesOrd:B",
        ])
        // orderId is the join key and MUST be the HotWax-written field: it means "this NetSuite order
        // came from that OMS order", where otherrefnum only means "these two strings are equal".
        assertEquals("M942074", record.orderId)
        assertEquals("#GOR197201194", record.orderName)
        assertEquals("7156795932803", record.externalId)
        assertEquals("SO7117992", record.netsuiteOrderId)
        assertEquals("1234567", record.netsuiteInternalId)
        assertEquals("2026-08-14", record.orderDate)
        assertEquals("SalesOrd:B", record.statusId)
    }

    @Test
    void rowWithNoHotWaxOrderIdMapsToANullJoinKeyRatherThanBeingDropped() {
        // Orders keyed in directly in NetSuite carry no custbody_hc_order_id (~0.3% of a sampled
        // month). Dropping them here would hide them; they are excluded by a configured sourceFilters
        // rule instead, so the operator decides and the count stays explainable.
        Map record = NsSuiteQlOrderSupport.mapRowToRecord(salesOrderSpec(),
                [id: "99", tranid: "SO99", trandate: "2026-08-02"])
        assertNull(record.orderId)
        assertEquals("SO99", record.netsuiteOrderId)
    }

    @Test
    void mappingIgnoresTheLinksArraySuiteQlAddsToEveryRow() {
        // SuiteQL decorates each row with a "links" array. It is transport metadata, not order data,
        // and would otherwise reach the compare stage as a field. Mapping is now driven by the
        // configured field list, so an unmapped key cannot survive by construction.
        Map record = NsSuiteQlOrderSupport.mapRowToRecord(salesOrderSpec(),
                [id: "1", tranid: "SO1", links: [[rel: "self"]]])
        assertTrue(!record.containsKey("links"), "links must not survive mapping: ${record}")
    }

    @Test
    void orderOriginDistinguishesHotWaxOrdersFromNetSuiteNativeOnes() {
        // SourceFilterSupport keeps a record that LACKS the field — "exclude these values" cannot
        // match an absent value. So a null orderId can never be excluded by rule on its own, and the
        // origin has to be an explicit value present on every record for the operator to act on.
        Map spec = salesOrderSpec()
        Map hotwax = NsSuiteQlOrderSupport.mapRowToRecord(spec, [id: "1", custbody_hc_order_id: "M942074"])
        Map native_ = NsSuiteQlOrderSupport.mapRowToRecord(spec, [id: "2"])
        assertEquals("HOTWAX", hotwax.orderOrigin)
        assertEquals("NETSUITE_NATIVE", native_.orderOrigin)
    }

    @Test
    void aSpecWithNoOriginFieldEmitsNoOriginAtAll() {
        // A within-NetSuite pair has no HotWax-written column and no origin question to answer;
        // emitting a constant there would invite a rule that silently matches everything.
        Map spec = NsSuiteQlOrderSupport.normalizeSpec([recordType: "ItemFulfillment"],
                [[recordFieldName: "fulfillmentId", sourceColumn: "tranid"]])
        Map record = NsSuiteQlOrderSupport.mapRowToRecord(spec, [tranid: "IF1"])
        assertEquals(["fulfillmentId"] as Set, record.keySet() as Set)
    }

    // ------------------------------------------------------------------ window normalisation

    @Test
    void windowBoundsNormaliseToSuiteQlDatesFromEveryShapeDispatchSends() {
        // Dispatch hands windowStart/windowEnd through as Timestamp, ISO text, SQL text or epoch
        // millis depending on the caller; SuiteQL needs yyyy-MM-dd and nothing else.
        assertEquals("2026-08-01", NsSuiteQlOrderSupport.toSuiteQlDate("2026-08-01"))
        assertEquals("2026-08-01", NsSuiteQlOrderSupport.toSuiteQlDate("2026-08-01T00:00:00Z"))
        assertEquals("2026-08-01", NsSuiteQlOrderSupport.toSuiteQlDate("2026-08-01 00:00:00.0"))
        assertEquals("2026-08-01", NsSuiteQlOrderSupport.toSuiteQlDate(
                java.sql.Timestamp.valueOf("2026-08-01 13:45:00")))
        assertEquals("2026-08-01", NsSuiteQlOrderSupport.toSuiteQlDate(1785542400000L))  // 2026-08-01T00:00:00Z
    }

    @Test
    void anUnparseableWindowBoundIsRefusedRatherThanSilentlyDefaulted() {
        // Defaulting here would run a window nobody asked for and report its result as authoritative.
        assertThrows(IllegalArgumentException) { NsSuiteQlOrderSupport.toSuiteQlDate(null) }
        assertThrows(IllegalArgumentException) { NsSuiteQlOrderSupport.toSuiteQlDate("last tuesday") }
    }

    // ------------------------------------------------------------------ paging + envelope

    private static final Closure<List<Long>> BY_ID = { Map row -> [row.id as Long] }

    private static List<String> writtenIds(StringWriter out) {
        return ((List<Map>) new groovy.json.JsonSlurper().parseText(out.toString()).records)*.netsuiteInternalId
    }

    /**
     * DAR-OPS-029 S41a (OC109): pages are keyset, not offset. Each page is the same query with the page key selected and, after the
     * first, "AND id > last" before its ORDER BY, always at offset 0; the metadata keeps the query as built.
     */
    @Test
    void streamsEveryPageUntilHasMoreIsFalse() {
        List<Map> rows = [[id: "1", custbody_hc_order_id: "A"], [id: "2", custbody_hc_order_id: "B"], [id: "3", custbody_hc_order_id: "C"]]
        List<String> sent = []
        List<Integer> offsetsSeen = []
        StringWriter out = new StringWriter()
        Map result = NsSuiteQlOrderSupport.streamOrdersToWriter(out,
                [spec: salesOrderSpec(), fromDate: "2026-08-01", toDate: "2026-09-01", pageSize: 2],
                { String q, int limit, int offset -> sent << q; offsetsSeen << offset; SuiteQlKeysetFake.page(rows, q, limit, BY_ID) })

        assertEquals(3, result.recordCount)
        assertEquals(2, result.pageCount)
        assertEquals([0, 0], offsetsSeen, "keyset pages never move the offset")
        assertEquals(["1", "2", "3"], writtenIds(out))
        String built = NsSuiteQlOrderSupport.buildRecordsQuery(salesOrderSpec(), "2026-08-01", "2026-09-01")
        assertEquals([built, built], sent.collect { SuiteQlKeysetFake.baseQuery(it) })
        assertTrue(sent[0].startsWith("SELECT id AS darpan_page_key, "), sent[0])
        assertEquals(null, SuiteQlKeysetFake.after(sent[0]))
        assertEquals([2L], SuiteQlKeysetFake.after(sent[1]))
        assertTrue((boolean) result.dataAvailable)
        assertEquals(built, new groovy.json.JsonSlurper().parseText(out.toString()).metadata.query, "the metadata names the query as built")
    }

    /**
     * Conservation under a live NetSuite (OC109). With offset paging a record that left the window between two pages (deleted,
     * or edited out of it) shifted every later row back one, and the row at the page boundary was never read. Keyset paging
     * asks for the rows after the last one read, so nothing is skipped.
     */
    @Test
    void aRowLeavingTheWindowMidReadMakesNoOtherRowSkipped() {
        List<Map> rows = (1..5).collect { [id: it.toString(), custbody_hc_order_id: "O${it}".toString()] as Map }
        int calls = 0
        StringWriter out = new StringWriter()
        NsSuiteQlOrderSupport.streamOrdersToWriter(out, [spec: salesOrderSpec(), fromDate: "2026-08-01", toDate: "2026-09-01", pageSize: 2],
                { String q, int limit, int offset ->
                    if (calls++ == 1) rows.removeAll { it.id == "2" }   // record 2, already read, leaves the window
                    SuiteQlKeysetFake.page(rows, q, limit, BY_ID)
                })
        assertEquals(["1", "2", "3", "4", "5"], writtenIds(out), "record 3 was the one offset paging skipped")
    }

    /** OC109: a record edited INTO the window with a lower id shifted rows forward under offset paging and one was read twice. */
    @Test
    void aLowIdJoiningTheWindowMidReadMakesNoRowReadTwice() {
        List<Map> rows = [2, 4, 6, 8].collect { [id: it.toString(), custbody_hc_order_id: "O${it}".toString()] as Map }
        int calls = 0
        StringWriter out = new StringWriter()
        Map result = NsSuiteQlOrderSupport.streamOrdersToWriter(out, [spec: salesOrderSpec(), fromDate: "2026-08-01", toDate: "2026-09-01", pageSize: 2],
                { String q, int limit, int offset ->
                    if (calls++ == 1) rows.add(0, [id: "1", custbody_hc_order_id: "O1"])
                    SuiteQlKeysetFake.page(rows, q, limit, BY_ID)
                })
        List<String> ids = writtenIds(out)
        assertEquals(ids.unique(false), ids, "no record twice")
        assertEquals(["2", "4", "6", "8"], ids, "a record that joined behind the read is the next read's")
        assertEquals(4, result.recordCount)
    }

    /** QA D1: the page queries exactly, so a wrong operator or a swapped column in the key condition cannot pass unseen. */
    @Test
    void thePagedQueryTextIsExact() {
        String one = "SELECT tranid FROM transaction WHERE type = 'SalesOrd' ORDER BY id"
        assertEquals("SELECT id AS darpan_page_key, tranid FROM transaction WHERE type = 'SalesOrd' ORDER BY id",
                NsSuiteQlOrderSupport.pagedQuery(one, ["id"], null))
        assertEquals("SELECT id AS darpan_page_key, tranid FROM transaction WHERE type = 'SalesOrd' AND id > 2000 ORDER BY id",
                NsSuiteQlOrderSupport.pagedQuery(one, ["id"], ["2000"]))
        String two = "SELECT t.tranid FROM transaction t JOIN transactionline tl ON tl.transaction = t.id WHERE t.type = 'SalesOrd' ORDER BY t.id, tl.id"
        assertEquals("SELECT t.id AS darpan_page_key, tl.id AS darpan_page_key2, t.tranid FROM transaction t JOIN transactionline tl " +
                "ON tl.transaction = t.id WHERE t.type = 'SalesOrd' AND (t.id > 10 OR (t.id = 10 AND tl.id > 102)) ORDER BY t.id, tl.id",
                NsSuiteQlOrderSupport.pagedQuery(two, NsSuiteQlOrderSupport.pageKeys(two), ["10", "102"]))
        String distinct = "SELECT DISTINCT tl.item AS item FROM transactionline tl WHERE tl.item IS NOT NULL ORDER BY tl.item"
        assertEquals("SELECT DISTINCT tl.item AS darpan_page_key, tl.item AS item FROM transactionline tl WHERE tl.item IS NOT NULL " +
                "AND tl.item > 7 ORDER BY tl.item", NsSuiteQlOrderSupport.pagedQuery(distinct, ["tl.item"], ["7"]))
        // the outer ORDER BY is the key, not one inside a LISTAGG ... WITHIN GROUP (ORDER BY ...) in the select list
        assertEquals(["t.id"], NsSuiteQlOrderSupport.pageKeys("SELECT (SELECT LISTAGG(x, '|') WITHIN GROUP (ORDER BY l.id) FROM l) AS y FROM transaction t WHERE 1 = 1 ORDER BY t.id"))
        assertThrows(IllegalArgumentException) { NsSuiteQlOrderSupport.pageKeys("SELECT a FROM t WHERE 1 = 1 ORDER BY a, b, c") }
        assertThrows(IllegalArgumentException) { NsSuiteQlOrderSupport.pageKeys("SELECT a FROM t WHERE 1 = 1 ORDER BY UPPER(a)") }
    }

    /**
     * QA D3: RECORDS sorts by the bare name id. A field aliased id that is not the id column would make ORDER BY id sort by the
     * alias while the page key reads the real id, and keyset paging would skip rows; such a field is refused.
     */
    @Test
    void aRecordsFieldAliasedIdThatIsNotTheIdColumnIsRefused() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException) {
            NsSuiteQlOrderSupport.normalizeSpec([nsSuiteQlSourceQueryId: "Q", recordType: "SalesOrd"],
                    [[recordFieldName: "orderName", sourceColumn: "tranid AS id", sequenceNum: 1]])
        }
        assertTrue(thrown.message.contains("id"), thrown.message)
        // the id column itself, aliased or not, stays legal
        NsSuiteQlOrderSupport.normalizeSpec([nsSuiteQlSourceQueryId: "Q", recordType: "SalesOrd"],
                [[recordFieldName: "internalId", sourceColumn: "id", sequenceNum: 1]])
    }

    /** OC109: NetSuite answering more to come with an empty page would leave no key to continue from: the read fails. */
    @Test
    void anEmptyPageThatSaysMoreFails() {
        IllegalStateException thrown = assertThrows(IllegalStateException) {
            NsSuiteQlOrderSupport.streamOrdersToWriter(new StringWriter(), [spec: salesOrderSpec(), fromDate: "2026-08-01", toDate: "2026-09-01"],
                    { String q, int limit, int offset -> [items: [], hasMore: true] })
        }
        assertTrue(thrown.message.contains("empty page"), thrown.message)
    }

    /** OC109: SALES_LINES fans an order out into lines, so its key is (order id, line id): lines of one order across a page break. */
    @Test
    void salesLinesPageByOrderAndLineSoAnOrderSplitAcrossPagesKeepsEveryLine() {
        int seq = 0
        Map<String, Object> spec = NsSuiteQlOrderSupport.normalizeSpec([nsSuiteQlSourceQueryId: "NS_LINES", recordType: "SalesOrd",
                fromTable: "transaction", dateColumn: "trandate", joinKeyFieldName: "orderId", queryTemplate: "SALES_LINES",
                lineKeyColumn: "custcol_hc_order_line_id"],
                [[recordFieldName: "orderId", sourceColumn: "custbody_hc_order_id"], [recordFieldName: "netsuiteOrderId", sourceColumn: "tranid"]]
                        .collect { it + [sequenceNum: ++seq] as Map<String, Object> })
        List<Map> rows = [[10, 101], [10, 102], [10, 103], [11, 111]].collect { List<Integer> k ->
            [order: k[0], line: k[1], custbody_hc_order_id: "M${k[0]}".toString(), order_line_id: "L${k[1]}".toString()] as Map
        }
        List<String> sent = []
        StringWriter out = new StringWriter()
        Map result = NsSuiteQlOrderSupport.streamOrdersToWriter(out, [spec: spec, fromDate: "2026-08-01", toDate: "2026-09-01", pageSize: 2],
                { String q, int limit, int offset -> sent << q; SuiteQlKeysetFake.page(rows, q, limit, { Map r -> [r.order as Long, r.line as Long] }) })
        assertEquals(["L101", "L102", "L103", "L111"], ((List<Map>) new groovy.json.JsonSlurper().parseText(out.toString()).records)*.orderLineId)
        assertEquals(4, result.recordCount)
        assertTrue(sent[0].startsWith("SELECT t.id AS darpan_page_key, tl.id AS darpan_page_key2, "), sent[0])
        assertEquals([10L, 102L], SuiteQlKeysetFake.after(sent[1]), "the second page starts after order 10's line 102")
    }

    // DAR-BE-111: each page's records go to the enricher (the HotWax status lookup) before they are written, so a Keep rule
    // or a conclusion can read what it adds; a failing enricher fails the extract rather than writing half an answer.
    @Test
    void anEnricherSeesEachPageBeforeItIsWrittenAndItsFieldsAreWritten() {
        List<Map> rows = [[id: "1", custbody_hc_order_id: "145059"], [id: "2", custbody_hc_order_id: "155939"], [id: "3", custbody_hc_order_id: null]]
        List<Integer> pageSizes = []
        StringWriter out = new StringWriter()
        Map result = NsSuiteQlOrderSupport.streamOrdersToWriter(out,
                [spec: salesOrderSpec(), fromDate: "2026-10-01", toDate: "2026-10-20", pageSize: 2,
                 pageEnricher: { List<Map<String, Object>> records ->
                     pageSizes << records.size()
                     records.each { if (it.orderId) it.omsStatusId = it.orderId == "145059" ? "ORDER_COMPLETED" : "ORDER_APPROVED" }
                 }],
                { String q, int limit, int offset -> SuiteQlKeysetFake.page(rows, q, limit, BY_ID) })

        assertEquals([2, 1], pageSizes)
        List<Map> written = (List<Map>) new groovy.json.JsonSlurper().parseText(out.toString()).records
        assertEquals(["145059", "155939", null], written*.orderId)
        assertEquals(["ORDER_COMPLETED", "ORDER_APPROVED", null], written*.omsStatusId, "the enriched field is in the file Keep reads")
        assertEquals(3, result.recordCount)

        assertThrows(IllegalStateException) {
            NsSuiteQlOrderSupport.streamOrdersToWriter(new StringWriter(),
                    [spec: salesOrderSpec(), fromDate: "2026-10-01", toDate: "2026-10-20",
                     pageEnricher: { List records -> throw new IllegalStateException("OMS unavailable") }],
                    { String q, int limit, int offset -> [items: [[id: "1", custbody_hc_order_id: "145059"]], hasMore: false] })
        }
    }

    // DAR-BE-111: which mapped field names the HotWax order, checked like every other configured fragment.
    @Test
    void theOmsStatusKeyMustBeAMappedFieldAndTheConfigComesWithIt() {
        Map base = [nsSuiteQlSourceQueryId: "Q1", recordType: "SalesOrd", fromTable: "transaction", dateColumn: "trandate"]
        List fields = [[recordFieldName: "orderId", sourceColumn: "custbody_hc_order_id", sequenceNum: 1]]
        Map spec = NsSuiteQlOrderSupport.normalizeSpec(base + [omsStatusConfigId: "rails_prod", omsStatusKeyField: "orderId"], fields)
        assertEquals(["rails_prod", "orderId"], [spec.omsStatusConfigId, spec.omsStatusKeyField])
        assertNull(NsSuiteQlOrderSupport.normalizeSpec(base, fields).omsStatusConfigId)
        assertThrows(IllegalArgumentException) {
            NsSuiteQlOrderSupport.normalizeSpec(base + [omsStatusConfigId: "rails_prod", omsStatusKeyField: "nope"], fields) }
        assertThrows(IllegalArgumentException) {
            NsSuiteQlOrderSupport.normalizeSpec(base + [omsStatusConfigId: "rails_prod"], fields) }
    }

    // DAR-BE-190 (gorjana: "if there is a HC order Id the fulfillment should be imported"): a line's HotWax item status
    // needs the field naming the item as well as the order. On SALES_LINES that is the line key (orderLineId), which the
    // template maps itself; elsewhere it must be a mapped field. It comes only with the order key.
    @Test
    void theOmsStatusLineKeyNamesTheItemAndComesWithTheOrderKey() {
        Map lines = [nsSuiteQlSourceQueryId: "Q1", recordType: "SalesOrd", fromTable: "transaction", dateColumn: "lastmodifieddate",
                     queryTemplate: "SALES_LINES", lineKeyColumn: "custcol_hc_order_line_id", joinKeyFieldName: "orderId"]
        List fields = [[recordFieldName: "orderId", sourceColumn: "custbody_hc_order_id", sequenceNum: 1]]
        Map oms = [omsStatusConfigId: "gorjana_prod", omsStatusKeyField: "orderId"]

        Map spec = NsSuiteQlOrderSupport.normalizeSpec(lines + oms + [omsStatusLineKeyField: "orderLineId"], fields)
        assertEquals("orderLineId", spec.omsStatusLineKeyField)
        assertNull(NsSuiteQlOrderSupport.normalizeSpec(lines + oms, fields).omsStatusLineKeyField)

        assertThrows(IllegalArgumentException, {
            NsSuiteQlOrderSupport.normalizeSpec(lines + [omsStatusLineKeyField: "orderLineId"], fields) }, "no order key, no item key")
        assertThrows(IllegalArgumentException, {
            NsSuiteQlOrderSupport.normalizeSpec(lines + oms + [omsStatusLineKeyField: "nope"], fields) }, "not a field of the record")
        Map headers = [nsSuiteQlSourceQueryId: "Q1", recordType: "SalesOrd", fromTable: "transaction", dateColumn: "trandate"]
        assertThrows(IllegalArgumentException, {
            NsSuiteQlOrderSupport.normalizeSpec(headers + oms + [omsStatusLineKeyField: "orderLineId"], fields) },
                "orderLineId is only the template's own field on SALES_LINES")
    }

    // DAR-BE-245/329 (RAILS: one NetSuite fulfillment became two HotWax shipments on 18 orders): a sales order's shipped
    // fulfillments as "location^number", read once per order and counted against HotWax's shipments per location.
    @Test
    void linkedLocationsListsEachShippedFulfillmentWithItsLocationAndOnlyAnOrderRecordCountsShipGroups() {
        Map base = [nsSuiteQlSourceQueryId: "Q1", recordType: "SalesOrd", fromTable: "transaction", dateColumn: "lastmodifieddate"]
        List fields = [[recordFieldName: "orderId", sourceColumn: "custbody_hc_order_id", sequenceNum: 1],
                       [recordFieldName: "fulfillments", sourceColumn: "LINKED_LOCATIONS(ItemShip) AS fulfillments", sequenceNum: 2]]
        Map oms = [omsStatusConfigId: "rails_prod", omsStatusKeyField: "orderId"]
        Map spec = NsSuiteQlOrderSupport.normalizeSpec(base + oms + [omsShipGroupsField: "fulfillments"], fields)
        assertEquals("fulfillments", spec.omsShipGroupsField)

        String sql = NsSuiteQlOrderSupport.selectColumns(spec, "transaction")[1]
        assertTrue(sql.contains("lr_fulfillments.type = 'ItemShip' AND lr_fulfillments.status = 'C'"), "only a Shipped fulfillment counts: ${sql}")
        assertTrue(sql.contains("lk_fulfillments.previousdoc = transaction.id"), sql)
        assertTrue(sql.contains("LISTAGG(TO_CHAR((SELECT MAX(ll_fulfillments.location) FROM transactionline ll_fulfillments " +
                "WHERE ll_fulfillments.transaction = lr_fulfillments.id AND ll_fulfillments.mainline = 'F')) || '^' || lr_fulfillments.tranid"),
                "one entry per fulfillment, not per line (SuiteQL ignores LISTAGG DISTINCT): ${sql}")
        assertTrue(sql.endsWith(" AS fulfillments"), sql)
        Map record = NsSuiteQlOrderSupport.mapRowToRecord(spec, [custbody_hc_order_id: "150738", fulfillments: "77^IF387146"])
        assertEquals(["77^IF387146"], record.fulfillments)
        assertEquals([], NsSuiteQlOrderSupport.mapRowToRecord(spec, [custbody_hc_order_id: "1", fulfillments: null]).fulfillments,
                "an order with no shipped fulfillment reads an empty list, not null")

        assertThrows(IllegalArgumentException, {
            NsSuiteQlOrderSupport.normalizeSpec(base + [omsShipGroupsField: "fulfillments"], fields) }, "shipments are asked of an order")
        assertThrows(IllegalArgumentException, {
            NsSuiteQlOrderSupport.normalizeSpec(base + oms + [omsShipGroupsField: "orderId"], fields) }, "must be a LINKED_LOCATIONS field")
    }

    @Test
    void writesRecordsFirstThenMetadataSoPagesCanBeAppended() {
        StringWriter out = new StringWriter()
        NsSuiteQlOrderSupport.streamOrdersToWriter(out,
                [spec: salesOrderSpec(), fromDate: "2026-08-01", toDate: "2026-09-01", pageSize: 1000],
                { String q, int limit, int offset -> [items: [[id: "1", tranid: "SO1"]], hasMore: false] })

        String doc = out.toString()
        assertTrue(doc.startsWith('{"records":['), "records must stream first: ${doc.take(40)}")
        assertTrue(doc.contains('"metadata":'), "metadata must be present: ${doc}")
        Map parsed = (Map) new groovy.json.JsonSlurper().parseText(doc)
        assertEquals(1, ((List) parsed.records).size())
        assertEquals(1, ((Map) parsed.metadata).recordCount)
        // The metadata names the record type, so a run file says which NetSuite population it holds.
        // With one connector serving every pair, the file is otherwise indistinguishable.
        assertEquals("SalesOrd", ((Map) parsed.metadata).recordType)
    }

    @Test
    void anEmptyWindowStillWritesAWellFormedEmptyDocument() {
        // A window with no orders must not produce a truncated file the compare stage cannot parse.
        StringWriter out = new StringWriter()
        Map result = NsSuiteQlOrderSupport.streamOrdersToWriter(out,
                [spec: salesOrderSpec(), fromDate: "2026-08-01", toDate: "2026-09-01", pageSize: 1000],
                { String q, int limit, int offset -> [items: [], hasMore: false] })

        assertEquals(0, result.recordCount)
        Map parsed = (Map) new groovy.json.JsonSlurper().parseText(out.toString())
        assertEquals([], parsed.records)
    }

    // ------------------------------------------------------------------ DAR-BE-318 plan 3b-2b: Keep runs in Spark

    /** The SALES_LINES shape: one record per item line, the line key from the HotWax line id column. */
    private static Map<String, Object> salesLinesSpec() {
        return NsSuiteQlOrderSupport.normalizeSpec(
                [nsSuiteQlSourceQueryId: "NS_SALES_LINES", recordType: "SalesOrd", fromTable: "transaction", dateColumn: "trandate",
                 queryTemplate: "SALES_LINES", lineKeyColumn: "custcol_hc_order_line_id", joinKeyFieldName: "orderId",
                 originFieldName: "orderOrigin"],
                [[recordFieldName: "orderId", sourceColumn: "custbody_hc_order_id", sequenceNum: 1],
                 [recordFieldName: "netsuiteOrderId", sourceColumn: "tranid", sequenceNum: 2]])
    }

    /** gorjana SO7365765: a keyed line, the store-credit payment line (no line id), and a blank-id gift card line. */
    private static final List<Map> SO7365765 = [
            [custbody_hc_order_id: "M1205413", tranid: "SO7365765", order_line_id: "01", item_type: "InvtPart", line_state: "BILLED",
             item_name: "2311-104-67-G", line_amount: "82"],
            [custbody_hc_order_id: "M1205413", tranid: "SO7365765", item_type: "Payment", line_state: "OPEN",
             item_name: "Store Credit - Redemption", line_amount: "-89.3"],
            [custbody_hc_order_id: "M1205413", tranid: "SO7365765", order_line_id: " ", item_type: "Payment", item_name: "Gift Card"]]

    private static List<Map> streamed(Map options, List<Map> rows) {
        StringWriter out = new StringWriter()
        NsSuiteQlOrderSupport.streamOrdersToWriter(out, [fromDate: "2026-10-31", toDate: "2026-11-01", pageSize: 1000] + options,
                { String q, int limit, int offset -> [items: rows, hasMore: false] })
        return (List<Map>) new groovy.json.JsonSlurper().parseText(out.toString()).records
    }

    /**
     * Ruling 3b2b-12: a SALES_LINES line with no HotWax line id is no longer set aside by the extractor. It is written with
     * nsUnkeyedLine "Y", after the projection so a narrowed read keeps it, and the connector's declared Keep line sets it aside
     * in Spark. A keyed line carries no such field.
     */
    @Test
    void anUnkeyedSalesLineIsWrittenWithNsUnkeyedLine() {
        List<Map> all = streamed([spec: salesLinesSpec()], SO7365765)
        assertEquals(["01", null, null], all*.orderLineId)
        assertEquals([null, "Y", "Y"], all*.nsUnkeyedLine)
        assertFalse(all[0].containsKey("nsUnkeyedLine"))
        assertEquals("Store Credit - Redemption", all[1].itemName)

        List<Map> narrowed = streamed([spec: salesLinesSpec(), keepFields: ["orderId", "itemName"]], SO7365765)
        assertEquals([[orderId: "M1205413", itemName: "2311-104-67-G"],
                      [orderId: "M1205413", itemName: "Store Credit - Redemption", nsUnkeyedLine: "Y"],
                      [orderId: "M1205413", itemName: "Gift Card", nsUnkeyedLine: "Y"]], narrowed,
                "the projection cannot drop the field the Keep line reads")
    }

    /** Only SALES_LINES numbers lines: a RECORDS record has no orderLineId at all and never carries the field. */
    @Test
    void aRecordsTemplateRecordNeverCarriesIt() {
        List<Map> all = streamed([spec: salesOrderSpec()], [[id: "1", custbody_hc_order_id: "A"], [id: "2"]])
        assertEquals(2, all.size())
        assertTrue(all.every { !it.containsKey("nsUnkeyedLine") && !it.containsKey("orderLineId") }, "${all}")
    }

    /**
     * Keep runs in Spark (Ruling 3b2b-23), so the extract drops nothing for a tenant's filter: a caller that still sends
     * rules gets every record, and the file reports no exclusion counts (Spark's .keep.json carries them).
     */
    @Test
    void noRecordIsDroppedForATenantFilter() {
        StringWriter out = new StringWriter()
        Map result = NsSuiteQlOrderSupport.streamOrdersToWriter(out,
                [spec       : salesOrderSpec(), fromDate: "2026-08-01", toDate: "2026-09-01", pageSize: 1000,
                 filterRules: [[sequenceNum: 1, fieldExpression: "orderOrigin", operator: "EXCLUDE_IN", filterValues: "NETSUITE_NATIVE"],
                               [sequenceNum: 2, fieldExpression: "statusId", operator: "INCLUDE_IN", filterValues: "A,B,G"]]],
                { String q, int limit, int offset ->
                    [items: [[id: "1", custbody_hc_order_id: "A", status: "B"], [id: "2", status: "H"], [id: "3", custbody_hc_order_id: "C"]],
                     hasMore: false]
                })
        Map parsed = (Map) new groovy.json.JsonSlurper().parseText(out.toString())
        assertEquals(["1", "2", "3"], ((List<Map>) parsed.records)*.netsuiteInternalId)
        assertEquals(3, result.recordCount)
        Map metadata = (Map) parsed.metadata
        ["configuredExclusions", "excludedCount", "unkeyedLinesSetAside"].each {
            assertFalse(metadata.containsKey(it), "the read filtered nothing, so it reports no ${it}")
        }
        assertFalse(result.containsKey("excludedCount") || result.containsKey("excludedCollector"), "${result.keySet()}")
    }

    /**
     * Ruling 3b2b-12: a keyed lookup answers exactly as today. A conclusion lookup asks for an order's lines by key and compares
     * nothing, so no Spark Keep follows it: its unkeyed lines are left out, as they always were. A run's read by the other
     * side's keys sends no conclusionLookup and is kept in Spark like any run read, so it writes them marked.
     */
    @Test
    void aConclusionLookupLeavesOutUnkeyedLinesAsToday() {
        Map<String, Object> keyed = salesLinesSpec()
        keyed.lookupKeyField = "orderId"
        keyed.lookupKeys = ["M1205413"]
        assertEquals([["01"], ["01", null, null]], [
                streamed([spec: keyed, conclusionLookup: true], SO7365765)*.orderLineId,
                streamed([spec: keyed], SO7365765)*.orderLineId])
        assertEquals(3, streamed([spec: salesLinesSpec(), conclusionLookup: true], SO7365765).size(),
                "a window read is never a lookup, whatever it is sent")
    }

    @Test
    void keepFieldsNarrowsEachRecordToTheRequestedProjection() {
        // The service still accepts a projection even though the connector no longer drives one (the
        // mapping IS the projection now, as it is for DATABASE). A direct caller can still narrow.
        StringWriter out = new StringWriter()
        NsSuiteQlOrderSupport.streamOrdersToWriter(out,
                [spec      : salesOrderSpec(), fromDate: "2026-08-01", toDate: "2026-09-01", pageSize: 1000,
                 keepFields: ["orderId", "netsuiteOrderId"]],
                { String q, int limit, int offset ->
                    [items: [[id: "1", tranid: "SO1", custbody_hc_order_id: "A", otherrefnum: "#G1"]], hasMore: false]
                })
        Map parsed = (Map) new groovy.json.JsonSlurper().parseText(out.toString())
        Map record = (Map) ((List) parsed.records)[0]
        assertEquals(["orderId", "netsuiteOrderId"] as Set, record.keySet() as Set)
        assertEquals("A", record.orderId)
    }

    @Test
    void streamingWithoutASpecIsRefused() {
        // Dispatch always resolves one. A missing spec means the config lookup failed silently, and
        // continuing would write a well-formed empty file that reads exactly like a clean run.
        assertThrows(IllegalArgumentException) {
            NsSuiteQlOrderSupport.streamOrdersToWriter(new StringWriter(),
                    [fromDate: "2026-08-01", toDate: "2026-09-01"],
                    { String q, int limit, int offset -> [items: [], hasMore: false] })
        }
    }

    @Test
    void failsAtTheHardPageCeilingRatherThanTruncating() {
        // A source that always answers hasMore=true (a bug, or a window that keeps growing) must terminate rather than spin
        // until the run times out. DAR-OPS-029 S41a: and it fails, as NsLifecycleSnapshot does: a warning beside a partial
        // record count let a truncated read compare as a complete one (no hollow green).
        int calls = 0
        IllegalStateException thrown = assertThrows(IllegalStateException) {
            NsSuiteQlOrderSupport.streamOrdersToWriter(new StringWriter(),
                    [spec: salesOrderSpec(), fromDate: "2026-08-01", toDate: "2026-09-01", pageSize: 1000, maxPages: 3],
                    { String q, int limit, int offset -> calls++; SuiteQlKeysetFake.page((1..100).collect { [id: it.toString()] as Map }, q, 1, BY_ID) })
        }
        assertEquals(3, calls, "it stops at the ceiling")
        assertTrue(thrown.message.contains("3 pages"), thrown.message)
    }

    @Test
    void theUsedItemsReadStopsAtThePageCeilingToo() {
        // DAR-OPS-029 S41a: ACTIVE_ITEMS reads the used items first, and that loop had no ceiling at all. The fetcher's own
        // guard (50 calls) only stops this test from hanging if the read has none.
        int seq = 0
        Map<String, Object> spec = NsSuiteQlOrderSupport.normalizeSpec([nsSuiteQlSourceQueryId: "Q1", recordType: "item", fromTable: "item",
                queryTemplate: "ACTIVE_ITEMS", joinKeyFieldName: "field1"],
                ["id", "itemid", "live"].collect { [recordFieldName: "field${++seq}".toString(), sourceColumn: it, sequenceNum: seq] as Map<String, Object> })
        int calls = 0
        IllegalStateException thrown = assertThrows(IllegalStateException) {
            NsSuiteQlOrderSupport.streamOrdersToWriter(new StringWriter(),
                    [spec: spec, asOfDate: "2026-10-28", pageSize: 1000, maxPages: 3],
                    { String q, int limit, int offset ->
                        if (++calls > 50) throw new IllegalStateException("the used-items read looped past 50 pages with no ceiling")
                        SuiteQlKeysetFake.page((1..100).collect { [item: it.toString()] as Map }, q, 1, { Map r -> [r.item as Long] })
                    })
        }
        assertEquals(3, calls, "the used-items read stops at the ceiling: ${thrown.message}")
        assertTrue(thrown.message.contains("3 pages"), thrown.message)
    }

    @Test
    void pageSizeIsCappedAtTheSuiteQlCeiling() {
        // NetSuite silently caps a larger limit; asking for more than 1000 would make the caller
        // believe it had read further than it had.
        assertEquals(1000, NsSuiteQlOrderSupport.normalizePageSize(5000))
        assertEquals(1000, NsSuiteQlOrderSupport.normalizePageSize(null))
        assertEquals(250, NsSuiteQlOrderSupport.normalizePageSize(250))
        assertEquals(1000, NsSuiteQlOrderSupport.normalizePageSize(0))
    }
}
