package netsuite.reconciliation.orders

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import java.nio.charset.StandardCharsets

/**
 * DAR-BE-073. A lifecycle's records read ONCE per run, and every one of its questions answered from that read.
 *
 * A lifecycle's questions all ask about the same records, the start type in the run's window, and differ only in
 * conditions on each: is a stage missing, does an exception excuse it, does a written finding match. Asked live, every
 * question re-read the whole window from NetSuite and the walk kept only its parent's records afterwards (local gorjana
 * 2026-10-17, ~10k orders: 9 questions at 4-25s each, the NetSuite read ~90% of every one; one question about ~150
 * records took 25s). The count of questions grows with the square of the stages, so a month of a four-stage lifecycle
 * would read the window 27 times.
 *
 * Here the window's records are read once, to a file, and each distinct condition once, as the ids that meet it
 * (one predicate per query, see NsSuiteQlOrderSupport.buildConditionIdQuery). A question's records are the window's
 * records its template's condition admits, served through streamOrdersToWriter exactly as live pages are, so mapping,
 * source filters, projection and the extract document are unchanged.
 *
 * Exact or not at all: a question is answered here only when its template is one of the shapes below, it carries none
 * of the gates the shapes do not model, and every column it selects was read. Anything else is asked live.
 *
 * One deliberate difference from the live SQL, three-valued logic: live, "NOT (has OR waived)" is NULL when the
 * exception's field is empty (BUILTIN.DF(t.shipmethod) IN (...) on an order with no shipping method), so that record
 * was found by no question and sat in no place. Here an empty field does not excuse, so it is found missing the stage.
 */
class NsLifecycleSnapshot {

    private static final List<String> TEMPLATES = [NsSuiteQlOrderSupport.TEMPLATE_RECORDS,
            NsSuiteQlOrderSupport.TEMPLATE_LINKED_CHILD_ABSENT, NsSuiteQlOrderSupport.TEMPLATE_LINKED_CHILD_PRESENT,
            NsSuiteQlOrderSupport.TEMPLATE_LINKED_CHILD_PRESENT_ABSENT, NsSuiteQlOrderSupport.TEMPLATE_LINKED_CHILD_EXCUSED,
            NsSuiteQlOrderSupport.TEMPLATE_RECORDS_MATCHING].asImmutable()
    /** The id column every condition is matched on. */
    private static final String ID_COLUMN = "id"

    final String fromDate
    final String toDate
    final int baseCount
    final long readMillis
    private final File baseFile
    private final Set<String> baseColumns
    private final Map<String, Set<String>> idsByCondition = [:]
    private final int maxPages
    private final int pageSize

    private NsLifecycleSnapshot(String fromDate, String toDate, File baseFile, Set<String> baseColumns, int baseCount,
                                long readMillis, int maxPages, int pageSize) {
        this.fromDate = fromDate
        this.toDate = toDate
        this.baseFile = baseFile
        this.baseColumns = baseColumns
        this.baseCount = baseCount
        this.readMillis = readMillis
        this.maxPages = maxPages
        this.pageSize = pageSize
    }

    /** True when the spec's question can be answered exactly from a snapshot of its window. */
    static boolean canAnswer(Map<String, Object> spec) {
        if (!(spec.get("queryTemplate") in TEMPLATES)) return false
        if (spec.get("fromTable") != NsSuiteQlOrderSupport.DEFAULT_FROM_TABLE) return false
        if (spec.get("requireFulfillableOpenLine") || spec.get("requireOverdueShipDate")) return false
        if (spec.get("requireParentRecordType") || spec.get("amountColumn") || spec.get("lineKeyColumn")) return false
        if (spec.get("overdueExcludedShipMethods")) return false
        return idRowKey(spec) != null
    }

    /** What a snapshot was read for: the account and tenant, the record type and the window. */
    static String keyFor(Map<String, Object> spec, String fromDate, String toDate, String account) {
        return ["ns-lifecycle-snapshot", account, spec.get("recordType"), spec.get("fromTable"), spec.get("dateColumn"),
                fromDate, toDate].join("|")
    }

