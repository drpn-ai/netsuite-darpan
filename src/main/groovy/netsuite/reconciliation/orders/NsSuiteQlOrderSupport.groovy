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

    /**
     * VETTED QUERY TEMPLATES (DAR-BE-053). The SHAPES live here, in code; configuration picks one and
     * names its record types.
     *
     * WHY NOT A QUERY COLUMN. Every within-NetSuite chain check is an anti-join across the transaction
     * LINK TABLE, which the assembled-parts model had no slot for. But DAR-BE-044 refused raw query text
     * for two reasons that still hold: the window bounds are interpolated as TEXT rather than bound as
     * parameters, and `ORDER BY id` is what makes offset paging safe. A free-form SELECT would put both
     * in operator hands. Templates keep every interpolated fragment identifier-checked and keep the
     * ordering out of configuration, at the cost of only ever expressing shapes that were shipped.
     */
    static final String TEMPLATE_RECORDS = "RECORDS"
    /** Parent rows with NO linked child of a given type — "orders that never shipped". */
    static final String TEMPLATE_LINKED_CHILD_ABSENT = "LINKED_CHILD_ABSENT"
    /** Parent rows WITH one linked child type and WITHOUT another — "shipped but never billed". */
    static final String TEMPLATE_LINKED_CHILD_PRESENT_ABSENT = "LINKED_CHILD_PRESENT_ABSENT"
    /**
     * STATE_CONTRADICTION asks the question the other templates only approximate: does the order's own
     * STATUS agree with its transaction chain? The status is NetSuite's claim about state and the chain
     * is the evidence; a finding is a disagreement, not merely a missing child.
     *
     * Why that matters, measured on gorjana 2026-09-29 over two days and 8,882 orders: a plain
     * "no ItemShip" check returned 1042 findings for yesterday, every one status B — orders NetSuite
     * itself records as Pending Fulfillment, with 0 past their promised ship date. Nothing was wrong
     * with any of them. Meanwhile 53 (Aug 1) and 26 (Sep 28) orders sat at status G — invoiced in
     * 100% of 8,882 cases, so unambiguously Billed — with no fulfillment at all, and the line-level
     * triage gate was discarding exactly those as "no fulfillable line".
     */
    static final String TEMPLATE_STATE_CONTRADICTION = "STATE_CONTRADICTION"
    private static final List<String> QUERY_TEMPLATES =
            [TEMPLATE_RECORDS, TEMPLATE_LINKED_CHILD_ABSENT, TEMPLATE_LINKED_CHILD_PRESENT_ABSENT,
             TEMPLATE_STATE_CONTRADICTION].asImmutable()
    private static final List<String> LINK_TEMPLATES =
            [TEMPLATE_LINKED_CHILD_ABSENT, TEMPLATE_LINKED_CHILD_PRESENT_ABSENT].asImmutable()

    /**
     * Transaction-to-transaction linkage lives in a link table, NOT in a `createdfrom` column on
     * `transaction` — NetSuite answers `Unknown identifier 'createdfrom'` there (measured against gorjana
     * 2026-09-17). Hardcoded rather than configured: it is the data model, not a choice.
     */
    private static final String LINK_TABLE = "nexttransactionlink"
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
        // TIGHTENED FROM requireSafeFragment TO requireIdentifier (DAR-BE-053). A "safe fragment" allows
        // BALANCED quotes and does not forbid OR, so `ItemShip' OR '1'='1` passed every check and
        // widened `type = '<recordType>'` to every transaction type in the account — a read-only scope
        // escalation from an operator-editable field. Every NetSuite record-type code is alphanumeric
        // (SalesOrd, ItemShip, CustInvc, RtnAuth, CustCred, CustRfnd, CashSale, ItemRcpt), so the
        // stricter check refuses nothing legitimate.
        requireIdentifier(recordType, "recordType")

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

        String queryTemplate = (text(query.get("queryTemplate")) ?: TEMPLATE_RECORDS).toUpperCase()
        if (!QUERY_TEMPLATES.contains(queryTemplate)) {
            // Deliberately not defaulting to RECORDS: a typo'd template would silently run a plain
            // windowed extract and report every row as a finding.
            throw new IllegalArgumentException("NsSuiteQlSourceQuery ${query.get('nsSuiteQlSourceQueryId')} " +
                    "has unsupported queryTemplate ${query.get('queryTemplate')}. Supported values: ${QUERY_TEMPLATES.join(', ')}")
        }
        boolean linkTemplate = LINK_TEMPLATES.contains(queryTemplate)

        String absentChildRecordType = text(query.get("absentChildRecordType"))
        String presentChildRecordType = text(query.get("presentChildRecordType"))
        if (linkTemplate) {
            // The link table relates TRANSACTIONS. Pointing a link template at another table assembles
            // a query that parses and means nothing.
            if (fromTable != DEFAULT_FROM_TABLE) {
                throw new IllegalArgumentException("queryTemplate ${queryTemplate} reads the ${LINK_TABLE} " +
                        "link table, which relates transactions, so fromTable must be ${DEFAULT_FROM_TABLE}, got: ${fromTable}")
            }
            if (!absentChildRecordType) {
                throw new IllegalArgumentException("queryTemplate ${queryTemplate} requires absentChildRecordType")
            }
            requireIdentifier(absentChildRecordType, "absentChildRecordType")
        } else if (absentChildRecordType) {
            throw new IllegalArgumentException("absentChildRecordType is only meaningful on " +
                    "${LINK_TEMPLATES.join(' or ')}; queryTemplate is ${queryTemplate}")
        }
        if (queryTemplate == TEMPLATE_LINKED_CHILD_PRESENT_ABSENT) {
            if (!presentChildRecordType) {
                throw new IllegalArgumentException("queryTemplate ${queryTemplate} requires presentChildRecordType")
            }
            requireIdentifier(presentChildRecordType, "presentChildRecordType")
        } else if (presentChildRecordType) {
            // Rejected rather than ignored: silently dropping it is how an operator comes to believe a
            // filter is applied that is not.
            throw new IllegalArgumentException("presentChildRecordType is only meaningful on " +
                    "${TEMPLATE_LINKED_CHILD_PRESENT_ABSENT}; queryTemplate is ${queryTemplate}")
        }
        boolean requireFulfillableOpenLine = "Y".equalsIgnoreCase(text(query.get("requireFulfillableOpenLine")) ?: "N")
        // Read as a VALUE, never for truth: "N" is a non-empty String and therefore truthy in Groovy.
        // Status codes reach interpolated SQL, so they are format-enforced rather than escaped — the
        // same decision recordType took after `ItemShip' OR '1'='1` widened a type predicate.
        List<String> pendingStates = parseStatusList(query.get("pendingStates"), "pendingStates")
        List<String> statesExpectingShipment = parseStatusList(query.get("statesExpectingShipment"), "statesExpectingShipment")
        List<String> statesExpectingInvoice = parseStatusList(query.get("statesExpectingInvoice"), "statesExpectingInvoice")
        List<String> disallowedStates = parseStatusList(query.get("disallowedStates"), "disallowedStates")
        if (queryTemplate == TEMPLATE_STATE_CONTRADICTION && pendingStates.isEmpty()
                && statesExpectingShipment.isEmpty() && statesExpectingInvoice.isEmpty()
                && disallowedStates.isEmpty()) {
            // With no expectations every order matches, which is not a reconciliation.
            throw new IllegalArgumentException("${TEMPLATE_STATE_CONTRADICTION} needs at least one of " +
                    "pendingStates, statesExpectingShipment, statesExpectingInvoice or disallowedStates; " +
                    "with none set it would report every order in the window")
        }
        if (queryTemplate != TEMPLATE_STATE_CONTRADICTION
                && (pendingStates || statesExpectingShipment || statesExpectingInvoice || disallowedStates)) {
            throw new IllegalArgumentException("status expectations are only meaningful on " +
                    "${TEMPLATE_STATE_CONTRADICTION}; queryTemplate is ${queryTemplate}")
        }

        List<String> overdueExcludedShipMethods = parseShipMethodList(
                query.get("overdueExcludedShipMethods"), "overdueExcludedShipMethods")

        boolean requireOverdueShipDate = "Y".equalsIgnoreCase(text(query.get("requireOverdueShipDate")) ?: "N")
        if (requireOverdueShipDate && !(queryTemplate in LINK_TEMPLATES)) {
            // Refused rather than ignored, like the child types above: silently dropping a gate is how
            // an operator comes to believe a run is filtered when it is not.
            throw new IllegalArgumentException("requireOverdueShipDate is only meaningful on " +
                    "${LINK_TEMPLATES.join(' or ')}; queryTemplate is ${queryTemplate}")
        }

        // A SCOPE qualifier rather than an expectation: it says which records the check is about
        // ("invoices that came from a sales order"), where the state lists say what is wrong with
        // them. Only STATE_CONTRADICTION reads it today; refused elsewhere rather than ignored, the
        // same call taken for requireOverdueShipDate.
        String requireParentRecordType = text(query.get("requireParentRecordType"))
        if (requireParentRecordType) {
            requireIdentifier(requireParentRecordType, "requireParentRecordType")
            if (queryTemplate != TEMPLATE_STATE_CONTRADICTION) {
                throw new IllegalArgumentException("requireParentRecordType is only meaningful on " +
                        "${TEMPLATE_STATE_CONTRADICTION}; queryTemplate is ${queryTemplate}")
            }
        }

        // A FLOOR, not an expectation: below it the record is not worth an operator's attention.
        // Measured on gorjana 2026-09-01, 257 of 272 open invoices owed under $1 — rounding
        // residue, the same false-positive floor the exchange exclusion has. Column and threshold
        // are required together; a threshold with no column would silently gate nothing.
        String amountColumn = text(query.get("amountColumn"))
        String minAmount = text(query.get("minAmount"))
        if (amountColumn || minAmount) {
            if (!amountColumn) {
                throw new IllegalArgumentException("minAmount ${minAmount} needs amountColumn: " +
                        "a threshold with no column to read would gate nothing and report success")
            }
            if (!minAmount) {
                throw new IllegalArgumentException("amountColumn ${amountColumn} needs minAmount: " +
                        "naming a column with no floor would gate nothing and report success")
            }
            requireIdentifier(amountColumn, "amountColumn")
            if (!(minAmount ==~ /\d{1,12}(\.\d{1,4})?/)) {
                throw new IllegalArgumentException("minAmount must be a non-negative decimal " +
                        "(it is interpolated into SuiteQL, not bound), got: ${minAmount}")
            }
            if (queryTemplate != TEMPLATE_STATE_CONTRADICTION) {
                throw new IllegalArgumentException("amountColumn/minAmount are only meaningful on " +
                        "${TEMPLATE_STATE_CONTRADICTION}; queryTemplate is ${queryTemplate}")
            }
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
                queryTemplate         : queryTemplate,
                absentChildRecordType : absentChildRecordType,
                presentChildRecordType: presentChildRecordType,
                requireFulfillableOpenLine: requireFulfillableOpenLine,
                requireOverdueShipDate    : requireOverdueShipDate,
                pendingStates             : pendingStates,
                statesExpectingShipment   : statesExpectingShipment,
                statesExpectingInvoice    : statesExpectingInvoice,
                disallowedStates          : disallowedStates,
                requireParentRecordType   : requireParentRecordType,
                amountColumn              : amountColumn,
                minAmount                 : minAmount,
                overdueExcludedShipMethods: overdueExcludedShipMethods,
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
    static String buildRecordsQuery(Map<String, Object> spec, String fromDate, String toDate, String asOfDate = null) {
        requireIsoDate(fromDate, "fromDate")
        requireIsoDate(toDate, "toDate")
        String dateColumn = (String) spec.get("dateColumn")
        String template = (String) (spec.get("queryTemplate") ?: TEMPLATE_RECORDS)

        // RECORDS stays BYTE-IDENTICAL to what shipped for DAR-BE-032/044, alias and all (there is
        // none). Every stored row predates the template field, so a default that even reformatted this
        // query would change an extract already proven against gorjana production.
        if (template == TEMPLATE_RECORDS) {
            return "SELECT ${selectColumns(spec).join(', ')} FROM ${spec.get('fromTable')} " +
                    "WHERE type = '${spec.get('recordType')}' " +
                    "AND ${dateColumn} >= TO_DATE('${fromDate}', 'YYYY-MM-DD') " +
                    "AND ${dateColumn} < TO_DATE('${toDate}', 'YYYY-MM-DD') " +
                    "ORDER BY id"
        }

        if (template == TEMPLATE_STATE_CONTRADICTION) {
            return buildStateContradictionQuery(spec, fromDate, toDate, asOfDate, dateColumn)
        }

        StringBuilder sql = new StringBuilder()
        sql << "SELECT ${selectColumns(spec).join(', ')} FROM ${spec.get('fromTable')} t "
        sql << "WHERE t.type = '${spec.get('recordType')}' "
        sql << "AND t.${dateColumn} >= TO_DATE('${fromDate}', 'YYYY-MM-DD') "
        sql << "AND t.${dateColumn} < TO_DATE('${toDate}', 'YYYY-MM-DD') "
        if (template == TEMPLATE_LINKED_CHILD_PRESENT_ABSENT) {
            sql << "AND ${linkedChildPredicate(true, (String) spec.get('presentChildRecordType'), '')} "
        }
        sql << "AND NOT ${linkedChildPredicate(false, (String) spec.get('absentChildRecordType'), template == TEMPLATE_LINKED_CHILD_PRESENT_ABSENT ? '2' : '')} "
        if (spec.get("requireFulfillableOpenLine")) sql << "AND ${FULFILLABLE_OPEN_LINE_PREDICATE} "
        if (spec.get("requireOverdueShipDate")) {
            if (!asOfDate) {
                throw new IllegalArgumentException("requireOverdueShipDate needs an asOfDate: the gate " +
                        "compares shipdate against the day the run is taken, and omitting it would " +
                        "silently drop the gate rather than fail")
            }
            requireIsoDate(asOfDate, "asOfDate")
            sql << "AND t.shipdate IS NOT NULL AND t.shipdate < TO_DATE('${asOfDate}', 'YYYY-MM-DD') "
        }
        sql << "ORDER BY t.id"
        return sql.toString()
    }

    /**
     * "Is there ANY link from this parent to a child of this type."
     *
     * NO LINKTYPE FILTER, deliberately: a stage is reachable by SEVERAL linktypes — gorjana's
     * fulfillments arrive as both ShipRcpt and KitShip — so pinning one silently drops 48 kit shipments
     * a day. And the subquery is NOT windowed: the child transaction is dated later than its parent
     * (fulfillment p99 4 days, invoice p99 36), so bounding it would report every order near the end of
     * the window as having no child, which is the false positive this feature exists to avoid.
     */
    /**
     * Does the order's status agree with its chain?
     *
     * <p>Three branches, each a different disagreement, OR-ed together. A status named in no list is
     * not reported at all — that is how H (closed with neither child 39 times in 55, i.e. cancelled)
     * stays out without needing an exclusion rule.
     *
     * <p>Only the pending branch is time-sensitive. An order at a post-billing status with no
     * fulfillment is wrong today; an order still pending is wrong only once it passes the date
     * NetSuite itself promised.
     */
    private static String buildStateContradictionQuery(Map<String, Object> spec, String fromDate,
                                                       String toDate, String asOfDate, String dateColumn) {
        List<String> pending = (List<String>) spec.get("pendingStates")
        List<String> expectShipment = (List<String>) spec.get("statesExpectingShipment")
        List<String> expectInvoice = (List<String>) spec.get("statesExpectingInvoice")
        // Wrong on its own terms rather than in disagreement with the chain: "nothing should sit in
        // Pending Billing" holds whether or not an invoice exists, so this is the one expectation
        // here that needs no chain lookup at all.
        List<String> disallowed = (List<String>) spec.get("disallowedStates")
        String noShipment = "NOT " + linkedChildPredicate(false, "ItemShip", "")
        String noInvoice = "NOT " + linkedChildPredicate(false, "CustInvc", "2")

        if (pending && !asOfDate) {
            throw new IllegalArgumentException("pendingStates needs an asOfDate: those rows are a " +
                    "finding only once past the promised ship date, and omitting it would report every " +
                    "pending order in the window")
        }
        if (asOfDate) requireIsoDate(asOfDate, "asOfDate")

        // Collected-in-store orders have no ship deadline to miss. A NOT EXISTS rather than a join so
        // the FROM stays single-table like every other predicate here. Pending branch ONLY: a picked-up
        // order is still fulfilled and still invoiced, and excluding it from those would hide real
        // failures behind a delivery choice.
        List<String> excludedMethods = (List<String>) spec.get("overdueExcludedShipMethods")
        String notCollected = excludedMethods
                ? " AND NOT EXISTS (SELECT 1 FROM shipitem sm WHERE sm.id = t.shipmethod " +
                  "AND sm.itemid IN ${inList(excludedMethods)})"
                : ""

        // The shipment branch alone carries the line gate. An order with nothing shippable on it was
        // never going to have a fulfillment — measured: every "billed, not shipped" finding on
        // 2026-08-01 was a gift-card order, and NetSuite's own fulfillable flag already separates
        // them (6803 of 6856 status-G orders had an open fulfillable line; the 53 that did not were
        // exactly those findings). Reading the flag beats naming the SKU: it generalises to every
        // unshippable item and does not rot when the item id changes.
        String shippableLine = spec.get("requireFulfillableOpenLine") ? " AND ${FULFILLABLE_OPEN_LINE_PREDICATE}" : ""

        // Condition, code and wording travel together so a branch cannot reach the WHERE without a
        // matching label. They were three parallel lists before, which is how the first cut of this
        // template shipped a CASE whose ELSE claimed NOT_INVOICED for rows that had reached the
        // result some other way.
        //
        // Wording is deliberately generic across each status list — "billed, never shipped" reads
        // better but is only true for D and G, and would be a lie on E and F. It has to stay generic
        // across RECORD TYPES too: this template ran only on sales orders until NS_INVOICE_OPEN, and
        // the STATUS_NOT_ALLOWED brief said "Order is in a status ..." on a CustInvc for exactly one
        // live run. Name no noun the config can contradict. The brief is written
        // by the QUERY rather than composed in the UI, so it is frozen into the run document: a
        // result re-opened in six months says what it said the day it ran.
        List<List<String>> findings = []
        if (pending) {
            findings.add([("t.status IN ${inList(pending)} AND t.shipdate IS NOT NULL " +
                    "AND t.shipdate < TO_DATE('${asOfDate}', 'YYYY-MM-DD') AND ${noShipment}" +
                    "${notCollected}").toString(),
                          "OVERDUE_NOT_SHIPPED", "Past its ship date, not shipped"])
        }
        if (expectShipment) {
            findings.add(["t.status IN ${inList(expectShipment)} AND ${noShipment}${shippableLine}".toString(),
                          "NOT_SHIPPED", "Status expects a shipment; none recorded"])
        }
        if (expectInvoice) {
            findings.add(["t.status IN ${inList(expectInvoice)} AND ${noInvoice}".toString(),
                          "NOT_INVOICED", "Status expects an invoice; none recorded"])
        }
        // Last, so that where it overlaps a chain expectation the more specific reason wins the
        // label: "billed but never shipped" tells an operator what to go fix, where "should not be
        // in this status" only tells them where it is sitting.
        if (disallowed) {
            findings.add(["t.status IN ${inList(disallowed)}".toString(),
                          "STATUS_NOT_ALLOWED", "In a status it should never rest in"])
        }

        List<String> branches = findings.collect { "(${it[0]})".toString() }

        // Codes are code-owned, not configurable: a finding that does not say WHICH contradiction it
        // is leaves the operator to re-derive it from the status, and the config layer cannot express
        // a CASE anyway (COLUMN_EXPRESSION allows a column or one aliased function call).
        //
        // A single-branch query needs no CASE at all — and must not emit one, since `CASE ELSE x END`
        // is not SQL. Otherwise every branch but the last is a WHEN and the last is the ELSE, which
        // keeps the generated SQL the same shape it had before disallowedStates existed.
        String contradiction = caseOver(findings, 1)
        String briefColumn = caseOver(findings, 2)

        StringBuilder sql = new StringBuilder()
        sql << "SELECT ${selectColumns(spec).join(', ')}, ${contradiction} AS contradiction, " +
                "${briefColumn} AS contradiction_brief "
        sql << "FROM ${spec.get('fromTable')} t "
        sql << "WHERE t.type = '${spec.get('recordType')}' "
        sql << "AND t.${dateColumn} >= TO_DATE('${fromDate}', 'YYYY-MM-DD') "
        sql << "AND t.${dateColumn} < TO_DATE('${toDate}', 'YYYY-MM-DD') "
        String parentType = (String) spec.get("requireParentRecordType")
        if (parentType) sql << "AND ${linkedParentPredicate(parentType)} "
        // IS NOT NULL is redundant against SQL's three-valued logic — `NULL >= 1` is unknown and
        // already drops the row — but written out so which way the gate falls is readable without
        // knowing that. gorjana has no null balances today, so the behaviour is otherwise untested.
        String amountCol = (String) spec.get("amountColumn")
        if (amountCol) {
            sql << "AND t.${amountCol} IS NOT NULL AND t.${amountCol} >= ${spec.get('minAmount')} "
        }
        sql << "AND (${branches.join(' OR ')}) "
        sql << "ORDER BY t.id"
        return sql.toString()
    }

    /**
     * Renders one of the parallel label columns: the value at {@code slot} in each finding triple,
     * keyed by that finding's condition. Every branch but the last becomes a WHEN and the last
     * becomes the ELSE, so a row in the result always leaves with a reason.
     */
    private static String caseOver(List<List<String>> findings, int slot) {
        if (findings.size() == 1) return "'${findings[0][slot]}'".toString()
        List<String> whens = findings[0..-2].collect { "WHEN ${it[0]} THEN '${it[slot]}'".toString() }
        return "CASE ${whens.join(' ')} ELSE '${findings[-1][slot]}' END".toString()
    }

    /**
     * Shipping method NAMES, which carry spaces ("Store Pickup") and so cannot use the identifier
     * check the status codes use. Still a whitelist rather than escaping: these are interpolated into
     * SuiteQL text, and a quote or semicolon is refused outright.
     */
    private static List<String> parseShipMethodList(Object raw, String label) {
        String value = text(raw)
        if (!value) return []
        return value.split(",").collect { it.trim() }.findAll { it }.collect { String name ->
            if (!(name ==~ /[A-Za-z0-9][A-Za-z0-9 _\-]{0,40}/)) {
                throw new IllegalArgumentException("${label} must be comma-separated shipping method " +
                        "names (letters, digits, spaces, dashes), got: ${name}")
            }
            return name
        }
    }

    /** Plain NetSuite status codes only: letters and digits, comma separated. */
    private static List<String> parseStatusList(Object raw, String label) {
        String value = text(raw)
        if (!value) return []
        return value.split(",").collect { it.trim() }.findAll { it }.collect { String code ->
            if (!(code ==~ /[A-Za-z0-9]{1,8}/)) {
                throw new IllegalArgumentException("${label} must be comma-separated status codes, got: ${code}")
            }
            return code.toUpperCase()
        }
    }

    private static String inList(List<String> codes) {
        return "(" + codes.collect { "'${it}'" }.join(",") + ")"
    }

    /**
     * The link read backwards: nextdoc is the record under test, previousdoc is what produced it.
     * Alias suffix 0 because l/c and l2/c2 are already taken by the child predicates, and a silent
     * alias collision inside a correlated subquery changes which row it tests.
     */
    private static String linkedParentPredicate(String parentRecordType) {
        return "EXISTS (SELECT 1 FROM ${LINK_TABLE} l0 " +
                "JOIN transaction p ON p.id = l0.previousdoc " +
                "WHERE l0.nextdoc = t.id AND p.type = '${parentRecordType}')"
    }

    private static String linkedChildPredicate(boolean present, String childRecordType, String suffix) {
        String link = "l${suffix}", child = "c${suffix}"
        return "EXISTS (SELECT 1 FROM ${LINK_TABLE} ${link} " +
                "JOIN transaction ${child} ON ${child}.id = ${link}.nextdoc " +
                "WHERE ${link}.previousdoc = t.id AND ${child}.type = '${childRecordType}')"
    }

    /**
     * THE TRIAGE GATE, and it is the difference between 97 findings and 5 (measured, gorjana 2026-09-17).
     * An order with no fulfillment is not a defect if no line was ever fulfillable — service and
     * non-inventory orders can never have one — or if its lines were deliberately closed. Both are read
     * from the line, which is why this cannot be a post-extraction sourceFilters rule: a filter can only
     * match a field the extractor already emitted.
     */
    /**
     * THE OVERDUE GATE. "Has no fulfillment" and "is late" are the same question only at distance: run
     * over a day eight weeks back the answer was 4, run over YESTERDAY it was 1042 — every one status
     * B, a whole day's order book that simply had not shipped yet. Measured lag is p50 0 / p95 2 /
     * max 15 days, so any recent window is mostly pipeline.
     *
     * shipdate is the date NetSuite itself committed to, which beats trandate-plus-a-guessed-lag: it
     * is the business's own promise rather than our estimate of one. A null shipdate is NOT overdue —
     * an order nobody promised a date for cannot be late.
     */
    private static final String FULFILLABLE_OPEN_LINE_PREDICATE =
            "EXISTS (SELECT 1 FROM transactionline tl WHERE tl.transaction = t.id " +
                    "AND tl.fulfillable = 'T' AND tl.isclosed = 'F')"

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
    /**
     * One metadata entry per configured rule, including rules that rejected nothing.
     *
     * This connector reported only a flat excludedCount until DAR-BE-054, which made "the rule
     * matched nothing" and "this build predates the rule" the same observation — the ambiguity that
     * made a withdrawn Shopify pill undiagnosable for a day. Mirrors the shape the OMS and Shopify
     * getters emit, so one diagnosis works across all four.
     */
    protected static List<Map<String, Object>> buildConfiguredExclusions(
            List<Map<String, Object>> rules,
            Map<Integer, Integer> excludedByRuleCounts,
            Map<Integer, Integer> fieldAbsentByRuleCounts) {
        return (rules ?: []).collect { Map<String, Object> rule ->
            Object sequenceNum = rule.get("sequenceNum")
            return [
                    sequenceNum     : sequenceNum,
                    fieldExpression : rule.get("fieldExpression"),
                    operator        : rule.get("operator"),
                    values          : new ArrayList<String>((List<String>) rule.get("values")),
                    excludedCount   : (excludedByRuleCounts?.get(sequenceNum) ?: 0),
                    fieldAbsentCount: (fieldAbsentByRuleCounts?.get(sequenceNum) ?: 0),
            ] as Map<String, Object>
        } as List<Map<String, Object>>
    }

    /**
     * Row key -> record field for columns a template contributes itself. They are not in
     * NsSuiteQlSourceQueryField because an operator does not choose them: the template emits them or
     * it does not, and a projection that could omit them would let a run report findings with no
     * stated reason.
     */
    private static final Map<String, String> TEMPLATE_RECORD_COLUMNS = [
            contradiction      : "contradiction",
            contradiction_brief: "contradictionBrief",
    ].asImmutable()

    static Map<String, Object> mapRowToRecord(Map<String, Object> spec, Map<String, Object> row) {
        if (row == null) return [:]
        Map<String, Object> record = [:]
        for (Map<String, Object> field : (List<Map<String, Object>>) spec.get("fields")) {
            record.put((String) field.get("recordFieldName"), text(row.get(field.get("rowKey"))))
        }
        // Columns the TEMPLATE adds, as opposed to the ones an operator configured. Without this the
        // query selected `contradiction` and the record dropped it — 57 findings arrived with no
        // disagreement value at all, because mapping only walked the configured projection.
        TEMPLATE_RECORD_COLUMNS.each { String rowKey, String recordFieldName ->
            if (row.containsKey(rowKey)) record.put(recordFieldName, text(row.get(rowKey)))
        }

        String originFieldName = (String) spec.get("originFieldName")
        if (originFieldName) {
            // Present on EVERY record on purpose. An EXCLUDE_IN rule keeps a record that lacks the
            // field it filters on ("exclude these values" cannot match an absent value), so a null
            // join key is not by itself excludable. This gives the operator a concrete value to write
            // a rule against: EXCLUDE_IN orderOrigin = NETSUITE_NATIVE.
            // Since DAR-BE-054 an INCLUDE_IN rule DOES reject a record with no usable value, but that
            // is no reason to stop stamping this one — a rule reading "only HOTWAX" should say so on
            // its own terms, not work by accident because the field happened to be missing.
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

        // The day the run is taken, for the time-sensitive branches. Supplied by the caller rather
        // than read here from the clock: a query built from a hidden "now" is not reproducible, and
        // the same run replayed for an audit would quietly answer a different question.
        String asOfDate = (String) options.get("asOfDate")
        String query = buildRecordsQuery(spec, fromDate, toDate, asOfDate)
        List<String> warnings = []
        int recordCount = 0
        int excludedCount = 0
        Map<Integer, Integer> excludedByRuleCounts = [:]
        Map<Integer, Integer> fieldAbsentByRuleCounts = [:]
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
                Map<String, Object> verdict = SourceFilterSupport.evaluate(record, rules)
                if (verdict != null) {
                    Integer sequenceNum = (Integer) ((Map) verdict.get("rule")).get("sequenceNum")
                    Map<Integer, Integer> bucket = SourceFilterSupport.REASON_FIELD_ABSENT == verdict.get("reason")
                            ? fieldAbsentByRuleCounts
                            : excludedByRuleCounts
                    bucket.put(sequenceNum, (bucket.get(sequenceNum) ?: 0) + 1)
                    // The flat total keeps its existing meaning: every record the configured rules
                    // rejected, for any reason. Callers read it off the returned map.
                    excludedCount++
                    continue
                }
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
        Map<String, Object> metadata = [
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
        ] as Map<String, Object>
        // Absent entirely when no rules are configured, matching the OMS and Shopify getters: a
        // block that appeared on every extract would read as "this always applies".
        if (rules) {
            metadata.put("configuredExclusions",
                    buildConfiguredExclusions(rules, excludedByRuleCounts, fieldAbsentByRuleCounts))
        }
        writer.write(JsonOutput.toJson(metadata))
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
