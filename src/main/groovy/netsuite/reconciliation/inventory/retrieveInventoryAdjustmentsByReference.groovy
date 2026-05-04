import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.apache.spark.sql.Dataset
import org.apache.spark.sql.Row
import org.apache.spark.sql.SparkSession
import org.slf4j.LoggerFactory
import static org.apache.spark.sql.functions.col

import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

def logger = LoggerFactory.getLogger("netsuite.reconciliation.inventory.retrieveInventoryAdjustmentsByReference")

def normalize = { Object value -> value?.toString()?.trim() }
def normalizeBool = { Object value, boolean defaultValue ->
    if (value == null) return defaultValue
    if (value instanceof Boolean) return (boolean) value
    String raw = normalize(value)?.toLowerCase()
    if (["y", "yes", "true", "1"].contains(raw)) return true
    if (["n", "no", "false", "0"].contains(raw)) return false
    return defaultValue
}
def toInt = { Object value ->
    if (value == null) return 0
    if (value instanceof Number) return ((Number) value).intValue()
    String raw = normalize(value)
    if (!raw) return 0
    try {
        return raw.toBigDecimal().intValue()
    } catch (Exception ignored) {
        return 0
    }
}
def toDecimal = { Object value ->
    if (value == null) return BigDecimal.ZERO
    if (value instanceof BigDecimal) return (BigDecimal) value
    if (value instanceof Number) return new BigDecimal(value.toString())
    String raw = normalize(value)
    if (!raw) return BigDecimal.ZERO
    try {
        return new BigDecimal(raw)
    } catch (Exception ignored) {
        return BigDecimal.ZERO
    }
}
def warningList = [] as List<String>

def detectFileTypeFromLocation = { String location ->
    String lower = location?.toLowerCase()
    if (lower?.endsWith(".csv")) return "CSV"
    if (lower?.endsWith(".json")) return "JSON"
    return null
}

def resolvePath = { String location ->
    def rr = ec.resource.getLocationReference(location)
    if (rr != null && rr.supportsUrl()) {
        def url = rr.getUrl()
        if ("file".equalsIgnoreCase(url.protocol)) {
            try {
                return new File(url.toURI()).getAbsolutePath()
            } catch (Exception ignored) {
                return url.getPath()
            }
        }
        return url.toString()
    }
    return location
}

def writeOutputJson = { File outputDir, String prefix, String baseName, String timestamp, Map payload ->
    File out = new File(outputDir, "${prefix}-${baseName}-${timestamp}.json")
    int suffix = 1
    while (out.exists()) {
        out = new File(outputDir, "${prefix}-${baseName}-${timestamp}-${suffix}.json")
        suffix++
    }
    out.withWriter("UTF-8") { writer -> writer << JsonOutput.prettyPrint(JsonOutput.toJson(payload)) }
    return out
}

def escapeCsvValue = { Object value ->
    String raw = value == null ? "" : value.toString()
    if (raw.contains("\"")) raw = raw.replace("\"", "\"\"")
    if (raw.contains(",") || raw.contains("\n") || raw.contains("\r") || raw.contains("\"")) return "\"${raw}\""
    return raw
}

def writeOutputCsv = { File outputDir, String prefix, String baseName, String timestamp, List<String> headers, List<Map> rows ->
    File out = new File(outputDir, "${prefix}-${baseName}-${timestamp}.csv")
    int suffix = 1
    while (out.exists()) {
        out = new File(outputDir, "${prefix}-${baseName}-${timestamp}-${suffix}.csv")
        suffix++
    }
    out.withWriter("UTF-8") { writer ->
        writer << headers.collect { String header -> escapeCsvValue(header) }.join(",") << "\n"
        rows.each { Map row ->
            writer << headers.collect { String header -> escapeCsvValue(row[header]) }.join(",") << "\n"
        }
    }
    return out
}

def collectDistinctValues = { List<Map> rows, String fieldName, int limit ->
    LinkedHashSet<String> values = new LinkedHashSet<>()
    rows.each { Map row ->
        String value = normalize(row[fieldName])
        if (value) values.add(value)
    }
    List<String> valueList = new ArrayList<>(values)
    return valueList.take(limit)
}

def hasOmsSignal = { List<Map> rows, String fieldName ->
    rows.any { Map row -> normalize(row[fieldName]) }
}

def isRetryableFailure = { String message ->
    String raw = normalize(message)?.toLowerCase()
    if (!raw) return false
    return raw.contains("http 429") || raw.contains("http 500") || raw.contains("http 502") ||
            raw.contains("http 503") || raw.contains("http 504") || raw.contains("http 408") ||
            raw.contains("timeout") || raw.contains("connection") || raw.contains("reset")
}

def parseRowJson = { String jsonText ->
    if (!jsonText) return [:]
    try {
        Object parsed = new JsonSlurper().parseText(jsonText)
        return (parsed instanceof Map) ? ((Map) parsed) : [value: parsed]
    } catch (Exception ignored) {
        return [raw: jsonText]
    }
}

def safeFieldExpr = { String expression, String label ->
    if (!(expression ==~ /[A-Za-z0-9_$.]+/)) {
        throw new IllegalArgumentException("${label} has unsupported characters: ${expression}")
    }
}

def buildRunId = {
    String seed = [normalize(referenceFileLocation), normalize(omsDetailFileLocation), normalize(from), normalize(to), normalize(nsRestletConfigId), normalize(comparisonRuleSetId)].join("|")
    String hash = Integer.toHexString(seed.hashCode())
    return "ns-run-${ec.l10n.format(ec.user.nowTimestamp, 'yyyyMMdd-HHmmss')}-${hash}"
}

def extractTransaction = { Map record ->
    if (record?.transaction instanceof Map) return (Map) record.transaction
    return record ?: [:]
}

def extractQty = { Map record ->
    Map tx = extractTransaction(record)
    for (String key in ["qty", "quantity", "adjustmentQty", "qtyDelta"]) {
        if (tx.containsKey(key)) return toDecimal(tx[key])
    }
    return BigDecimal.ZERO
}

def extractTxnDate = { Map record ->
    Map tx = extractTransaction(record)
    for (String key in ["date", "transactionDate", "trxDate", "effectiveDate", "createdAt"]) {
        String raw = normalize(tx[key])
        if (raw) return raw
    }
    return null
}

def extractTxnId = { Map record ->
    Map tx = extractTransaction(record)
    for (String key in ["id", "internalid", "internalId", "transactionId", "tranInternalId"]) {
        String raw = normalize(tx[key])
        if (raw) return raw
    }
    return null
}

def extractTranId = { Map record ->
    Map tx = extractTransaction(record)
    for (String key in ["tranid", "tranId", "transactionNumber", "docnum", "documentNumber", "tran_num"]) {
        String raw = normalize(tx[key])
        if (raw) return raw
    }
    return null
}

def normalizeNsRecords = { List rawRecords, String fallbackItemId, String fallbackLocationId ->
    List<Map> normalizedRecords = []
    (rawRecords ?: []).each { Object recordObj ->
        if (!(recordObj instanceof Map)) return
        Map record = new LinkedHashMap((Map) recordObj)
        Map tx = new LinkedHashMap(extractTransaction(record))

        String txId = normalize(tx.id ?: tx.internalid ?: tx.internalId ?: tx.transactionId ?: tx.tranInternalId)
        String txTranId = normalize(tx.tranid ?: tx.tranId ?: tx.transactionNumber ?: tx.docnum ?: tx.documentNumber ?: tx.tran_num)
        String txType = normalize(tx.type ?: tx.transactionType ?: tx.recordType)
        String txDate = normalize(tx.date ?: tx.transactionDate ?: tx.trandate ?: tx.trxDate ?: tx.effectiveDate ?: tx.createdAt)

        if (txId) tx.id = txId
        if (txTranId) tx.tranid = txTranId
        if (txType) tx.type = txType
        if (txDate) tx.date = txDate
        if (!normalize(tx.itemId) && fallbackItemId) tx.itemId = fallbackItemId
        if (!normalize(tx.locationId) && fallbackLocationId) tx.locationId = fallbackLocationId
        if (tx.qty == null) tx.qty = extractQty([transaction: tx]).toPlainString()

        record.transaction = tx
        if (!normalize(record.itemId) && fallbackItemId) record.itemId = fallbackItemId
        if (!normalize(record.locationId) && fallbackLocationId) record.locationId = fallbackLocationId
        normalizedRecords << record
    }
    return normalizedRecords
}

def parseDateEpoch = { String rawDate ->
    String raw = normalize(rawDate)
    if (!raw) return Long.MAX_VALUE
    List<Map> formats = [
            [pattern: "yyyy-MM-dd", hasTime: false],
            [pattern: "M/d/yyyy", hasTime: false],
            [pattern: "M/d/yy", hasTime: false],
            [pattern: "yyyy-MM-dd HH:mm:ss", hasTime: true],
            [pattern: "yyyy-MM-dd'T'HH:mm:ss", hasTime: true]
    ]
    for (Map fmt in formats) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(fmt.pattern as String)
        try {
            if (fmt.hasTime) {
                LocalDateTime ldt = LocalDateTime.parse(raw, formatter)
                return ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }
            LocalDate ld = LocalDate.parse(raw, formatter)
            return ld.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (DateTimeParseException ignored) {}
    }
    return Long.MAX_VALUE
}

def buildInitialSignalFlags = { List<Map> nsRecords, List<Map> omsRows, Map supplementalRows, Map discrepancyRow ->
    List<Map> transferLifecycleRows = (supplementalRows?.transferLifecycle instanceof List) ? (List<Map>) supplementalRows.transferLifecycle : []
    List<Map> returnRefundRows = (supplementalRows?.returnRefundLinkage instanceof List) ? (List<Map>) supplementalRows.returnRefundLinkage : []
    List<Map> cycleCountRows = (supplementalRows?.cycleCountAudit instanceof List) ? (List<Map>) supplementalRows.cycleCountAudit : []

    int nsSalesOrderCount = 0
    int nsFulfillmentCount = 0
    int nsCreditMemoCount = 0
    int nsTransferCount = 0
    int nsInventoryAdjustmentCount = 0
    nsRecords.each { Map nsRecord ->
        String txnType = normalize(extractTransaction(nsRecord).type)
        if (!txnType) return
        if (txnType == "Sales Order") nsSalesOrderCount++
        if (txnType == "Item Fulfillment") nsFulfillmentCount++
        if (txnType == "Credit Memo") nsCreditMemoCount++
        if (txnType in ["Transfer Order", "Inventory Transfer", "Item Receipt"]) nsTransferCount++
        if (txnType == "Inventory Adjustment") nsInventoryAdjustmentCount++
    }

    boolean hasOmsReturnSignal = omsRows.any { Map row ->
        normalize(row.RETURN_ID) || normalize(row.REASON_ENUM_ID)?.startsWith("RTN_")
    } || returnRefundRows.any { Map row -> normalize(row.return_id) }

    boolean hasTransferSignal = nsTransferCount > 0 || !transferLifecycleRows.isEmpty()
    BigDecimal discrepancyQohDiff = toDecimal(discrepancyRow?.get("QOH DIFF"))
    boolean kitSingleNarrativeSignal = (nsSalesOrderCount > 0) &&
            (nsFulfillmentCount > 0) &&
            !hasOmsReturnSignal &&
            !hasTransferSignal &&
            discrepancyQohDiff.abs() == BigDecimal.ONE

    boolean hasSupplementalReceiptSignal = transferLifecycleRows.any { Map row ->
        String lifecycleState = normalize(row.transfer_lifecycle_state)?.toUpperCase()
        toDecimal(row.received_qty) > BigDecimal.ZERO ||
                normalize(row.receipt_datetime) ||
                ["RECEIVED", "PARTIALLY_RECEIVED"].contains(lifecycleState)
    }
    boolean hasSupplementalRefundSignal = returnRefundRows.any { Map row ->
        normalize(row.payment_id) ||
                normalize(row.payment_application_id) ||
                toDecimal(row.refund_amount) != BigDecimal.ZERO
    }

    return [
            hasSalesOrderSignal          : nsSalesOrderCount > 0,
            hasItemFulfillmentSignal     : nsFulfillmentCount > 0,
            hasCreditMemoSignal          : nsCreditMemoCount > 0,
            hasTransferSignal            : hasTransferSignal,
            hasInventoryAdjustmentSignal : nsInventoryAdjustmentCount > 0,
            hasOmsReturnSignal           : hasOmsReturnSignal,
            hasOmsShipmentSignal         : omsRows.any { Map row -> normalize(row.SHIPMENT_ID) } || transferLifecycleRows.any { Map row -> normalize(row.shipment_id) },
            hasPhysicalInventorySignal   : omsRows.any { Map row -> normalize(row.PHYSICAL_INVENTORY_ID) } || cycleCountRows.any { Map row -> normalize(row.physical_inventory_id) },
            hasSupplementalTransferSignal: !transferLifecycleRows.isEmpty(),
            hasSupplementalReceiptSignal : hasSupplementalReceiptSignal,
            hasSupplementalRefundSignal  : hasSupplementalRefundSignal,
            strictCycleCountSignal       : false,
            kitSingleNarrativeSignal     : kitSingleNarrativeSignal
    ]
}

