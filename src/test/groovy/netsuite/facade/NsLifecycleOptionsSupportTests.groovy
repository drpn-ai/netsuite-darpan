package netsuite.facade

import org.junit.jupiter.api.Test

import java.time.LocalDate

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-UI-050. The lifecycle builder's dropdowns are filled from the account: which records exist, what follows
 * a record, how often and how soon. Every SuiteQL string is asserted, and the account's answers are stubbed.
 */
class NsLifecycleOptionsSupportTests {

    static final LocalDate TODAY = LocalDate.parse("2026-10-16")

    @Test
    void recordTypeCountsNameEveryTypeBecauseNetSuiteRefusesAnOpenGroupBy() {
        List<String> asked = []
        Map out = NsLifecycleOptionsSupport.options(null, TODAY) { String q -> asked << q
            [[type: "SalesOrd", n: "24310"], [type: "RtnAuth", n: "1904"], [type: "CashSale", n: "0"]] }

        assertEquals(1, asked.size(), "without a record type only the counts are read")
        assertTrue(asked[0].contains("type IN ('SalesOrd', 'ItemShip', 'CustInvc', 'CashSale', 'RtnAuth', 'ItemRcpt', 'CustCred', 'CustRfnd')"), asked[0])
        assertTrue(asked[0].contains("trandate >= TO_DATE('2026-09-16', 'YYYY-MM-DD') AND trandate < TO_DATE('2026-10-17', 'YYYY-MM-DD')"), asked[0])
        assertEquals(["SalesOrd", "RtnAuth"], out.recordTypes*.recordType, "most first, and never a type with no records")
        assertEquals(["Sales orders", 24310L], [out.recordTypes[0].label, out.recordTypes[0].count])
    }

    @Test
    void stagesComeFromTheLinkTableWithRateAndLag() {
        List<String> asked = []
        Map out = NsLifecycleOptionsSupport.options("SalesOrd", TODAY) { String q ->
            asked << q
            if (q.contains("nexttransactionlink")) return [
                    [childtype: "CustInvc", lag: "0", n: "700"], [childtype: "CustInvc", lag: "1", n: "200"],
                    [childtype: "CustInvc", lag: "3", n: "60"],
                    [childtype: "ItemShip", lag: "1", n: "900"], [childtype: "ItemShip", lag: "2", n: "70"],
                    [childtype: "RtnAuth", lag: "18", n: "30"], [childtype: "RtnAuth", lag: "-1", n: "1"]]
            if (q.startsWith("SELECT COUNT(*)")) return [[n: "1000"]]
            return [[type: "SalesOrd", n: "24310"]]
        }

        String links = asked.find { it.contains("nexttransactionlink") }
        // The sample is a settled week: started 21..14 days ago, so its later stages have had time to happen.
        assertTrue(links.contains("t.type = 'SalesOrd'"), links)
        assertTrue(links.contains("t.trandate >= TO_DATE('2026-09-25', 'YYYY-MM-DD') AND t.trandate < TO_DATE('2026-10-02', 'YYYY-MM-DD')"), links)
        assertTrue(links.contains("c.type IN ('ItemShip', 'CustInvc', 'CashSale', 'RtnAuth', 'ItemRcpt', 'CustCred', 'CustRfnd')"), links)
        assertTrue(links.contains("MIN(c.trandate - t.trandate)"), "the first follower, not every follower: ${links}")
        assertFalse(links.contains("'SalesOrd', 'ItemShip'"), "a record never follows itself")

        assertEquals(1000L, out.sampleSize)
        // Sorted by how soon they usually happen; that is the order the builder offers and saves them in.
        assertEquals(["CustInvc", "ItemShip", "RtnAuth"], out.stages*.recordType)
        Map invoice = out.stages[0]
        assertEquals(["Invoice", "Billed", 960L, 96, 0], [invoice.label, invoice.past, invoice.reached, invoice.rate, invoice.typicalDays])
        // Share of the records that got there at all, done by each day.
        assertEquals(73, invoice.coverage.find { it.days == 0 }.percent)
        assertEquals(94, invoice.coverage.find { it.days == 1 }.percent)
        assertEquals(100, invoice.coverage.find { it.days == 3 }.percent)
        Map rtn = out.stages[2]
        assertEquals(3, rtn.rate)
        assertEquals(3, rtn.coverage.find { it.days == 0 }.percent, "a follower dated before its record counts as same-day (1 of 31)")
    }

