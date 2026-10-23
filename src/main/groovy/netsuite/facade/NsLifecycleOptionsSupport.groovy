package netsuite.facade

import darpan.facade.common.SharedConfigAccessSupport
import darpan.reconciliation.flowchart.LifecycleStageCompiler
import netsuite.reconciliation.orders.NsSuiteQlOrderSupport

import java.math.RoundingMode
import java.time.LocalDate

/**
 * DAR-UI-050. Fills the lifecycle builder's dropdowns from the account itself, so a person picks from what
 * actually happens there and never from a list of NetSuite terms.
 *
 *  - Which records: transaction types with records in the last 30 days, most first.
 *  - What follows a record: NetSuite's own link table (nexttransactionlink), over a settled week that started
 *    21 to 14 days ago so later stages have had time to happen. For each follower type: how many records reached
 *    it, the share of the sample, the typical days to the first one, and the share done by each of a few days
 *    (which is what "late after" offers). DAR-UI-052: or a field both records carry, when that reaches clearly more
 *    (a return's credit memo by HC Return Id); such a stage says so in `reach`, which save#Lifecycle takes as stageReach.
 *
 * Aggregate queries only; every record type interpolated into them comes from LifecycleStageCompiler.RECORD_TYPES, and
 * every field id from NetSuite's own customfield list for the record type.
 * Measured on gorjana 2026-10-16: a month of 127,966 sales orders took ~33s per link query, a week ~8s.
 */
class NsLifecycleOptionsSupport {

    static final List<Integer> COVERAGE_DAYS = [0, 1, 2, 3, 5, 7, 14, 30]
    static final int COUNT_DAYS = 30
    static final int SAMPLE_START_DAYS_AGO = 21
    static final int SAMPLE_END_DAYS_AGO = 14

    /**
     * @param recordType null for the record-type counts only
     * @param suiteQl one SuiteQL string in, its rows out (string-valued, as NetSuite returns them)
     */
    static Map options(String recordType, LocalDate today, Closure<List<Map>> suiteQl) {
        if (recordType != null) LifecycleStageCompiler.words(recordType)   // refuses anything off the list
        List<String> types = LifecycleStageCompiler.RECORD_TYPES.keySet() as List<String>

        List<Map> counts = suiteQl.call("SELECT type, COUNT(*) AS n FROM transaction WHERE type IN (${quoted(types)}) " +
                "AND ${window('trandate', today.minusDays(COUNT_DAYS), today.plusDays(1))} GROUP BY type") ?: []
        List<Map> recordTypes = counts.collect { Map r -> [recordType: r.type as String, count: num(r.n)] }
                .findAll { it.count > 0 && LifecycleStageCompiler.RECORD_TYPES.containsKey(it.recordType) }
                .sort { -(it.count as long) }
                .collect { it + [label: LifecycleStageCompiler.words(it.recordType as String).plural] }
        Map out = [recordTypes: recordTypes]
        if (recordType == null) return out

        LocalDate from = today.minusDays(SAMPLE_START_DAYS_AGO), thru = today.minusDays(SAMPLE_END_DAYS_AGO)
        long sampleSize = num((suiteQl.call("SELECT COUNT(*) AS n FROM transaction WHERE type = '${recordType}' " +
                "AND ${window('trandate', from, thru)}") ?: [[:]])[0]?.n)
        List<String> followers = types.findAll { it != recordType }
        // One row per (follower type, days to the FIRST follower of that type): the inner query keeps each record's
        // earliest follower, so a record with three invoices counts once.
        List<Map> lags = suiteQl.call("SELECT childtype, lag, COUNT(*) AS n FROM (SELECT t.id AS pid, c.type AS childtype, " +
                "MIN(c.trandate - t.trandate) AS lag FROM transaction t JOIN nexttransactionlink l ON l.previousdoc = t.id " +
                "JOIN transaction c ON c.id = l.nextdoc WHERE t.type = '${recordType}' AND ${window('t.trandate', from, thru)} " +
                "AND c.type IN (${quoted(followers)}) GROUP BY t.id, c.type) GROUP BY childtype, lag") ?: []

        Map<String, Map<Integer, Long>> byType = lagsByType(lags)
        Map<String, Map> stagesByType = sampleSize > 0 ? byType.collectEntries { String type, Map<Integer, Long> d ->
            [(type): stage(type, d, sampleSize)] } : [:]
        if (sampleSize > 0) {
            Closure<Boolean> takesOver = { String type, long reached ->
                reached - ((stagesByType[type]?.reached ?: 0L) as long) >= sampleSize * FIELD_GAIN_PERCENT / 100 }
            fieldReaches(recordType, followers, sampleSize, from, thru, takesOver, suiteQl).each { String type, Map found ->
                stagesByType[type] = stage(type, found.lags as Map<Integer, Long>, sampleSize) + [reach: found.reach]
            }
        }
        List<Map> stages = stagesByType.values().findAll { it.reached > 0 }
                .sort { a, b -> a.typicalDays <=> b.typicalDays ?: b.reached <=> a.reached }

        return out + [stages: stages, sampleSize: sampleSize, sampleFrom: from.toString(), sampleThru: thru.minusDays(1).toString()]
    }

