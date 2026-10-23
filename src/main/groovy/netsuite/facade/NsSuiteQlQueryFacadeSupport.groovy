package netsuite.facade

import darpan.facade.common.SharedConfigAccessSupport
import groovy.json.JsonOutput
import groovy.transform.CompileDynamic
import netsuite.reconciliation.orders.NsSuiteQlOrderSupport

import java.time.LocalDate
import java.time.ZoneId

/**
 * DAR-BE-058: the SPA's read and write path for NetSuite SuiteQL check definitions.
 *
 * <p>Until this existed a check reached an environment only through {@code gradlew load} of a
 * hand-written entity-facade-xml, and the run rows only through a LOCAL test class. An environment
 * with no shell had no way to create a NetSuite check at all.</p>
 *
 * <p><b>Validation is by construction, not by restatement.</b> {@link #saveQuery} assembles the
 * same spec map the extractor assembles and hands it to
 * {@link NsSuiteQlOrderSupport#normalizeSpec}, the single complete statement of what makes a row
 * legal — record-type and column identifier formats, template membership, the cross-field rules
 * (a link template needs a child type, pendingStates only on STATE_CONTRADICTION, amountColumn and
 * minAmount only together), and that the projection is non-empty and its join key is one of the
 * mapped fields. A row that saves is therefore a row that runs.</p>
 *
 * <p>That matters more here than in most save paths. These values are interpolated into SuiteQL: a
 * mistyped record type does not fail, it reconciles a different leg of the chain and reports clean
 * forever. The formats are whitelists for the same reason — {@code ItemShip' OR '1'='1} once
 * widened a type predicate (DAR-BE-053).</p>
 */
@CompileDynamic
class NsSuiteQlQueryFacadeSupport {

    private static final String QUERY_ENTITY = "darpan.reconciliation.NsSuiteQlSourceQuery"
    private static final String FIELD_ENTITY = "darpan.reconciliation.NsSuiteQlSourceQueryField"

    /** Attributes copied verbatim between the entity row and the spec normalizeSpec reads. */
    private static final List<String> SPEC_FIELDS = [
            "recordType", "fromTable", "dateColumn", "queryTemplate",
            "absentChildRecordType", "presentChildRecordType",
            "requireFulfillableOpenLine", "requireOverdueShipDate",
            "pendingStates", "statesExpectingShipment", "statesExpectingInvoice",
            "disallowedStates", "overdueExcludedShipMethods", "requireParentRecordType",
            "amountColumn", "minAmount", "joinKeyFieldName", "originFieldName", "lineKeyColumn",
            // DAR-UI-064: a RECORDS_MATCHING query's conditions, JSON [{fieldId, op, values}]. The entity has carried
            // it since DAR-UI-050; without it here a query written in the app could not say which records it reads.
            "matchRules",
    ]

    /** DAR-UI-064: what the query editor offers. normalizeSpec still decides; these are the editor's choices. */
    static final List<String> EDITOR_TEMPLATES = [NsSuiteQlOrderSupport.TEMPLATE_RECORDS,
                                                  NsSuiteQlOrderSupport.TEMPLATE_RECORDS_MATCHING].asImmutable()
    static final List<String> EDITOR_DATE_COLUMNS = ["trandate", "lastmodifieddate", "createddate"].asImmutable()
    /** The common transaction types; any other identifier is still accepted and validated by normalizeSpec. */
    static final List<String> EDITOR_RECORD_TYPES = ["SalesOrd", "CustInvc", "CashSale", "CustCred", "CashRfnd",
                                                     "CustRfnd", "CustDep", "RtnAuth", "ItemShip", "ItemRcpt"].asImmutable()
    static final int PREVIEW_ROWS = 5

    /**
     * Tenant-scoped, through the shared finder. A bare {@code disableAuthz()} read here would list
     * every tenant's checks — and each row names an nsAuthConfigId, so the leak would be a map of
     * who holds credentials for which NetSuite account.
     */
    static Map<String, Object> listQueries(def ec, Object pageIndex = null, Object pageSize = null) {
        List<Map<String, Object>> rows = collectQueries(ec)
        int page = Math.max(0, (pageIndex ?: 0) as int)
        int size = Math.min(200, Math.max(1, (pageSize ?: 20) as int))
        return [
                queries   : darpan.facade.common.PaginationSupport.pageRows(rows, page, size),
                pagination: darpan.facade.common.PaginationSupport.pagination(page, size, rows.size()),
        ] as Map<String, Object>
    }