def rowMatchesItemFacilityScope = { Map rowMap, Set<String> itemCandidates, Set<String> facilityCandidates ->
    if (!(rowMap instanceof Map)) return false
    List<String> itemKeys = [
            "netsuite_product_id", "INVENTORY_ITEM_ID", "inventory_item_id", "inventoryItemId",
            "product_id", "itemId", "nsItemId", "oms_item_id", "omsItemId"
    ]
    List<String> facilityKeys = [
            "facility_id", "external_facility_id", "locationId", "nsLocationId",
            "oms_location_id", "facilityId", "location_id"
    ]
    boolean itemMatch = itemCandidates.isEmpty() || itemKeys.any { String key ->
        String value = normalize(rowMap[key])
        value && itemCandidates.contains(value)
    }
    boolean facilityMatch = facilityCandidates.isEmpty() || facilityKeys.any { String key ->
        String value = normalize(rowMap[key])
        value && facilityCandidates.contains(value)
    }
    return itemMatch && facilityMatch
}

String refLocation = normalize(referenceFileLocation)
String omsDetailLocation = normalize(omsDetailFileLocation)
boolean twoCsvMode = !!omsDetailLocation

if (!twoCsvMode) {
    Map legacyInMap = [:]
    context.each { k, v -> if (!(k in ["context", "ec"])) legacyInMap[k] = v }
    Map legacyOut = ec.service.sync()
            .name("reconciliation.ReconciliationInventoryServices.retrieve#InventoryAdjustmentsByReferenceLegacy")
            .parameters(legacyInMap)
            .call()
    (legacyOut ?: [:]).each { k, v -> this."${k}" = v }
    return
}

String refTypeRaw = normalize(referenceFileType)
boolean refHasHeader = (referenceHasHeader != null) ? (referenceHasHeader as Boolean) : true
String omsTypeRaw = normalize(omsDetailFileType)
boolean omsHasHeader = (omsDetailHasHeader != null) ? (omsDetailHasHeader as Boolean) : true
String fromDateStr = normalize(from)
String toDateStr = normalize(to)
String nsConfigId = normalize(nsRestletConfigId)
String persistedNsOutputLocationValue = normalize(persistedNsOutputLocation)
boolean fetchMissingNsPairsEnabled = normalizeBool(fetchMissingNsPairs, false)
String comparisonRuleSetIdToUse = normalize(comparisonRuleSetId)
String compareStatusFieldName = normalize(compareStatusField) ?: "compareStatus"
String reasonCodeFieldName = normalize(reasonCodeField) ?: "reasonCode"
String reasonTextFieldName = normalize(reasonTextField) ?: "reasonText"
String matchedRuleIdsFieldName = normalize(matchedRuleIdsField) ?: "_matchedRuleIds"
String missingInNsStatusValue = normalize(missingInNsStatus) ?: "MISSING_IN_NS"
String missingInReadDbStatusValue = normalize(missingInReadDbStatus) ?: "MISSING_IN_READ_DB"
String countMismatchStatusValue = normalize(countMismatchStatus) ?: "COUNT_MISMATCH"
String matchedStatusValue = normalize(matchedStatus) ?: "MATCHED_COUNT"
String noRecordStatusValue = normalize(noRecordStatus) ?: "NO_RECORDS"
String errorStatusValue = normalize(errorStatus) ?: "ERROR"

int maxItemsToProcess = maxItems ? (maxItems as int) : 0
int chunkSize = nsChunkSize ? (nsChunkSize as int) : 100
if (chunkSize < 1) chunkSize = 1
if (chunkSize > 100) chunkSize = 100
int maxRetries = nsMaxRetries ? (nsMaxRetries as int) : 2
if (maxRetries < 0) maxRetries = 0
int retryBackoffMs = nsRetryBackoffMs ? (nsRetryBackoffMs as int) : 1000
if (retryBackoffMs < 0) retryBackoffMs = 0
BigDecimal backoffMultiplier = nsBackoffMultiplier ? (nsBackoffMultiplier as BigDecimal) : new BigDecimal("2.0")
if (backoffMultiplier <= BigDecimal.ONE) backoffMultiplier = new BigDecimal("2.0")
boolean continueOnChunkFailure = normalizeBool(continueOnNsChunkFailure, true)
int nsDelayMsToUse = nsDelayMs ? (nsDelayMs as int) : 0
String runIdToUse = normalize(runId) ?: buildRunId()
String nsDetailCacheLocationValue = normalize(nsDetailCacheLocation)
boolean forceRefreshNsDetailsEnabled = normalizeBool(forceRefreshNsDetails, false)

String defaultItemIdFieldExpr = normalize(itemIdField) ?: "itemId"
String defaultLocationIdFieldExpr = normalize(locationIdField) ?: "locationId"
String nsItemIdFieldExpr = normalize(discrepancyNsItemIdField) ?: normalize(nsItemIdField) ?: defaultItemIdFieldExpr
String nsLocationIdFieldExpr = normalize(discrepancyLocationIdField) ?: normalize(nsLocationIdField) ?: defaultLocationIdFieldExpr
String omsItemIdFieldExpr = normalize(omsItemIdField) ?: "itemId"
String omsLocationIdFieldExpr = normalize(omsLocationIdField) ?: "locationId"
String omsTxnDateFieldExpr = normalize(omsTxnDateField)
String omsQuantityFieldExpr = normalize(omsQuantityField)
String omsSupplementalDirLocationValue = normalize(omsSupplementalDirLocation)
boolean omsSupplementalHasHeaderValue = (omsSupplementalHasHeader != null) ? (omsSupplementalHasHeader as Boolean) : true
if (!omsSupplementalDirLocationValue) {
    omsSupplementalDirLocationValue = "runtime://tmp/reconciliation/inventory/input/oms-supplemental"
}

if (!refLocation) throw new IllegalArgumentException("referenceFileLocation is required")
if (!omsDetailLocation) throw new IllegalArgumentException("omsDetailFileLocation is required in two-CSV mode")
if (!fromDateStr) throw new IllegalArgumentException("from is required in yyyy-MM-dd format")
if (!toDateStr) throw new IllegalArgumentException("to is required in yyyy-MM-dd format")
if (!nsConfigId) throw new IllegalArgumentException("nsRestletConfigId is required")
if (!comparisonRuleSetIdToUse) throw new IllegalArgumentException("comparisonRuleSetId is required")
if (!(fromDateStr ==~ /\d{4}-\d{2}-\d{2}/)) throw new IllegalArgumentException("from must be yyyy-MM-dd")
if (!(toDateStr ==~ /\d{4}-\d{2}-\d{2}/)) throw new IllegalArgumentException("to must be yyyy-MM-dd")

LocalDate fromDate = LocalDate.parse(fromDateStr)
LocalDate toDate = LocalDate.parse(toDateStr)
if (toDate.isBefore(fromDate)) throw new IllegalArgumentException("to date must be greater than or equal to from date")

def comparisonRuleSetExists = ec.entity.find("darpan.rule.RuleSet")
        .condition("ruleSetId", comparisonRuleSetIdToUse)
        .useCache(false)
        .one()
if (!comparisonRuleSetExists) {
    throw new IllegalArgumentException("comparisonRuleSetId ${comparisonRuleSetIdToUse} not found. Create it in Rule Engine.")
}

safeFieldExpr(nsItemIdFieldExpr, "discrepancyNsItemIdField")
safeFieldExpr(nsLocationIdFieldExpr, "discrepancyLocationIdField")
safeFieldExpr(omsItemIdFieldExpr, "omsItemIdField")
safeFieldExpr(omsLocationIdFieldExpr, "omsLocationIdField")
if (omsTxnDateFieldExpr) safeFieldExpr(omsTxnDateFieldExpr, "omsTxnDateField")
if (omsQuantityFieldExpr) safeFieldExpr(omsQuantityFieldExpr, "omsQuantityField")
safeFieldExpr(compareStatusFieldName, "compareStatusField")

String refType = refTypeRaw?.toUpperCase() ?: detectFileTypeFromLocation(refLocation)
String omsType = omsTypeRaw?.toUpperCase() ?: detectFileTypeFromLocation(omsDetailLocation)
if (!refType || !["CSV", "JSON"].contains(refType)) throw new IllegalArgumentException("referenceFileType must be CSV or JSON (or inferable by extension)")
if (!omsType || !["CSV", "JSON"].contains(omsType)) throw new IllegalArgumentException("omsDetailFileType must be CSV or JSON (or inferable by extension)")

String refPath = resolvePath(refLocation)
String omsPath = resolvePath(omsDetailLocation)
String omsSupplementalDirPath = resolvePath(omsSupplementalDirLocationValue)