    /** (follower type, days, n) rows -> type -> days -> n. A follower dated before its record counts as same-day. */
    private static Map<String, Map<Integer, Long>> lagsByType(List<Map> rows) {
        Map<String, Map<Integer, Long>> byType = [:]
        rows.each { Map r ->
            String type = r.childtype as String
            if (!LifecycleStageCompiler.RECORD_TYPES.containsKey(type)) return
            int lag = Math.max(0, Math.round(dec(r.lag)) as int)
            Map<Integer, Long> d = byType.computeIfAbsent(type) { [:] }
            d[lag] = (d[lag] ?: 0L) + num(r.n)
        }
        return byType
    }

    private static Map stage(String type, Map<Integer, Long> d, long sampleSize) {
        long reached = (d.values().sum() ?: 0L) as long
        Map<String, String> w = LifecycleStageCompiler.words(type)
        return [recordType : type, label: w.name, past: w.past, reached: reached,
                rate       : percent(reached, sampleSize), typicalDays: median(d, reached),
                coverage   : COVERAGE_DAYS.collect { int days ->
                    [days: days, percent: percent((d.findAll { it.key <= days }.values().sum() ?: 0L) as long, reached)] }]
    }

    /**
     * DAR-UI-052. A field names one start record when it is filled on at least half the sample and nearly never repeats
     * (HC Return Id on returns; an order id repeats across an order's returns, a name across customers).
     */
    static final int FIELD_FILLED_PERCENT = 50
    static final int FIELD_DISTINCT_PERCENT = 99
    /** Followers found through such a field count only if nearly none come before the start record or several to one. */
    static final int FIELD_STRAY_PERCENT = 2
    /** A field match replaces NetSuite's own link only when it reaches at least this many more points of the sample. */
    static final int FIELD_GAIN_PERCENT = 5
    /** Fields counted per query: one aggregate column pair each. */
    static final int FIELD_COUNT_CHUNK = 40

