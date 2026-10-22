import darpan.facade.common.DataManagerSupport
import darpan.facade.common.SharedConfigAccessSupport
import darpan.facade.common.TenantAccessSupport
import darpan.facade.reconciliation.RunObservability
import darpan.reconciliation.conclusion.ExcludedRecordsSidecar
import darpan.reconciliation.core.RunSnapshotScope
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import netsuite.reconciliation.inventory.NsTokenCache
import netsuite.reconciliation.orders.NsLifecycleSnapshot
import netsuite.reconciliation.orders.NsOAuthTokenMinter
import netsuite.reconciliation.orders.NsSuiteQlOrderSupport
import netsuite.reconciliation.orders.NsSuiteQlPageRetry

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

// Service edge for extract#NetSuiteOrders (DAR-BE-032).
//
// Deliberately mirrors extractOmsReconciliationOrders.groovy step for step — same tenant gate, same
// output location, same atomic .partial move, same progress heartbeat — so the automation edge and
// the on-disk {records, metadata} contract stay identical across connectors. Everything that varies
// lives in NsSuiteQlOrderSupport (query, mapping, paging) and NsOAuthTokenMinter (credential), not
// here, and both are unit-tested without a NetSuite account.

String queryIdValue = nsSuiteQlSourceQueryId?.toString()?.trim()
String companyUserGroupIdValue = companyUserGroupId?.toString()?.trim()
warnings = []
errors = []
requestMetadata = [:]
dataAvailable = false
recordCount = 0

if (!queryIdValue) {
    errors = ["NetSuite SuiteQL source query ID is required."]
    return
}

// DAR-BE-044: the per-side query row is what says WHICH NetSuite population this side enumerates.
// It is owned by a tenant outright (unlike the credential below, which is a shareable config), so it
// is gated the way extractDatabaseRecords gates DatabaseSourceQuery -- the same shape, because it is
// the same kind of object.
def queryRow = ec.entity.find("darpan.reconciliation.NsSuiteQlSourceQuery")
        .condition("nsSuiteQlSourceQueryId", queryIdValue)
        .disableAuthz().useCache(false).one()

if (companyUserGroupIdValue) {
    if (!queryRow) {
        errors = ["NetSuite SuiteQL source query ${queryIdValue} not found.".toString()]
        return
    }
    if (queryRow.companyUserGroupId?.toString()?.trim() != companyUserGroupIdValue) {
        errors = ["NetSuite SuiteQL source query ${queryIdValue} is not available in this automation tenant.".toString()]
        return
    }
} else {
    TenantAccessSupport.requireTenantRecordAccess(
            ec, queryRow,
            "NetSuite SuiteQL source query ${queryIdValue} not found.".toString(),
            "NetSuite SuiteQL source query ${queryIdValue} is not available in your active tenant.".toString())
    if (ec.message.hasError()) {
        errors = new ArrayList<String>(ec.message.getErrors())
        return
    }
}
if ((queryRow.isActive ?: "Y").toString().equalsIgnoreCase("N")) {
    errors = ["NetSuite SuiteQL source query ${queryIdValue} is inactive.".toString()]
    return
}

String authConfigIdValue = queryRow.nsAuthConfigId?.toString()?.trim()
if (!authConfigIdValue) {
    errors = ["NetSuite SuiteQL source query ${queryIdValue} has no auth config.".toString()]
    return
}

def authConfig = ec.entity.find("darpan.reconciliation.NsAuthConfig")
        .condition("nsAuthConfigId", authConfigIdValue)
        .useCache(false)
        .one()

// Same gate the OMS getters use, against the NsAuthConfig shared-config type: a trusted automation
// tenant is checked directly, an interactive run against the session's active tenant. A missing or
// unusable config reports "not found" rather than "forbidden" so the error cannot be used to probe
// which config ids exist in other tenants.
//
// The CREDENTIAL keeps the shared-config gate rather than the query row's ownership gate, because an
// NsAuthConfig is legitimately shareable across tenants by grant; collapsing the two would quietly
// revoke that sharing.
boolean usable = companyUserGroupIdValue
        ? SharedConfigAccessSupport.canTenantUseConfig(ec,
                SharedConfigAccessSupport.CONFIG_TYPE_NS_AUTH, authConfig, companyUserGroupIdValue)
        : SharedConfigAccessSupport.canActiveTenantUseConfig(ec,
                SharedConfigAccessSupport.CONFIG_TYPE_NS_AUTH, authConfig)
