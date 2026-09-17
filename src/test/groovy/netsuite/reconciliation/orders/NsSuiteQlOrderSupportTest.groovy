package netsuite.reconciliation.orders

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
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

    @Test
    void streamsEveryPageUntilHasMoreIsFalse() {
        List<Map> pages = [
                [items: [[id: "1", custbody_hc_order_id: "A"], [id: "2", custbody_hc_order_id: "B"]], hasMore: true],
                [items: [[id: "3", custbody_hc_order_id: "C"]], hasMore: false],
        ]
        List<Integer> offsetsSeen = []
        StringWriter out = new StringWriter()
        Map result = NsSuiteQlOrderSupport.streamOrdersToWriter(out,
                [spec: salesOrderSpec(), fromDate: "2026-08-01", toDate: "2026-09-01", pageSize: 2],
                { String q, int limit, int offset -> offsetsSeen << offset; return pages.remove(0) })

        assertEquals(3, result.recordCount)
        assertEquals(2, result.pageCount)
        assertEquals([0, 2], offsetsSeen, "offset must advance by the page size")
        assertTrue((boolean) result.dataAvailable)
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

    @Test
    void excludedRecordsAreCountedNotSilentlyDropped() {
        // The run has to be able to explain its own numbers: an operator seeing 2 records from a
        // 3-order window needs the third accounted for, not vanished.
        StringWriter out = new StringWriter()
        Map result = NsSuiteQlOrderSupport.streamOrdersToWriter(out,
                [spec       : salesOrderSpec(), fromDate: "2026-08-01", toDate: "2026-09-01", pageSize: 1000,
                 filterRules: [[sequenceNum: 1, fieldExpression: "orderOrigin",
                                operator   : "EXCLUDE_IN", filterValues: "NETSUITE_NATIVE"]]],
                { String q, int limit, int offset ->
                    [items  : [[id: "1", custbody_hc_order_id: "A"], [id: "2"], [id: "3", custbody_hc_order_id: "C"]],
                     hasMore: false]
                })

        assertEquals(2, result.recordCount)
        assertEquals(1, result.excludedCount)
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
    void stopsAtAHardPageCeilingRatherThanLoopingForever() {
        // A source that always answers hasMore=true (a bug, or a window that keeps growing) must
        // terminate with a warning rather than spin until the run times out.
        StringWriter out = new StringWriter()
        Map result = NsSuiteQlOrderSupport.streamOrdersToWriter(out,
                [spec: salesOrderSpec(), fromDate: "2026-08-01", toDate: "2026-09-01", pageSize: 1000, maxPages: 3],
                { String q, int limit, int offset -> [items: [[id: offset.toString()]], hasMore: true] })

        assertEquals(3, result.pageCount)
        assertTrue(((List) result.warnings).any { it.toString().contains("page") },
                "hitting the ceiling must warn: ${result.warnings}")
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
