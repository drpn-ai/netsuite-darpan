package netsuite.reconciliation.orders

/**
 * DAR-BE-245, DAR-BE-329. Does each NetSuite item fulfillment of a sales order become one HotWax shipment?
 *
 * Aditi 2026-11-12 (RAILS): "one IF is created in NS for 4 items, but in HW 4 ship groups were created". Aditi 2026-11-20
 * (Malbon): "we should do a IF to shipment check rather than IF to shipgroup check": a ship group is HotWax's plan, a
 * shipment is what left. Malbon keeps x-redo (a digital good) in a ship group of its own that ships nothing, so 23 of
 * its 33 "splits" were one fulfillment and one shipment; RAILS's Canada import really does make two shipments of one
 * fulfillment (18 of 25). MORE shipped HotWax shipments than NetSuite fulfillments is the error; fewer is normal (a store
 * take-home HotWax completes at the till has no shipment). Counted per location (the origin facility's externalId is
 * the NetSuite location id), because the order-level count also exceeds when HotWax shipped from a store NetSuite has
 * no fulfillment from (#1018928). A shipment from a facility with no NetSuite location is not counted.
 *
 * The record fields keep their names (omsShipGroupCheck, omsShipGroupCount, omsShipGroupDetail): the audits' causes
 * read them, and a rename would leave every record unmatched between a deploy and the config that follows it.
 */
class NsShipGroupCountSupport {

    static final String MATCHES = "MATCHES"
    static final String SPLIT = "SPLIT"
    static final String HOTWAX_ONLY = "HOTWAX_ONLY"
    static final String NOT_IN_OMS = "NOT_IN_OMS"
    /** NetSuite has shipped nothing on the order: nothing to count here (whether it should have is R1's question). */
    static final String NO_FULFILLMENT = "NO_FULFILLMENT"

    /**
     * DAR-BE-294. An open line's own HotWax ship group (itemShipGroupByKey "orderId|seq" -> group) and, from
     * shipGroupFacilityByKey "orderId|group" -> [facilityId, externalId, shipmentMethodTypeId], where HotWax holds it and
     * how it ships. Blank when HotWax did not say; never another group's.
     */
    static Map<String, String> lineShipGroup(String orderId, String seq, Map itemShipGroupByKey, Map shipGroupFacilityByKey) {
        String group = itemShipGroupByKey?.get("${orderId}|${seq}".toString())?.toString()
        List facility = group ? (List) shipGroupFacilityByKey?.get("${orderId}|${group}".toString()) : null
        return [omsItemShipGroupSeqId      : group,
                omsItemFacilityId          : facility ? facility[0]?.toString() : null,
                omsItemShipmentMethodTypeId: facility && facility.size() > 2 ? facility[2]?.toString() : null]
    }

    /** A shipment HotWax has sent: the ones counted against NetSuite's fulfillments. */
    static final Set<String> SHIPPED = ["SHIPMENT_SHIPPED", "SHIPMENT_DELIVERED"] as Set

    /**
     * fulfillments: ["location^tranId", ...] (LINKED_LOCATIONS). shipments: the order's HotWax sales shipments, each
     * [shipmentId, facilityId, location, statusId] (lookup#HotWaxOmsOrderStatuses withShipments). Locations are compared
     * only when the order has two shipped shipments or more, or more than fulfillments, since a split needs two at one
     * location (RAILS #9040CAN, 160028: IF396750 from CAN WH became two shipments there beside IF397723's one at US WH).
     * Returns the fields the record gets.
     */
    static Map<String, String> check(List fulfillments, List shipments, boolean notInOms) {
        List<String> ifs = (fulfillments ?: []).collect { it.toString() }
        List<Map> shipped = ((shipments ?: []) as List<Map>).findAll { SHIPPED.contains(it?.statusId?.toString()) }
        Map<String, String> out = [nsFulfillmentCount: ifs.size().toString(), omsShipGroupCount: shipped.size().toString(),
                                   omsShipGroupCheck: MATCHES, omsShipGroupDetail: null]
        if (notInOms) {
            out.omsShipGroupCheck = NOT_IN_OMS
            return out
        }
        if (!ifs) {
            out.omsShipGroupCheck = NO_FULFILLMENT
            return out
        }
        if (shipped.size() < 2 && shipped.size() <= ifs.size()) return out

        Map<String, List<String>> ifsByLocation = [:].withDefault { [] }
        ifs.each { String entry ->
            int at = entry.indexOf('^')
            ifsByLocation[at < 0 ? entry : entry.substring(0, at)] << (at < 0 ? entry : entry.substring(at + 1))
        }
        Map<String, List<String>> shipmentsByLocation = [:].withDefault { [] }
        Map<String, String> facilityByLocation = [:]
        shipped.each { Map s ->
            String location = s.location?.toString()
            if (!location) return
            shipmentsByLocation[location] << s.shipmentId?.toString()
            facilityByLocation[location] = s.facilityId?.toString()
        }
        List<String> split = shipmentsByLocation.keySet().findAll { String loc ->
            int n = ifsByLocation.containsKey(loc) ? ifsByLocation[loc].size() : 0
            n >= 1 && shipmentsByLocation[loc].size() > n
        }.sort()
        List<String> hotwaxOnly = shipmentsByLocation.keySet().findAll { !ifsByLocation.containsKey(it) }.sort()
        if (!split && !hotwaxOnly) return out

        out.omsShipGroupCheck = split ? SPLIT : HOTWAX_ONLY
        out.omsShipGroupDetail = (split + hotwaxOnly).collect { String loc ->
            List<String> numbers = ifsByLocation.containsKey(loc) ? ifsByLocation[loc] : []
            "location ${loc}: NetSuite ${numbers ? numbers.join(', ') : 'no fulfillment'}; HotWax shipments " +
                    "${shipmentsByLocation[loc].sort().join(', ')} from ${facilityByLocation[loc]}"
        }.join("; ")
        return out
    }
}
