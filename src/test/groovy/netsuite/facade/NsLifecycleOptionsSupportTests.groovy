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
}
