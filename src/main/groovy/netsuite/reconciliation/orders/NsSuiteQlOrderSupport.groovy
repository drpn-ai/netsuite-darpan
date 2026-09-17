package netsuite.reconciliation.orders

import darpan.facade.common.OutboundHttpPolicy
import darpan.reconciliation.source.SourceFilterSupport
import groovy.json.JsonOutput
import groovy.transform.CompileStatic

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.regex.Pattern

/**
 * SuiteQL extraction logic for NetSuite reconciliation sources (DAR-BE-032, generalized by
 * DAR-BE-044).
 *
 * WHY SuiteQL rather than a RESTlet: measured against a live account on 2026-09-02, SuiteQL returns
 * 1000 rows per page (the documented ceiling, honoured) at ~880 rows/sec, so a 161k-order month
 * enumerates in about three minutes. A RESTlet would need a SuiteScript record, a deployment and a
 * role grant inside the CLIENT's production NetSuite, and buys nothing for a presence check. The
 * OAuth2 M2M profile Darpan already models covers it: NsAuthConfig.scope defaults to
 * "restlets rest_webservices".
 *
 * WHAT DAR-BE-044 CHANGED. The record type, the selected columns and the join key used to be
 * constants here, which meant every new NetSuite pair needed its own systemEnumId, connector row and
 * extract service — four more of each for the within-NetSuite pairs, deepening the
 * endpoint-as-system overload DAR-BE-024 exists to retire. They now arrive as a SPEC read from
 * NsSuiteQlSourceQuery, so one connector row serves every NetSuite pair and a new pair is
 * configuration. Nothing about the transport, paging, window or record contract moved.
 *
 * Everything here is pure so it is unit-testable without Moqui or a NetSuite account; the HTTP
 * exchange lives in the service edge script.
 */
@CompileStatic
class NsSuiteQlOrderSupport {

    /** NetSuite's documented SuiteQL page ceiling. A larger limit is silently capped server-side. */
    static final int MAX_PAGE_SIZE = 1000

    /** Origin values for the operator-facing exclusion rule; see mapRowToRecord. */
    static final String ORIGIN_HOTWAX = "HOTWAX"
    static final String ORIGIN_NETSUITE_NATIVE = "NETSUITE_NATIVE"

    /** Safety ceiling on offset paging so a source stuck on hasMore=true cannot spin forever. */
    static final int DEFAULT_MAX_PAGES = 2000

    /** Same allow-list the inventory path enforces; SuiteQL lives on the suitetalk host. */
    static final List<String> NETSUITE_HOST_SUFFIXES = [".suitetalk.api.netsuite.com", ".app.netsuite.com"]

    /** Defaults matching the transaction shape every NetSuite transaction pair starts from. */
    static final String DEFAULT_FROM_TABLE = "transaction"
    static final String DEFAULT_DATE_COLUMN = "trandate"

    /**
     * THE VALIDATION BELOW IS NEW SURFACE, NOT CEREMONY. The window bounds are interpolated into
     * SuiteQL text rather than bound as parameters (NetSuite's query endpoint takes a string), which
     * is why requireIsoDate has always existed. While the table, record type and columns were compile
     * time constants they were trivially safe. Reading them from NsSuiteQlSourceQuery puts them on
     * that same interpolation path with an operator at the other end, so each one is checked to be an
     * identifier — or, for a column, an identifier-shaped expression — before it can reach the query.
     */
    private static final Pattern IDENTIFIER = ~/[A-Za-z_][A-Za-z0-9_]*/