    @Test
    void anUnknownRecordTypeIsRefusedBeforeAnySuiteQl() {
        assertThrows(IllegalArgumentException) {
            NsLifecycleOptionsSupport.options("SalesOrd' OR 1=1", TODAY) { String q -> throw new AssertionError("asked ${q}") }
        }
    }

    @Test
    void anEmptySampleOffersNoStages() {
        Map out = NsLifecycleOptionsSupport.options("RtnAuth", TODAY) { String q ->
            q.startsWith("SELECT COUNT(*)") ? [[n: "0"]] : q.contains("nexttransactionlink") ? [] : [[type: "RtnAuth", n: "3"]] }
        assertEquals([], out.stages)
        assertEquals(0L, out.sampleSize)
    }

    // --- rule options: "leave out sales orders where [field] is [value]" ------------------------------------

    @Test
    void ruleFieldsAreTheTextFieldsOnTheRecordTypeByTheirNetSuiteNames() {
        List<String> asked = []
        Map out = NsLifecycleOptionsSupport.ruleOptions("SalesOrd", null, TODAY) { String q, boolean firstPage -> asked << q
            [[scriptid: "CUSTBODY_HC_SALES_CHANNEL", name: "HC Sales Channel", visibleontransactions: "CashSale, SalesOrd, TrnfrOrd"],
             [scriptid: "CUSTBODY_HC_ORDER_ID", name: "HC Order Id", visibleontransactions: "SalesOrd"],
             [scriptid: "CUSTBODY_NM_LOOP_CANCELLED", name: " Loop Cancelled", visibleontransactions: "RtnAuth, SalesOrd"],
             [scriptid: "CUSTBODY_DEPOSIT_ONLY", name: "Deposit Only", visibleontransactions: "Deposit, SalesOrdX"],
             [scriptid: "CUSTBODY_NOWHERE", name: "Nowhere"]] }
        assertEquals(1, asked.size())
        assertTrue(asked[0].contains("FROM customfield WHERE fieldtype = 'BODY' AND fieldvaluetype = 'Free-Form Text'"), asked[0])
        assertFalse(asked[0].contains("LIKE"), "SuiteQL returns nothing for a LIKE on visibleontransactions: ${asked[0]}")
        assertEquals([["status", "Status"], ["custbody_hc_order_id", "HC Order Id"], ["custbody_hc_sales_channel", "HC Sales Channel"],
                      ["custbody_nm_loop_cancelled", "Loop Cancelled"]], out.fields.collect { [it.fieldId, it.label] })
    }

    // A Closed sales order reached its final state: Status leaves it out, offered by name, never as "No value".
    @Test
    void statusIsALeaveOutFieldReadByName() {
        List<String> asked = []
        Map out = NsLifecycleOptionsSupport.ruleOptions("SalesOrd", "status", TODAY) { String q, boolean firstPage -> asked << q
            q.contains("customfield") ? [] : [[v: "Sales Order : Billed", n: "9000"], [v: "Sales Order : Closed", n: "120"]] }
        assertTrue(asked.any { it.contains("SELECT BUILTIN.DF(t.status) AS v") }, asked.toString())
        assertFalse(asked.any { it.contains("IS NULL") }, asked.toString())
        assertEquals([["Sales Order : Billed", "Billed"], ["Sales Order : Closed", "Closed"]], out.values.collect { [it.value, it.label] })
    }

