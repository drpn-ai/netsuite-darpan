package netsuite.reconciliation.orders

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-073. How a stage is reached, and the one-condition id queries a run snapshot reads.
 *
 * <p>A return's credit memo is linked to the return authorization only for POS and some refunds; every one carries the
 * return's custbody_hc_return_id (gorjana, 6,749 of 6,960 returns, zero duplicates). So "reached Credit memo" has to be
 * "a credit memo carrying the same return id", not a row in nexttransactionlink.</p>
 *
 * <p>Pure: no Moqui, no NetSuite account.</p>
 */
class NsStageReachQueryTest {

    private static final String REACH = '[{"recordType":"CustCred","startField":"custbody_hc_return_id","stageField":"custbody_hc_return_id"}]'

    private static Map<String, Object> spec(Map<String, Object> overrides) {
        Map<String, Object> query = [nsSuiteQlSourceQueryId: "Q1", recordType: "RtnAuth",
                                     fromTable: "transaction", dateColumn: "trandate"] + overrides
        return NsSuiteQlOrderSupport.normalizeSpec(query, [[recordFieldName: "internalId", sourceColumn: "id", sequenceNum: 1]])
    }

    private static String query(Map<String, Object> overrides) {
        return NsSuiteQlOrderSupport.buildRecordsQuery(spec(overrides), "2026-09-15", "2026-09-22")
    }

    @Test
    void aStageReachedByAFieldIsMatchedOnThatFieldNotTheLinkTable() {
        String absent = query([queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "CustCred", stageReach: REACH])
        assertTrue(absent.contains("AND NOT EXISTS (SELECT 1 FROM transaction c WHERE c.type = 'CustCred' " +
                "AND c.custbody_hc_return_id = t.custbody_hc_return_id)"), absent)
        assertFalse(absent.contains("nexttransactionlink"), absent)

        String present = query([queryTemplate: "LINKED_CHILD_PRESENT", presentChildRecordType: "CustCred", stageReach: REACH])
        assertTrue(present.contains("AND EXISTS (SELECT 1 FROM transaction c WHERE c.type = 'CustCred' " +
                "AND c.custbody_hc_return_id = t.custbody_hc_return_id)"), present)
    }

    // Only the stage it names changes: a receipt is still found through the link table on the same query.
    @Test
    void aStageWithoutAReachKeepsTheLinkTable() {
        String both = query([queryTemplate: "LINKED_CHILD_PRESENT_ABSENT", presentChildRecordType: "ItemRcpt",
                             absentChildRecordType: "CustCred", stageReach: REACH])
        assertTrue(both.contains("AND EXISTS (SELECT 1 FROM nexttransactionlink l JOIN transaction c ON c.id = l.nextdoc " +
                "WHERE l.previousdoc = t.id AND c.type = 'ItemRcpt')"), both)
        assertTrue(both.contains("AND NOT EXISTS (SELECT 1 FROM transaction c2 WHERE c2.type = 'CustCred' " +
                "AND c2.custbody_hc_return_id = t.custbody_hc_return_id)"), both)
    }

    // Field names reach SuiteQL by interpolation, so they are identifiers or the query is refused.
    @Test
    void aReachIsRefusedUnlessItNamesAStageTheQueryAsksAboutWithPlainFieldIds() {
        assertThrows(IllegalArgumentException) {
            spec([queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "CustCred",
                  stageReach: '[{"recordType":"CustCred","startField":"custbody_x = 1 OR 1","stageField":"custbody_x"}]'])
        }
        assertThrows(IllegalArgumentException) {
            spec([queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "CustCred",
                  stageReach: '[{"recordType":"CustCred","startField":"custbody_hc_return_id"}]'])
        }
        // A reach for a stage this query does not ask about would be silently unused.
        assertThrows(IllegalArgumentException) {
            spec([queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "ItemRcpt", stageReach: REACH])
        }
        assertThrows(IllegalArgumentException) { spec([queryTemplate: "RECORDS", stageReach: REACH]) }
        // Not given, or empty, changes nothing.
        assertEquals([:], spec([queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "CustCred", stageReach: ""]).stageReach)
    }

    // ------------------------------------------------- DAR-UI-052: a stage reached by any of several records