if (!usable) {
    errors = ["NetSuite auth config ${authConfigIdValue} not found.".toString()]
    return
}
if ((authConfig.isActive ?: "Y").toString().equalsIgnoreCase("N")) {
    errors = ["NetSuite auth config ${authConfigIdValue} is inactive.".toString()]
    return
}

List<Map<String, Object>> queryFieldRows = ec.entity.find("darpan.reconciliation.NsSuiteQlSourceQueryField")
        .condition("nsSuiteQlSourceQueryId", queryIdValue)
        .disableAuthz().useCache(false).list()
        .collect { [recordFieldName  : it.recordFieldName, sourceColumn: it.sourceColumn,
                    sourceColumnAlias: it.sourceColumnAlias, sequenceNum: it.sequenceNum] as Map<String, Object> }

String fromDate, thruDate, suiteQlUrl, asOfDateValue
Map<String, Object> querySpec
try {
    // Today, in the tenant's zone rather than the JVM's — the same lesson DAR-BE-057 taught about the
    // window bounds: a calendar day derived in the wrong zone is off by one for anyone east of UTC.
    asOfDateValue = java.time.LocalDate.now(
            java.time.ZoneId.of(darpan.facade.common.TenantAccessSupport.resolveActiveTenantTimeZone(ec) as String)
    ).toString()
    fromDate = NsSuiteQlOrderSupport.toSuiteQlDate(windowStart)
    thruDate = NsSuiteQlOrderSupport.toSuiteQlDate(windowEnd)
    suiteQlUrl = NsSuiteQlOrderSupport.suiteQlUrlFromTokenUrl(authConfig.tokenUrl?.toString())
    // Validates every configured fragment BEFORE it can reach the interpolated query text.
    querySpec = NsSuiteQlOrderSupport.normalizeSpec(
            // EVERY configured field must be listed here. This map is named rather than the row passed
            // wholesale, so a field added to NsSuiteQlSourceQuery and not added here is SILENTLY IGNORED
            // — an operator would set queryTemplate=LINKED_CHILD_ABSENT and get a plain windowed extract
            // that reports every order as a finding (DAR-BE-053).
            [nsSuiteQlSourceQueryId: queryIdValue, recordType: queryRow.recordType,
             fromTable             : queryRow.fromTable, dateColumn: queryRow.dateColumn,
             joinKeyFieldName      : queryRow.joinKeyFieldName, originFieldName: queryRow.originFieldName,
             queryTemplate         : queryRow.queryTemplate,
             absentChildRecordType : queryRow.absentChildRecordType,
             presentChildRecordType: queryRow.presentChildRecordType,
             requireFulfillableOpenLine: queryRow.requireFulfillableOpenLine,
             requireOverdueShipDate: queryRow.requireOverdueShipDate,
             pendingStates         : queryRow.pendingStates,
             statesExpectingShipment: queryRow.statesExpectingShipment,
             statesExpectingInvoice: queryRow.statesExpectingInvoice,
             disallowedStates      : queryRow.disallowedStates,
             requireParentRecordType: queryRow.requireParentRecordType,
             amountColumn          : queryRow.amountColumn,
             minAmount             : queryRow.minAmount,
             overdueExcludedShipMethods: queryRow.overdueExcludedShipMethods,
             // Guarded: EntityValue.get THROWS on a field the entity does not declare, and a
             // deployment whose entity predates lineKeyColumn must keep running every other check.
             lineKeyColumn         : queryRow.isField("lineKeyColumn") ? queryRow.get("lineKeyColumn") : null,
             // DAR-UI-050. A lifecycle stage's exceptions; guarded the same way for an entity that predates them.
             waiveRules            : queryRow.isField("waiveRules") ? queryRow.get("waiveRules") : null,
             // DAR-UI-050. A written finding's conditions.
             matchRules            : queryRow.isField("matchRules") ? queryRow.get("matchRules") : null,
             // DAR-BE-073. How the stages this query asks about are reached, when not by link.
             stageReach            : queryRow.isField("stageReach") ? queryRow.get("stageReach") : null,
             // DAR-BE-111. Each record's HotWax status: the OMS config to ask and the field naming the order there.
             omsStatusConfigId     : queryRow.isField("omsStatusConfigId") ? queryRow.get("omsStatusConfigId") : null,
             omsStatusKeyField     : queryRow.isField("omsStatusKeyField") ? queryRow.get("omsStatusKeyField") : null],
            queryFieldRows)
} catch (IllegalArgumentException e) {
    errors = [e.message]
    return
}