    private static List<Map<String, Object>> collectQueries(def ec) {
        List<Map<String, Object>> out = []
        darpan.facade.common.TenantScopedFinder.findTenantScoped(ec, QUERY_ENTITY)
                .useCache(false).list().each { row ->
            out.add([
                    nsSuiteQlSourceQueryId: row.nsSuiteQlSourceQueryId,
                    nsAuthConfigId        : row.nsAuthConfigId,
                    // Lets a caller check each row's owner as defence-in-depth on top of the
                    // tenant-scoped read, the way the other settings lists do.
                    companyUserGroupId    : row.companyUserGroupId,
                    description           : row.description,
                    recordType            : row.recordType,
                    queryTemplate         : row.queryTemplate,
                    isActive              : row.isActive,
                    fieldCount            : projectionOf(ec, row.nsSuiteQlSourceQueryId as String).size(),
            ] as Map<String, Object>)
        }
        return out.sort { it.nsSuiteQlSourceQueryId as String }
    }

    static Map<String, Object> getQuery(def ec, String queryId) {
        def row = one(ec, queryId)
        if (row == null) {
            ec.message.addError("NetSuite check '${queryId}' was not found.")
            return null
        }
        Map<String, Object> out = [nsSuiteQlSourceQueryId: row.nsSuiteQlSourceQueryId,
                                   nsAuthConfigId        : row.nsAuthConfigId,
                                   description           : row.description,
                                   isActive              : row.isActive] as Map<String, Object>
        // isField, not a bare get: EntityValue.get THROWS on an undeclared field, and lineKeyColumn
        // reaches the entity in a separate change from this one.
        SPEC_FIELDS.each { String f -> out.put(f, row.isField(f) ? row.get(f)?.toString() : null) }
        out.put("fields", projectionOf(ec, queryId).collect { f ->
            [recordFieldName: f.recordFieldName, sourceColumn: f.sourceColumn,
             sequenceNum    : f.sequenceNum] as Map<String, Object>
        })
        return out
    }

    /**
     * Create-or-replace. The projection is rewritten wholesale rather than merged: sequenceNum is
     * the only ordering, so a merge that left a stale row behind would silently add a column to
     * the SELECT — the kind of change nobody reviews because nobody asked for it.
     */
    static Map<String, Object> saveQuery(def ec, Map<String, Object> context) {
        String queryId = text(context.get("nsSuiteQlSourceQueryId"))
        List<Map<String, Object>> fields = normalizeSubmittedFields(context.get("fields"))
        // THE GATE. Anything the extractor would refuse is refused here, in the operator's words
        // rather than as a 500 halfway through a run against a client's account.
        if (validatedSpec(ec, queryId, context, fields) == null) return null

        if (!darpan.facade.common.TenantAccessSupport.requireActiveTenantWriteAccess(
                ec, "Your active tenant only has view access for NetSuite checks.")) {
            return null
        }

        def existing = one(ec, queryId)
        def row = existing ?: ec.entity.makeValue(QUERY_ENTITY)
        // A submitted value for a column this deployment's entity lacks is refused, not dropped: the
        // spec just validated WITH it, so dropping it would store a row that no longer runs.
        for (String f : SPEC_FIELDS) {
            if (!row.isField(f) && text(context.get(f))) {
                ec.message.addError("This server cannot store ${f} yet; the NetSuite check entity predates it.")
                return null
            }
        }
        row.set("nsSuiteQlSourceQueryId", queryId)
        row.set("nsAuthConfigId", text(context.get("nsAuthConfigId")))
        row.set("description", text(context.get("description")))
        row.set("isActive", asBool(context.get("isActive"), true) ? "Y" : "N")
        SPEC_FIELDS.each { String f -> if (row.isField(f)) row.set(f, entityValue(f, context.get(f))) }
        if (existing == null) {
            darpan.facade.common.TenantAccessSupport.assignTenantOwnershipOnCreate(row, ec)
            row.create()
        } else {
            row.update()
        }

        ec.entity.find(FIELD_ENTITY).condition("nsSuiteQlSourceQueryId", queryId)
                .disableAuthz().deleteAll()
        fields.eachWithIndex { Map<String, Object> f, int i ->
            def fieldRow = ec.entity.makeValue(FIELD_ENTITY)
            fieldRow.set("nsSuiteQlSourceQueryId", queryId)
            fieldRow.set("recordFieldName", f.get("recordFieldName"))
            fieldRow.set("sourceColumn", f.get("sourceColumn"))
            fieldRow.set("sequenceNum", i + 1)
            fieldRow.create()
        }

        return getQuery(ec, queryId)
    }