    /**
     * Reads the window's records of the spec's record type with the spec's columns. Fails rather than truncate: a short
     * read would leave every question that follows short without saying so.
     */
    static NsLifecycleSnapshot read(Map<String, Object> spec, String fromDate, String toDate, File dir, Closure pageFetcher,
                                    int maxPages = NsSuiteQlOrderSupport.DEFAULT_MAX_PAGES,
                                    int pageSize = NsSuiteQlOrderSupport.MAX_PAGE_SIZE) {
        long started = System.currentTimeMillis()
        Map<String, Object> base = spec + [queryTemplate: NsSuiteQlOrderSupport.TEMPLATE_RECORDS, absentChildRecordType: null,
                presentChildRecordType: null, waiveRules: [], matchRules: [], stageReach: [:]]
        String idKey = idRowKey(spec)
        if (idKey == null) throw new IllegalArgumentException("a snapshot needs the record's id column")
        dir.mkdirs()
        File file = File.createTempFile("ns-lifecycle-", ".rows", dir)
        int count = 0
        file.withWriter(StandardCharsets.UTF_8.name()) { Writer w ->
            eachRow(NsSuiteQlOrderSupport.buildRecordsQuery(base, fromDate, toDate), pageFetcher, maxPages, pageSize) { Map row ->
                row.remove("links")
                // The id leads the line so a question skips the rows it does not admit without parsing them.
                w.write(String.valueOf(row.get(idKey)))
                w.write('\t')
                w.write(JsonOutput.toJson(row))
                w.write('\n')
                count++
            }
        }
        return new NsLifecycleSnapshot(fromDate, toDate, file, columnsOf(spec), count, System.currentTimeMillis() - started,
                maxPages, pageSize)
    }

    /** True when every column the spec selects was read. */
    boolean covers(Map<String, Object> spec) {
        return baseColumns.containsAll(columnsOf(spec))
    }

    /** The ids each condition read so far holds, by condition key (for the run's log). */
    Map<String, Integer> getConditionCounts() {
        return idsByCondition.collectEntries { k, v -> [(k): v.size()] } as Map<String, Integer>
    }

    /**
     * Whether an id is one of the spec's records, by the same logic as its live template. A condition not yet read is
     * read now through pageFetcher, once for the whole run.
     */
    Closure<Boolean> admits(Map<String, Object> spec, Closure pageFetcher) {
        String template = spec.get("queryTemplate")
        boolean hasWaive = spec.get("waiveRules") as boolean
        Closure<Set<String>> missing = { String childType -> condition(spec, NsSuiteQlOrderSupport.CONDITION_MISSING, childType, pageFetcher) }
        Set<String> waived = hasWaive ? condition(spec, NsSuiteQlOrderSupport.CONDITION_WAIVED, null, pageFetcher) : ([] as Set<String>)
        switch (template) {
            case NsSuiteQlOrderSupport.TEMPLATE_RECORDS:
                return { String id -> true }
            case NsSuiteQlOrderSupport.TEMPLATE_LINKED_CHILD_ABSENT:
                Set<String> absent = missing((String) spec.get("absentChildRecordType"))
                return { String id -> absent.contains(id) && !waived.contains(id) }
            case NsSuiteQlOrderSupport.TEMPLATE_LINKED_CHILD_PRESENT:
                Set<String> notYet = missing((String) spec.get("presentChildRecordType"))
                return { String id -> !notYet.contains(id) || waived.contains(id) }
            case NsSuiteQlOrderSupport.TEMPLATE_LINKED_CHILD_EXCUSED:
                Set<String> absent = missing((String) spec.get("absentChildRecordType"))
                return { String id -> absent.contains(id) && waived.contains(id) }
            case NsSuiteQlOrderSupport.TEMPLATE_LINKED_CHILD_PRESENT_ABSENT:
                Set<String> notYet = missing((String) spec.get("presentChildRecordType"))
                Set<String> absent = missing((String) spec.get("absentChildRecordType"))
                return { String id -> !notYet.contains(id) && absent.contains(id) }
            case NsSuiteQlOrderSupport.TEMPLATE_RECORDS_MATCHING:
                Set<String> matching = condition(spec, NsSuiteQlOrderSupport.CONDITION_MATCHING, null, pageFetcher)
                return { String id -> matching.contains(id) }
            default:
                throw new IllegalArgumentException("a snapshot cannot answer ${template}")
        }
    }

