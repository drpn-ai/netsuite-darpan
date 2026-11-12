package netsuite.reconciliation.orders

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-245. RAILS orders read live 2026-11-12 (NetSuite SuiteQL + HotWax GraphQL), Oct 15-Nov 10: 15,677 sales orders,
 * 27 with more completed ship groups than fulfillments; 23 of them one fulfillment split into several ship groups at
 * the same facility (20 at CAN WH, 100102 = location 77).
 */
class NsShipGroupCountSupportTest {

    @Test
    void oneFulfillmentThatBecameTwoShipGroupsAtTheSameLocationIsASplit() {
        // #8777CAN (150738): IF387146 from location 77; items 01 in group 00004, 02-05 in 00003, both at 100102.
        Map out = NsShipGroupCountSupport.check(["77^IF387146"], ["00003", "00004"] as Set,
                ["00003": ["100102", "77"], "00004": ["100102", "77"]], false)
        assertEquals("SPLIT", out.omsShipGroupCheck)
        assertEquals(["1", "2"], [out.nsFulfillmentCount, out.omsShipGroupCount])
        assertEquals("location 77: NetSuite IF387146; HotWax ship groups 00003, 00004 at 100102", out.omsShipGroupDetail)
    }

    @Test
    void aSplitIsStillASplitBesideAStoreFulfillmentThatMatches() {
        // #1026228 (162646): IF397525 from Atlanta (50 = 100001, group 00001) matches; IF402747 from US WH (4 = 100000)
        // became groups 00004 and 00005.
        Map out = NsShipGroupCountSupport.check(["4^IF402747", "50^IF397525"], ["00001", "00004", "00005"] as Set,
                ["00001": ["100001", "50"], "00004": ["100000", "4"], "00005": ["100000", "4"]], false)
        assertEquals("SPLIT", out.omsShipGroupCheck)
        assertEquals("location 4: NetSuite IF402747; HotWax ship groups 00004, 00005 at 100000", out.omsShipGroupDetail)
    }

    @Test
    void aShipGroupFromALocationNetSuiteHasNoFulfillmentFromIsHotWaxOnly() {
        // #1018928 (153429): three fulfillments (US WH 4, Scottsdale 51, Nashville 69); HotWax also shipped item 08 from
        // Greene St (100013 = 41), which NetSuite has nothing from.
        Map out = NsShipGroupCountSupport.check(["4^IF386893", "51^IF387306", "69^IF387247"], ["00002", "00003", "00004", "00005"] as Set,
                ["00002": ["100000", "4"], "00003": ["100011", "69"], "00004": ["100015", "51"], "00005": ["100013", "41"]], false)
        assertEquals("HOTWAX_ONLY", out.omsShipGroupCheck)
        assertEquals("location 41: NetSuite no fulfillment; HotWax ship groups 00005 at 100013", out.omsShipGroupDetail)
    }

    @Test
    void aShipGroupNothingShipsFromIsNotCounted() {
        // #8673CAN (147241): item 03 completed in group 00002 at _NA_ (no NetSuite location); 00003 matches IF397575.
        Map out = NsShipGroupCountSupport.check(["4^IF397575"], ["00002", "00003"] as Set,
                ["00002": ["_NA_", null], "00003": ["100102", "4"]], false)
        assertEquals("MATCHES", out.omsShipGroupCheck)
        assertNull(out.omsShipGroupDetail)
    }

    @Test
    void aSplitIsFoundWhenAnotherFulfillmentNeverReachedHotWaxAndTheCountsAgree() {
        // #9040CAN (160028): IF396750 (CAN WH, 77) shipped items 02-04, which HotWax holds in groups 00003 and 00004;
        // IF397723 (US WH, 4) shipped item 01, still ITEM_APPROVED in HotWax. Two fulfillments, two completed groups.
        assertTrue(NsShipGroupCountSupport.needsFacilities(["77^IF396750", "4^IF397723"], ["00003", "00004"] as Set))
        Map out = NsShipGroupCountSupport.check(["77^IF396750", "4^IF397723"], ["00003", "00004"] as Set,
                ["00003": ["100102", "77"], "00004": ["100102", "77"]], false)
        assertEquals("SPLIT", out.omsShipGroupCheck)
        assertEquals("location 77: NetSuite IF396750; HotWax ship groups 00003, 00004 at 100102", out.omsShipGroupDetail)
    }

    @Test
    void fewerShipGroupsThanFulfillmentsIsNormalAndNeedsNoFacilityRead() {
        // #1017116 (150995): two partial shipments from US WH, both into group 00002 (204 such orders, all healthy).
        assertFalse(NsShipGroupCountSupport.needsFacilities(["4^IF1", "4^IF2"], ["00002"] as Set))
        assertEquals("MATCHES", NsShipGroupCountSupport.check(["4^IF1", "4^IF2"], ["00002"] as Set, [:], false).omsShipGroupCheck)
        assertTrue(NsShipGroupCountSupport.needsFacilities(["77^IF387146"], ["00003", "00004"] as Set))
        assertEquals("NOT_IN_OMS", NsShipGroupCountSupport.check(["4^IF1"], [] as Set, [:], true).omsShipGroupCheck)
        // A POS take-home HotWax completed before its NetSuite fulfillment arrived: nothing of NetSuite's to count yet.
        assertFalse(NsShipGroupCountSupport.needsFacilities([], ["00001"] as Set))
        assertEquals("NO_FULFILLMENT", NsShipGroupCountSupport.check([], ["00001"] as Set, [:], false).omsShipGroupCheck)
    }

    @Test
    void onlyCompletedItemsMakeAShipGroupCount() {
        // An open item waiting in its own group is in flight, not a split (101 orders by all items, 27 by completed).
        Map status = ["9|01": "ITEM_COMPLETED", "9|02": "ITEM_APPROVED", "9|03": "ITEM_CANCELLED", "10|01": "ITEM_COMPLETED"]
        Map group = ["9|01": "00002", "9|02": "00003", "9|03": "00004", "10|01": "00009"]
        assertEquals(["00002"] as Set, NsShipGroupCountSupport.completedShipGroups("9", status, group))
    }
}