String tokenUrl = authConfig.tokenUrl?.toString()?.trim()
String scope = authConfig.scope?.toString()?.trim() ?: "restlets rest_webservices"
String authCacheKey = authConfigIdValue

// Declared BEFORE mintToken: a Groovy closure captures the variable, and a local declared later is
// simply not in scope when the closure body runs.
HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()

Closure<String> mintToken = {
    String assertionJwt = NsOAuthTokenMinter.buildClientAssertion(tokenUrl,
            authConfig.clientId?.toString()?.trim(),
            authConfig.certId?.toString()?.trim(),
            authConfig.privateKeyPem?.toString(),
            scope)
    String form = "grant_type=" + URLEncoder.encode("client_credentials", StandardCharsets.UTF_8) +
            "&client_assertion_type=" + URLEncoder.encode("urn:ietf:params:oauth:client-assertion-type:jwt-bearer", StandardCharsets.UTF_8) +
            "&client_assertion=" + URLEncoder.encode(assertionJwt, StandardCharsets.UTF_8)

    HttpResponse<String> resp = httpClient.send(
            HttpRequest.newBuilder(URI.create(tokenUrl))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                    .build(),
            HttpResponse.BodyHandlers.ofString())
    if (resp.statusCode() != 200) {
        // NetSuite answers every client-assertion problem with an opaque 500 {"error":"server_error"} —
        // a wrong scope, a wrong certificate id and swapped iss/kid are indistinguishable here. Name
        // the likely causes in the error so the operator is not left with the bare status.
        throw new IllegalStateException("NetSuite token endpoint returned ${resp.statusCode()}. " +
                "Check that clientId holds the integration's Client ID (64 hex) and certId the " +
                "Certificate ID (43-char base64url) — entering them swapped fails exactly like this.")
    }
    Map parsed = new JsonSlurper().parseText(resp.body()) as Map
    String token = parsed.access_token?.toString()
    long expiresAtSec = (long) (System.currentTimeMillis() / 1000L) +
            ((parsed.expires_in ?: 3600) as long) - 60L   // refresh a minute early
    NsTokenCache.put(authCacheKey, tokenUrl, token, expiresAtSec)
    return token
}

String timestamp = DataManagerSupport.formatRunTimestamp(ec)
String outputBaseLocation = outputLocation ?: DataManagerSupport.resolveReconciliationRunLocation(
        ec, automationExecutionId ?: queryIdValue, timestamp)
File outputDirectory = DataManagerSupport.resolveDirectoryFile(ec, outputBaseLocation, true)
File workFile = outputDirectory != null
        ? File.createTempFile("netsuite-orders-extract-", ".partial", outputDirectory)
        : File.createTempFile("netsuite-orders-extract-", ".partial")

final long PROGRESS_MIN_INTERVAL_MS = 2000L
String progressRunId = reconciliationRunResultId?.toString()?.trim()
String progressStage = progressStageCode?.toString()?.trim() ?: RunObservability.STAGE_EXTRACT_FILE2
Integer progressExpectedTotal = null
try {
    progressExpectedTotal = expectedRecordCount != null ? (expectedRecordCount as Integer) : null
} catch (Exception ignored) { }
long lastReportedAtMs = 0L