    /**
     * DAR-UI-052. Stages reached by a field both records carry instead of the link table: gorjana writes most return
     * credit memos standalone, tied to the return only by custbody_hc_return_id (2026-10-21, 6,281 returns: 2,038 credits
     * linked, 5,981 by return id). One aggregate query per FIELD_COUNT_CHUNK fields finds the fields that name one start
     * record each, then one query per such field finds what carries it (~2.5s each on gorjana). Answers follower type ->
     * [lags: days -> n, reach: {startField, stageField, fieldLabel}], the field reaching the most records of that type,
     * only for types where takesOver(type, reached) says the field beats the link.
     */
    private static Map<String, Map> fieldReaches(String recordType, List<String> followers, long sampleSize, LocalDate from,
                                                 LocalDate thru, Closure<Boolean> takesOver, Closure<List<Map>> suiteQl) {
        List<Map> keys = []
        storedTextFields(recordType, suiteQl).findAll { it.fieldId ==~ /[a-z][a-z0-9_]*/ }.collate(FIELD_COUNT_CHUNK).each { List<Map> chunk ->
            String columns = chunk.withIndex().collect { Map f, int i -> "COUNT(t.${f.fieldId}) AS n${i}, COUNT(DISTINCT t.${f.fieldId}) AS d${i}" }.join(", ")
            Map row = (suiteQl.call("SELECT ${columns} FROM transaction t WHERE t.type = '${recordType}' AND ${window('t.trandate', from, thru)}") ?: [[:]])[0] ?: [:]
            chunk.eachWithIndex { Map f, int i ->
                long filled = num(row.get("n${i}".toString())), distinct = num(row.get("d${i}".toString()))
                if (filled * 100 >= sampleSize * FIELD_FILLED_PERCENT && distinct * 100 >= filled * FIELD_DISTINCT_PERCENT) keys << f
            }
        }
        // Per field: follower type -> [reached, lags]; only types the field reaches one each, on or after the start record.
        Map<Map, Map<String, Map>> byField = [:]
        keys.each { Map f ->
            String col = f.fieldId
            // dups: start records with more than one follower of the type carrying the field. Every follower dated before
            // its record is one bucket (-1): an order id finds the order's own sales and shipments, years of distinct lags.
            List<Map> rows = suiteQl.call("SELECT childtype, lag, COUNT(*) AS n, SUM(CASE WHEN m > 1 THEN 1 ELSE 0 END) AS dups FROM (" +
                    "SELECT t.id AS pid, c.type AS childtype, CASE WHEN MIN(c.trandate - t.trandate) < 0 THEN -1 " +
                    "ELSE MIN(c.trandate - t.trandate) END AS lag, COUNT(*) AS m FROM transaction t " +
                    "JOIN transaction c ON c.${col} = t.${col} WHERE t.type = '${recordType}' AND ${window('t.trandate', from, thru)} " +
                    "AND t.${col} IS NOT NULL AND c.type IN (${quoted(followers)}) GROUP BY t.id, c.type) GROUP BY childtype, lag") ?: []
            Map<String, Map> found = [:]
            lagsByType(rows).each { String type, Map<Integer, Long> d ->
                List<Map> ofType = rows.findAll { it.childtype == type }
                long reached = (d.values().sum() ?: 0L) as long
                long before = (ofType.findAll { dec(it.lag) < 0 }.collect { num(it.n) }.sum() ?: 0L) as long
                long several = (ofType.collect { num(it.dups) }.sum() ?: 0L) as long
                if (before * 100 > reached * FIELD_STRAY_PERCENT || several * 100 > reached * FIELD_STRAY_PERCENT) return
                if (takesOver.call(type, reached)) found[type] = [reached: reached, lags: d]
            }
            byField[f] = found
        }
        // A type reached as often by two fields goes to the one that takes more stages from the link. gorjana 2026-10-21:
        // HC POS Return Id and HC Return Id find the same 5,981 credit memos; the POS id also finds the receipts, which the
        // link finds as well, while the return id also finds refunds and invoices, which nothing else does.
        Map<Map, Long> total = byField.collectEntries { Map f, Map<String, Map> found ->
            [(f): (found.values().collect { it.reached as long }.sum() ?: 0L) as long] }
        Map<String, Map> best = [:]
        byField.each { Map f, Map<String, Map> found ->
            found.each { String type, Map m ->
                Map held = best[type]
                long reached = m.reached as long, heldReached = (held?.reached ?: 0L) as long
                if (reached > heldReached || (held && reached == heldReached && total[f] > total[held.field])) {
                    best[type] = m + [field: f, reach: [startField: f.fieldId, stageField: f.fieldId, fieldLabel: f.label]]
                }
            }
        }
        return best
    }