    /**
     * A return is settled by a refund or an invoice carrying its return id, or by the replacement order that names its
     * credit memo (custbody_hc_exchange_credit_memo_id holds the credit's internal id: 4,437 of a week's returns, 0 by
     * tranid). gorjana Sep 15-21: 6,740 of 6,750 credited returns settled.
     */
    static final String SETTLED = '[{"recordType":"Settled","anyOf":[' +
            '{"recordType":"CustRfnd","startField":"custbody_hc_return_id","stageField":"custbody_hc_return_id"},' +
            '{"recordType":"CustInvc","startField":"custbody_hc_return_id","stageField":"custbody_hc_return_id"},' +
            '{"recordType":"SalesOrd","stageField":"custbody_hc_exchange_credit_memo_id","via":' +
            '{"recordType":"CustCred","startField":"custbody_hc_return_id","stageField":"custbody_hc_return_id"}}]}]'

    @Test
    void aStageReachedByAnyOfSeveralRecordsIsOneOrOfTheirPredicates() {
        String absent = query([queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "Settled", stageReach: SETTLED])
        assertTrue(absent.contains("AND NOT (EXISTS (SELECT 1 FROM transaction c_0 WHERE c_0.type = 'CustRfnd' " +
                "AND c_0.custbody_hc_return_id = t.custbody_hc_return_id) " +
                "OR EXISTS (SELECT 1 FROM transaction c_1 WHERE c_1.type = 'CustInvc' AND c_1.custbody_hc_return_id = t.custbody_hc_return_id) " +
                "OR EXISTS (SELECT 1 FROM transaction v_2 JOIN transaction c_2 ON c_2.custbody_hc_exchange_credit_memo_id = TO_CHAR(v_2.id) " +
                "WHERE v_2.type = 'CustCred' AND v_2.custbody_hc_return_id = t.custbody_hc_return_id AND c_2.type = 'SalesOrd'))"), absent)
        assertFalse(absent.contains("'Settled'"), "Settled is no NetSuite type: ${absent}")
        // The snapshot's id query asks the same single predicate.
        String missing = NsSuiteQlOrderSupport.buildConditionIdQuery(spec([queryTemplate: "LINKED_CHILD_ABSENT",
                absentChildRecordType: "Settled", stageReach: SETTLED]), NsSuiteQlOrderSupport.CONDITION_MISSING, "Settled",
                "2026-09-15", "2026-09-22")
        assertTrue(missing.contains("AND NOT (EXISTS (SELECT 1 FROM transaction c_0 WHERE c_0.type = 'CustRfnd'"), missing)
    }

    @Test
    void anAlternativeWithNoFieldsIsALinkedRecord() {
        String present = query([queryTemplate: "LINKED_CHILD_PRESENT", presentChildRecordType: "Settled",
                                stageReach: '[{"recordType":"Settled","anyOf":[{"recordType":"CustRfnd"}]}]'])
        assertTrue(present.contains("AND (EXISTS (SELECT 1 FROM nexttransactionlink l_0 JOIN transaction c_0 ON c_0.id = l_0.nextdoc " +
                "WHERE l_0.previousdoc = t.id AND c_0.type = 'CustRfnd'))"), present)
    }

    // Settled names no NetSuite type: without what settles it, it would be asked as type 'Settled' and match nothing.
    @Test
    void aCompositeStageNeedsItsAlternativesAndEachMustBeARecordTypeWithPlainFields() {
        assertThrows(IllegalArgumentException) { query([queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "Settled"]) }
        assertThrows(IllegalArgumentException) {
            spec([queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "Settled",
                  stageReach: '[{"recordType":"Settled","anyOf":[{"recordType":"Journal"}]}]'])
        }
        assertThrows(IllegalArgumentException) {
            spec([queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "Settled",
                  stageReach: '[{"recordType":"Settled","anyOf":[{"recordType":"SalesOrd","stageField":"x = 1 OR 1",' +
                          '"via":{"recordType":"CustCred","startField":"custbody_hc_return_id","stageField":"custbody_hc_return_id"}}]}]'])
        }
        assertThrows(IllegalArgumentException) {
            spec([queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "Settled",
                  stageReach: '[{"recordType":"Settled","anyOf":[]}]'])
        }
    }