// One page of SuiteQL, live. DAR-BE-056: a page that times out, drops its connection or meets a gateway 429/5xx is
// retried on its own (NsSuiteQlPageRetry) instead of aborting the extraction; each retry is a warning, and each
// page's time is kept so a month-scale run shows what the timeout must cover.
long cumulative = 0L
List<String> pageRetryWarnings = []
List<Long> pageMillis = []
Closure livePage = { String query, int limit, int offset ->
    long pageStartedAt = System.currentTimeMillis()
    String token = NsTokenCache.getValidAccessToken(authCacheKey, tokenUrl) ?: mintToken()
    HttpResponse<String> resp = NsSuiteQlPageRetry.send({ -> sendSuiteQl(httpClient, suiteQlUrl, token, query, limit, offset) },
            offset, pageRetryWarnings)
    if (resp.statusCode() == 401) {
        // Token revoked or rotated NetSuite-side. Evict so the next mint is fresh,
        // then retry ONCE — a second 401 is a real credential problem, not a race.
        NsTokenCache.evict(authCacheKey)
        String freshToken = mintToken()
        resp = NsSuiteQlPageRetry.send({ -> sendSuiteQl(httpClient, suiteQlUrl, freshToken, query, limit, offset) },
                offset, pageRetryWarnings)
    }
    pageMillis.add(System.currentTimeMillis() - pageStartedAt)
    if (resp.statusCode() != 200) {
        throw new IllegalStateException("NetSuite SuiteQL returned ${resp.statusCode()}: " +
                "${resp.body()?.take(500)}")
    }
    Map parsed = new JsonSlurper().parseText(resp.body()) as Map

    if (progressRunId) {
        cumulative += ((List) (parsed.items ?: [])).size()
        long nowMs = System.currentTimeMillis()
        if (nowMs - lastReportedAtMs >= PROGRESS_MIN_INTERVAL_MS) {
            lastReportedAtMs = nowMs
            RunObservability.heartbeatStageProgress(ec, progressRunId, progressStage,
                    cumulative, (progressExpectedTotal ?: null))
        }
    }
    return [items: (parsed.items ?: []), hasMore: parsed.hasMore == true]
}