SparkSession spark = null
try {
    String sparkAppNameToUse = normalize(sparkAppName) ?: "InventoryReferenceRetrieval"
    String sparkMasterToUse = normalize(sparkMaster) ?: "local[*]"

    SparkSession.Builder sparkBuilder = SparkSession.builder()
            .appName(sparkAppNameToUse)
            .master(sparkMasterToUse)
            .config("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
            .config("spark.sql.adaptive.enabled", "true")

    if (sparkMasterToUse.toLowerCase().startsWith("local")) {
        // Prevent local Spark from binding to an unavailable interface in desktop dev environments.
        sparkBuilder = sparkBuilder
                .config("spark.driver.bindAddress", "127.0.0.1")
                .config("spark.driver.host", "127.0.0.1")
    }

    spark = sparkBuilder.getOrCreate()

    Dataset discrepancyDf = (refType == "JSON")
            ? spark.read().option("multiLine", "true").json(refPath)
            : spark.read().option("header", refHasHeader.toString()).option("multiLine", "true").csv(refPath)

    Dataset omsDf = (omsType == "JSON")
            ? spark.read().option("multiLine", "true").json(omsPath)
            : spark.read().option("header", omsHasHeader.toString()).option("multiLine", "true").csv(omsPath)

    Dataset pairDf = discrepancyDf
            .selectExpr(
                    "cast(${nsItemIdFieldExpr} as string) as ns_item_id",
                    "cast(${nsLocationIdFieldExpr} as string) as ns_location_id"
            )
            .filter("ns_item_id IS NOT NULL AND length(trim(ns_item_id)) > 0 AND ns_location_id IS NOT NULL AND length(trim(ns_location_id)) > 0")
            .distinct()
            .orderBy("ns_item_id", "ns_location_id")

    referenceItemCount = pairDf.count() as int
    if (referenceItemCount == 0) {
        throw new IllegalArgumentException("No usable discrepancy rows found in ${refLocation}")
    }

    Dataset finalPairDf = pairDf
    if (maxItemsToProcess > 0 && maxItemsToProcess < referenceItemCount) {
        finalPairDf = pairDf.limit(maxItemsToProcess)
        warningList.add("Processing limited to ${maxItemsToProcess} rows out of ${referenceItemCount} discrepancy rows.")
    }

    List<Map> itemPairs = finalPairDf.collectAsList().collect { Row row ->
        String itemVal = normalize(row.getAs("ns_item_id"))
        String locVal = normalize(row.getAs("ns_location_id"))
        [
                pairId    : "${itemVal}|${locVal}".toString(),
                itemId    : itemVal,
                locationId: locVal
        ]
    }

    Map<String, Map<String, List<Map>>> supplementalRowsByDatasetByPair = [:]
    Map<String, Integer> supplementalRowCountByDataset = [:].withDefault { 0 }
    Map<String, List<String>> supplementalInputFilesByDataset = [:].withDefault { [] }
    int supplementalRecordCount = 0

    List<Map> supplementalConfigs = [
            [
                    key          : "transferLifecycle",
                    filePrefixes : ["oms_transfer_status_history_20260223_plus", "oms_transfer_lifecycle_20260223_plus"],
                    itemFieldExpr: "netsuite_product_id",
                    locFieldExpr : "facility_id"
            ],
            [
                    key          : "returnRefundLinkage",
                    filePrefixes : ["oms_refund_posting_history_20260223_plus", "oms_return_refund_linkage_20260223_plus"],
                    itemFieldExpr: "netsuite_product_id",
                    locFieldExpr : "facility_id"
            ],
            [
                    key          : "orderFulfillmentBackorder",
                    filePrefixes : ["oms_order_fulfillment_backorder_20260223_plus"],
                    itemFieldExpr: "netsuite_product_id",
                    locFieldExpr : "facility_id"
            ],
            [
                    key          : "cycleCountAudit",
                    filePrefixes : ["oms_physical_inventory_session_20260223_plus", "oms_cycle_count_audit_20260223_plus"],
                    itemFieldExpr: "netsuite_product_id",
                    locFieldExpr : "facility_id"
            ],
            [
                    key          : "itemFacilityMapping",
                    filePrefixes : ["oms_item_facility_mapping_20260223_plus"],
                    itemFieldExpr: "netsuite_product_id",
                    locFieldExpr : "facility_id"
            ]
    ]

    def resolveSupplementalFilesForPrefix = { File directory, String prefix ->
        if (directory == null || !prefix) return [] as List<File>
        List<File> pagedFiles = (directory.listFiles() ?: [] as File[]).findAll { File f ->
            String fileName = f?.name
            return fileName && fileName.startsWith("${prefix}_") && fileName.toLowerCase().endsWith(".csv")
        } as List<File>
        if (!pagedFiles.isEmpty()) return (pagedFiles.sort { File a, File b -> a.name <=> b.name } as List<File>)
        String canonicalName = "${prefix}.csv"
        File canonicalFile = new File(directory, canonicalName)
        if (canonicalFile.exists() && canonicalFile.isFile()) return [canonicalFile]
        return [] as List<File>
    }

    File omsSupplementalDir = (omsSupplementalDirPath ? new File(omsSupplementalDirPath) : null)
    if (omsSupplementalDir != null && omsSupplementalDir.exists() && omsSupplementalDir.isDirectory()) {
        supplementalConfigs.each { Map cfg ->
            String datasetKey = cfg.key as String
            List<String> filePrefixes = []
            if (cfg.filePrefixes instanceof List) {
                filePrefixes = ((List) cfg.filePrefixes).collect { normalize(it) }.findAll { it } as List<String>
            } else if (cfg.filePrefix) {
                filePrefixes = [normalize(cfg.filePrefix)]
            }
            List<File> datasetFiles = []
            String matchedPrefix = null
            for (String candidatePrefix in filePrefixes) {
                List<File> candidateFiles = resolveSupplementalFilesForPrefix(omsSupplementalDir, candidatePrefix)
                if (!candidateFiles.isEmpty()) {
                    datasetFiles = candidateFiles
                    matchedPrefix = candidatePrefix
                    break
                }
            }

            if (datasetFiles.isEmpty()) {
                warningList.add("OMS supplemental dataset ${datasetKey} not found in ${omsSupplementalDirLocationValue}. Checked prefixes: ${filePrefixes.join(', ')}")
                supplementalRowsByDatasetByPair[datasetKey] = [:].withDefault { [] }
                return
            }

            Map<String, List<Map>> datasetByPair = [:].withDefault { [] }
            datasetFiles.each { File datasetFile ->
                try {
                    Dataset supplementalDf = spark.read()
                            .option("header", omsSupplementalHasHeaderValue.toString())
                            .option("multiLine", "true")
                            .csv(datasetFile.absolutePath)
                    Dataset supplementalRowsDf = supplementalDf
                            .selectExpr(
                                    "cast(${cfg.itemFieldExpr} as string) as ns_item_id",
                                    "cast(${cfg.locFieldExpr} as string) as ns_location_id",
                                    "to_json(struct(*)) as row_json"
                            )
                            .filter("ns_item_id IS NOT NULL AND length(trim(ns_item_id)) > 0 AND ns_location_id IS NOT NULL AND length(trim(ns_location_id)) > 0")
                            .alias("supp")
                            .join(
                                    finalPairDf.alias("pairs"),
                                    col("supp.ns_item_id").equalTo(col("pairs.ns_item_id"))
                                            .and(col("supp.ns_location_id").equalTo(col("pairs.ns_location_id"))),
                                    "inner"
                            )
                            .select(
                                    col("supp.ns_item_id").alias("ns_item_id"),
                                    col("supp.ns_location_id").alias("ns_location_id"),
                                    col("supp.row_json").alias("row_json")
                            )

                    supplementalRowsDf.collectAsList().each { Row sRow ->
                        String pairKey = "${normalize(sRow.getAs('ns_item_id'))}|${normalize(sRow.getAs('ns_location_id'))}".toString()
                        Map payload = parseRowJson(normalize(sRow.getAs("row_json")))
                        datasetByPair[pairKey] << payload
                        supplementalRowCountByDataset[datasetKey] = supplementalRowCountByDataset[datasetKey] + 1
                        supplementalRecordCount++
                    }
                    supplementalInputFilesByDataset[datasetKey] << datasetFile.absolutePath
                } catch (Exception e) {
                    warningList.add("Failed loading OMS supplemental dataset ${datasetKey} from ${datasetFile.name}: ${normalize(e.message) ?: e.class.simpleName}")
                }
            }
            if (matchedPrefix) warningList.add("OMS supplemental dataset ${datasetKey} loaded using prefix ${matchedPrefix}.")
            supplementalRowsByDatasetByPair[datasetKey] = datasetByPair
        }
    } else {
        warningList.add("OMS supplemental directory not found: ${omsSupplementalDirLocationValue}.")
        supplementalConfigs.each { Map cfg ->
            supplementalRowsByDatasetByPair[(cfg.key as String)] = [:].withDefault { [] }
        }
    }

    Dataset discrepancyRowsDf = discrepancyDf
            .selectExpr(
                    "cast(${nsItemIdFieldExpr} as string) as ns_item_id",
                    "cast(${nsLocationIdFieldExpr} as string) as ns_location_id",
                    "to_json(struct(*)) as row_json"
            )
            .filter("ns_item_id IS NOT NULL AND length(trim(ns_item_id)) > 0 AND ns_location_id IS NOT NULL AND length(trim(ns_location_id)) > 0")

    Map<String, List<Map>> discrepancyByPair = [:].withDefault { [] }
    discrepancyRowsDf.collectAsList().each { Row row ->
        String key = "${normalize(row.getAs('ns_item_id'))}|${normalize(row.getAs('ns_location_id'))}".toString()
        discrepancyByPair[key] << parseRowJson(normalize(row.getAs("row_json")))
    }

    List<String> omsSelectExpr = [
            "cast(${omsItemIdFieldExpr} as string) as oms_item_id",
            "cast(${omsLocationIdFieldExpr} as string) as oms_location_id",
            "to_json(struct(*)) as row_json"
    ]
    if (omsTxnDateFieldExpr) omsSelectExpr.add("cast(${omsTxnDateFieldExpr} as string) as oms_txn_date")
    if (omsQuantityFieldExpr) omsSelectExpr.add("cast(${omsQuantityFieldExpr} as string) as oms_qty")

    Dataset omsRowsDf = omsDf
            .selectExpr(omsSelectExpr as String[])
            .filter("oms_item_id IS NOT NULL AND length(trim(oms_item_id)) > 0 AND oms_location_id IS NOT NULL AND length(trim(oms_location_id)) > 0")

    Map<String, List<Map>> omsRowsByPair = [:].withDefault { [] }
    omsRowsDf.collectAsList().each { Row row ->
        String key = "${normalize(row.getAs('oms_item_id'))}|${normalize(row.getAs('oms_location_id'))}".toString()
        Map payload = parseRowJson(normalize(row.getAs("row_json")))
        payload.__omsTxnDate = normalize(row.getAs("oms_txn_date"))
        payload.__omsQty = normalize(row.getAs("oms_qty"))
        omsRowsByPair[key] << payload
    }

    Map<String, Map> nsResultByPair = [:]
    List<Map> nsChunkResultsList = []
    int totalRetries = 0
    String nsDataSourceValue = "FETCHED"

    if (persistedNsOutputLocationValue) {
        String persistedPath = resolvePath(persistedNsOutputLocationValue)
        Object persistedPayload = null
        try {
            def persistedRef = ec.resource.getLocationReference(persistedNsOutputLocationValue)
            if (persistedRef != null && persistedRef.getExists()) {
                persistedPayload = new JsonSlurper().parseText(persistedRef.getText())
            } else {
                File persistedFile = new File(persistedPath)
                if (!persistedFile.exists()) {
                    throw new IllegalArgumentException("Persisted NS output file not found at ${persistedPath}")
                }
                persistedPayload = new JsonSlurper().parse(persistedFile)
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to parse persistedNsOutputLocation ${persistedNsOutputLocationValue}: ${normalize(e.message) ?: e.class.simpleName}")
        }

        List persistedRows = []
        if (persistedPayload instanceof Map) {
            Map payloadMap = (Map) persistedPayload
            if (payloadMap.rows instanceof List) persistedRows = (List) payloadMap.rows
            else if (payloadMap.itemResults instanceof List) persistedRows = (List) payloadMap.itemResults
            else throw new IllegalArgumentException("Persisted NS payload must include rows[] (NS output) or itemResults[] (summary)")
        } else if (persistedPayload instanceof List) {
            persistedRows = (List) persistedPayload
        } else {
            throw new IllegalArgumentException("Persisted NS payload must be a JSON object or list")
        }

        persistedRows.each { Object rowObj ->
            if (!(rowObj instanceof Map)) return
            Map row = (Map) rowObj
            String rowPairId = normalize(row.pairId)
            String rowItemId = normalize(row.itemId ?: row.nsItemId)
            String rowLocationId = normalize(row.locationId ?: row.nsLocationId)
            String pairKey = rowPairId ?: ((rowItemId && rowLocationId) ? "${rowItemId}|${rowLocationId}".toString() : null)
            if (!pairKey) return

            String fallbackItemId = rowItemId
            String fallbackLocationId = rowLocationId
            if ((!fallbackItemId || !fallbackLocationId) && pairKey.contains("|")) {
                int pipeIdx = pairKey.indexOf("|")
                if (!fallbackItemId) fallbackItemId = pairKey.substring(0, pipeIdx)
                if (!fallbackLocationId) fallbackLocationId = pairKey.substring(pipeIdx + 1)
            }

            List rowRecords = []
            if (row.records instanceof List) rowRecords = (List) row.records
            else if (row.nsRecords instanceof List) rowRecords = (List) row.nsRecords
            List<Map> normalizedRecords = normalizeNsRecords(rowRecords, fallbackItemId, fallbackLocationId)
            int rowCount = row.recordCount != null
                    ? toInt(row.recordCount)
                    : (row.nsRecordCount != null ? toInt(row.nsRecordCount) : normalizedRecords.size())

            nsResultByPair[pairKey] = [
                    pairId      : pairKey,
                    itemId      : fallbackItemId,
                    locationId  : fallbackLocationId,
                    status      : normalize(row.status ?: row.nsStatus) ?: "OK",
                    errorMessage: normalize(row.errorMessage ?: row.error ?: row.nsError),
                    recordCount : rowCount,
                    records     : normalizedRecords
            ]
        }

        warningList.add("Reusing persisted NS data from ${persistedNsOutputLocationValue}; loaded ${nsResultByPair.size()} pair results.")
        nsDataSourceValue = "REUSED_PERSISTED"
    }

    List<Map> pairsToFetch = itemPairs.findAll { Map pair -> !nsResultByPair.containsKey(pair.pairId) }
    if (pairsToFetch && persistedNsOutputLocationValue && !fetchMissingNsPairsEnabled) {
        warningList.add("Persisted NS data missing ${pairsToFetch.size()} pair(s); fetchMissingNsPairs=false so missing pairs were marked as ERROR.")
        pairsToFetch.each { Map pair ->
            nsResultByPair[pair.pairId] = [
                    pairId      : pair.pairId,
                    itemId      : pair.itemId,
                    locationId  : pair.locationId,
                    status      : "ERROR",
                    errorMessage: "Missing from persisted NS data and fetchMissingNsPairs is false",
                    recordCount : 0,
                    records     : []
            ]
        }
        nsChunkResultsList << [
                chunkIndex: 0,
                pairCount : itemPairs.size(),
                attempts  : 0,
                status    : "REUSED_WITH_GAPS",
                error     : "Missing persisted pairs were not fetched",
                pairIds   : itemPairs.collect { it.pairId }
        ]
    } else {
        if (persistedNsOutputLocationValue && !pairsToFetch) {
            nsChunkResultsList << [
                    chunkIndex: 0,
                    pairCount : itemPairs.size(),
                    attempts  : 0,
                    status    : "REUSED",
                    error     : null,
                    pairIds   : itemPairs.collect { it.pairId }
            ]
        }

        if (pairsToFetch) {
            if (persistedNsOutputLocationValue && fetchMissingNsPairsEnabled) {
                warningList.add("Persisted NS data missing ${pairsToFetch.size()} pair(s); fetching only missing pairs from NetSuite.")
                nsDataSourceValue = "MIXED_REUSED_AND_FETCHED"
            } else {
                nsDataSourceValue = "FETCHED"
            }

            int chunkIndexBase = nsChunkResultsList.size()
            List<List<Map>> chunks = pairsToFetch.collate(chunkSize)
            chunks.eachWithIndex { List<Map> chunk, int chunkIdx ->
                int effectiveChunkIndex = chunkIndexBase + chunkIdx
                int attempt = 0
                boolean done = false
                Map chunkResult = null
                Exception lastFailure = null

                while (!done) {
                    attempt++
                    try {
                        Map bulkOut = ec.service.sync()
                                .name("reconciliation.NetSuiteInventoryServices.fetch#NsInventoryAdjustmentsBulk")
                                .parameters([
                                        nsRestletConfigId: nsConfigId,
                                        itemPairs        : chunk,
                                        from             : fromDateStr,
                                        to               : toDateStr,
                                        strictMaxPairs   : true
                                ])
                                .call()

                        List pairResults = (bulkOut.pairResults ?: []) as List
                        pairResults.each { Object rowObj ->
                            Map row = (rowObj instanceof Map) ? ((Map) rowObj) : [:]
                            String rowPairId = normalize(row.pairId) ?: "${normalize(row.itemId)}|${normalize(row.locationId)}".toString()
                            if (rowPairId) {
                                String rowItemId = normalize(row.itemId) ?: normalize(row.nsItemId)
                                String rowLocationId = normalize(row.locationId) ?: normalize(row.nsLocationId)
                                List rowRecords = (row.records instanceof List) ? (List) row.records : []
                                List<Map> normalizedRecords = normalizeNsRecords(rowRecords, rowItemId, rowLocationId)
                                Map normalizedRow = new LinkedHashMap(row)
                                normalizedRow.itemId = rowItemId
                                normalizedRow.locationId = rowLocationId
                                normalizedRow.records = normalizedRecords
                                if (normalizedRow.recordCount == null) normalizedRow.recordCount = normalizedRecords.size()
                                nsResultByPair[rowPairId] = normalizedRow
                            }
                        }

                        chunkResult = [
                                chunkIndex: effectiveChunkIndex,
                                pairCount : chunk.size(),
                                attempts  : attempt,
                                status    : "SUCCESS",
                                error     : null,
                                pairIds   : chunk.collect { it.pairId }
                        ]
                        done = true
                    } catch (Exception e) {
                        lastFailure = e
                        boolean retryable = isRetryableFailure(e.message)
                        if (retryable && attempt <= maxRetries) {
                            totalRetries++
                            long sleepMs = (retryBackoffMs * backoffMultiplier.pow(attempt - 1)).longValue()
                            if (sleepMs > 0L) Thread.sleep(sleepMs)
                            continue
                        }

                        chunkResult = [
                                chunkIndex: effectiveChunkIndex,
                                pairCount : chunk.size(),
                                attempts  : attempt,
                                status    : "FAILED",
                                error     : normalize(e.message) ?: "Chunk failed",
                                pairIds   : chunk.collect { it.pairId }
                        ]

                        if (continueOnChunkFailure) {
                            chunk.each { Map pair ->
                                nsResultByPair[pair.pairId] = [
                                        pairId      : pair.pairId,
                                        itemId      : pair.itemId,
                                        locationId  : pair.locationId,
                                        status      : "ERROR",
                                        errorMessage: chunkResult.error,
                                        recordCount : 0,
                                        records     : []
                                ]
                            }
                            warningList.add("NS chunk ${effectiveChunkIndex} failed after ${attempt} attempt(s): ${chunkResult.error}")
                            done = true
                        } else {
                            throw lastFailure
                        }
                    }
                }

                nsChunkResultsList << chunkResult
                if (nsDelayMsToUse > 0 && chunkIdx < chunks.size() - 1) Thread.sleep(nsDelayMsToUse as long)
            }
        }
    }

    List<Map> itemResultRows = itemPairs.collect { Map pair ->
        String pairKey = pair.pairId
        List<Map> discrepancyRows = (discrepancyByPair[pairKey] ?: []) as List<Map>
        List<Map> omsRows = (omsRowsByPair[pairKey] ?: []) as List<Map>
        Map<String, List<Map>> supplementalRows = [:]
        supplementalRowsByDatasetByPair.each { String datasetKey, Map<String, List<Map>> byPair ->
            supplementalRows[datasetKey] = ((byPair?.get(pairKey) ?: []) as List<Map>)
        }
        int supplementalCount = supplementalRows.values().collect { List<Map> rows -> rows?.size() ?: 0 }.sum() as int
        Map nsRow = (nsResultByPair[pairKey] ?: [
                pairId      : pairKey,
                itemId      : pair.itemId,
                locationId  : pair.locationId,
                status      : "ERROR",
                errorMessage: "No NS result returned",
                recordCount : 0,
                records     : []
        ]) as Map

        List<Map> nsRecords = normalizeNsRecords((nsRow.records instanceof List) ? (List) nsRow.records : [], pair.itemId, pair.locationId)
        int nsCount = nsRow.recordCount != null ? toInt(nsRow.recordCount) : nsRecords.size()

        BigDecimal omsQtyTotal = omsRows.collect { Map r -> toDecimal(r.__omsQty) }.inject(BigDecimal.ZERO) { a, b -> a + b }
        String omsMinDate = omsRows.collect { Map r -> normalize(r.__omsTxnDate) }.findAll { it }.sort().with { it ? it.first() : null }
        String omsMaxDate = omsRows.collect { Map r -> normalize(r.__omsTxnDate) }.findAll { it }.sort().with { it ? it.last() : null }

        BigDecimal nsQtyTotal = nsRecords.collect { Map r -> extractQty(r) }.inject(BigDecimal.ZERO) { a, b -> a + b }
        List<String> nsDates = nsRecords.collect { Map r -> extractTxnDate(r) }.findAll { it }
        nsDates = nsDates.sort()
        String nsMinDate = nsDates ? nsDates.first() : null
        String nsMaxDate = nsDates ? nsDates.last() : null

        int omsCount = omsRows.size()
        String nsStatus = normalize(nsRow.status) ?: "OK"

        List<String> matchedRuleIdsSeed = []
        Map signalFlagsSeed = buildInitialSignalFlags(nsRecords, omsRows, supplementalRows, discrepancyRows ? discrepancyRows[0] : [:])

        [
                pairId            : pairKey,
                itemId            : pair.itemId,
                locationId        : pair.locationId,
                nsItemId          : pair.itemId,
                nsLocationId      : pair.locationId,
                readDbItemId      : pair.itemId,
                readDbLocationId  : pair.locationId,
                discrepancy       : discrepancyRows ? discrepancyRows[0] : [:],
                discrepancyRows   : discrepancyRows,
                omsDetailRows     : omsRows,
                omsSupplementalRows: supplementalRows,
                omsSupplementalRecordCount: supplementalCount,
                nsStatus          : nsStatus,
                nsRecordCount     : nsCount,
                nsError           : normalize(nsRow.errorMessage ?: nsRow.error),
                nsRecords         : nsRecords,
                readDbStatus      : (omsCount > 0 ? "OK" : "MISSING"),
                readDbRecordCount : omsCount,
                readDbError       : null,
                readDbRecords     : omsRows,
                omsRecordCount    : omsCount,
                omsQuantityTotal  : omsQtyTotal,
                omsMinTxnDate     : omsMinDate,
                omsMaxTxnDate     : omsMaxDate,
                nsQuantityTotal   : nsQtyTotal,
                nsMinTxnDate      : nsMinDate,
                nsMaxTxnDate      : nsMaxDate,
                recordCountDelta  : nsCount - omsCount,
                quantityDelta     : nsQtyTotal.subtract(omsQtyTotal),
                (compareStatusFieldName): null,
                (reasonCodeFieldName)  : null,
                (reasonTextFieldName)  : null,
                candidates        : [],
                signalFlags       : signalFlagsSeed,
                matchedRuleIds    : matchedRuleIdsSeed,
                (matchedRuleIdsFieldName): matchedRuleIdsSeed
        ]
    }

    Map ruleOut = ec.service.sync()
            .name("reconciliation.ReconciliationInventoryServices.evaluate#InventoryAdjustmentComparisonRules")
            .parameters([
                    ruleSetId     : comparisonRuleSetIdToUse,
                    dataList      : itemResultRows,
                    returnAllFacts: true
            ])
            .call()
    if (ruleOut.error) {
        throw new IllegalArgumentException("Comparison rules failed for ruleSet ${comparisonRuleSetIdToUse}: ${normalize(ruleOut.error)}")
    }

    List<Map> ruledRows = ((ruleOut.results ?: []) as List).collect { Object rowObj ->
        (rowObj instanceof Map) ? new LinkedHashMap((Map) rowObj) : [:]
    }
    ruledRows.each { Map row ->
        List matchedAliases = (row.matchedRuleIds instanceof List) ? (List) row.matchedRuleIds : []
        List matchedField = (row[matchedRuleIdsFieldName] instanceof List) ? (List) row[matchedRuleIdsFieldName] : []
        LinkedHashSet merged = new LinkedHashSet()
        merged.addAll(matchedAliases)
        merged.addAll(matchedField)
        List mergedList = new ArrayList(merged)
        row.matchedRuleIds = mergedList
        row[matchedRuleIdsFieldName] = mergedList
    }
    if (ruledRows.size() != itemResultRows.size()) {
        throw new IllegalArgumentException("Comparison rules returned ${ruledRows.size()} rows; expected ${itemResultRows.size()}")
    }

    Map<String, Map> nsSecondaryDetailCacheByPair = [:]
    Map<String, Map> nsSecondaryDetailByPair = [:]
    int nsDetailCacheHitCount = 0
    int nsDetailComputedCount = 0
    int nsDetailErrorCount = 0
    String nsDetailDataSourceValue = "DERIVED_PRIMARY"

    if (nsDetailCacheLocationValue && !forceRefreshNsDetailsEnabled) {
        try {
            Object detailPayload
            def detailRef = ec.resource.getLocationReference(nsDetailCacheLocationValue)
            if (detailRef != null && detailRef.getExists()) {
                detailPayload = new JsonSlurper().parseText(detailRef.getText())
            } else {
                File detailFile = new File(resolvePath(nsDetailCacheLocationValue))
                if (detailFile.exists() && detailFile.isFile()) {
                    detailPayload = new JsonSlurper().parse(detailFile)
                } else {
                    warningList.add("nsDetailCacheLocation not found at ${nsDetailCacheLocationValue}; deriving secondary detail from primary NS rows.")
                }
            }
            List cacheRows = []
            if (detailPayload instanceof Map) {
                Map detailMap = (Map) detailPayload
                if (detailMap.rows instanceof List) cacheRows = (List) detailMap.rows
                else if (detailMap.pairs instanceof List) cacheRows = (List) detailMap.pairs
            } else if (detailPayload instanceof List) {
                cacheRows = (List) detailPayload
            }
            cacheRows.each { Object rowObj ->
                if (!(rowObj instanceof Map)) return
                Map row = (Map) rowObj
                String cachePairId = normalize(row.pairId)
                if (cachePairId) nsSecondaryDetailCacheByPair[cachePairId] = new LinkedHashMap(row)
            }
            if (!nsSecondaryDetailCacheByPair.isEmpty()) {
                nsDetailDataSourceValue = "REUSED_CACHE"
                warningList.add("Loaded NS secondary detail cache from ${nsDetailCacheLocationValue} for ${nsSecondaryDetailCacheByPair.size()} pair(s).")
            }
        } catch (Exception e) {
            nsDetailErrorCount++
            warningList.add("Unable to parse nsDetailCacheLocation ${nsDetailCacheLocationValue}: ${normalize(e.message) ?: e.class.simpleName}")
        }
    } else if (nsDetailCacheLocationValue && forceRefreshNsDetailsEnabled) {
        warningList.add("forceRefreshNsDetails=true; ignoring nsDetailCacheLocation ${nsDetailCacheLocationValue}.")
    }

    int strictCycleGuardReclassifiedCount = 0
    int strictCycleConfirmedCount = 0
    ruledRows.each { Map row ->
        String statusValue = normalize(row[compareStatusFieldName])
        if (!normalize(row[reasonCodeFieldName]) && statusValue) {
            row[reasonCodeFieldName] = statusValue
            row[reasonTextFieldName] = normalize(row[reasonTextFieldName]) ?: "Derived from compareStatus ${statusValue}"
        }
        Object matchedIds = row[matchedRuleIdsFieldName]
        if (!(matchedIds instanceof List)) {
            if (matchedIds == null) row[matchedRuleIdsFieldName] = []
            else row[matchedRuleIdsFieldName] = [matchedIds]
        }

        Map discrepancy = (row.discrepancy instanceof Map) ? (Map) row.discrepancy : [:]
        List<Map> discrepancyRowsRaw = (row.discrepancyRows instanceof List) ? (List<Map>) row.discrepancyRows : []
        List<Map> omsRowsRaw = (row.omsDetailRows instanceof List) ? (List<Map>) row.omsDetailRows : []
        Map supplementalRowsRaw = (row.omsSupplementalRows instanceof Map) ? (Map) row.omsSupplementalRows : [:]
        List<Map> nsRows = normalizeNsRecords((row.nsRecords instanceof List) ? (List) row.nsRecords : [], normalize(row.nsItemId ?: row.itemId), normalize(row.nsLocationId ?: row.locationId))

        LinkedHashSet<String> itemScopeCandidates = new LinkedHashSet<>()
        [normalize(row.nsItemId), normalize(row.itemId), normalize(discrepancy.netsuite_product_id), normalize(discrepancy.product_id)].findAll { it }.each { itemScopeCandidates.add(it) }
        LinkedHashSet<String> facilityScopeCandidates = new LinkedHashSet<>()
        [normalize(row.nsLocationId), normalize(row.locationId), normalize(discrepancy.facility_id), normalize(discrepancy.external_facility_id)].findAll { it }.each { facilityScopeCandidates.add(it) }

        List<Map> discrepancyRows = discrepancyRowsRaw.findAll { Map dRow -> rowMatchesItemFacilityScope(dRow, itemScopeCandidates, facilityScopeCandidates) } as List<Map>
        if (discrepancyRows.isEmpty()) discrepancyRows = discrepancyRowsRaw
        List<Map> omsRows = omsRowsRaw.findAll { Map oRow -> rowMatchesItemFacilityScope(oRow, itemScopeCandidates, facilityScopeCandidates) } as List<Map>
        if (omsRows.isEmpty()) omsRows = omsRowsRaw
        List<Map> transferLifecycleRowsRaw = (supplementalRowsRaw.transferLifecycle instanceof List) ? (List<Map>) supplementalRowsRaw.transferLifecycle : []
        List<Map> returnRefundRowsRaw = (supplementalRowsRaw.returnRefundLinkage instanceof List) ? (List<Map>) supplementalRowsRaw.returnRefundLinkage : []
        List<Map> orderFulfillmentRowsRaw = (supplementalRowsRaw.orderFulfillmentBackorder instanceof List) ? (List<Map>) supplementalRowsRaw.orderFulfillmentBackorder : []
        List<Map> cycleCountRowsRaw = (supplementalRowsRaw.cycleCountAudit instanceof List) ? (List<Map>) supplementalRowsRaw.cycleCountAudit : []
        List<Map> itemFacilityRowsRaw = (supplementalRowsRaw.itemFacilityMapping instanceof List) ? (List<Map>) supplementalRowsRaw.itemFacilityMapping : []

        List<Map> transferLifecycleRows = transferLifecycleRowsRaw.findAll { Map sRow -> rowMatchesItemFacilityScope(sRow, itemScopeCandidates, facilityScopeCandidates) } as List<Map>
        if (transferLifecycleRows.isEmpty()) transferLifecycleRows = transferLifecycleRowsRaw
        List<Map> returnRefundRows = returnRefundRowsRaw.findAll { Map sRow -> rowMatchesItemFacilityScope(sRow, itemScopeCandidates, facilityScopeCandidates) } as List<Map>
        if (returnRefundRows.isEmpty()) returnRefundRows = returnRefundRowsRaw
        List<Map> orderFulfillmentRows = orderFulfillmentRowsRaw.findAll { Map sRow -> rowMatchesItemFacilityScope(sRow, itemScopeCandidates, facilityScopeCandidates) } as List<Map>
        if (orderFulfillmentRows.isEmpty()) orderFulfillmentRows = orderFulfillmentRowsRaw
        List<Map> cycleCountRows = cycleCountRowsRaw.findAll { Map sRow -> rowMatchesItemFacilityScope(sRow, itemScopeCandidates, facilityScopeCandidates) } as List<Map>
        if (cycleCountRows.isEmpty()) cycleCountRows = cycleCountRowsRaw
        List<Map> itemFacilityRows = itemFacilityRowsRaw.findAll { Map sRow -> rowMatchesItemFacilityScope(sRow, itemScopeCandidates, facilityScopeCandidates) } as List<Map>
        if (itemFacilityRows.isEmpty()) itemFacilityRows = itemFacilityRowsRaw

        row.discrepancyRows = discrepancyRows
        row.omsDetailRows = omsRows
        row.readDbRecords = omsRows
        row.omsSupplementalRows = [
                transferLifecycle      : transferLifecycleRows,
                returnRefundLinkage    : returnRefundRows,
                orderFulfillmentBackorder: orderFulfillmentRows,
                cycleCountAudit        : cycleCountRows,
                itemFacilityMapping    : itemFacilityRows
        ]
        row.omsSupplementalRecordCount = (transferLifecycleRows.size() + returnRefundRows.size() + orderFulfillmentRows.size() + cycleCountRows.size() + itemFacilityRows.size())
        row.nsRecords = nsRows

        LinkedHashSet<String> txnRefDedup = new LinkedHashSet<>()
        List<Map> nsTransactionRefs = []
        nsRows.each { Map nsRecord ->
            Map tx = extractTransaction(nsRecord)
            String refId = extractTxnId(nsRecord)
            String refTranId = extractTranId(nsRecord)
            String txnTypeRef = normalize(tx.type)
            String refDate = extractTxnDate(nsRecord)
            String refQty = extractQty(nsRecord).toPlainString()
            String dedupKey = [refId ?: "", refTranId ?: "", txnTypeRef ?: "", refDate ?: ""].join("|")
            if (txnRefDedup.contains(dedupKey)) return
            txnRefDedup.add(dedupKey)
            nsTransactionRefs << [
                    id   : refId,
                    tranid: refTranId,
                    type : txnTypeRef,
                    date : refDate,
                    qty  : refQty
            ]
        }
        row.nsTransactionRefs = nsTransactionRefs

        String rowPairId = normalize(row.pairId)
        Map cachedSecondary = rowPairId ? nsSecondaryDetailCacheByPair[rowPairId] : null
        Map nsSecondaryDetails
        if (cachedSecondary instanceof Map) {
            nsSecondaryDetails = new LinkedHashMap((Map) cachedSecondary)
            nsDetailCacheHitCount++
        } else {
            nsSecondaryDetails = [
                    pairId              : rowPairId,
                    dataSource          : "DERIVED_PRIMARY",
                    generatedAt         : ec.user.nowTimestamp?.toString(),
                    detailRows          : nsRows.collect { Map nsRecord ->
                        Map tx = extractTransaction(nsRecord)
                        [
                                id                  : extractTxnId(nsRecord),
                                tranid              : extractTranId(nsRecord),
                                type                : normalize(tx.type),
                                status              : normalize(tx.transferStatus ?: tx.status ?: tx.orderStatus),
                                sourceLocationId    : normalize(tx.sourceLocationId ?: tx.fromLocationId ?: tx.transferFromLocationId ?: tx.sourceLocation),
                                destinationLocationId: normalize(tx.destinationLocationId ?: tx.toLocationId ?: tx.transferToLocationId ?: tx.destinationLocation),
                                shippedQty          : toDecimal(tx.shippedQty ?: tx.quantityShipped ?: tx.qtyShipped).toPlainString(),
                                receivedQty         : toDecimal(tx.receivedQty ?: tx.quantityReceived ?: tx.qtyReceived).toPlainString(),
                                itemReceiptId       : normalize(tx.itemReceiptId ?: tx.receiptTransactionId ?: tx.receiptId),
                                receiptDate         : normalize(tx.receiptDate ?: tx.itemReceiptDate),
                                transferStatus      : normalize(tx.transferStatus ?: tx.status),
                                raw                 : tx
                        ]
                    },
                    detailByTransaction : nsTransactionRefs.collect { Map ref ->
                        [
                                id   : normalize(ref.id),
                                tranid: normalize(ref.tranid),
                                type : normalize(ref.type),
                                date : normalize(ref.date),
                                qty  : normalize(ref.qty)
                        ]
                    }
            ]
            nsDetailComputedCount++
        }
        row.nsSecondaryDetails = nsSecondaryDetails
        if (rowPairId) nsSecondaryDetailByPair[rowPairId] = nsSecondaryDetails
        List<Map> nsDetailRows = (nsSecondaryDetails.detailRows instanceof List) ? ((List) nsSecondaryDetails.detailRows).collect { it instanceof Map ? new LinkedHashMap((Map) it) : [value: it] } : []

        LinkedHashSet<String> nsTxnTypes = new LinkedHashSet<>()
        int nsSalesOrderCount = 0
        int nsFulfillmentCount = 0
        int nsCreditMemoCount = 0
        int nsTransferCount = 0
        int nsInventoryAdjustmentCount = 0
        BigDecimal nsFulfillmentQtyAbsMax = BigDecimal.ZERO
        nsRows.each { Map nsRecord ->
            Map tx = extractTransaction(nsRecord)
            String txnType = normalize(tx.type)
            if (!txnType) return
            nsTxnTypes.add(txnType)
            BigDecimal txnQtyAbs = extractQty(nsRecord).abs()
            if (txnType == "Sales Order") nsSalesOrderCount++
            if (txnType == "Item Fulfillment") {
                nsFulfillmentCount++
                if (txnQtyAbs > nsFulfillmentQtyAbsMax) nsFulfillmentQtyAbsMax = txnQtyAbs
            }
            if (txnType == "Credit Memo") nsCreditMemoCount++
            if (txnType == "Transfer Order" || txnType == "Inventory Transfer") nsTransferCount++
            if (txnType == "Inventory Adjustment") nsInventoryAdjustmentCount++
        }

        List<Map> transferDetailRows = nsDetailRows.findAll { Map dRow ->
            String txnType = normalize(dRow.type)
            txnType in ["Transfer Order", "Inventory Transfer", "Item Receipt"]
        } as List<Map>
        int nsTransferDetailEventCount = transferDetailRows.size()
        int nsReceivedEventCount = transferDetailRows.count { Map dRow ->
            String status = normalize(dRow.transferStatus ?: dRow.status)?.toLowerCase()
            toDecimal(dRow.receivedQty) > BigDecimal.ZERO ||
                    normalize(dRow.itemReceiptId) ||
                    normalize(dRow.receiptDate) ||
                    normalize(dRow.type) == "Item Receipt" ||
                    (status?.contains("received") ?: false)
        } as int
        BigDecimal nsTransferredQtyTotal = transferDetailRows.collect { Map dRow -> toDecimal(dRow.shippedQty) }.inject(BigDecimal.ZERO) { a, b -> a + b }
        BigDecimal nsReceivedQtyTotal = transferDetailRows.collect { Map dRow -> toDecimal(dRow.receivedQty) }.inject(BigDecimal.ZERO) { a, b -> a + b }
        LinkedHashSet<String> nsTransferStatuses = new LinkedHashSet<>()
        transferDetailRows.each { Map dRow ->
            String status = normalize(dRow.transferStatus ?: dRow.status)
            if (status) nsTransferStatuses.add(status)
        }

        List<String> orderIds = collectDistinctValues(omsRows, "ORDER_ID", 5)
        List<String> returnIds = collectDistinctValues(omsRows, "RETURN_ID", 5)
        List<String> shipmentIds = collectDistinctValues(omsRows, "SHIPMENT_ID", 5)
        List<String> receiptIds = collectDistinctValues(omsRows, "RECEIPT_ID", 5)
        List<String> physicalInventoryIds = collectDistinctValues(omsRows, "PHYSICAL_INVENTORY_ID", 5)

        int omsOrderEventCount = 0
        int omsReturnEventCount = 0
        int omsShipmentEventCount = 0
        int omsReceiptEventCount = 0
        int omsPhysicalInventoryEventCount = 0
        int omsReasonEnumEventCount = 0
        int omsItemIssuanceEventCount = 0
        BigDecimal omsOrderQtyAbsMax = BigDecimal.ZERO
        omsRows.each { Map omsRow ->
            String orderId = normalize(omsRow.ORDER_ID)
            String returnId = normalize(omsRow.RETURN_ID)
            String shipmentId = normalize(omsRow.SHIPMENT_ID)
            String receiptId = normalize(omsRow.RECEIPT_ID)
            String itemIssuanceId = normalize(omsRow.ITEM_ISSUANCE_ID)
            String physicalInventoryId = normalize(omsRow.PHYSICAL_INVENTORY_ID)
            String reasonEnum = normalize(omsRow.REASON_ENUM_ID)

            if (orderId) {
                omsOrderEventCount++
                BigDecimal qtyAbs = toDecimal(omsRow.QUANTITY_ON_HAND_DIFF ?: omsRow.__omsQty).abs()
                if (qtyAbs > omsOrderQtyAbsMax) omsOrderQtyAbsMax = qtyAbs
            }
            if (returnId) omsReturnEventCount++
            if (shipmentId) omsShipmentEventCount++
            if (receiptId) omsReceiptEventCount++
            if (itemIssuanceId) omsItemIssuanceEventCount++
            if (physicalInventoryId) omsPhysicalInventoryEventCount++
            if (reasonEnum) omsReasonEnumEventCount++
        }

        int supplementalTransferEventCount = transferLifecycleRows.size()
        int supplementalShipmentEventCount = transferLifecycleRows.count { Map sRow -> normalize(sRow.shipment_id) } as int
        int supplementalReceiptEventCount = transferLifecycleRows.count { Map sRow ->
            String lifecycleState = normalize(sRow.transfer_lifecycle_state)?.toUpperCase()
            toDecimal(sRow.received_qty) > BigDecimal.ZERO ||
                    normalize(sRow.receipt_datetime) ||
                    ["RECEIVED", "PARTIALLY_RECEIVED"].contains(lifecycleState)
        } as int
        int supplementalReturnEventCount = returnRefundRows.count { Map sRow -> normalize(sRow.return_id) } as int
        int supplementalRefundEventCount = returnRefundRows.count { Map sRow ->
            normalize(sRow.payment_id) || normalize(sRow.payment_application_id) || toDecimal(sRow.refund_amount) != BigDecimal.ZERO
        } as int
        int supplementalPhysicalInventoryEventCount = cycleCountRows.count { Map sRow -> normalize(sRow.physical_inventory_id) } as int
        int supplementalReasonEnumEventCount = cycleCountRows.count { Map sRow -> normalize(sRow.reason_enum_id) } as int
        int supplementalBackorderEventCount = orderFulfillmentRows.count { Map sRow ->
            String fulfillmentState = normalize(sRow.fulfillment_state)?.toUpperCase()
            fulfillmentState == "BACKORDER_OR_OPEN" || toDecimal(sRow.open_unissued_qty) > BigDecimal.ZERO
        } as int

        LinkedHashSet<String> supplementalOrderIds = new LinkedHashSet<>(collectDistinctValues(transferLifecycleRows, "transfer_order_id", 5))
        supplementalOrderIds.addAll(collectDistinctValues(orderFulfillmentRows, "order_id", 5))
        LinkedHashSet<String> supplementalReturnIds = new LinkedHashSet<>(collectDistinctValues(returnRefundRows, "return_id", 5))
        LinkedHashSet<String> supplementalShipmentIds = new LinkedHashSet<>(collectDistinctValues(transferLifecycleRows, "shipment_id", 5))

        orderIds.addAll(supplementalOrderIds as List<String>)
        returnIds.addAll(supplementalReturnIds as List<String>)
        shipmentIds.addAll(supplementalShipmentIds as List<String>)

        boolean hasSalesOrderSignal = nsSalesOrderCount > 0
        boolean hasItemFulfillmentSignal = nsFulfillmentCount > 0
        boolean hasCreditMemoSignal = nsCreditMemoCount > 0
        boolean hasTransferSignal = nsTransferCount > 0 || nsTransferDetailEventCount > 0
        boolean hasInventoryAdjustmentSignal = nsInventoryAdjustmentCount > 0
        boolean hasOmsReturnSignal = omsReturnEventCount > 0 || supplementalReturnEventCount > 0 || omsRows.any { Map omsRow -> normalize(omsRow.REASON_ENUM_ID)?.startsWith("RTN_") }
        boolean hasOmsShipmentSignal = omsShipmentEventCount > 0 || supplementalShipmentEventCount > 0
        boolean hasPhysicalInventorySignal = omsPhysicalInventoryEventCount > 0 || supplementalPhysicalInventoryEventCount > 0

        boolean nsToReceived = nsReceivedEventCount > 0 || nsReceivedQtyTotal > BigDecimal.ZERO
        boolean hcToReceived = omsReceiptEventCount > 0 || supplementalReceiptEventCount > 0
        String toLifecycleState = "NOT_APPLICABLE"
        if (hasTransferSignal || hasOmsShipmentSignal || hcToReceived || omsItemIssuanceEventCount > 0) {
            if (nsToReceived || hcToReceived) toLifecycleState = "RECEIVED"
            else toLifecycleState = "IN_TRANSIT"
        }
        Map toLifecycle = [
                state: toLifecycleState,
                ns   : [
                        transferEventCount: nsTransferDetailEventCount,
                        receivedEventCount: nsReceivedEventCount,
                        transferredQty    : nsTransferredQtyTotal.toPlainString(),
                        receivedQty       : nsReceivedQtyTotal.toPlainString(),
                        statuses          : nsTransferStatuses as List<String>,
                        received          : nsToReceived
                ],
                hc   : [
                        shipmentEventCount : omsShipmentEventCount + supplementalShipmentEventCount,
                        receiptEventCount  : omsReceiptEventCount + supplementalReceiptEventCount,
                        itemIssuanceCount  : omsItemIssuanceEventCount,
                        shipmentIds        : shipmentIds,
                        receiptIds         : receiptIds,
                        supplementalTransferEventCount: supplementalTransferEventCount,
                        supplementalRefundEventCount: supplementalRefundEventCount,
                        supplementalBackorderEventCount: supplementalBackorderEventCount,
                        received           : hcToReceived
                ]
        ]
        row.toLifecycle = toLifecycle

        List<Map> eventTimeline = []
        nsRows.each { Map nsRecord ->
            Map tx = extractTransaction(nsRecord)
            eventTimeline << [
                    source      : "NS_PRIMARY",
                    eventType   : normalize(tx.type) ?: "UNKNOWN",
                    txnDate     : extractTxnDate(nsRecord),
                    epochMillis : parseDateEpoch(extractTxnDate(nsRecord)),
                    id          : extractTxnId(nsRecord),
                    tranid      : extractTranId(nsRecord),
                    status      : normalize(tx.transferStatus ?: tx.status ?: tx.orderStatus),
                    qty         : extractQty(nsRecord).toPlainString()
            ]
        }
        nsDetailRows.each { Map detailRow ->
            String detailDate = normalize(detailRow.receiptDate ?: detailRow.date)
            eventTimeline << [
                    source      : "NS_DETAIL",
                    eventType   : normalize(detailRow.type) ?: "DETAIL",
                    txnDate     : detailDate,
                    epochMillis : parseDateEpoch(detailDate),
                    id          : normalize(detailRow.id),
                    tranid      : normalize(detailRow.tranid),
                    status      : normalize(detailRow.transferStatus ?: detailRow.status),
                    qty         : toDecimal(detailRow.receivedQty ?: detailRow.shippedQty).toPlainString()
            ]
        }
        omsRows.each { Map omsRow ->
            String rowDate = normalize(omsRow.__omsTxnDate ?: omsRow.EFFECTIVE_DATE)
            [
                    [id: normalize(omsRow.ORDER_ID), type: "OMS_ORDER"],
                    [id: normalize(omsRow.RETURN_ID), type: "OMS_RETURN"],
                    [id: normalize(omsRow.SHIPMENT_ID), type: "HC_SHIPMENT"],
                    [id: normalize(omsRow.RECEIPT_ID), type: "HC_RECEIPT"],
                    [id: normalize(omsRow.PHYSICAL_INVENTORY_ID), type: "PHYSICAL_INVENTORY"]
            ].each { Map event ->
                if (!event.id) return
                eventTimeline << [
                        source      : "OMS_HC",
                        eventType   : event.type,
                        txnDate     : rowDate,
                        epochMillis : parseDateEpoch(rowDate),
                        id          : event.id,
                        tranid      : null,
                        status      : normalize(omsRow.REASON_ENUM_ID),
                        qty         : toDecimal(omsRow.QUANTITY_ON_HAND_DIFF ?: omsRow.__omsQty).toPlainString()
                ]
            }
        }
        transferLifecycleRows.each { Map suppRow ->
            String rowDate = normalize(suppRow.event_date)
            String lifecycleState = normalize(suppRow.transfer_lifecycle_state)
            String shipmentId = normalize(suppRow.shipment_id)
            String transferOrderId = normalize(suppRow.transfer_order_id)
            if (shipmentId || transferOrderId) {
                eventTimeline << [
                        source      : "OMS_SUPPLEMENTAL_TRANSFER",
                        eventType   : "TRANSFER_SHIPMENT",
                        txnDate     : rowDate,
                        epochMillis : parseDateEpoch(rowDate),
                        id          : shipmentId ?: transferOrderId,
                        tranid      : transferOrderId,
                        status      : lifecycleState ?: normalize(suppRow.shipment_status_id),
                        qty         : toDecimal(suppRow.shipped_qty).toPlainString()
                ]
            }
            String receiptDate = normalize(suppRow.receipt_datetime)
            if (toDecimal(suppRow.received_qty) > BigDecimal.ZERO || receiptDate || ["RECEIVED", "PARTIALLY_RECEIVED"].contains(lifecycleState?.toUpperCase())) {
                eventTimeline << [
                        source      : "OMS_SUPPLEMENTAL_TRANSFER",
                        eventType   : "TRANSFER_RECEIPT",
                        txnDate     : receiptDate ?: rowDate,
                        epochMillis : parseDateEpoch(receiptDate ?: rowDate),
                        id          : transferOrderId ?: shipmentId,
                        tranid      : transferOrderId,
                        status      : lifecycleState ?: normalize(suppRow.shipment_status_id),
                        qty         : toDecimal(suppRow.received_qty).toPlainString()
                ]
            }
        }
        returnRefundRows.each { Map suppRow ->
            String rowDate = normalize(suppRow.event_date ?: suppRow.refund_effective_date)
            String returnId = normalize(suppRow.return_id)
            String paymentId = normalize(suppRow.payment_id)
            if (returnId) {
                eventTimeline << [
                        source      : "OMS_SUPPLEMENTAL_REFUND",
                        eventType   : "RETURN",
                        txnDate     : rowDate,
                        epochMillis : parseDateEpoch(rowDate),
                        id          : returnId,
                        tranid      : normalize(suppRow.return_item_seq_id),
                        status      : normalize(suppRow.return_status_id),
                        qty         : toDecimal(suppRow.refund_amount).toPlainString()
                ]
            }
            if (paymentId || normalize(suppRow.payment_application_id)) {
                eventTimeline << [
                        source      : "OMS_SUPPLEMENTAL_REFUND",
                        eventType   : "REFUND",
                        txnDate     : rowDate,
                        epochMillis : parseDateEpoch(rowDate),
                        id          : paymentId ?: normalize(suppRow.payment_application_id),
                        tranid      : normalize(suppRow.payment_preference_id),
                        status      : normalize(suppRow.payment_status_id ?: suppRow.payment_preference_status_id),
                        qty         : toDecimal(suppRow.refund_amount).toPlainString()
                ]
            }
        }
        orderFulfillmentRows.each { Map suppRow ->
            String rowDate = normalize(suppRow.event_date)
            if (!normalize(suppRow.order_id)) return
            eventTimeline << [
                    source      : "OMS_SUPPLEMENTAL_ORDER",
                    eventType   : "ORDER_FULFILLMENT_STATE",
                    txnDate     : rowDate,
                    epochMillis : parseDateEpoch(rowDate),
                    id          : normalize(suppRow.order_id),
                    tranid      : normalize(suppRow.order_item_seq_id),
                    status      : normalize(suppRow.fulfillment_state ?: suppRow.order_item_status_id),
                    qty         : toDecimal(suppRow.open_unissued_qty).toPlainString()
            ]
        }
        eventTimeline = eventTimeline.sort { Map a, Map b ->
            long aEpoch = (a.epochMillis instanceof Number) ? ((Number) a.epochMillis).longValue() : Long.MAX_VALUE
            long bEpoch = (b.epochMillis instanceof Number) ? ((Number) b.epochMillis).longValue() : Long.MAX_VALUE
            if (aEpoch != bEpoch) return aEpoch <=> bEpoch
            String aType = normalize(a.eventType) ?: ""
            String bType = normalize(b.eventType) ?: ""
            return aType <=> bType
        } as List<Map>
        row.eventTimeline = eventTimeline.collect { Map event ->
            Map eventCopy = new LinkedHashMap(event)
            eventCopy.remove("epochMillis")
            return eventCopy
        }

        List<Map> supportingFindings = [
                [code: "NS_TRANSFER_SIGNAL", passed: hasTransferSignal, value: nsTransferDetailEventCount, detail: "NetSuite transfer-like activity detected."],
                [code: "NS_RECEIPT_POSTED", passed: nsToReceived, value: nsReceivedEventCount, detail: "NetSuite indicates destination receipt completion."],
                [code: "HC_RECEIPT_PRESENT", passed: hcToReceived, value: (omsReceiptEventCount + supplementalReceiptEventCount), detail: "HC/OMS receipt markers are present for this pair."],
                [code: "OMS_SUPP_TRANSFER_ROWS", passed: supplementalTransferEventCount > 0, value: supplementalTransferEventCount, detail: "Supplemental transfer lifecycle rows are available for this pair."],
                [code: "OMS_SUPP_REFUND_ROWS", passed: supplementalRefundEventCount > 0, value: supplementalRefundEventCount, detail: "Supplemental refund linkage rows are available for this pair."],
                [code: "OMS_SUPP_BACKORDER_ROWS", passed: supplementalBackorderEventCount > 0, value: supplementalBackorderEventCount, detail: "Supplemental order fulfillment/backorder rows are available for this pair."],
                [code: "OMS_SUPP_CYCLE_ROWS", passed: supplementalPhysicalInventoryEventCount > 0 || supplementalReasonEnumEventCount > 0, value: (supplementalPhysicalInventoryEventCount + supplementalReasonEnumEventCount), detail: "Supplemental cycle-count/physical-inventory rows are available for this pair."],
                [code: "TO_PENDING_RECEIPT", passed: (toLifecycleState == "IN_TRANSIT"), value: toLifecycleState, detail: "Transfer appears pending receipt and not yet complete."],
                [code: "TIMELINE_EVENT_COUNT", passed: !eventTimeline.isEmpty(), value: eventTimeline.size(), detail: "Combined source timeline was generated for analysis."]
        ]
        row.supportingFindings = supportingFindings

        BigDecimal discrepancyOmsQoh = toDecimal(discrepancy["HOTWAX QOH"])
        BigDecimal discrepancyNsQoh = toDecimal(discrepancy["NETSUITE QOH"])
        BigDecimal discrepancyQohDiff = toDecimal(discrepancy["QOH DIFF"])
        BigDecimal quantityDeltaValue = toDecimal(row.quantityDelta)
        String mismatchDirection = "BALANCED"
        if (discrepancyQohDiff < BigDecimal.ZERO) mismatchDirection = "OMS_LT_NS"
        if (discrepancyQohDiff > BigDecimal.ZERO) mismatchDirection = "OMS_GT_NS"

        boolean kitSingleNarrativeSignal =
                hasSalesOrderSignal &&
                        hasItemFulfillmentSignal &&
                        !hasOmsReturnSignal &&
                        !hasTransferSignal &&
                        discrepancyQohDiff.abs() == BigDecimal.ONE

        boolean strictCycleCountSignal =
                normalize(row[compareStatusFieldName]) == countMismatchStatusValue &&
                        !hasOmsReturnSignal &&
                        !hasTransferSignal &&
                        (hasPhysicalInventorySignal || hasInventoryAdjustmentSignal || kitSingleNarrativeSignal)

        List<Map> candidates = (row.candidates instanceof List) ? (List<Map>) row.candidates : []
        List<String> severityOrder = ["INCORRECT_CYCLE_COUNT", "FULFILLED_BUT_NS_BACKORDERED", "MANUAL_TRANSFER_PROCESS_GAP", "DC_PULLBACK_MISSING_IN_HC", "HG_RETURN_MISSING_IN_NS", "REFUND_TRANSACTION_MISSING", "UNCLASSIFIED_REVIEW_REQUIRED"]

        candidates.sort { Map a, Map b ->
            int scoreA = toInt(a.score)
            int scoreB = toInt(b.score)
            if (scoreA != scoreB) return scoreB <=> scoreA
            int sevA = severityOrder.indexOf(a.family)
            int sevB = severityOrder.indexOf(b.family)
            if (sevA == -1) sevA = 999
            if (sevB == -1) sevB = 999
            return sevA <=> sevB
        }

        Map bestCandidate = candidates ? candidates[0] : null
        Map secondBestCandidate = candidates.size() > 1 ? candidates[1] : null

        int bestScore = bestCandidate ? toInt(bestCandidate.score) : 0
        int secondScore = secondBestCandidate ? toInt(secondBestCandidate.score) : 0
        int margin = bestScore - secondScore
        List<Map> eligibleConflictCandidates = candidates.findAll { Map cand ->
            toInt(cand.score) >= 70 && (cand.gatesPass == null || cand.gatesPass == true)
        } as List<Map>
        LinkedHashSet<String> eligibleFamilies = new LinkedHashSet<>()
        eligibleConflictCandidates.each { Map cand ->
            String fam = normalize(cand.family)
            if (fam) eligibleFamilies.add(fam)
        }
        boolean isDualFamilyConflictCase = (eligibleFamilies.size() == 2)

        String finalFamily = "UNCLASSIFIED_REVIEW_REQUIRED"
        String finalSubReason = "MISSING_EVIDENCE"
        int finalScore = 0

        boolean coreCriticalMissing = (!hasItemFulfillmentSignal && !hasSalesOrderSignal && !hasTransferSignal && !hasOmsReturnSignal && nsRecordCount == 0 && omsRecordCount == 0 && nsTransactionRefs.isEmpty())

        if (coreCriticalMissing) {
            finalFamily = "UNCLASSIFIED_REVIEW_REQUIRED"
            finalSubReason = "MISSING_EVIDENCE"
        } else if (bestCandidate != null && bestScore >= 70) {
            if (isDualFamilyConflictCase && secondBestCandidate != null && margin < 8 && bestCandidate.family != secondBestCandidate.family) {
                finalFamily = "UNCLASSIFIED_REVIEW_REQUIRED"
                finalSubReason = "CONFLICTING_EVIDENCE"
            } else {
                finalFamily = (String) bestCandidate.family
                finalSubReason = (String) bestCandidate.subReason
                finalScore = bestScore
            }
        }

        String confidenceTier = finalScore >= 85 ? "High" : (finalScore >= 70 ? "Medium" : "Low")

        row[reasonCodeFieldName] = finalFamily
        row.subReasonCode = finalSubReason
        row.confidenceScore = finalScore
        row.confidenceTier = confidenceTier
        row.scoreBreakdown = [margin: margin, bestScore: bestScore, secondScore: secondScore]

        String effectiveReasonCode = finalFamily
        if (effectiveReasonCode == "HG_RETURN_MISSING_IN_NS") row[compareStatusFieldName] = "MISSING_IN_NS"
        else if (effectiveReasonCode == "UNCLASSIFIED_REVIEW_REQUIRED") row[compareStatusFieldName] = "ERROR"
        else row[compareStatusFieldName] = "COUNT_MISMATCH"

        String actionHint = ""
        if (effectiveReasonCode == "UNCLASSIFIED_REVIEW_REQUIRED") actionHint = "Action: Review required by Reconciliation Analyst."
        else if (finalSubReason == "HG_MANUAL_NS_DESYNC") actionHint = "Action: Review required by NS Ops."
        else if (finalSubReason == "REFUND_WRONG_LINK_IMPACT") actionHint = "Action: Review required by NS Finance/Ops."

        row[reasonTextFieldName] = "${effectiveReasonCode} -> ${finalScore} -> ${actionHint}"
        String effectiveReasonText = normalize(row[reasonTextFieldName])

        List<String> observationSteps = []
        observationSteps.add("Discrepancy snapshot: OMS QOH=${discrepancyOmsQoh.toPlainString()}, NS QOH=${discrepancyNsQoh.toPlainString()}, QOH DIFF=${discrepancyQohDiff.toPlainString()} (${mismatchDirection}).")
        observationSteps.add("Record totals: OMS rows=${toInt(row.omsRecordCount)}, NS transactions=${toInt(row.nsRecordCount)}, quantity delta (NS-OMS)=${quantityDeltaValue.toPlainString()}.")
        observationSteps.add("Signals: salesOrder=${hasSalesOrderSignal}, itemFulfillment=${hasItemFulfillmentSignal}, creditMemo=${hasCreditMemoSignal}, transfer=${hasTransferSignal}, inventoryAdjustment=${hasInventoryAdjustmentSignal}, omsReturn=${hasOmsReturnSignal}, physicalInventory=${hasPhysicalInventorySignal}.")
        observationSteps.add("Transfer lifecycle: state=${toLifecycleState}, NS received=${nsToReceived}, HC received=${hcToReceived}, NS statuses=${(nsTransferStatuses as List<String>).join('|') ?: 'None'}.")
        if (bestCandidate != null) {
            observationSteps.add("Candidate resolution: Best candidate ${bestCandidate.family} (${bestCandidate.subReason}) scored ${bestScore}. Margin to next: ${margin}.")
        } else {
            observationSteps.add("Candidate resolution: No candidates passed thresholds or missing evidence.")
        }
        if (coreCriticalMissing) observationSteps.add("No-go condition triggered: core critical fields missing.")

        row.aggregateContext = [
                qohDiff        : discrepancyQohDiff.toPlainString(),
                mismatchDirection: mismatchDirection,
                omsRecordCount : toInt(row.omsRecordCount),
                nsRecordCount  : toInt(row.nsRecordCount),
                omsQuantityTotal: toDecimal(row.omsQuantityTotal).toPlainString(),
                nsQuantityTotal: toDecimal(row.nsQuantityTotal).toPlainString(),
                quantityDelta  : quantityDeltaValue.toPlainString(),
                recordCountDelta: toInt(row.recordCountDelta),
                toLifecycleState: toLifecycleState,
                supplementalRecordCount: toInt(row.omsSupplementalRecordCount),
                supplementalTransferEventCount: supplementalTransferEventCount,
                supplementalReceiptEventCount: supplementalReceiptEventCount,
                supplementalRefundEventCount: supplementalRefundEventCount,
                supplementalBackorderEventCount: supplementalBackorderEventCount
        ]
        row.rawEvidenceScoped = [
                scope           : [
                        pairId               : normalize(row.pairId),
                        itemCandidates       : itemScopeCandidates as List<String>,
                        facilityCandidates   : facilityScopeCandidates as List<String>
                ],
                netSuitePrimaryRaw: nsRows,
                netSuiteDetailRaw : nsDetailRows,
                omsHcRaw          : omsRows,
                omsSupplementalRaw: [
                        transferLifecycle      : transferLifecycleRows,
                        returnRefundLinkage    : returnRefundRows,
                        orderFulfillmentBackorder: orderFulfillmentRows,
                        cycleCountAudit        : cycleCountRows,
                        itemFacilityMapping    : itemFacilityRows
                ],
                discrepancyRaw    : discrepancyRows
        ]

        row.signalFlags = [
                hasSalesOrderSignal        : hasSalesOrderSignal,
                hasItemFulfillmentSignal   : hasItemFulfillmentSignal,
                hasCreditMemoSignal        : hasCreditMemoSignal,
                hasTransferSignal          : hasTransferSignal,
                hasInventoryAdjustmentSignal: hasInventoryAdjustmentSignal,
                hasOmsReturnSignal         : hasOmsReturnSignal,
                hasOmsShipmentSignal       : hasOmsShipmentSignal,
                hasPhysicalInventorySignal : hasPhysicalInventorySignal,
                hasSupplementalTransferSignal: supplementalTransferEventCount > 0,
                hasSupplementalReceiptSignal: supplementalReceiptEventCount > 0,
                hasSupplementalRefundSignal: supplementalRefundEventCount > 0,
                strictCycleCountSignal     : strictCycleCountSignal,
                kitSingleNarrativeSignal   : kitSingleNarrativeSignal
        ]
        row.causeDataPoints = [
                facilityId               : normalize(discrepancy.facility_id),
                facilityName             : normalize(discrepancy.facility_name),
                productId                : normalize(discrepancy.product_id),
                netsuiteProductId        : normalize(discrepancy.netsuite_product_id),
                productName              : normalize(discrepancy.product_name),
                discrepancyOmsQoh        : discrepancyOmsQoh,
                discrepancyNsQoh         : discrepancyNsQoh,
                discrepancyQohDiff       : discrepancyQohDiff,
                omsRecordCount           : toInt(row.omsRecordCount),
                nsRecordCount            : toInt(row.nsRecordCount),
                omsQuantityTotal         : toDecimal(row.omsQuantityTotal),
                nsQuantityTotal          : toDecimal(row.nsQuantityTotal),
                quantityDelta            : quantityDeltaValue,
                recordCountDelta         : toInt(row.recordCountDelta),
                orderEventCount          : omsOrderEventCount,
                returnEventCount         : omsReturnEventCount,
                shipmentEventCount       : omsShipmentEventCount,
                receiptEventCount        : omsReceiptEventCount,
                physicalInventoryEventCount: omsPhysicalInventoryEventCount,
                reasonEnumEventCount     : omsReasonEnumEventCount,
                itemIssuanceEventCount   : omsItemIssuanceEventCount,
                supplementalTransferEventCount: supplementalTransferEventCount,
                supplementalShipmentEventCount: supplementalShipmentEventCount,
                supplementalReceiptEventCount: supplementalReceiptEventCount,
                supplementalReturnEventCount: supplementalReturnEventCount,
                supplementalRefundEventCount: supplementalRefundEventCount,
                supplementalBackorderEventCount: supplementalBackorderEventCount,
                supplementalPhysicalInventoryEventCount: supplementalPhysicalInventoryEventCount,
                supplementalReasonEnumEventCount: supplementalReasonEnumEventCount,
                orderQtyAbsMax           : omsOrderQtyAbsMax,
                nsFulfillmentQtyAbsMax   : nsFulfillmentQtyAbsMax,
                nsTransactionTypes       : nsTxnTypes as List,
                nsTransactionRefs        : nsTransactionRefs,
                toLifecycleState         : toLifecycleState,
                sampleOrderIds           : orderIds,
                sampleReturnIds          : returnIds,
                sampleShipmentIds        : shipmentIds,
                sampleReceiptIds         : receiptIds,
                samplePhysicalInventoryIds: physicalInventoryIds,
                supplementalSampleTransferOrderIds: supplementalOrderIds as List<String>
        ]
        row.conclusionCode = effectiveReasonCode
        row.conclusion = effectiveReasonText
        row.observationSteps = observationSteps
    }

    processedItemCount = ruledRows.size()
    nsRecordCount = ruledRows.collect { toInt(it.nsRecordCount) }.sum() as int
    readDbRecordCount = ruledRows.collect { toInt(it.readDbRecordCount) }.sum() as int
    missingInNsCount = ruledRows.count { normalize(it[compareStatusFieldName]) == missingInNsStatusValue } as int
    missingInReadDbCount = ruledRows.count { normalize(it[compareStatusFieldName]) == missingInReadDbStatusValue } as int
    countMismatchCount = ruledRows.count { normalize(it[compareStatusFieldName]) == countMismatchStatusValue } as int
    matchedCount = ruledRows.count { normalize(it[compareStatusFieldName]) == matchedStatusValue } as int
    noRecordCount = ruledRows.count { normalize(it[compareStatusFieldName]) == noRecordStatusValue } as int
    errorItemCount = ruledRows.count { normalize(it[compareStatusFieldName]) == errorStatusValue } as int

    Map<String, Integer> reasonCountMap = [:].withDefault { 0 }
    ruledRows.each { Map row ->
        String reasonCodeValue = normalize(row[reasonCodeFieldName])
        if (reasonCodeValue) reasonCountMap[reasonCodeValue] = reasonCountMap[reasonCodeValue] + 1
    }
    explainedItemCount = reasonCountMap.values().sum() ?: 0
    unexplainedItemCount = processedItemCount - explainedItemCount
    reasonCounts = reasonCountMap.collect { k, v -> [reasonCode: k, count: v] }

    runId = runIdToUse
    nsChunkCount = nsChunkResultsList.size()
    nsChunkSuccessCount = nsChunkResultsList.count { it.status == "SUCCESS" } as int
    nsChunkFailureCount = nsChunkResultsList.count { it.status == "FAILED" } as int
    nsTotalRetryCount = totalRetries
    nsDataSource = nsDataSourceValue
    nsChunkResults = nsChunkResultsList

    String outputBaseLocation = normalize(outputLocation) ?: "runtime://tmp/reconciliation/inventory/retrieval"
    def outputRef = ec.resource.getLocationReference(outputBaseLocation)
    File outputDir = outputRef?.getFile()
    if (outputDir == null) {
        outputDir = new File(ec.factory.getRuntimePath(), outputBaseLocation.replace("runtime://", ""))
    }
    if (!outputDir.exists() && !outputDir.mkdirs()) {
        throw new IllegalArgumentException("Unable to create output directory at ${outputDir.absolutePath}")
    }

    String baseName = (normalize(outputBaseName)?.replaceAll(/[^A-Za-z0-9._-]/, "-")) ?: "inventory-adjustments"
    String timestamp = ec.l10n.format(ec.user.nowTimestamp, "yyyyMMdd-HHmmss")

    List<Map> reviewRows = ruledRows.collect { Map row ->
        Map discrepancy = (row.discrepancy instanceof Map) ? (Map) row.discrepancy : [:]
        Map signalFlags = (row.signalFlags instanceof Map) ? (Map) row.signalFlags : [:]
        Map causeData = (row.causeDataPoints instanceof Map) ? (Map) row.causeDataPoints : [:]
        Map toLifecycle = (row.toLifecycle instanceof Map) ? (Map) row.toLifecycle : [:]
        Map toLifecycleNs = (toLifecycle.ns instanceof Map) ? (Map) toLifecycle.ns : [:]
        Map toLifecycleHc = (toLifecycle.hc instanceof Map) ? (Map) toLifecycle.hc : [:]
        List<String> observationSteps = (row.observationSteps instanceof List) ? ((List) row.observationSteps).collect { normalize(it) }.findAll { it } : []
        List matchedRuleIds = (row[matchedRuleIdsFieldName] instanceof List) ? (List) row[matchedRuleIdsFieldName] : []
        List<Map> nsTransactionRefs = (row.nsTransactionRefs instanceof List) ? (List<Map>) row.nsTransactionRefs : []
        List<Map> supportingFindings = (row.supportingFindings instanceof List) ? (List<Map>) row.supportingFindings : []
        List<Map> eventTimeline = (row.eventTimeline instanceof List) ? (List<Map>) row.eventTimeline : []

        [
                pairId                     : normalize(row.pairId),
                facility_id                : normalize(discrepancy.facility_id),
                facility_name              : normalize(discrepancy.facility_name),
                netsuite_product_id        : normalize(discrepancy.netsuite_product_id),
                product_id                 : normalize(discrepancy.product_id),
                product_name               : normalize(discrepancy.product_name),
                hotwax_qoh                 : toDecimal(discrepancy["HOTWAX QOH"]).toPlainString(),
                netsuite_qoh               : toDecimal(discrepancy["NETSUITE QOH"]).toPlainString(),
                qoh_diff                   : toDecimal(discrepancy["QOH DIFF"]).toPlainString(),
                compareStatus              : normalize(row[compareStatusFieldName]),
                reasonCode                 : normalize(row[reasonCodeFieldName]),
                subReasonCode              : normalize(row.subReasonCode),
                reasonText                 : normalize(row[reasonTextFieldName]),
                confidenceScore            : row.confidenceScore instanceof Number ? (Number) row.confidenceScore : 0,
                confidenceTier             : normalize(row.confidenceTier),
                marginSummary              : (row.scoreBreakdown instanceof Map) ? "Best: \${row.scoreBreakdown.bestScore}, Next: \${row.scoreBreakdown.secondScore}, Margin: \${row.scoreBreakdown.margin}" : "",
                scoreBreakdown             : row.scoreBreakdown,
                conclusion                 : normalize(row.conclusion),
                strictCycleCountSignal     : signalFlags.strictCycleCountSignal,
                kitSingleNarrativeSignal   : signalFlags.kitSingleNarrativeSignal,
                omsRecordCount             : toInt(row.omsRecordCount),
                nsRecordCount              : toInt(row.nsRecordCount),
                omsQuantityTotal           : toDecimal(row.omsQuantityTotal).toPlainString(),
                nsQuantityTotal            : toDecimal(row.nsQuantityTotal).toPlainString(),
                quantityDelta              : toDecimal(row.quantityDelta).toPlainString(),
                recordCountDelta           : toInt(row.recordCountDelta),
                hasSalesOrderSignal        : signalFlags.hasSalesOrderSignal,
                hasItemFulfillmentSignal   : signalFlags.hasItemFulfillmentSignal,
                hasCreditMemoSignal        : signalFlags.hasCreditMemoSignal,
                hasTransferSignal          : signalFlags.hasTransferSignal,
                hasInventoryAdjustmentSignal: signalFlags.hasInventoryAdjustmentSignal,
                hasOmsReturnSignal         : signalFlags.hasOmsReturnSignal,
                hasOmsShipmentSignal       : signalFlags.hasOmsShipmentSignal,
                hasPhysicalInventorySignal : signalFlags.hasPhysicalInventorySignal,
                orderEventCount            : toInt(causeData.orderEventCount),
                returnEventCount           : toInt(causeData.returnEventCount),
                shipmentEventCount         : toInt(causeData.shipmentEventCount),
                receiptEventCount          : toInt(causeData.receiptEventCount),
                physicalInventoryEventCount: toInt(causeData.physicalInventoryEventCount),
                orderQtyAbsMax             : toDecimal(causeData.orderQtyAbsMax).toPlainString(),
                nsFulfillmentQtyAbsMax     : toDecimal(causeData.nsFulfillmentQtyAbsMax).toPlainString(),
                toLifecycleState           : normalize(toLifecycle.state),
                toLifecycleNsReceived      : toLifecycleNs.received,
                toLifecycleHcReceived      : toLifecycleHc.received,
                toLifecycleNsStatuses      : ((toLifecycleNs.statuses instanceof List) ? (List) toLifecycleNs.statuses : []).collect { normalize(it) }.findAll { it }.join("|"),
                sampleOrderIds             : ((causeData.sampleOrderIds instanceof List) ? (List) causeData.sampleOrderIds : []).collect { normalize(it) }.findAll { it }.join("|"),
                sampleReturnIds            : ((causeData.sampleReturnIds instanceof List) ? (List) causeData.sampleReturnIds : []).collect { normalize(it) }.findAll { it }.join("|"),
                sampleShipmentIds          : ((causeData.sampleShipmentIds instanceof List) ? (List) causeData.sampleShipmentIds : []).collect { normalize(it) }.findAll { it }.join("|"),
                sampleReceiptIds           : ((causeData.sampleReceiptIds instanceof List) ? (List) causeData.sampleReceiptIds : []).collect { normalize(it) }.findAll { it }.join("|"),
                samplePhysicalInventoryIds : ((causeData.samplePhysicalInventoryIds instanceof List) ? (List) causeData.samplePhysicalInventoryIds : []).collect { normalize(it) }.findAll { it }.join("|"),
                nsTransactionTypes         : ((causeData.nsTransactionTypes instanceof List) ? (List) causeData.nsTransactionTypes : []).collect { normalize(it) }.findAll { it }.join("|"),
                nsTransactionRefs          : nsTransactionRefs.collect { Map ref ->
                    String idVal = "[REDACTED]"
                    String tranidVal = "[REDACTED]"
                    String typeVal = normalize(ref.type)
                    [typeVal, idVal, tranidVal].findAll { it }.join(":")
                }.findAll { it }.join("|"),
                timelineEventCount         : eventTimeline.size(),
                supportingFindingsCount    : supportingFindings.size(),
                matchedRuleIds             : matchedRuleIds.collect { normalize(it) }.findAll { it }.join("|"),
                observationSteps           : observationSteps.join(" => ")
        ]
    }
    List<String> reviewCsvHeaders = reviewRows ? (reviewRows[0].keySet() as List<String>) : [
            "pairId", "facility_id", "facility_name", "netsuite_product_id", "product_id", "product_name",
            "hotwax_qoh", "netsuite_qoh", "qoh_diff",
            "compareStatus", "reasonCode", "subReasonCode", "reasonText", "confidenceScore", "confidenceTier", "marginSummary", "conclusion",
            "strictCycleCountSignal", "kitSingleNarrativeSignal",
            "omsRecordCount", "nsRecordCount", "omsQuantityTotal", "nsQuantityTotal", "quantityDelta", "recordCountDelta",
            "hasSalesOrderSignal", "hasItemFulfillmentSignal", "hasCreditMemoSignal", "hasTransferSignal", "hasInventoryAdjustmentSignal",
            "hasOmsReturnSignal", "hasOmsShipmentSignal", "hasPhysicalInventorySignal",
            "orderEventCount", "returnEventCount", "shipmentEventCount", "receiptEventCount", "physicalInventoryEventCount",
            "orderQtyAbsMax", "nsFulfillmentQtyAbsMax",
            "toLifecycleState", "toLifecycleNsReceived", "toLifecycleHcReceived", "toLifecycleNsStatuses",
            "sampleOrderIds", "sampleReturnIds", "sampleShipmentIds", "sampleReceiptIds", "samplePhysicalInventoryIds",
            "nsTransactionTypes", "nsTransactionRefs", "timelineEventCount", "supportingFindingsCount",
            "matchedRuleIds", "observationSteps"
    ]

    Map nsDoc = [
            metadata: [
                    source          : "NS",
                    nsDataSource    : nsDataSourceValue,
                    persistedNsOutputLocation: persistedNsOutputLocationValue,
                    runId           : runIdToUse,
                    nsRestletConfigId: nsConfigId,
                    from            : fromDateStr,
                    to              : toDateStr,
                    generatedAt     : ec.user.nowTimestamp?.toString()
            ],
            rows    : itemPairs.collect { Map pair ->
                Map nsRow = (nsResultByPair[pair.pairId] ?: [:]) as Map
                [
                        pairId      : pair.pairId,
                        itemId      : pair.itemId,
                        locationId  : pair.locationId,
                        status      : normalize(nsRow.status) ?: "ERROR",
                        error       : normalize(nsRow.errorMessage ?: nsRow.error),
                        recordCount : toInt(nsRow.recordCount ?: ((nsRow.records instanceof List) ? ((List) nsRow.records).size() : 0)),
                        records     : (nsRow.records instanceof List) ? nsRow.records : []
                ]
            }
    ]

    Map readDbDoc = [
            metadata: [
                    source      : "OMS_CSV",
                    runId       : runIdToUse,
                    from        : fromDateStr,
                    to          : toDateStr,
                    generatedAt : ec.user.nowTimestamp?.toString()
            ],
            rows    : itemPairs.collect { Map pair ->
                List<Map> rows = (omsRowsByPair[pair.pairId] ?: []) as List<Map>
                [
                        pairId      : pair.pairId,
                        itemId      : pair.itemId,
                        locationId  : pair.locationId,
                        status      : rows ? "OK" : "MISSING",
                        error       : null,
                        recordCount : rows.size(),
                        records     : rows
                ]
            }
    ]

    Map nsDetailDoc = [
            metadata: [
                    source               : "NS_SECONDARY_DETAIL",
                    dataSource           : nsDetailDataSourceValue,
                    inputCacheLocation   : nsDetailCacheLocationValue,
                    forceRefreshNsDetails: forceRefreshNsDetailsEnabled,
                    runId                : runIdToUse,
                    from                 : fromDateStr,
                    to                   : toDateStr,
                    generatedAt          : ec.user.nowTimestamp?.toString()
            ],
            rows    : ruledRows.collect { Map row ->
                [
                        pairId           : normalize(row.pairId),
                        itemId           : normalize(row.nsItemId ?: row.itemId),
                        locationId       : normalize(row.nsLocationId ?: row.locationId),
                        nsTransactionRefs: (row.nsTransactionRefs instanceof List) ? row.nsTransactionRefs : [],
                        toLifecycle      : (row.toLifecycle instanceof Map) ? row.toLifecycle : [:],
                        supportingFindings: (row.supportingFindings instanceof List) ? row.supportingFindings : [],
                        eventTimeline    : (row.eventTimeline instanceof List) ? row.eventTimeline : [],
                        nsSecondaryDetails: (row.nsSecondaryDetails instanceof Map) ? row.nsSecondaryDetails : [:]
                ]
            }
    ]

    Map summaryDoc = [
            metadata : [
                    runId               : runIdToUse,
                    referenceFileLocation: refLocation,
                    omsDetailFileLocation: omsDetailLocation,
                    referenceFileType   : refType,
                    omsDetailFileType   : omsType,
                    discrepancyNsItemIdField: nsItemIdFieldExpr,
                    discrepancyLocationIdField: nsLocationIdFieldExpr,
                    omsItemIdField      : omsItemIdFieldExpr,
                    omsLocationIdField  : omsLocationIdFieldExpr,
                    omsTxnDateField     : omsTxnDateFieldExpr,
                    omsQuantityField    : omsQuantityFieldExpr,
                    comparisonRuleSetId : comparisonRuleSetIdToUse,
                    nsDataSource        : nsDataSourceValue,
                    nsDetailDataSource  : nsDetailDataSourceValue,
                    persistedNsOutputLocation: persistedNsOutputLocationValue,
                    nsDetailCacheLocation: nsDetailCacheLocationValue,
                    forceRefreshNsDetails: forceRefreshNsDetailsEnabled,
                    fetchMissingNsPairs : fetchMissingNsPairsEnabled,
                    compareStatusField  : compareStatusFieldName,
                    reasonCodeField     : reasonCodeFieldName,
                    reasonTextField     : reasonTextFieldName,
                    matchedRuleIdsField : matchedRuleIdsFieldName,
                    reviewCsvColumns    : reviewCsvHeaders,
                    from                : fromDateStr,
                    to                  : toDateStr,
                    generatedAt         : ec.user.nowTimestamp?.toString()
            ],
            summary  : [
                    referenceItemCount   : referenceItemCount,
                    processedItemCount   : processedItemCount,
                    nsRecordCount        : nsRecordCount,
                    readDbRecordCount    : readDbRecordCount,
                    missingInNsCount     : missingInNsCount,
                    missingInReadDbCount : missingInReadDbCount,
                    countMismatchCount   : countMismatchCount,
                    matchedCount         : matchedCount,
                    noRecordCount        : noRecordCount,
                    errorItemCount       : errorItemCount,
                    explainedItemCount   : explainedItemCount,
                    unexplainedItemCount : unexplainedItemCount,
                    reasonCounts         : reasonCounts,
                    strictCycleConfirmedCount: strictCycleConfirmedCount,
                    strictCycleGuardReclassifiedCount: strictCycleGuardReclassifiedCount,
                    nsChunkCount         : nsChunkCount,
                    nsChunkSuccessCount  : nsChunkSuccessCount,
                    nsChunkFailureCount  : nsChunkFailureCount,
                    nsTotalRetryCount    : nsTotalRetryCount,
                    nsDetailCacheHitCount: nsDetailCacheHitCount,
                    nsDetailComputedCount: nsDetailComputedCount,
                    nsDetailErrorCount   : nsDetailErrorCount
            ],
            nsChunkResults: nsChunkResultsList,
            reviewRows: reviewRows,
            itemResults: ruledRows,
            warnings: warningList.unique()
    ]

    File nsFile = writeOutputJson(outputDir, "ns", baseName, timestamp, nsDoc)
    File nsDetailFile = writeOutputJson(outputDir, "ns-detail-cache", baseName, timestamp, nsDetailDoc)
    File readDbFile = writeOutputJson(outputDir, "read-db", baseName, timestamp, readDbDoc)
    File reviewCsvFile = writeOutputCsv(outputDir, "review", baseName, timestamp, reviewCsvHeaders, reviewRows)
    ((Map) summaryDoc.metadata).reviewCsvLocation = reviewCsvFile.absolutePath
    ((Map) summaryDoc.metadata).nsDetailOutputLocation = nsDetailFile.absolutePath
    File summaryFile = writeOutputJson(outputDir, "summary", baseName, timestamp, summaryDoc)

    nsOutputLocation = nsFile.absolutePath
    nsDetailOutputLocation = nsDetailFile.absolutePath
    readDbOutputLocation = readDbFile.absolutePath
    summaryLocation = summaryFile.absolutePath
    reviewCsvLocation = reviewCsvFile.absolutePath
    itemResults = ruledRows
    processingWarnings = warningList.unique().collect { [warningMessage: it] }

    logger.info("Two-CSV inventory retrieval complete runId={} processedItems={} chunkCount={} retries={} summary={}",
            runIdToUse, processedItemCount, nsChunkCount, nsTotalRetryCount, summaryLocation)
} finally {
    try { spark?.stop() } catch (Exception ignored) {}
}