    /** The submitted values as the extractor's spec, through normalizeSpec; null (with the reason on ec.message) if refused. */
    private static Map<String, Object> validatedSpec(def ec, String queryId, Map<String, Object> context,
                                                     List<Map<String, Object>> fields) {
        if (fields.isEmpty()) {
            ec.message.addError("Add at least one column: a check with no projection selects nothing.")
            return null
        }
        Map<String, Object> spec = [nsSuiteQlSourceQueryId: queryId] as Map<String, Object>
        SPEC_FIELDS.each { String f -> spec.put(f, specValue(context.get(f))) }
        try {
            return NsSuiteQlOrderSupport.normalizeSpec(spec, fields)
        } catch (IllegalArgumentException e) {
            ec.message.addError(e.message)
            return null
        }
    }

    /**
     * DAR-UI-064. The editor's choices: templates, date columns, common record types, the fields a match rule may ask
     * about (any custbody_ field besides), its operators, and the NetSuite accounts this tenant may read.
     */
    static Map<String, Object> queryOptions(def ec) {
        List<Map<String, Object>> accounts = SharedConfigAccessSupport.listAccessibleConfigRows(ec, SharedConfigAccessSupport.CONFIG_TYPE_NS_AUTH)
                .findAll { (it.get("isActive") ?: "Y").toString() != "N" }
                .collect { [nsAuthConfigId: it.get("nsAuthConfigId"), description: it.get("description")] as Map<String, Object> }
                .sort { it.nsAuthConfigId as String }
        return [templates   : EDITOR_TEMPLATES, dateColumns: EDITOR_DATE_COLUMNS, recordTypes: EDITOR_RECORD_TYPES,
                matchFields : NsSuiteQlOrderSupport.MATCH_COLUMNS.keySet().toList(), matchOps: NsSuiteQlOrderSupport.MATCH_OPS,
                customFieldPattern: "custbody_...", accounts: accounts] as Map<String, Object>
    }

    /**
     * DAR-UI-064. Runs the submitted (not yet saved) query for one day and answers its first rows as records, plus the
     * SuiteQL it sent, so a typo shows before a run exists: a mistyped status does not fail, it reads a different
     * population forever (DAR-BE-058). Read-only, one page, against an account this tenant may read; nothing is stored.
     */
    static Map<String, Object> previewQuery(def ec, Map<String, Object> context) {
        List<Map<String, Object>> fields = normalizeSubmittedFields(context.get("fields"))
        Map<String, Object> spec = validatedSpec(ec, text(context.get("nsSuiteQlSourceQueryId")) ?: "PREVIEW", context, fields)
        if (spec == null) return null
        if (!darpan.facade.common.TenantAccessSupport.requireActiveTenantRunAccess(
                ec, "Your active tenant only has view access, so it cannot read NetSuite.")) {
            return null
        }
        LocalDate day
        try {
            day = text(context.get("day")) ? LocalDate.parse(text(context.get("day"))) : LocalDate.now(ZoneId.of(
                    darpan.facade.common.TenantAccessSupport.resolveActiveTenantTimeZone(ec) as String)).minusDays(1)
        } catch (Exception ignored) {
            ec.message.addError("The preview day must read YYYY-MM-DD.")
            return null
        }
        String sql = NsSuiteQlOrderSupport.buildRecordsQuery(spec, day.toString(), day.plusDays(1).toString(), day.toString())
        String authId = text(context.get("nsAuthConfigId"))
        def auth = authId ? SharedConfigAccessSupport.listAccessibleConfigRows(ec, SharedConfigAccessSupport.CONFIG_TYPE_NS_AUTH)
                .find { it.get("nsAuthConfigId") == authId } : null
        if (auth == null) {
            ec.message.addError("NetSuite account '${authId}' was not found.")
            return null
        }
        List<Map> rows
        try {
            rows = new NsSuiteQlClient(auth).query(sql, true)
        } catch (Exception e) {
            ec.message.addError("NetSuite refused the query: ${e.message}")
            return [day: day.toString(), sql: sql, records: [], more: false] as Map<String, Object>
        }
        return [day    : day.toString(), sql: sql,
                records: rows.take(PREVIEW_ROWS).collect { NsSuiteQlOrderSupport.mapRowToRecord(spec, (Map<String, Object>) it) },
                more   : rows.size() > PREVIEW_ROWS] as Map<String, Object>
    }

