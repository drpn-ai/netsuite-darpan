package netsuite.reconciliation.orders

/**
 * DAR-BE-245. Does each NetSuite item fulfillment of a sales order become one HotWax ship group?
 *
 * Aditi 2026-11-12: "one IF is created in NS for 4 items, but in HW 4 ship groups were created". MORE completed HotWax
 * ship groups than NetSuite fulfillments is the error; fewer is normal, as one ship group takes several partial
 * fulfillments (RAILS Oct 15-Nov 10: 204 of 15,677 orders, all healthy). Counted per location, because the order-level
 * count also exceeds when HotWax shipped from a store NetSuite has no fulfillment from (#1018928), or when a completed
 * item sits in a ship group nothing ships from (_NA_, #8673CAN).
 *
 * The order-level count needs only what every order already gets (its items, each with its ship group); where a ship
 * group ships from is asked only of the orders with two completed ship groups or more (694 of 15,677).
 */
class NsShipGroupCountSupport {

    static final String MATCHES = "MATCHES"
    static final String SPLIT = "SPLIT"
    static final String HOTWAX_ONLY = "HOTWAX_ONLY"
    static final String NOT_IN_OMS = "NOT_IN_OMS"
    /** NetSuite has shipped nothing on the order: nothing to count here (whether it should have is R1's question). */
    static final String NO_FULFILLMENT = "NO_FULFILLMENT"
    static final String COMPLETED = "ITEM_COMPLETED"

    /** The ship groups holding at least one completed item, from "orderId|seq" -> status and -> ship group. */
    static Set<String> completedShipGroups(String orderId, Map itemStatusByKey, Map itemShipGroupByKey) {
        String prefix = orderId + "|"
        Set<String> groups = new TreeSet<String>()
        (itemStatusByKey ?: [:]).each { Object key, Object status ->
            if (key.toString().startsWith(prefix) && status == COMPLETED) {
                Object group = itemShipGroupByKey?.get(key)
                if (group) groups.add(group.toString())
            }
        }
        return groups
    }

    /**
     * Whether where the ship groups ship from has to be asked: whenever the order has two completed ship groups or more,
     * since a split needs two at one location. Not only when the groups outnumber the fulfillments: #9040CAN (160028)
     * reads 2 and 2, yet IF396750's three items became two groups at CAN WH while IF397723 (from US WH) never reached
     * HotWax. RAILS Oct 15-Nov 10: 694 of 15,677 orders asked.
     */
    static boolean needsFacilities(List fulfillments, Set<String> completedGroups) {
        int n = fulfillments?.size() ?: 0
        int groups = completedGroups?.size() ?: 0
        return n > 0 && (groups >= 2 || groups > n)
    }

    /**
     * fulfillments: ["location^tranId", ...] (LINKED_LOCATIONS). facilityByGroup: shipGroupSeqId -> [facilityId,
     * externalId], asked only when needsFacilities. Returns the fields the record gets.
     */
    static Map<String, String> check(List fulfillments, Set<String> completedGroups, Map<String, List> facilityByGroup,
                                     boolean notInOms) {
        List<String> ifs = (fulfillments ?: []).collect { it.toString() }
        Map<String, String> out = [nsFulfillmentCount: ifs.size().toString(), omsShipGroupCount: (completedGroups?.size() ?: 0).toString(),
                                   omsShipGroupCheck: MATCHES, omsShipGroupDetail: null]
        if (notInOms) {
            out.omsShipGroupCheck = NOT_IN_OMS
            return out
        }
        if (!ifs) {
            out.omsShipGroupCheck = NO_FULFILLMENT
            return out
        }
        if (!needsFacilities(ifs, completedGroups)) return out

        Map<String, List<String>> ifsByLocation = [:].withDefault { [] }
        ifs.each { String entry ->
            int at = entry.indexOf('^')
            ifsByLocation[at < 0 ? entry : entry.substring(0, at)] << (at < 0 ? entry : entry.substring(at + 1))
        }
        // A ship group with no NetSuite location (_NA_, or a facility HotWax holds no externalId for) ships nothing
        // NetSuite could have fulfilled, so it is not counted.
        Map<String, List<String>> groupsByLocation = [:].withDefault { [] }
        Map<String, String> facilityByLocation = [:]
        completedGroups.each { String group ->
            List facility = facilityByGroup?.get(group)
            String location = facility && facility.size() > 1 ? facility[1]?.toString() : null
            if (!location) return
            groupsByLocation[location] << group
            facilityByLocation[location] = facility[0]?.toString()
        }
        List<String> split = groupsByLocation.keySet().findAll { String loc ->
            int n = ifsByLocation.containsKey(loc) ? ifsByLocation[loc].size() : 0
            n >= 1 && groupsByLocation[loc].size() > n
        }.sort()
        List<String> hotwaxOnly = groupsByLocation.keySet().findAll { !ifsByLocation.containsKey(it) }.sort()
        if (!split && !hotwaxOnly) return out

        out.omsShipGroupCheck = split ? SPLIT : HOTWAX_ONLY
        out.omsShipGroupDetail = (split + hotwaxOnly).collect { String loc ->
            List<String> numbers = ifsByLocation.containsKey(loc) ? ifsByLocation[loc] : []
            "location ${loc}: NetSuite ${numbers ? numbers.join(', ') : 'no fulfillment'}; HotWax ship groups " +
                    "${groupsByLocation[loc].join(', ')} at ${facilityByLocation[loc]}"
        }.join("; ")
        return out
    }
}