try {
    // DAR-BE-073. Inside a run's walk, a lifecycle question is answered from one read of its window (NsLifecycleSnapshot)
    // when it can be answered exactly; the first such question reads it, the rest only read the ids of the conditions
    // they add. Anything else, and every run outside a walk, reads live.
    Map<String, Closure> served = null
    RunSnapshotScope runScope = RunSnapshotScope.current()
    if (runScope != null && NsLifecycleSnapshot.canAnswer(querySpec)) {
        String snapshotKey = NsLifecycleSnapshot.keyFor(querySpec, fromDate, thruDate,
                "${authConfigIdValue}|${queryRow.companyUserGroupId}".toString())
        NsLifecycleSnapshot snapshot = (NsLifecycleSnapshot) runScope.get(snapshotKey)
        if (snapshot == null) {
            snapshot = NsLifecycleSnapshot.read(querySpec, fromDate, thruDate, runScope.dir, livePage)
            NsLifecycleSnapshot read = snapshot
            runScope.put(snapshotKey, snapshot) { -> read.discard() }
            ec.logger.info("lifecycle snapshot read ${snapshot.baseCount} ${querySpec.recordType} records ${fromDate}..${thruDate} " +
                    "in ${snapshot.readMillis}ms for query ${queryIdValue}")
        }
        if (snapshot.covers(querySpec)) {
            long before = System.currentTimeMillis()
            Closure<Boolean> admits = snapshot.admits(querySpec, livePage)
            served = snapshot.serve(admits)
            ec.logger.info("lifecycle snapshot answers query ${queryIdValue} (${querySpec.queryTemplate}); conditions " +
                    "${snapshot.conditionCounts} ready in ${System.currentTimeMillis() - before}ms")
        } else {
            ec.logger.info("lifecycle snapshot does not hold every column query ${queryIdValue} selects; reading it live")
        }
    }

    // DAR-BE-111. With an OMS config on the query row, every record naming an OMS order gets that order's HotWax
    // statusId as omsStatusId, NOT_IN_OMS when HotWax has no such order. Asked by id, a page at a time, under the query
    // row's own tenant (the trusted run owner); a lookup that fails fails the extract rather than call orders missing.
    Closure omsStatusEnricher = null
    if (querySpec.omsStatusConfigId) {
        String keyField = querySpec.omsStatusKeyField as String
        omsStatusEnricher = { List<Map<String, Object>> records ->
            List<String> ids = records.collect { it.get(keyField)?.toString()?.trim() }.findAll { it }.unique()
            if (!ids) return
            Map looked = ec.service.sync().name("reconciliation.HotWaxOmsExtractionServices.lookup#HotWaxOmsOrderStatuses")
                    .parameters([omsRestSourceConfigId: querySpec.omsStatusConfigId, orderIds: ids,
                                 companyUserGroupId   : queryRow.companyUserGroupId]).disableAuthz().call()
            if (!(looked?.ok)) {
                throw new IllegalStateException("HotWax order status lookup failed: ${(looked?.errors ?: ec.message.errors) as List}")
            }
            Map statusByOrderId = (Map) looked.statusByOrderId
            records.each { Map<String, Object> record ->
                String id = record.get(keyField)?.toString()?.trim()
                if (id) record.put("omsStatusId", statusByOrderId.get(id) ?: "NOT_IN_OMS")
            }
        }
    }

    Map extraction = null
    try {
        workFile.withWriter("UTF-8") { Writer writer ->
            extraction = NsSuiteQlOrderSupport.streamOrdersToWriter(writer,
                    [spec       : querySpec,
                     fromDate   : fromDate,
                     toDate     : thruDate,
                     pageSize   : (pageSize != null ? pageSize as Integer : null),
                     // The run's own day, resolved in the window's zone rather than the JVM's. The
                     // time-sensitive branches compare a promised ship date against it, and reading a
                     // clock deeper in the stack would make the query depend on where it executed.
                     asOfDate   : asOfDateValue,
                     keepFields : (keepRecordFields instanceof List ? keepRecordFields : null),
                     filterRules: (sourceFilters instanceof List ? sourceFilters : null),
                     pageEnricher: omsStatusEnricher],
                    served != null ? served.fetch : livePage)
        }
    } finally {
        served?.close?.call()
    }

    recordCount = extraction.recordCount ?: 0
    dataAvailable = extraction.dataAvailable == true
    warnings = ((extraction.warnings ?: []) as List) + pageRetryWarnings
    requestMetadata = [
            endpoint         : suiteQlUrl,
            windowFrom       : fromDate,
            windowThru       : thruDate,
            pageCount        : extraction.pageCount,
            excludedCount    : extraction.excludedCount,
            answeredFrom     : served != null ? "run snapshot" : "NetSuite",
            // DAR-BE-056: what the per-request timeout has to cover, measured.
            requestsSent     : pageMillis.size(),
            slowestRequestMs : pageMillis ? pageMillis.max() : null,
            totalRequestMs   : pageMillis ? pageMillis.sum() : 0L,
            requestTimeoutSec: NsSuiteQlPageRetry.REQUEST_TIMEOUT.seconds,
            pageRetries      : pageRetryWarnings.size(),
    ]

    String outputFileName = (fileName ?: "netsuite-orders-${fromDate}-${thruDate}.json").toString()
            .replaceAll("[^A-Za-z0-9._-]", "_")
    fileName = outputFileName
    fileLocation = DataManagerSupport.childLocation(outputBaseLocation, outputFileName)
    DataManagerSupport.moveIntoLocation(ec, workFile, fileLocation as String)
    // DAR-UI-044: what the source filters dropped, beside the extract, for the conclude pass.
    try {
        ExcludedRecordsSidecar.writeBeside(ec, (Map) extraction.excludedCollector, outputBaseLocation, outputFileName, outputDirectory)
    } catch (Exception sidecarError) {
        warnings = ((warnings ?: []) as List) + ["Excluded-records sidecar not written: ${sidecarError.message}".toString()]
    }
} catch (Exception e) {
    errors = [e.message ?: e.toString()]
    // The pages retried before the one that gave up, so a failed read still shows how it failed.
    warnings = (((warnings ?: []) as List) + pageRetryWarnings).unique()
    dataAvailable = false
    recordCount = 0
    fileLocation = null
    fileName = null
} finally {
    // JDK 21 HttpClient holds a selector thread and a connection pool until closed; the inventory
    // path leaked one per call before 0d713bd.
    try { httpClient.close() } catch (Exception ignored) { }
    if (workFile?.exists()) { try { workFile.delete() } catch (Exception ignored) { } }
}

static HttpResponse<String> sendSuiteQl(HttpClient client, String url, String token,
                                        String query, int limit, int offset) {
    // `Prefer: transient` is REQUIRED by NetSuite: without it the SuiteQL request is rejected.
    return client.send(
            HttpRequest.newBuilder(URI.create("${url}?limit=${limit}&offset=${offset}"))
                    .timeout(NsSuiteQlPageRetry.REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("Prefer", "transient")
                    .header("Authorization", "Bearer ${token}")
                    .POST(HttpRequest.BodyPublishers.ofString(JsonOutput.toJson([q: query]), StandardCharsets.UTF_8))
                    .build(),
            HttpResponse.BodyHandlers.ofString())
}