    /**
     * Submitted order wins over submitted sequenceNum, and the stored sequence is renumbered from
     * 1 on every save. A form that lets someone reorder rows cannot be trusted to keep its indices
     * dense, and a gap in sequenceNum is invisible until the projection comes out in a surprising
     * order.
     */
    private static List<Map<String, Object>> normalizeSubmittedFields(Object raw) {
        if (!(raw instanceof List)) return []
        List<Map<String, Object>> out = []
        ((List) raw).each { entry ->
            if (!(entry instanceof Map)) return
            Map m = (Map) entry
            String name = text(m.get("recordFieldName"))
            String column = text(m.get("sourceColumn"))
            if (!name && !column) return
            out.add([recordFieldName: name, sourceColumn: column,
                     sequenceNum    : out.size() + 1] as Map<String, Object>)
        }
        return out
    }

    /**
     * The projection carries no tenant column of its own; it is reached only through a parent this
     * class has already scoped, which is the same containment the entity model assumes.
     */
    private static List projectionOf(def ec, String queryId) {
        return ec.entity.find(FIELD_ENTITY).condition("nsSuiteQlSourceQueryId", queryId)
                .orderBy("sequenceNum").disableAuthz().useCache(false).list()
    }

    /**
     * Scoped the same way, so an id belonging to another tenant reads as "not found".
     *
     * <p>The QUIET variant deliberately: this is also the create-or-update existence check, and the
     * loud one posts "Requested record was not found." to ec.message — which on a create turns a
     * successful save into a response carrying an error. getQuery raises its own message when a
     * caller actually asked for a row that is missing.</p>
     */
    private static def one(def ec, String queryId) {
        if (!queryId) return null
        return darpan.facade.common.TenantScopedFinder
                .findTenantScopedByIdQuiet(ec, QUERY_ENTITY, "nsSuiteQlSourceQueryId", queryId)
    }

    /** Booleans reach the entity as Y/N; everything else as trimmed text or null. */
    private static Object entityValue(String field, Object value) {
        if (field == "requireFulfillableOpenLine" || field == "requireOverdueShipDate") {
            return asBool(value, false) ? "Y" : "N"
        }
        if (value instanceof Collection || value instanceof Map) return JsonOutput.toJson(value)
        return text(value)
    }

    /** normalizeSpec reads the same Y/N strings the entity stores, not Booleans. */
    private static Object specValue(Object value) {
        if (value instanceof Boolean) return ((Boolean) value) ? "Y" : "N"
        if (value instanceof Collection || value instanceof Map) return JsonOutput.toJson(value)
        return text(value)
    }

    private static boolean asBool(Object value, boolean fallback) {
        if (value == null) return fallback
        if (value instanceof Boolean) return (Boolean) value
        String s = value.toString().trim()
        if (!s) return fallback
        return s.equalsIgnoreCase("true") || s.equalsIgnoreCase("Y")
    }

    private static String text(Object value) {
        String s = value?.toString()?.trim()
        return s ?: null
    }
}