    /**
     * The text fields NetSuite shows on a record type, by their own names. visibleontransactions is a list like
     * "CashSale, SalesOrd, ..." that SuiteQL cannot filter (a LIKE on it returns no rows, measured on gorjana 2026-10-16),
     * so it is read and matched here. A field NetSuite does not store (sourced from another record) is no column on
     * transaction: naming one answers HTTP 400 "Field ... was not found" (gorjana 2026-10-21, the Avalara name fields).
     */
    private static List<Map> storedTextFields(String recordType, Closure<List<Map>> suiteQl) {
        return (suiteQl.call("SELECT scriptid, name, visibleontransactions FROM customfield WHERE fieldtype = 'BODY' " +
                "AND fieldvaluetype = 'Free-Form Text' AND isstored = 'T'") ?: [])
                .findAll { Map r -> ((r.visibleontransactions ?: "") as String).split(",")*.trim().contains(recordType) }
                .collect { Map r -> [fieldId: (r.scriptid as String)?.toLowerCase(), label: (r.name as String)?.trim()] }
                .findAll { it.fieldId && it.label }
                .sort { it.label.toLowerCase() }
    }

    /** A rule's empty value, as LifecycleRuleSupport projects it (NVL) so a filter can match it. */
    static final String NO_VALUE = "NO_VALUE"

    /**
     * The rule dropdowns: the text fields NetSuite shows on this record type, by their own names, and, given one of
     * them, what it holds over the last 30 days, most common first, empty as "No value". A field with a value per
     * record (an order id) has more than a page of values; the first page is enough, since "No value" sorts first.
     *
     * @param suiteQl (sql, firstPageOnly) -> rows
     */
    /**
     * Standard list fields a stage exception may name, read by display name (BUILTIN.DF): Store Pickup orders never
     * ship, and Shipping Method is how NetSuite says so. Not offered for leaving records out, whose column cannot wrap
     * BUILTIN.DF in NVL. Item names items on the record's lines (a gift card never ships): see NsSuiteQlOrderSupport's
     * waive predicate.
     */
    static final Map<String, String> STANDARD_STAGE_FIELDS = [shipmethod: "Shipping Method", (NsSuiteQlOrderSupport.WAIVE_ITEM): "Item"].asImmutable()

    /**
     * Standard list fields a leave-out rule may name, read by display name (BUILTIN.DF), as each record shows them:
     * a Closed sales order reached its final state, so it is in no place. Never empty, so no "No value".
     */
    static final Map<String, String> STANDARD_RULE_FIELDS = [status: "Status"].asImmutable()

    /** The items an Item exception offers: the kinds NetSuite never ships, as they appear on this record type's lines. */
    static final List<String> UNSHIPPED_ITEM_TYPES = ["NonInvtPart", "Service", "GiftCert", "OthCharge"].asImmutable()

    /**
     * What a written finding may ask about beyond the custom text fields (NsSuiteQlOrderSupport.MATCH_COLUMNS): list
     * fields by name, and numbers, which offer "more than" a threshold rather than values read from the account.
     */
    static final Map<String, String> FINDING_FIELDS = [status: "Status", shipmethod: "Shipping Method",
                                                       itemsbackordered: "Items Backordered", total: "Total"].asImmutable()
    static final Map<String, List<String>> FINDING_THRESHOLDS = [itemsbackordered: ["0", "1", "2", "5", "10"],
                                                                 total: ["0", "50", "100", "500", "1000"]].asImmutable()