    /**
     * A column is either a bare identifier or a single function call over identifiers and quoted
     * literals, optionally aliased: TO_CHAR(trandate, 'YYYY-MM-DD') AS trandate. Nested parentheses
     * are not matched, so a subquery cannot be expressed even before the keyword check below.
     */
    private static final Pattern COLUMN_EXPRESSION =
            ~/[A-Za-z_][A-Za-z0-9_]*(\s*\(\s*[A-Za-z0-9_,'\- \/:.]*\s*\))?(\s+[Aa][Ss]\s+[A-Za-z_][A-Za-z0-9_]*)?/

    /** Belt and braces beside the shape check: no statement breaks, no comment starts. */
    private static final List<String> FORBIDDEN_SEQUENCES = [";", "--", "/*", "*/"]

    /**
     * Rejected anywhere in a configured fragment. The shape patterns above already refuse these, but
     * a keyword check states the intent directly and survives a later loosening of the pattern.
     */
    private static final Pattern FORBIDDEN_KEYWORDS =
            ~/(?i).*\b(select|from|where|union|join|insert|update|delete|drop|alter|exec)\b.*/

    /**
     * Derives the SuiteQL endpoint from the auth profile's token URL so the account id is configured
     * exactly once. Validating here (rather than trusting the stored row) is the SSRF backstop: a
     * NsAuthConfig mutated out-of-band could otherwise aim a valid bearer token at any host.
     */
    static String suiteQlUrlFromTokenUrl(String tokenUrl) {
        String raw = tokenUrl?.trim()
        if (!raw) throw new IllegalArgumentException("NsAuthConfig tokenUrl is required to derive the SuiteQL endpoint")
        def check = OutboundHttpPolicy.validate(raw, NETSUITE_HOST_SUFFIXES)
        if (!check.ok) throw new IllegalArgumentException("NetSuite token URL blocked by outbound policy: ${check.error}")
        String host = URI.create(raw).host
        return "https://${host}/services/rest/query/v1/suiteql".toString()
    }

    /**
     * Normalizes a NsSuiteQlSourceQuery row plus its NsSuiteQlSourceQueryField rows into the spec the
     * rest of this class takes, validating every fragment that reaches the query text.
     *
     * Field order is the operator's (sequenceNum), because the SELECT list is also the thing an
     * operator reads back when checking what a side projects.
     */
    static Map<String, Object> normalizeSpec(Map<String, Object> query, List<Map<String, Object>> fields) {
        if (query == null) throw new IllegalArgumentException("NsSuiteQlSourceQuery row is required")

        String recordType = text(query.get("recordType"))
        if (!recordType) throw new IllegalArgumentException("NsSuiteQlSourceQuery.recordType is required")
        requireSafeFragment(recordType, "recordType")

        String fromTable = text(query.get("fromTable")) ?: DEFAULT_FROM_TABLE
        requireIdentifier(fromTable, "fromTable")

        String dateColumn = text(query.get("dateColumn")) ?: DEFAULT_DATE_COLUMN
        requireIdentifier(dateColumn, "dateColumn")

        List<Map<String, Object>> rawFields = (fields ?: []) as List<Map<String, Object>>
        if (rawFields.isEmpty()) {
            throw new IllegalArgumentException("NsSuiteQlSourceQuery ${query.get('nsSuiteQlSourceQueryId')} " +
                    "has no field mappings, so it would select nothing and project nothing")
        }

        List<Map<String, Object>> normalized = rawFields
                .sort { Map<String, Object> f -> ((Number) (f.get("sequenceNum") ?: 0)).intValue() }
                .collect { Map<String, Object> f ->
                    String recordFieldName = text(f.get("recordFieldName"))
                    String sourceColumn = text(f.get("sourceColumn"))
                    if (!recordFieldName) throw new IllegalArgumentException("a field mapping is missing recordFieldName")
                    if (!sourceColumn) {
                        throw new IllegalArgumentException("field mapping ${recordFieldName} is missing sourceColumn")
                    }
                    requireIdentifier(recordFieldName, "recordFieldName")
                    requireColumnExpression(sourceColumn, "sourceColumn for ${recordFieldName}")
                    String alias = text(f.get("sourceColumnAlias")) ?: impliedAlias(sourceColumn)
                    requireIdentifier(alias, "sourceColumnAlias for ${recordFieldName}")
                    return [recordFieldName: recordFieldName, sourceColumn: sourceColumn, rowKey: alias] as Map<String, Object>
                } as List<Map<String, Object>>

        String joinKeyFieldName = text(query.get("joinKeyFieldName"))
        String joinKeyRowKey = null
        if (joinKeyFieldName) {
            Map<String, Object> joinField = normalized.find { it.get("recordFieldName") == joinKeyFieldName }
            if (joinField == null) {
                throw new IllegalArgumentException("joinKeyFieldName ${joinKeyFieldName} is not one of the mapped " +
                        "record fields ${normalized.collect { it.get('recordFieldName') }}")
            }
            joinKeyRowKey = (String) joinField.get("rowKey")
        }

        String originFieldName = text(query.get("originFieldName"))
        if (originFieldName) {
            requireIdentifier(originFieldName, "originFieldName")
            if (!joinKeyRowKey) {
                throw new IllegalArgumentException("originFieldName ${originFieldName} needs joinKeyFieldName: " +
                        "a record's origin is decided by whether the join key's column was populated")
            }
        }

        return [
                nsSuiteQlSourceQueryId: text(query.get("nsSuiteQlSourceQueryId")),
                recordType            : recordType,
                fromTable             : fromTable,
                dateColumn            : dateColumn,
                fields                : normalized,
                joinKeyFieldName      : joinKeyFieldName,
                joinKeyRowKey         : joinKeyRowKey,
                originFieldName       : originFieldName,
        ] as Map<String, Object>
    }

    /**
     * A bare column is its own row key. An aliased expression comes back under the alias, so that is
     * what mapRowToRecord must read — reading the expression text would find nothing and produce a
     * silently null field.
     */
    private static String impliedAlias(String sourceColumn) {
        def m = sourceColumn =~ /(?i)\s+as\s+([A-Za-z_][A-Za-z0-9_]*)\s*$/
        if (m.find()) return m.group(1)
        return sourceColumn.trim()
    }

    private static void requireIdentifier(String value, String label) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("${label} must be a plain identifier, got: ${value}")
        }
    }

    private static void requireColumnExpression(String value, String label) {
        requireSafeFragment(value, label)
        if (!COLUMN_EXPRESSION.matcher(value.trim()).matches()) {
            throw new IllegalArgumentException("${label} must be a column or a single aliased function " +
                    "call over columns, got: ${value}")
        }
    }

    private static void requireSafeFragment(String value, String label) {
        String raw = value == null ? "" : value
        for (String bad : FORBIDDEN_SEQUENCES) {
            if (raw.contains(bad)) throw new IllegalArgumentException("${label} may not contain '${bad}': ${raw}")
        }
        if (FORBIDDEN_KEYWORDS.matcher(raw).matches()) {
            throw new IllegalArgumentException("${label} may not contain a SQL keyword: ${raw}")
        }
        if (raw.count("'") % 2 != 0) {
            throw new IllegalArgumentException("${label} has an unbalanced quote: ${raw}")
        }
    }

    /** The SELECT list is exactly the mapped columns — see NsSuiteQlSourceQueryField's description. */
    static List<String> selectColumns(Map<String, Object> spec) {
        return ((List<Map<String, Object>>) spec.get("fields")).collect { (String) it.get("sourceColumn") }
    }

    /**
     * Half-open [from, to) on the configured date column, matching the OMS connector's
     * orderDateFrom/orderDateThru so consecutive windows cannot double-count a record sitting exactly
     * on the boundary. SuiteQL's BETWEEN is inclusive at both ends and is deliberately not used.
     *
     * NOTE trandate is DATE-granular and expressed in the NetSuite account's timezone, so windows
     * should be day-aligned; a sub-day window will not narrow the result.
     *
     * ORDER BY id is what makes offset paging safe — without a total order NetSuite may repeat or
     * skip rows across pages. It stays hardcoded on purpose: `id` is the internal key on every
     * SuiteQL table, and letting a config choose a non-unique sort would reintroduce exactly the
     * page-skew this prevents.
     */
    static String buildRecordsQuery(Map<String, Object> spec, String fromDate, String toDate) {
        requireIsoDate(fromDate, "fromDate")
        requireIsoDate(toDate, "toDate")
        String dateColumn = (String) spec.get("dateColumn")
        return "SELECT ${selectColumns(spec).join(', ')} FROM ${spec.get('fromTable')} " +
                "WHERE type = '${spec.get('recordType')}' " +
                "AND ${dateColumn} >= TO_DATE('${fromDate}', 'YYYY-MM-DD') " +
                "AND ${dateColumn} < TO_DATE('${toDate}', 'YYYY-MM-DD') " +
                "ORDER BY id"
    }

    /**
     * The dates are interpolated into SuiteQL text, so the format is enforced rather than escaped —
     * anything that is not exactly yyyy-MM-dd is refused before it can reach the query.
     */
    private static void requireIsoDate(String value, String label) {
        if (!(value ==~ /\d{4}-\d{2}-\d{2}/)) {
            throw new IllegalArgumentException("${label} must be yyyy-MM-dd, got: ${value}")
        }
    }

    /**
     * Projects one SuiteQL row onto the reconciliation record shape named by the spec.
     *
     * For the OMS order-presence pair the join key is custbody_hc_order_id — the field the HotWax
     * integration itself writes, so a match means "this NetSuite order came from that OMS order"
     * rather than "these two strings happen to be equal", which is all otherrefnum could ever mean.
     *
     * A row with no join-key value keeps a null there rather than being dropped: those are records
     * keyed in directly in NetSuite, and hiding them here would make the difference count
     * unexplainable. They are removed by a configured sourceFilters rule instead, so the exclusion is
     * the operator's decision and shows up in the run's filter reporting.
     */
    static Map<String, Object> mapRowToRecord(Map<String, Object> spec, Map<String, Object> row) {
        if (row == null) return [:]
        Map<String, Object> record = [:]
        for (Map<String, Object> field : (List<Map<String, Object>>) spec.get("fields")) {
            record.put((String) field.get("recordFieldName"), text(row.get(field.get("rowKey"))))
        }
        String originFieldName = (String) spec.get("originFieldName")
        if (originFieldName) {
            // Present on EVERY record on purpose. SourceFilterSupport keeps a record that lacks the
            // field it filters on ("exclude these values" cannot match an absent value), so a null
            // join key is not by itself excludable. This gives the operator a concrete value to write
            // a rule against: EXCLUDE_IN orderOrigin = NETSUITE_NATIVE.
            record.put(originFieldName,
                    row.get(spec.get("joinKeyRowKey")) != null ? ORIGIN_HOTWAX : ORIGIN_NETSUITE_NATIVE)
        }
        return record
    }

    /** SuiteQL decorates every row with a "links" array; it is transport metadata, not record data. */
    private static String text(Object value) {
        String s = value?.toString()?.trim()
        return s ? s : null
    }

    /**
     * Streams one window to `writer` as {"records":[...],"metadata":{...}} — records first so pages
     * are appended as they arrive and the counts are written once at the end. That ordering is the
     * same contract the OMS and Shopify getters write, and it is what lets a large window stream
     * rather than accumulate in memory. Consumers read this document by KEY, never positionally.
     *
     * `pageFetcher` is injected rather than called directly so the paging, projection and exclusion
     * logic is testable without a NetSuite account: it receives (query, limit, offset) and returns a
     * Map with `items` and `hasMore`.
     */
    static Map<String, Object> streamOrdersToWriter(Writer writer, Map<String, Object> options, Closure pageFetcher) {
        Map<String, Object> spec = (Map<String, Object>) options.get("spec")
        if (spec == null) throw new IllegalArgumentException("a NsSuiteQlSourceQuery spec is required")
        String fromDate = (String) options.get("fromDate")
        String toDate = (String) options.get("toDate")
        int pageSize = normalizePageSize((Integer) options.get("pageSize"))
        int maxPages = options.get("maxPages") != null ? ((Number) options.get("maxPages")).intValue() : DEFAULT_MAX_PAGES
        List<Map<String, Object>> rules = SourceFilterSupport.parseRules(options.get("filterRules"))
        List<String> keepFields = normalizeKeepFields(options.get("keepFields"))

        String query = buildRecordsQuery(spec, fromDate, toDate)
        List<String> warnings = []
        int recordCount = 0
        int excludedCount = 0
        int pageCount = 0
        int offset = 0
        boolean hasMore = true

        writer.write('{"records":[')
        while (hasMore && pageCount < maxPages) {
            Map page = (Map) pageFetcher.call(query, pageSize, offset)
            pageCount++
            List items = (List) (page?.get("items") ?: [])
            for (Object rawRow : items) {
                Map<String, Object> record = mapRowToRecord(spec, (Map<String, Object>) rawRow)
                // Exclusions run client-side because SuiteQL has no knowledge of tenant rules, and
                // they run AFTER mapping so a rule names the reconciliation field an operator sees
                // (orderOrigin), not the raw NetSuite column.
                if (SourceFilterSupport.firstMatchingRule(record, rules) != null) { excludedCount++; continue }
                // Projection runs AFTER exclusion so a rule can filter on a field the caller did not
                // ask to keep — otherwise narrowing the output would silently disable the rule.
                if (keepFields != null) record = project(record, keepFields)
                if (recordCount > 0) writer.write(',')
                writer.write(JsonOutput.toJson(record))
                recordCount++
            }
            hasMore = page?.get("hasMore") == true
            offset += pageSize
        }
        if (hasMore && pageCount >= maxPages) {
            warnings.add("Stopped after the ${maxPages} page ceiling with more results reported; ".toString() +
                    "narrow the window or raise maxPages.")
        }
        writer.write('],"metadata":')
        writer.write(JsonOutput.toJson([
                source       : "NETSUITE_SUITEQL",
                recordType   : spec.get("recordType"),
                query        : query,
                windowFrom   : fromDate,
                windowThru   : toDate,
                pageSize     : pageSize,
                pageCount    : pageCount,
                recordCount  : recordCount,
                excludedCount: excludedCount,
                warnings     : warnings,
        ]))
        writer.write('}')
        writer.flush()

        return [
                recordCount  : recordCount,
                excludedCount: excludedCount,
                pageCount    : pageCount,
                dataAvailable: recordCount > 0,
                warnings     : warnings,
        ] as Map<String, Object>
    }

    private static List<String> normalizeKeepFields(Object raw) {
        if (!(raw instanceof List)) return null
        List<String> fields = ((List) raw).collect { it?.toString()?.trim() }.findAll { it } as List<String>
        return fields ? fields : null
    }

    private static Map<String, Object> project(Map<String, Object> record, List<String> keepFields) {
        Map<String, Object> out = [:]
        for (String f : keepFields) if (record.containsKey(f)) out.put(f, record.get(f))
        return out
    }

    /**
     * Normalises a window bound to the yyyy-MM-dd SuiteQL needs. Dispatch may hand this through as a
     * Timestamp, ISO-8601 text, SQL timestamp text or epoch millis depending on the caller.
     *
     * TIMEZONE: the date is taken in UTC, while NetSuite's trandate is stored in the ACCOUNT's
     * timezone. For a day-aligned window the two agree; for an account far from UTC the boundary day
     * can differ by one, so windows should be day-aligned and given a day of slack rather than
     * treated as exact. Narrowing this properly needs the account timezone, which NetSuite does not
     * expose on the auth profile.
     *
     * An unparseable bound throws rather than defaulting: a silently substituted window would run
     * against a period nobody asked for and report the result as authoritative.
     */
    static String toSuiteQlDate(Object value) {
        if (value == null) throw new IllegalArgumentException("window bound is required")

        // A genuine INSTANT (epoch millis, or a Date that is not a wall-clock Timestamp) has no
        // wall-clock meaning of its own, so it is read in UTC.
        if (value instanceof Number) {
            return LocalDate.ofInstant(Instant.ofEpochMilli(((Number) value).longValue()), ZoneOffset.UTC).toString()
        }
        // java.sql.Timestamp is a WALL-CLOCK value: Timestamp.valueOf("2026-08-01 00:00:00") means
        // midnight as written, not an instant. Pushing it through a zone moves the window boundary by
        // a day for any operator east or west of UTC, which is exactly how a window silently starts
        // reporting the wrong day's orders as missing.
        if (value instanceof java.sql.Timestamp) {
            return ((java.sql.Timestamp) value).toLocalDateTime().toLocalDate().toString()
        }
        if (value instanceof java.util.Date) {
            return LocalDate.ofInstant(Instant.ofEpochMilli(((java.util.Date) value).getTime()), ZoneOffset.UTC).toString()
        }

        String raw = value.toString().trim()
        if (!raw) throw new IllegalArgumentException("window bound is required")
        if (raw ==~ /\d{4}-\d{2}-\d{2}/) return raw
        // Zone-bearing text is an instant; zone-less text is wall clock. Try the zoned form first so
        // an explicit Z or offset is honoured, then fall back to reading the date as written.
        try {
            return LocalDate.ofInstant(OffsetDateTime.parse(raw).toInstant(), ZoneOffset.UTC).toString()
        } catch (Exception ignored) { }
        try {
            return LocalDateTime.parse(raw).toLocalDate().toString()
        } catch (Exception ignored) { }
        try {
            return java.sql.Timestamp.valueOf(raw).toLocalDateTime().toLocalDate().toString()
        } catch (Exception ignored) { }
        throw new IllegalArgumentException("Cannot read '${raw}' as a window bound; expected yyyy-MM-dd, " +
                "ISO-8601, a SQL timestamp or epoch millis")
    }

    static int normalizePageSize(Integer requested) {
        if (requested == null || requested <= 0) return MAX_PAGE_SIZE
        return Math.min(requested.intValue(), MAX_PAGE_SIZE)
    }
}
