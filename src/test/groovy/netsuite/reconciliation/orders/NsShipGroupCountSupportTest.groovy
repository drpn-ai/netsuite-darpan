package netsuite.reconciliation.orders

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull

/**
 * DAR-BE-245, DAR-BE-329. Each NetSuite fulfillment against HotWax's shipped shipments per location. Orders read live
 * 2026-11-20 (darpan CLI, NetSuite SuiteQL + HotWax GraphQL): Malbon's 33 open R10 findings and RAILS's 25.
 */
class NsShipGroupCountSupportTest {

    private static Map shipment(String id, String facility, String location, String status = "SHIPMENT_SHIPPED") {
        return [shipmentId: id, facilityId: facility, location: location, statusId: status]
    }

    @Test
    void oneFulfillmentAndOneShipmentMatchesWhateverTheShipGroupsSay() {
        // Malbon 119759 (#932561): IF1501081 from 221; items 01-02 in group 00001, x-redo (03, a digital good) in 00002,
        // one shipment 113735 from 100009. 23 of Malbon's 33 ship-group "splits" are this.
        Map out = NsShipGroupCountSupport.check(["221^IF1501081"], [shipment("113735", "100009", "221")], false)
        assertEquals("MATCHES", out.omsShipGroupCheck)
        assertEquals(["1", "1"], [out.nsFulfillmentCount, out.omsShipGroupCount])
        assertNull(out.omsShipGroupDetail)
    }

    @Test
    void oneFulfillmentThatBecameTwoShipmentsAtTheSameLocationIsASplit() {
        // Malbon 121378: IF1502137 from 221; HotWax shipped 114524 and 114703, both from 100009.
        Map out = NsShipGroupCountSupport.check(["221^IF1502137"], [shipment("114703", "100009", "221"), shipment("114524", "100009", "221")], false)
        assertEquals("SPLIT", out.omsShipGroupCheck)
        assertEquals(["1", "2"], [out.nsFulfillmentCount, out.omsShipGroupCount])
        assertEquals("location 221: NetSuite IF1502137; HotWax shipments 114524, 114703 from 100009", out.omsShipGroupDetail)
    }

    @Test
    void aSplitIsStillASplitBesideAFulfillmentElsewhereThatMatches() {
        // RAILS #9040CAN (160028): IF396750 from CAN WH (77) became shipments 140560 and 140562 at 100102; IF397723 from
        // US WH (4) is shipment 146054 at 100000.
        Map out = NsShipGroupCountSupport.check(["77^IF396750", "4^IF397723"],
                [shipment("140560", "100102", "77"), shipment("140562", "100102", "77"), shipment("146054", "100000", "4")], false)
        assertEquals("SPLIT", out.omsShipGroupCheck)
        assertEquals("location 77: NetSuite IF396750; HotWax shipments 140560, 140562 from 100102", out.omsShipGroupDetail)
    }

    @Test
    void aShipmentFromALocationNetSuiteHasNoFulfillmentFromIsHotWaxOnly() {
        // RAILS #1018928 (153429): fulfillments from US WH 4, Scottsdale 51, Nashville 69; HotWax also shipped from Greene
        // St (100013 = 41), which NetSuite has nothing from.
        Map out = NsShipGroupCountSupport.check(["4^IF386893", "51^IF387306", "69^IF387247"],
                [shipment("133475", "100000", "4"), shipment("132596", "100015", "51"), shipment("132543", "100011", "69"),
                 shipment("134343", "100013", "41")], false)
        assertEquals("HOTWAX_ONLY", out.omsShipGroupCheck)
        assertEquals("location 41: NetSuite no fulfillment; HotWax shipments 134343 from 100013", out.omsShipGroupDetail)
    }

    @Test
    void onlyShippedShipmentsFromAKnownLocationCount() {
        // A shipment still packing or cancelled has not left; one from a facility with no NetSuite location is not counted.
        Map out = NsShipGroupCountSupport.check(["221^IF1"], [shipment("1", "100009", "221"), shipment("2", "100009", "221", "SHIPMENT_PACKED"),
                shipment("3", "100009", "221", "SHIPMENT_CANCELLED"), shipment("4", "_NA_", null)], false)
        assertEquals("MATCHES", out.omsShipGroupCheck)
        assertEquals("2", out.omsShipGroupCount, "the shipped ones: 1 from 221 and 4 from no location")
        assertEquals("SPLIT", NsShipGroupCountSupport.check(["221^IF1"],
                [shipment("1", "100009", "221"), shipment("2", "100009", "221", "SHIPMENT_DELIVERED")], false).omsShipGroupCheck,
                "a delivered shipment has left too")
    }

    @Test
    void fewerShipmentsThanFulfillmentsIsNormal() {
        // Two partial NetSuite shipments HotWax recorded as one; a store take-home HotWax completes at the till with no
        // shipment; an order HotWax does not have; an order NetSuite has shipped nothing of.
        assertEquals("MATCHES", NsShipGroupCountSupport.check(["4^IF1", "4^IF2"], [shipment("1", "100000", "4")], false).omsShipGroupCheck)
        assertEquals("MATCHES", NsShipGroupCountSupport.check(["41^IF1"], [], false).omsShipGroupCheck)
        assertEquals("NOT_IN_OMS", NsShipGroupCountSupport.check(["4^IF1"], null, true).omsShipGroupCheck)
        assertEquals("NO_FULFILLMENT", NsShipGroupCountSupport.check([], [shipment("1", "100000", "4")], false).omsShipGroupCheck)
    }

    // DAR-BE-294. Gorjana R10 judged a line by ANY of its order's ship groups: a line at the warehouse read as parked when
    // another group of the order sat in parking. The line's own group says where HotWax holds it and how it ships.
    @Test
    void anOpenLineIsJudgedByItsOwnShipGroupNotTheOrders() {
        Map group = ["M1|01": "00006", "M1|02": "00002"]
        Map facility = ["M1|00006": ["UNF_HOLD_PARKING", null, "STANDARD"], "M1|00002": ["WH", "31", "STANDARD"],
                        "M2|00001": ["SM_STORE", "12", "STOREPICKUP"]]
        assertEquals([omsItemShipGroupSeqId: "00002", omsItemFacilityId: "WH", omsItemShipmentMethodTypeId: "STANDARD"],
                NsShipGroupCountSupport.lineShipGroup("M1", "02", group, facility))
        assertEquals("UNF_HOLD_PARKING", NsShipGroupCountSupport.lineShipGroup("M1", "01", group, facility).omsItemFacilityId)
        assertEquals([omsItemShipGroupSeqId: "00001", omsItemFacilityId: "SM_STORE", omsItemShipmentMethodTypeId: "STOREPICKUP"],
                NsShipGroupCountSupport.lineShipGroup("M2", "01", ["M2|01": "00001"], facility))
        assertEquals([omsItemShipGroupSeqId: null, omsItemFacilityId: null, omsItemShipmentMethodTypeId: null],
                NsShipGroupCountSupport.lineShipGroup("M3", "01", group, facility), "an item with no ship group read stays blank, never guessed")
        assertEquals([omsItemShipGroupSeqId: "00007", omsItemFacilityId: null, omsItemShipmentMethodTypeId: null],
                NsShipGroupCountSupport.lineShipGroup("M1", "03", ["M1|03": "00007"], facility), "a group HotWax did not return stays blank")
    }
}