    static Map ruleOptions(String recordType, String fieldId, LocalDate today, Closure<List<Map>> suiteQl, boolean forStage = false,
                           boolean forFinding = false) {
        LifecycleStageCompiler.words(recordType)
        List<Map> fields = storedTextFields(recordType) { String q -> suiteQl.call(q, false) }
        Map<String, String> standardFields = forFinding ? FINDING_FIELDS : forStage ? STANDARD_STAGE_FIELDS : STANDARD_RULE_FIELDS
        fields = standardFields.collect { k, v -> [fieldId: k, label: v] + (FINDING_THRESHOLDS.containsKey(k) ? [kind: "number"] : [:]) } + fields
        Map out = [fields: fields]
        if (fieldId == null) return out
        // The field reaches SuiteQL by interpolation: only one NetSuite itself listed for this record type passes.
        if (!fields.any { it.fieldId == fieldId }) throw new IllegalArgumentException("'${fieldId}' is not a field on ${recordType}.")
        // The plain GROUP BY is the form NetSuite takes: NVL in the GROUP BY with ORDER BY COUNT(*) answers HTTP 500
        // UNEXPECTED_ERROR (gorjana 2026-10-16). So values are sorted here, and empties are counted on their own:
        // a field with a value per record has more than a page, in no order, and "No value" must still be offered.
        if (fieldId == NsSuiteQlOrderSupport.WAIVE_ITEM) return out + [values: itemValues(recordType, today, suiteQl)]
        // A number is asked "more than" one of a few thresholds; nothing is read from the account for it.
        if (forFinding && FINDING_THRESHOLDS.containsKey(fieldId)) {
            return out + [values: FINDING_THRESHOLDS[fieldId].collect { [value: it, label: "more than ${it}".toString()] }]
        }
        boolean standard = STANDARD_STAGE_FIELDS.containsKey(fieldId) || STANDARD_RULE_FIELDS.containsKey(fieldId) || FINDING_FIELDS.containsKey(fieldId)
        String column = standard ? "BUILTIN.DF(t.${fieldId})" : "t.${fieldId}"
        String where = "FROM transaction t WHERE t.type = '${recordType}' AND ${window('t.trandate', today.minusDays(COUNT_DAYS), today.plusDays(1))}"
        List<Map> rows = suiteQl.call("SELECT ${column} AS v, COUNT(*) AS n ${where} GROUP BY ${column}", true) ?: []
        // An exception names values a record has; "has no value" is a leave-out rule's business.
        long empty = forStage || standard ? 0L : num((suiteQl.call("SELECT COUNT(*) AS n ${where} AND t.${fieldId} IS NULL", false) ?: [[:]])[0]?.n)
        List<Map> values = rows.findAll { (it.v as String)?.trim() }
                .sort { -num(it.n) }
                .collect { Map r -> [value: (r.v as String).trim(), label: standardValueLabel((r.v as String).trim()), n: num(r.n)] }
        if (empty > 0) {
            int at = values.findIndexOf { (it.n as long) < empty }
            values.add(at < 0 ? values.size() : at, [value: NO_VALUE, label: "No value", n: empty])
        }
        return out + [values: values.collect { [value: it.value, label: it.label] }]
    }

    /**
     * Items that never ship and are on this record type's lines in the last 30 days, most records first, by SKU and name.
     * gorjana 2026-10-16: four (GFT-CP on 1,340 sales orders, then POS Tax Variance, Warranties Variance,
     * PickTicketPaper), ~4s; the catalog holds 226 such items, most never sold.
     */
    private static List<Map> itemValues(String recordType, LocalDate today, Closure<List<Map>> suiteQl) {
        List<Map> rows = suiteQl.call("SELECT i.itemid AS v, i.displayname AS d, COUNT(DISTINCT t.id) AS n FROM transaction t " +
                "JOIN transactionline tl ON tl.transaction = t.id JOIN item i ON i.id = tl.item WHERE t.type = '${recordType}' " +
                "AND ${window('t.trandate', today.minusDays(COUNT_DAYS), today.plusDays(1))} AND tl.mainline = 'F' " +
                "AND i.itemtype IN (${quoted(UNSHIPPED_ITEM_TYPES)}) GROUP BY i.itemid, i.displayname", true) ?: []
        return rows.findAll { (it.v as String)?.trim() }
                .sort { -num(it.n) }
                .collect { Map r ->
                    String sku = (r.v as String).trim(), name = (r.d as String)?.trim()
                    [value: sku, label: name && name != sku ? "${sku} · ${name}".toString() : sku]
                }
    }