    @Test
    void ruleValuesAreWhatTheFieldHoldsMostCommonFirstWithEmptyAsNoValue() {
        List<String> asked = []
        Map out = NsLifecycleOptionsSupport.ruleOptions("SalesOrd", "custbody_hc_sales_channel", TODAY) { String q, boolean firstPage ->
            asked << q
            if (q.contains("customfield")) return [[scriptid: "CUSTBODY_HC_SALES_CHANNEL", name: "HC Sales Channel", visibleontransactions: "SalesOrd"]]
            if (q.contains("IS NULL")) return [[n: "975"]]
            assertTrue(firstPage, "a field with a value per record has more than a page; the first is enough")
            return [[v: "Shopify Draft Order", n: "750"], [v: null, n: "975"], [v: "POS Channel", n: "89394"]]
        }
        String values = asked[1]
        // The form NetSuite takes: a plain GROUP BY (NVL in it with ORDER BY COUNT(*) answers 500), sorted here.
        assertTrue(values.contains("SELECT t.custbody_hc_sales_channel AS v, COUNT(*) AS n FROM transaction t WHERE t.type = 'SalesOrd'"), values)
        assertTrue(values.endsWith("GROUP BY t.custbody_hc_sales_channel"), values)
        assertFalse(values.contains("NVL") || values.contains("ORDER BY"), values)
        assertTrue(asked[2].endsWith("AND t.custbody_hc_sales_channel IS NULL"), asked[2])
        assertEquals([["POS Channel", "POS Channel"], ["NO_VALUE", "No value"], ["Shopify Draft Order", "Shopify Draft Order"]],
                out.values.collect { [it.value, it.label] })
    }

    @Test
    void aRuleFieldNotOnTheRecordTypeIsRefusedBeforeItReachesTheQuery() {
        List<String> asked = []
        assertThrows(IllegalArgumentException) {
            NsLifecycleOptionsSupport.ruleOptions("SalesOrd", "custbody_x) OR (1=1", TODAY) { String q, boolean firstPage ->
                asked << q; [[scriptid: "CUSTBODY_HC_SALES_CHANNEL", name: "HC Sales Channel", visibleontransactions: "SalesOrd"]] }
        }
        assertTrue(asked.every { it.contains("customfield") }, "only the field list was asked: ${asked}")
    }

    @Test
    void aStageExceptionAlsoOffersShippingMethodByNameAndNeverNoValue() {
        List<String> asked = []
        Map fields = NsLifecycleOptionsSupport.ruleOptions("SalesOrd", null, TODAY, { String q, boolean firstPage -> asked << q
            [[scriptid: "CUSTBODY_HC_SALES_CHANNEL", name: "HC Sales Channel", visibleontransactions: "SalesOrd"]] }, true)
        assertEquals(["Shipping Method", "Item", "HC Sales Channel"], fields.fields*.label)

        asked.clear()
        Map values = NsLifecycleOptionsSupport.ruleOptions("SalesOrd", "shipmethod", TODAY, { String q, boolean firstPage -> asked << q
            q.contains("customfield") ? [] : [[v: "Standard", n: "24657"], [v: "Store Pickup", n: "6823"], [v: null, n: "1"]] }, true)
        assertTrue(asked.any { it.contains("SELECT BUILTIN.DF(t.shipmethod) AS v") && it.endsWith("GROUP BY BUILTIN.DF(t.shipmethod)") }, asked.toString())
        assertFalse(asked.any { it.contains("IS NULL") }, "an exception names values, so empties are not counted")
        assertEquals(["Standard", "Store Pickup"], values.values*.label)
    }

    // An Item exception offers the items that never ship, as they appear on the record type's lines, most first.
    @Test
    void anItemExceptionOffersItemsThatNeverShipBySku() {
        List<String> asked = []
        Map values = NsLifecycleOptionsSupport.ruleOptions("SalesOrd", "item", TODAY, { String q, boolean firstPage -> asked << q
            q.contains("customfield") ? [] : [[v: "POS Tax Variance", d: null, n: "12974"], [v: "GFT-CP", d: "Shopify Gift Card - Purchase", n: "1340"]] }, true)
        String itemQuery = asked.find { it.contains("transactionline") }
        assertTrue(itemQuery.contains("i.itemtype IN ('NonInvtPart', 'Service', 'GiftCert', 'OthCharge')"), itemQuery)
        assertTrue(itemQuery.contains("t.type = 'SalesOrd'"), itemQuery)
        assertEquals(["POS Tax Variance", "GFT-CP"], values.values*.value)
        assertEquals(["POS Tax Variance", "GFT-CP · Shopify Gift Card - Purchase"], values.values*.label)
    }
}