    // ------------------------------------------------- the one-condition id queries a run snapshot reads

    @Test
    void aMissingConditionAsksForTheIdsMissingTheStageOverTheWindow() {
        Map<String, Object> s = spec([queryTemplate: "RECORDS"])
        assertEquals("SELECT t.id AS id FROM transaction t WHERE t.type = 'RtnAuth' " +
                "AND t.trandate >= TO_DATE('2026-09-15', 'YYYY-MM-DD') AND t.trandate < TO_DATE('2026-09-22', 'YYYY-MM-DD') " +
                "AND NOT EXISTS (SELECT 1 FROM nexttransactionlink l JOIN transaction c ON c.id = l.nextdoc " +
                "WHERE l.previousdoc = t.id AND c.type = 'ItemRcpt') ORDER BY t.id",
                NsSuiteQlOrderSupport.buildConditionIdQuery(s, NsSuiteQlOrderSupport.CONDITION_MISSING, "ItemRcpt", "2026-09-15", "2026-09-22"))
    }

    @Test
    void aMissingConditionFollowsTheStagesReach() {
        Map<String, Object> s = spec([queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "CustCred", stageReach: REACH])
        String q = NsSuiteQlOrderSupport.buildConditionIdQuery(s, NsSuiteQlOrderSupport.CONDITION_MISSING, "CustCred", "2026-09-15", "2026-09-22")
        assertTrue(q.endsWith("AND NOT EXISTS (SELECT 1 FROM transaction c WHERE c.type = 'CustCred' " +
                "AND c.custbody_hc_return_id = t.custbody_hc_return_id) ORDER BY t.id"), q)
    }

    @Test
    void waivedAndMatchingConditionsAskTheirOwnPredicateAlone() {
        Map<String, Object> waived = NsSuiteQlOrderSupport.normalizeSpec([nsSuiteQlSourceQueryId: "Q1", recordType: "SalesOrd",
                queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "ItemShip",
                waiveRules: [[fieldId: "shipmethod", values: ["Store Pickup"]], [fieldId: "custbody_hc_sales_channel", values: ["POS Channel"]]]],
                [[recordFieldName: "internalId", sourceColumn: "id", sequenceNum: 1]])
        String w = NsSuiteQlOrderSupport.buildConditionIdQuery(waived, NsSuiteQlOrderSupport.CONDITION_WAIVED, null, "2026-10-12", "2026-10-15")
        // Parenthesised: the rules are OR-ed and must not bind to the window's ANDs.
        assertTrue(w.contains("AND t.trandate < TO_DATE('2026-10-15', 'YYYY-MM-DD') " +
                "AND (BUILTIN.DF(t.shipmethod) IN ('Store Pickup') OR t.custbody_hc_sales_channel IN ('POS Channel')) ORDER BY t.id"), w)

        Map<String, Object> matching = NsSuiteQlOrderSupport.normalizeSpec([nsSuiteQlSourceQueryId: "Q1", recordType: "SalesOrd",
                queryTemplate: "RECORDS_MATCHING", matchRules: [[fieldId: "itemsbackordered", op: "GT", values: ["0"]]]],
                [[recordFieldName: "internalId", sourceColumn: "id", sequenceNum: 1]])
        String m = NsSuiteQlOrderSupport.buildConditionIdQuery(matching, NsSuiteQlOrderSupport.CONDITION_MATCHING, null, "2026-10-12", "2026-10-15")
        assertTrue(m.endsWith("AND ((SELECT SUM(mb.quantitybackordered) FROM transactionline mb WHERE mb.transaction = t.id " +
                "AND mb.mainline = 'F') > 0) ORDER BY t.id"), m)

        assertThrows(IllegalArgumentException) {
            NsSuiteQlOrderSupport.buildConditionIdQuery(spec([queryTemplate: "RECORDS"]), NsSuiteQlOrderSupport.CONDITION_WAIVED, null, "2026-10-12", "2026-10-15")
        }
        assertThrows(IllegalArgumentException) {
            NsSuiteQlOrderSupport.buildConditionIdQuery(spec([queryTemplate: "RECORDS"]), NsSuiteQlOrderSupport.CONDITION_MISSING, "Item' OR '1'='1", "2026-10-12", "2026-10-15")
        }
    }
}
