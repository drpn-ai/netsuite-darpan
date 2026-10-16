package netsuite.facade

import darpan.facade.common.SharedConfigAccessSupport
import darpan.reconciliation.flowchart.LifecycleStageCompiler

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
 *    (which is what "late after" offers).
 *
 * Three aggregate queries; every value interpolated into them comes from LifecycleStageCompiler.RECORD_TYPES.
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

        Map<String, Map<Integer, Long>> byType = [:]
        lags.each { Map r ->
            String type = r.childtype as String
            if (!LifecycleStageCompiler.RECORD_TYPES.containsKey(type)) return
            // A follower dated before its record is a same-day follower entered out of order.
            int lag = Math.max(0, Math.round(dec(r.lag)) as int)
            Map<Integer, Long> d = byType.computeIfAbsent(type) { [:] }
            d[lag] = (d[lag] ?: 0L) + num(r.n)
        }
        List<Map> stages = sampleSize > 0 ? byType.collect { String type, Map<Integer, Long> d ->
            long reached = (d.values().sum() ?: 0L) as long
            Map<String, String> w = LifecycleStageCompiler.words(type)
            [recordType : type, label: w.name, past: w.past, reached: reached,
             rate       : percent(reached, sampleSize), typicalDays: median(d, reached),
             coverage   : COVERAGE_DAYS.collect { int days ->
                 [days: days, percent: percent((d.findAll { it.key <= days }.values().sum() ?: 0L) as long, reached)] }]
        }.findAll { it.reached > 0 }.sort { a, b -> a.typicalDays <=> b.typicalDays ?: b.reached <=> a.reached } : []

        return out + [stages: stages, sampleSize: sampleSize, sampleFrom: from.toString(), sampleThru: thru.minusDays(1).toString()]
    }

    /** The service: the account must be the active tenant's own or shared with it, as for every NetSuite read. */
    static Map forAccount(def ec, String authId, String recordType) {
        def auth = authId ? SharedConfigAccessSupport.listAccessibleConfigRows(ec, SharedConfigAccessSupport.CONFIG_TYPE_NS_AUTH)
                .find { it.get("nsAuthConfigId") == authId } : null
        if (auth == null) { ec.message.addError("NetSuite account '${authId}' was not found."); return [:] }
        if ((auth.get("isActive") ?: "Y").toString().equalsIgnoreCase("N")) {
            ec.message.addError("NetSuite account '${authId}' is turned off."); return [:]
        }
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
