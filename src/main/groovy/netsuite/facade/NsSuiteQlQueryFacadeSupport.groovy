package netsuite.facade

import groovy.transform.CompileDynamic
import netsuite.reconciliation.orders.NsSuiteQlOrderSupport

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
            "amountColumn", "minAmount", "joinKeyFieldName", "originFieldName",
    ]

    /**
     * Tenant-scoped, through the shared finder. A bare {@code disableAuthz()} read here would list
     * every tenant's checks — and each row names an nsAuthConfigId, so the leak would be a map of
     * who holds credentials for which NetSuite account.
     */
    static List<Map<String, Object>> listQueries(def ec) {
        List<Map<String, Object>> out = []
        darpan.facade.common.TenantScopedFinder.findTenantScoped(ec, QUERY_ENTITY)
                .useCache(false).list().each { row ->
            out.add([
                    nsSuiteQlSourceQueryId: row.nsSuiteQlSourceQueryId,
                    nsAuthConfigId        : row.nsAuthConfigId,
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
        SPEC_FIELDS.each { String f -> out.put(f, row.get(f)?.toString()) }
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
        if (fields.isEmpty()) {
            ec.message.addError("Add at least one column: a check with no projection selects nothing.")
            return null
        }

        Map<String, Object> spec = [nsSuiteQlSourceQueryId: queryId] as Map<String, Object>
        SPEC_FIELDS.each { String f -> spec.put(f, specValue(context.get(f))) }

        // THE GATE. Anything the extractor would refuse is refused here, in the operator's words
        // rather than as a 500 halfway through a run against a client's account.
        try {
            NsSuiteQlOrderSupport.normalizeSpec(spec, fields)
        } catch (IllegalArgumentException e) {
            ec.message.addError(e.message)
            return null
        }

        if (!darpan.facade.common.TenantAccessSupport.requireActiveTenantWriteAccess(
                ec, "Your active tenant only has view access for NetSuite checks.")) {
            return null
        }

        def existing = one(ec, queryId)
        def row = existing ?: ec.entity.makeValue(QUERY_ENTITY)
        row.set("nsSuiteQlSourceQueryId", queryId)
        row.set("nsAuthConfigId", text(context.get("nsAuthConfigId")))
        row.set("description", text(context.get("description")))
        row.set("isActive", asBool(context.get("isActive"), true) ? "Y" : "N")
        SPEC_FIELDS.each { String f -> row.set(f, entityValue(f, context.get(f))) }
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
        return text(value)
    }

    /** normalizeSpec reads the same Y/N strings the entity stores, not Booleans. */
    private static Object specValue(Object value) {
        if (value instanceof Boolean) return ((Boolean) value) ? "Y" : "N"
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