    /** A status reads "Sales Order : Closed"; the record type is already known, so the label is "Closed". */
    static String standardValueLabel(String value) {
        int at = value.indexOf(" : ")
        return at > 0 ? value.substring(at + 3) : value
    }

    static Map ruleOptionsForAccount(def ec, String authId, String recordType, String fieldId, String scope = null) {
        def auth = accessibleAccount(ec, authId)
        if (auth == null) return [:]
        try {
            NsSuiteQlClient client = new NsSuiteQlClient(auth)
            return ruleOptions(recordType, fieldId, ec.user.nowTimestamp.toLocalDateTime().toLocalDate(), { String q, boolean firstPage ->
                client.query(q, firstPage) }, scope == "STAGE", scope == "FINDING")
        } catch (IllegalArgumentException e) {
            ec.message.addError(e.message); return [:]
        } catch (Exception e) {
            ec.message.addError("Could not read NetSuite account '${authId}': ${e.message}"); return [:]
        }
    }

    /** The account when the active tenant may read through it (its own or shared, and not turned off); null with the error otherwise. */
    static def accessibleAccount(def ec, String authId) {
        def auth = authId ? SharedConfigAccessSupport.listAccessibleConfigRows(ec, SharedConfigAccessSupport.CONFIG_TYPE_NS_AUTH)
                .find { it.get("nsAuthConfigId") == authId } : null
        if (auth == null) { ec.message.addError("NetSuite account '${authId}' was not found."); return null }
        if ((auth.get("isActive") ?: "Y").toString().equalsIgnoreCase("N")) {
            ec.message.addError("NetSuite account '${authId}' is turned off."); return null
        }
        return auth
    }

    /** The service: the account must be the active tenant's own or shared with it, as for every NetSuite read. */
    static Map forAccount(def ec, String authId, String recordType) {
        def auth = accessibleAccount(ec, authId)
        if (auth == null) return [:]
        try {
            NsSuiteQlClient client = new NsSuiteQlClient(auth)
            return options(recordType, ec.user.nowTimestamp.toLocalDateTime().toLocalDate()) { String q -> client.query(q) }
        } catch (IllegalArgumentException e) {
            ec.message.addError(e.message); return [:]
        } catch (Exception e) {
            ec.message.addError("Could not read NetSuite account '${authId}': ${e.message}"); return [:]
        }
    }

    private static String window(String col, LocalDate from, LocalDate thruExclusive) {
        return "${col} >= TO_DATE('${from}', 'YYYY-MM-DD') AND ${col} < TO_DATE('${thruExclusive}', 'YYYY-MM-DD')"
    }

    private static String quoted(List<String> types) { types.collect { "'${it}'" }.join(", ") }

    private static int median(Map<Integer, Long> byDays, long total) {
        long seen = 0
        for (Integer days : byDays.keySet().sort()) {
            seen += byDays[days]
            if (seen * 2 >= total) return days
        }
        return 0
    }

    private static int percent(long part, long whole) {
        return whole > 0 ? new BigDecimal(part * 100).divide(new BigDecimal(whole), 0, RoundingMode.HALF_UP).intValue() : 0
    }

    private static long num(Object v) { v == null || v.toString().trim().isEmpty() ? 0L : new BigDecimal(v.toString().trim()).longValue() }

    private static double dec(Object v) { v == null || v.toString().trim().isEmpty() ? 0d : Double.parseDouble(v.toString().trim()) }
}
