package netsuite.reconciliation.orders

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-053: vetted query templates, so a within-NetSuite chain check is expressible at all.
 *
 * <p><b>Why templates rather than a query column.</b> Every one of DAR-BE-045's six pairs is an
 * anti-join across {@code nexttransactionlink}, and the triage gate that takes 97 raw orphans down to 5
 * real findings is a second join to {@code transactionline}. The assembled-parts model had no slot for
 * either. But DAR-BE-044 refused raw query text for two live reasons that still hold: the window bounds
 * are interpolated as TEXT rather than bound as parameters, and {@code ORDER BY id} is what makes offset
 * paging safe. So the SHAPES are owned by code and configuration only picks one and names its record
 * types — every fragment stays identifier-checked and the ordering stays out of operator hands.</p>
 *
 * <p>Pure: no Moqui, no NetSuite account.</p>
 */
class NsSuiteQlQueryTemplateTest {

    private static Map<String, Object> spec(Map<String, Object> overrides) {
        Map<String, Object> query = [nsSuiteQlSourceQueryId: "Q1", recordType: "SalesOrd",
                                     fromTable: "transaction", dateColumn: "trandate"] + overrides
        return NsSuiteQlOrderSupport.normalizeSpec(query,
                [[recordFieldName: "tranId", sourceColumn: "tranid", sequenceNum: 1]])
    }

    private static String query(Map<String, Object> overrides) {
        return NsSuiteQlOrderSupport.buildRecordsQuery(spec(overrides), "2026-08-01", "2026-09-01")
    }

    // ---------------------------------------------------------------- the default must not move

    @Test
    void absentTemplateIsRecordsAndTheQueryIsUnchanged() {
        // Byte-identical to what shipped for DAR-BE-032/044. Every existing NsSuiteQlSourceQuery row
        // stores no template, so a default that altered the query would silently change the OMS
        // order-presence extract that is already proven against gorjana production.
        assertEquals("SELECT tranid FROM transaction WHERE type = 'SalesOrd' " +
                "AND trandate >= TO_DATE('2026-08-01', 'YYYY-MM-DD') " +
                "AND trandate < TO_DATE('2026-09-01', 'YYYY-MM-DD') ORDER BY id", query([:]))
        assertEquals(NsSuiteQlOrderSupport.TEMPLATE_RECORDS, spec([:]).get("queryTemplate"))
    }

    @Test
    void anUnknownTemplateThrowsRatherThanFallingBackToRecords() {
        def e = assertThrows(IllegalArgumentException) { spec([queryTemplate: "LINKED_CHILD_MISSING"]) }
        assertTrue(e.message.contains("LINKED_CHILD_MISSING"), e.message)
        assertTrue(e.message.contains(NsSuiteQlOrderSupport.TEMPLATE_RECORDS), e.message)
    }

    // ---------------------------------------------------------------- the anti-join

    @Test
    void linkedChildAbsentEmitsTheAntiJoinOverTheLinkTable() {
        String q = query([queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "ItemShip"])
        assertTrue(q.contains("NOT EXISTS (SELECT 1 FROM nexttransactionlink l " +
                "JOIN transaction c ON c.id = l.nextdoc WHERE l.previousdoc = t.id AND c.type = 'ItemShip')"), q)
        assertTrue(q.contains("FROM transaction t WHERE t.type = 'SalesOrd'"), q)
    }

    @Test
    void theAntiJoinIsNotWindowedOnTheChild() {
        // The child transaction is dated LATER than its parent (measured: fulfillment p99 4 days,
        // invoice p99 36). Bounding the child by the same window would report every order near the end
        // of it as unfulfilled, which is the false positive the whole feature exists to avoid.
        String q = query([queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "ItemShip"])
        int subqueryStart = q.indexOf("NOT EXISTS")
        assertFalse(q.substring(subqueryStart).contains("TO_DATE"), q.substring(subqueryStart))
    }

    @Test
    void theAntiJoinDoesNotFilterOnLinktype() {
        // Deliberate: a stage is reachable by SEVERAL linktypes — gorjana's fulfillments arrive as both
        // ShipRcpt and KitShip — so pinning one would silently drop 48 kit shipments a day. The question
        // is "is there ANY link to a child of this type".
        String q = query([queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "ItemShip"])
        assertFalse(q.contains("linktype"), q)
    }

    @Test
    void orderByStaysOnTheParentIdSoOffsetPagingIsStillSafe() {
        String q = query([queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "ItemShip"])
        assertTrue(q.endsWith("ORDER BY t.id"), q)
    }

    @Test
    void linkedChildAbsentRequiresItsChildType() {
        def e = assertThrows(IllegalArgumentException) { spec([queryTemplate: "LINKED_CHILD_ABSENT"]) }
        assertTrue(e.message.contains("absentChildRecordType"), e.message)
    }

    // ---------------------------------------------------------------- present AND absent

    @Test
    void presentAbsentEmitsBothHalves() {
        // "Shipped but never billed" — the revenue leak. Needs both, or it reports every unshipped order.
        String q = query([queryTemplate: "LINKED_CHILD_PRESENT_ABSENT",
                          presentChildRecordType: "ItemShip", absentChildRecordType: "CustInvc"])
        assertTrue(q.contains("EXISTS (SELECT 1 FROM nexttransactionlink l " +
                "JOIN transaction c ON c.id = l.nextdoc WHERE l.previousdoc = t.id AND c.type = 'ItemShip')"), q)
        assertTrue(q.contains("NOT EXISTS (SELECT 1 FROM nexttransactionlink l2 " +
                "JOIN transaction c2 ON c2.id = l2.nextdoc WHERE l2.previousdoc = t.id AND c2.type = 'CustInvc')"), q)
    }