    /**
     * A page fetcher over the admitted rows, in id order as the live query pages them, for streamOrdersToWriter in
     * place of the live one. close() must be called when the extract is done.
     */
    Map<String, Closure> serve(Closure<Boolean> admits) {
        BufferedReader reader = baseFile.newReader(StandardCharsets.UTF_8.name())
        JsonSlurper slurper = new JsonSlurper()
        Map next = null
        Closure<Map> advance = {
            String line
            while ((line = reader.readLine()) != null) {
                int tab = line.indexOf('\t')
                if (admits.call(line.substring(0, tab))) return (Map) slurper.parseText(line.substring(tab + 1))
            }
            return null
        }
        next = advance()
        Closure fetch = { String query, int limit, int offset ->
            List<Map> items = []
            while (next != null && items.size() < limit) {
                items << next
                next = advance()
            }
            return [items: items, hasMore: next != null]
        }
        return [fetch: fetch, close: { reader.close() }]
    }

    void discard() {
        baseFile.delete()
    }

    private Set<String> condition(Map<String, Object> spec, String kind, String childType, Closure pageFetcher) {
        String key = conditionKey(spec, kind, childType)
        Set<String> ids = idsByCondition.get(key)
        if (ids != null) return ids
        ids = new HashSet<String>()
        Set<String> found = ids
        eachRow(NsSuiteQlOrderSupport.buildConditionIdQuery(spec, kind, childType, fromDate, toDate), pageFetcher, maxPages, pageSize) { Map row ->
            found.add(String.valueOf(row.get(ID_COLUMN)))
        }
        idsByCondition.put(key, ids)
        return ids
    }

    /** A condition is the same condition only when everything its query depends on is the same. */
    private static String conditionKey(Map<String, Object> spec, String kind, String childType) {
        if (kind == NsSuiteQlOrderSupport.CONDITION_MISSING) {
            Object reach = ((Map) spec.get("stageReach"))?.get(childType)
            return "${kind}|${childType}|${reach ? JsonOutput.toJson(reach) : ''}".toString()
        }
        if (kind == NsSuiteQlOrderSupport.CONDITION_WAIVED) return "${kind}|${JsonOutput.toJson(spec.get('waiveRules'))}".toString()
        return "${kind}|${JsonOutput.toJson(spec.get('matchRules'))}".toString()
    }

    private static void eachRow(String query, Closure pageFetcher, int maxPages, int size, Closure onRow) {
        int offset = 0, pages = 0
        boolean hasMore = true
        while (hasMore) {
            if (pages >= maxPages) {
                throw new IllegalStateException("The lifecycle's records ran past ${maxPages} pages of ${size}; narrow the window.")
            }
            Map page = (Map) pageFetcher.call(query, size, offset)
            pages++
            ((List) (page?.get("items") ?: [])).each { onRow.call((Map) it) }
            hasMore = page?.get("hasMore") == true
            offset += size
        }
    }

    /** The row key the id column comes back under, or null when the spec does not select it. */
    private static String idRowKey(Map<String, Object> spec) {
        Map f = ((List<Map>) spec.get("fields") ?: []).find { ((String) it.get("sourceColumn")).trim() == ID_COLUMN }
        return (String) f?.get("rowKey")
    }

    private static Set<String> columnsOf(Map<String, Object> spec) {
        return ((List<Map>) spec.get("fields") ?: []).collect { "${((String) it.get('sourceColumn')).trim()} -> ${it.get('rowKey')}".toString() } as Set<String>
    }
}
