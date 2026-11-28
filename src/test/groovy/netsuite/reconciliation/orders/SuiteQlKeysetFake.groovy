package netsuite.reconciliation.orders

/**
 * DAR-OPS-029 S41a (OC109). A stand-in NetSuite for keyset paging: NsSuiteQlOrderSupport.eachPage sends the query with its page
 * key selected (AS darpan_page_key[2]) and, after the first page, "AND key > last" (or the composite form) before its final
 * ORDER BY, always at offset 0. This reads a sent query back into its base text and the last key, and answers with the rows
 * after that key, as SuiteQL would: sorted by key, at most limit of them, each carrying its page key.
 */
class SuiteQlKeysetFake {

    private static final String KEY = NsSuiteQlOrderSupport.PAGE_KEY
    private static final String KEY2 = NsSuiteQlOrderSupport.PAGE_KEY_2

    /** The query as built, before the pager added its key columns and predicate. */
    static String baseQuery(String sent) {
        String s = sent.replaceFirst(/^SELECT (DISTINCT )?\S+ AS ${KEY}, (\S+ AS ${KEY2}, )?/, 'SELECT $1')
        return s.replaceFirst(/AND (\(\S+ > -?\d+ OR \(\S+ = -?\d+ AND \S+ > -?\d+\)\)|\S+ > -?\d+) (?=ORDER BY [^()]*$)/, "")
    }

    /** The last key the pager has read ([key] or [key, key2]), or null on the first page. */
    static List<Long> after(String sent) {
        def composite = sent =~ /AND \(\S+ > (-?\d+) OR \(\S+ = -?\d+ AND \S+ > (-?\d+)\)\) ORDER BY [^()]*$/
        if (composite.find()) return [composite.group(1) as Long, composite.group(2) as Long]
        def single = sent =~ /AND \S+ > (-?\d+) ORDER BY [^()]*$/
        return single.find() ? [single.group(1) as Long] : null
    }

    /**
     * One page of rows for a sent query: the rows whose key (keyOf: row -> [Long] or [Long, Long]) is after the query's last key,
     * in key order, at most limit, each with its page key fields; hasMore while more follow.
     */
    static Map page(List<Map> rows, String sent, int limit, Closure<List<Long>> keyOf) {
        List<Long> last = after(sent)
        List<Map> later = rows.findAll { last == null || compare(keyOf(it), last) > 0 }.sort { a, b -> compare(keyOf(a), keyOf(b)) }
        List<Map> items = later.take(limit).collect { Map row ->
            List<Long> key = keyOf(row)
            Map out = new LinkedHashMap(row)
            out.put(KEY, key[0].toString())
            if (key.size() > 1) out.put(KEY2, key[1].toString())
            out
        }
        return [items: items, hasMore: later.size() > limit]
    }

    private static int compare(List<Long> a, List<Long> b) {
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            int c = a[i] <=> b[i]
            if (c != 0) return c
        }
        return 0
    }
}