    @Test
    void presentAbsentRequiresBothChildTypes() {
        assertTrue(assertThrows(IllegalArgumentException) {
            spec([queryTemplate: "LINKED_CHILD_PRESENT_ABSENT", absentChildRecordType: "CustInvc"])
        }.message.contains("presentChildRecordType"))
        assertTrue(assertThrows(IllegalArgumentException) {
            spec([queryTemplate: "LINKED_CHILD_PRESENT_ABSENT", presentChildRecordType: "ItemShip"])
        }.message.contains("absentChildRecordType"))
    }

    @Test
    void childTypesAreRejectedOnTemplatesThatCannotUseThem() {
        // Silently ignoring them is how an operator ends up believing a filter is applied that is not.
        assertTrue(assertThrows(IllegalArgumentException) {
            spec([absentChildRecordType: "ItemShip"])
        }.message.contains(NsSuiteQlOrderSupport.TEMPLATE_RECORDS))
        assertTrue(assertThrows(IllegalArgumentException) {
            spec([queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "ItemShip",
                  presentChildRecordType: "CustInvc"])
        }.message.contains("presentChildRecordType"))
    }

    // ---------------------------------------------------------------- the triage gate

    @Test
    void requireFulfillableOpenLineAddsTheLineLevelGate() {
        // THE DIFFERENCE BETWEEN 97 FINDINGS AND 5. Without it the report is dominated by orders that
        // can never have a fulfillment (service/non-inventory) and by deliberately closed lines.
        String q = query([queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "ItemShip",
                          requireFulfillableOpenLine: "Y"])
        assertTrue(q.contains("EXISTS (SELECT 1 FROM transactionline tl WHERE tl.transaction = t.id " +
                "AND tl.fulfillable = 'T' AND tl.isclosed = 'F')"), q)
    }

    @Test
    void theTriageGateIsOffUnlessAskedFor() {
        assertFalse(query([queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "ItemShip"])
                .contains("transactionline"))
        assertFalse(query([:]).contains("transactionline"))
    }

    // ---------------------------------------------------------------- the fences that must survive

    @Test
    void childTypesMustBePlainIdentifiers() {
        // Anything that could LEAVE the quotes it is interpolated into.
        ["ItemShip' OR '1'='1", "ItemShip; DROP TABLE x", "ItemShip--", "Item Ship", "ItemShip'",
         "/*x*/ItemShip"].each { String hostile ->
            assertThrows(IllegalArgumentException, {
                spec([queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: hostile])
            }, "must reject: ${hostile}")
        }
    }

    @Test
    void aKeywordShapedIdentifierIsAccepted_becauseItCannotLeaveTheQuotes() {
        // Documented rather than blacklisted. Once the value is required to be a bare identifier it can
        // contain no quote, space or semicolon, so `c.type = 'SELECT'` is an inert literal that matches
        // no record type. A keyword check here would imply the keyword is what makes a value dangerous,
        // which is exactly the misreading that let `ItemShip' OR '1'='1` through the old fragment check.
        String q = query([queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "SELECT"])
        assertTrue(q.contains("c.type = 'SELECT'"), q)
        assertFalse(q.contains("' OR "), q)
    }

    @Test
    void recordTypeItselfRejectsTheSameInjection() {
        // PRE-EXISTING HOLE, found by the child-type test above and fixed in the same change.
        // requireSafeFragment allows BALANCED quotes and does not forbid OR, so this passed every check
        // and widened `type = '<recordType>'` to every transaction type in the account.
        assertThrows(IllegalArgumentException, { spec([recordType: "SalesOrd' OR '1'='1"]) })
        assertThrows(IllegalArgumentException, { spec([recordType: "SalesOrd' OR type = 'ItemShip"]) })
        // and still accepts every real NetSuite type code
        ["SalesOrd", "ItemShip", "CustInvc", "RtnAuth", "CustCred", "CustRfnd", "CashSale", "ItemRcpt"]
                .each { String t -> assertTrue(query([recordType: t]).contains("type = '${t}'")) }
    }

    @Test
    void linkTemplatesRequireTheTransactionTable() {
        // nexttransactionlink relates TRANSACTIONS; pointing a link template at another table would
        // assemble a query that is syntactically fine and semantically meaningless.
        def e = assertThrows(IllegalArgumentException) {
            spec([queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "ItemShip", fromTable: "item"])
        }
        assertTrue(e.message.contains("transaction"), e.message)
    }

    @Test
    void theWindowIsStillHalfOpenAndStillFormatChecked() {
        String q = query([queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "ItemShip"])
        assertTrue(q.contains("t.trandate >= TO_DATE('2026-08-01', 'YYYY-MM-DD')"), q)
        assertTrue(q.contains("t.trandate < TO_DATE('2026-09-01', 'YYYY-MM-DD')"), q)
        assertThrows(IllegalArgumentException) {
            NsSuiteQlOrderSupport.buildRecordsQuery(
                    spec([queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "ItemShip"]),
                    "2026-8-1", "2026-09-01")
        }
    }
}
