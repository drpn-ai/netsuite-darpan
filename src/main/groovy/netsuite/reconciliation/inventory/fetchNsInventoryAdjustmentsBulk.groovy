import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field
import org.slf4j.LoggerFactory

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.PSSParameterSpec
import java.time.Duration
import java.util.Arrays
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

@Field Map<String, Map> NS_TOKEN_CACHE = new ConcurrentHashMap<>()

def logger = LoggerFactory.getLogger("netsuite.reconciliation.inventory.fetchNsInventoryAdjustmentsBulk")

def normalize = { Object value -> value?.toString()?.trim() }
def unquote = { String value ->
    String raw = normalize(value)
    if (!raw || raw.length() < 2) return raw
    if ((raw.startsWith("'") && raw.endsWith("'")) || (raw.startsWith("\"") && raw.endsWith("\""))) {
        return raw.substring(1, raw.length() - 1).trim()
    }
    return raw
}
def normalizeBool = { Object value, boolean defaultValue ->
    if (value == null) return defaultValue
    if (value instanceof Boolean) return (boolean) value
    String raw = normalize(value)?.toLowerCase()
    if (["y", "yes", "true", "1"].contains(raw)) return true
    if (["n", "no", "false", "0"].contains(raw)) return false
    return defaultValue
}
def warningList = []

String nsConfigId = normalize(nsRestletConfigId)
String fromDate = normalize(from)
String toDate = normalize(to)
boolean strictMaxPairsEnabled = normalizeBool(strictMaxPairs, true)
List inputPairs = (itemPairs instanceof List) ? ((List) itemPairs) : []

if (!nsConfigId) throw new IllegalArgumentException("nsRestletConfigId is required")
if (!fromDate) throw new IllegalArgumentException("from is required in yyyy-MM-dd format")
if (!toDate) throw new IllegalArgumentException("to is required in yyyy-MM-dd format")
if (!(fromDate ==~ /\d{4}-\d{2}-\d{2}/)) throw new IllegalArgumentException("from must be yyyy-MM-dd")
if (!(toDate ==~ /\d{4}-\d{2}-\d{2}/)) throw new IllegalArgumentException("to must be yyyy-MM-dd")
if (!inputPairs) throw new IllegalArgumentException("itemPairs is required and must contain at least one pair")
if (strictMaxPairsEnabled && inputPairs.size() > 100) {
    throw new IllegalArgumentException("itemPairs cannot exceed 100 rows per NetSuite request")
}

List<Map> normalizedPairs = []
Set<String> pairIds = new LinkedHashSet<>()
inputPairs.eachWithIndex { Object rawObj, int idx ->
    Map rawPair = (rawObj instanceof Map) ? ((Map) rawObj) : [:]
    String itemIdStr = normalize(rawPair.itemId)
    String locationIdStr = normalize(rawPair.locationId)
    if (!itemIdStr) throw new IllegalArgumentException("itemPairs[${idx}] missing itemId")
    if (!locationIdStr) throw new IllegalArgumentException("itemPairs[${idx}] missing locationId")

    String pairId = normalize(rawPair.pairId) ?: "${itemIdStr}|${locationIdStr}"
    if (pairIds.contains(pairId)) {
        throw new IllegalArgumentException("Duplicate pairId in itemPairs: ${pairId}")
    }
    pairIds.add(pairId)

    normalizedPairs.add([
            pairId    : pairId,
            itemId    : itemIdStr,
            locationId: locationIdStr
    ])
}

def nsConfig = ec.entity.find("darpan.reconciliation.NsRestletConfig")
        .condition("nsRestletConfigId", nsConfigId)
        .useCache(false)
        .one()
if (!nsConfig) throw new IllegalArgumentException("NsRestletConfig ${nsConfigId} not found")
if ((normalize(nsConfig.isActive) ?: "Y").equalsIgnoreCase("N")) {
    throw new IllegalArgumentException("NsRestletConfig ${nsConfigId} is inactive")
}

String linkedAuthConfigId = normalize(nsConfig.nsAuthConfigId)
def nsAuthConfig = null
if (linkedAuthConfigId) {
    nsAuthConfig = ec.entity.find("darpan.reconciliation.NsAuthConfig")
            .condition("nsAuthConfigId", linkedAuthConfigId)
            .useCache(false)
            .one()
    if (!nsAuthConfig) {
        throw new IllegalArgumentException("NsRestletConfig ${nsConfigId} references missing NsAuthConfig ${linkedAuthConfigId}")
    }
    if ((normalize(nsAuthConfig.isActive) ?: "Y").equalsIgnoreCase("N")) {
        throw new IllegalArgumentException("NsAuthConfig ${linkedAuthConfigId} is inactive")
    }
}
def authSource = nsAuthConfig ?: nsConfig

String endpointUrl = normalize(nsConfig.endpointUrl)
if (!endpointUrl) throw new IllegalArgumentException("NsRestletConfig ${nsConfigId} is missing endpointUrl")

String httpMethod = (normalize(nsConfig.httpMethod) ?: "POST").toUpperCase()
if (!["POST", "PUT", "GET"].contains(httpMethod)) {
    throw new IllegalArgumentException("NsRestletConfig ${nsConfigId} httpMethod must be POST, PUT, or GET")
}
if (httpMethod == "GET" && normalizedPairs.size() > 1) {
    throw new IllegalArgumentException("NsRestletConfig ${nsConfigId} uses GET and cannot send more than one pair in bulk mode")
}

String authType = (normalize(authSource.authType) ?: "NONE").toUpperCase()
if (!["NONE", "BASIC", "BEARER", "OAUTH2_M2M_JWT"].contains(authType)) {
    throw new IllegalArgumentException("NsRestletConfig ${nsConfigId} authType must be NONE, BASIC, BEARER, or OAUTH2_M2M_JWT")
}

int connectTimeoutSeconds = (nsConfig.connectTimeoutSeconds ?: 30) as int
int readTimeoutSeconds = (nsConfig.readTimeoutSeconds ?: 60) as int
if (connectTimeoutSeconds < 1) connectTimeoutSeconds = 30
if (readTimeoutSeconds < 1) readTimeoutSeconds = 60

Map headersMap = [:]
String headersJson = normalize(nsConfig.headersJson)
if (headersJson) {
    def parsedHeaders = new JsonSlurper().parseText(headersJson)
    if (!(parsedHeaders instanceof Map)) {
        throw new IllegalArgumentException("NsRestletConfig ${nsConfigId} headersJson must be a JSON object")
    }
    parsedHeaders.each { key, value ->
        if (key != null && value != null) headersMap[key.toString()] = value.toString()
    }
}

def toTypedNumber = { String rawValue ->
    if (!rawValue) return null
    if (rawValue ==~ /-?\d+/) {
        try {
            return Long.valueOf(rawValue)
        } catch (Exception ignored) {
            return rawValue
        }
    }
    if (rawValue ==~ /-?\d+\.\d+/) {
        try {
            return new BigDecimal(rawValue)
        } catch (Exception ignored) {
            return rawValue
        }
    }
    return rawValue
}

Map payloadMap = [
        from : fromDate,
        to   : toDate,
        pairs: normalizedPairs.collect { Map pair ->
            [
                    pairId    : pair.pairId,
                    itemId    : toTypedNumber(pair.itemId),
                    locationId: toTypedNumber(pair.locationId)
            ]
        }
]
String payloadJson = JsonOutput.toJson(payloadMap)

HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
        .build()

def base64UrlEncode = { byte[] raw ->
    return Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
}

def randomJti = { int len ->
    String chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    SecureRandom rng = new SecureRandom()
    StringBuilder sb = new StringBuilder(len)
    (0..<len).each { sb.append(chars.charAt(rng.nextInt(chars.length()))) }
    return sb.toString()
}

def removeLeadingZeros = { byte[] src ->
    int idx = 0
    while (idx < src.length - 1 && src[idx] == 0) idx++
    return Arrays.copyOfRange(src, idx, src.length)
}

def toFixedLength = { byte[] raw, int length ->
    byte[] trimmed = removeLeadingZeros(raw)
    if (trimmed.length > length) {
        throw new IllegalArgumentException("ECDSA coordinate length ${trimmed.length} exceeds expected ${length}")
    }
    byte[] out = new byte[length]
    System.arraycopy(trimmed, 0, out, length - trimmed.length, trimmed.length)
    return out
}

def derToJoseEcdsa = { byte[] derSignature, int outputLength ->
    if (!derSignature || derSignature.length < 8 || derSignature[0] != (byte) 0x30) {
        throw new IllegalArgumentException("Invalid DER signature format for ECDSA JWT")
    }
    int offset = 1
    int seqLen = derSignature[offset++] & 0xFF
    if ((seqLen & 0x80) != 0) {
        int sizeBytes = seqLen & 0x7F
        seqLen = 0
        (0..<sizeBytes).each { seqLen = (seqLen << 8) | (derSignature[offset++] & 0xFF) }
    }
    if (offset >= derSignature.length || derSignature[offset++] != (byte) 0x02) {
        throw new IllegalArgumentException("Invalid DER signature R marker for ECDSA JWT")
    }
    int rLen = derSignature[offset++] & 0xFF
    byte[] rBytes = Arrays.copyOfRange(derSignature, offset, offset + rLen)
    offset += rLen
    if (offset >= derSignature.length || derSignature[offset++] != (byte) 0x02) {
        throw new IllegalArgumentException("Invalid DER signature S marker for ECDSA JWT")
    }
    int sLen = derSignature[offset++] & 0xFF
    byte[] sBytes = Arrays.copyOfRange(derSignature, offset, offset + sLen)

    int partLen = outputLength / 2
    byte[] jose = new byte[outputLength]
    byte[] rFixed = toFixedLength(rBytes, partLen)
    byte[] sFixed = toFixedLength(sBytes, partLen)
    System.arraycopy(rFixed, 0, jose, 0, partLen)
    System.arraycopy(sFixed, 0, jose, partLen, partLen)
    return jose
}

def parsePkcs8PrivateKey = { String pemText ->
    String normalizedPem = pemText?.replace("\\n", "\n")?.trim()
    if (!normalizedPem) throw new IllegalArgumentException("Private key PEM is required")

    String b64 = normalizedPem
            .replaceAll("-----BEGIN [A-Z ]+-----", "")
            .replaceAll("-----END [A-Z ]+-----", "")
            .replaceAll("\\s", "")
    byte[] keyBytes
    try {
        keyBytes = Base64.getDecoder().decode(b64)
    } catch (Exception e) {
        throw new IllegalArgumentException("Invalid PEM format for private key: ${e.message}")
    }

    PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes)
    try {
        PrivateKey rsaKey = KeyFactory.getInstance("RSA").generatePrivate(spec)
        return [privateKey: rsaKey, alg: "PS256"]
    } catch (Exception ignored) {}
    try {
        PrivateKey ecKey = KeyFactory.getInstance("EC").generatePrivate(spec)
        return [privateKey: ecKey, alg: "ES256"]
    } catch (Exception ignored) {}

    throw new IllegalArgumentException("Private key must be PKCS#8 RSA or EC PEM")
}

def signClientAssertionJwt = { String tokenUrl, String clientId, String certId, String privateKeyPem, String scope ->
    long nowSec = (System.currentTimeMillis() / 1000L) as long
    Map keyInfo = parsePkcs8PrivateKey(privateKeyPem)
    PrivateKey privateKey = (PrivateKey) keyInfo.privateKey
    String alg = keyInfo.alg as String

    Map jwtHeader = [alg: alg, typ: "JWT", kid: certId]
    Map jwtPayload = [
            iss  : clientId,
            aud  : tokenUrl,
            iat  : nowSec,
            exp  : nowSec + 300L,
            jti  : randomJti(24),
            scope: scope
    ]

    String encodedHeader = base64UrlEncode(JsonOutput.toJson(jwtHeader).getBytes(StandardCharsets.UTF_8))
    String encodedPayload = base64UrlEncode(JsonOutput.toJson(jwtPayload).getBytes(StandardCharsets.UTF_8))
    String signingInput = "${encodedHeader}.${encodedPayload}"

    byte[] signatureBytes
    if ("PS256".equals(alg)) {
        Signature sig = Signature.getInstance("RSASSA-PSS")
        sig.setParameter(new PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1))
        sig.initSign(privateKey)
        sig.update(signingInput.getBytes(StandardCharsets.UTF_8))
        signatureBytes = sig.sign()
    } else if ("ES256".equals(alg)) {
        Signature sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(privateKey)
        sig.update(signingInput.getBytes(StandardCharsets.UTF_8))
        signatureBytes = derToJoseEcdsa(sig.sign(), 64)
    } else {
        throw new IllegalArgumentException("Unsupported JWT alg ${alg}")
    }

    return [jwt: "${signingInput}.${base64UrlEncode(signatureBytes)}", alg: alg]
}

def fetchOauthAccessToken = { String tokenUrl, String clientAssertion, int timeoutSeconds ->
    String formBody = "grant_type=${URLEncoder.encode('client_credentials', StandardCharsets.UTF_8)}" +
            "&client_assertion_type=${URLEncoder.encode('urn:ietf:params:oauth:client-assertion-type:jwt-bearer', StandardCharsets.UTF_8)}" +
            "&client_assertion=${URLEncoder.encode(clientAssertion, StandardCharsets.UTF_8)}"

    HttpRequest tokenReq = HttpRequest.newBuilder()
            .uri(URI.create(tokenUrl))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(formBody))
            .build()

    HttpResponse<String> tokenResp = client.send(tokenReq, HttpResponse.BodyHandlers.ofString())
    int tokenStatus = tokenResp.statusCode()
    if (tokenStatus < 200 || tokenStatus > 299) {
        String err = normalize(tokenResp.body())
        if (err && err.length() > 400) err = err.substring(0, 400) + "..."
        throw new IllegalArgumentException("Token request failed with HTTP ${tokenStatus}${err ? ': ' + err : ''}")
    }

    def tokenParsed = new JsonSlurper().parseText(tokenResp.body() ?: "{}")
    if (!(tokenParsed instanceof Map)) throw new IllegalArgumentException("Token response is not a JSON object")
    String accessToken = normalize(tokenParsed.access_token)
    if (!accessToken) throw new IllegalArgumentException("Token response missing access_token")

    long expiresIn = 3600L
    Object expiresRaw = tokenParsed.expires_in
    if (expiresRaw != null) {
        try {
            expiresIn = (expiresRaw as BigDecimal).longValue()
        } catch (Exception ignored) {}
    }
    if (expiresIn < 60L) expiresIn = 60L
    long expiresAtSec = (System.currentTimeMillis() / 1000L) as long
    expiresAtSec += (expiresIn - 30L)

    return [accessToken: accessToken, expiresAtSec: expiresAtSec]
}

String authCacheKey = linkedAuthConfigId ?: nsConfigId
if (!authCacheKey) throw new IllegalArgumentException("Auth cache key is missing for ${nsConfigId}")
Map<String, Map> tokenCache = NS_TOKEN_CACHE
if (tokenCache == null) {
    tokenCache = new ConcurrentHashMap<>()
    NS_TOKEN_CACHE = tokenCache
}
def resolveOauthToken = { ->
    String tokenUrl = normalize(authSource.tokenUrl)
    String clientId = normalize(authSource.clientId)
    String certId = normalize(authSource.certId)
    String privateKeyPem = authSource.privateKeyPem?.toString()
    String scope = unquote(authSource.scope?.toString()) ?: "restlets rest_webservices"

    if (!tokenUrl) throw new IllegalArgumentException("Auth config ${authCacheKey} requires tokenUrl for OAUTH2_M2M_JWT")
    if (!clientId) throw new IllegalArgumentException("Auth config ${authCacheKey} requires clientId for OAUTH2_M2M_JWT")
    if (!certId) throw new IllegalArgumentException("Auth config ${authCacheKey} requires certId for OAUTH2_M2M_JWT")
    if (!privateKeyPem) throw new IllegalArgumentException("Auth config ${authCacheKey} requires privateKeyPem for OAUTH2_M2M_JWT")

    long nowSec = (System.currentTimeMillis() / 1000L) as long
    Map cached = tokenCache[authCacheKey]
    if (cached && cached.tokenUrl == tokenUrl && cached.expiresAtSec instanceof Number &&
            ((cached.expiresAtSec as Number).longValue() > nowSec)) {
        return cached.accessToken as String
    }

    synchronized (tokenCache) {
        nowSec = (System.currentTimeMillis() / 1000L) as long
        cached = tokenCache[authCacheKey]
        if (cached && cached.tokenUrl == tokenUrl && cached.expiresAtSec instanceof Number &&
                ((cached.expiresAtSec as Number).longValue() > nowSec)) {
            return cached.accessToken as String
        }

        Map assertionOut = signClientAssertionJwt(tokenUrl, clientId, certId, privateKeyPem, scope)
        warningList.add("NS OAuth2 JWT assertion generated using ${assertionOut.alg}.")
        Map tokenOut = fetchOauthAccessToken(tokenUrl, assertionOut.jwt as String, readTimeoutSeconds)
        tokenCache[authCacheKey] = [
                tokenUrl    : tokenUrl,
                accessToken : tokenOut.accessToken,
                expiresAtSec: tokenOut.expiresAtSec
        ]
        return tokenOut.accessToken as String
    }
}

HttpRequest.Builder requestBuilder
if (httpMethod == "GET") {
    Map firstPair = normalizedPairs.first()
    String separator = endpointUrl.contains("?") ? "&" : "?"
    String query = "itemId=${URLEncoder.encode(firstPair.itemId, StandardCharsets.UTF_8)}" +
            "&locationId=${URLEncoder.encode(firstPair.locationId, StandardCharsets.UTF_8)}" +
            "&from=${URLEncoder.encode(fromDate, StandardCharsets.UTF_8)}" +
            "&to=${URLEncoder.encode(toDate, StandardCharsets.UTF_8)}"
    requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(endpointUrl + separator + query))
            .timeout(Duration.ofSeconds(readTimeoutSeconds))
            .GET()
} else {
    requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(endpointUrl))
            .timeout(Duration.ofSeconds(readTimeoutSeconds))
            .header("Content-Type", "application/json")
    if (httpMethod == "PUT") requestBuilder.PUT(HttpRequest.BodyPublishers.ofString(payloadJson))
    else requestBuilder.POST(HttpRequest.BodyPublishers.ofString(payloadJson))
}

headersMap.each { key, value ->
    requestBuilder.header(key, value)
}
requestBuilder.header("X-Darpan-Pair-Count", normalizedPairs.size().toString())

String username = normalize(authSource.username)
String password = authSource.password?.toString()
String apiToken = authSource.apiToken?.toString() ?: password
if (authType == "BASIC") {
    if (!username || password == null) {
        throw new IllegalArgumentException("NsRestletConfig ${nsConfigId} requires username and password for BASIC auth")
    }
    String token = Base64.getEncoder().encodeToString("${username}:${password}".getBytes(StandardCharsets.UTF_8))
    requestBuilder.setHeader("Authorization", "Basic ${token}")
}
if (authType == "BEARER") {
    if (!apiToken) throw new IllegalArgumentException("NsRestletConfig ${nsConfigId} requires apiToken (or password) for BEARER auth")
    requestBuilder.setHeader("Authorization", "Bearer ${apiToken}")
}
if (authType == "OAUTH2_M2M_JWT") {
    String accessToken = resolveOauthToken()
    requestBuilder.setHeader("Authorization", "Bearer ${accessToken}")
}

logger.info("Calling NetSuite bulk restlet endpointConfig={} authConfig={} pairCount={} from={} to={} method={} authType={}",
        nsConfigId, (linkedAuthConfigId ?: "LEGACY_EMBEDDED"), normalizedPairs.size(), fromDate, toDate, httpMethod, authType)

HttpResponse<String> response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
statusCode = response.statusCode()
responseBody = response.body()
if (statusCode < 200 || statusCode > 299) {
    String errorBody = normalize(responseBody)
    if (errorBody && errorBody.length() > 400) errorBody = errorBody.substring(0, 400) + "..."
    throw new IllegalArgumentException("NetSuite restlet bulk call failed for ${nsConfigId} with HTTP ${statusCode}${errorBody ? ': ' + errorBody : ''}")
}

Object parsedBody = null
if (normalize(responseBody)) {
    try {
        parsedBody = new JsonSlurper().parseText(responseBody)
    } catch (Exception parseEx) {
        warningList.add("NS bulk response was not JSON; treating as opaque success payload.")
    }
}

def normalizeTxId = { Map tx ->
    return normalize(tx.id ?: tx.internalid ?: tx.internalId ?: tx.transactionId ?: tx.tranInternalId)
}

def normalizeTranId = { Map tx ->
    return normalize(tx.tranid ?: tx.tranId ?: tx.transactionNumber ?: tx.docnum ?: tx.documentNumber ?: tx.tran_num)
}

def normalizeNsRecordList = { List rawRecords, String fallbackItemId, String fallbackLocationId ->
    List<Map> normalizedRecords = []
    (rawRecords ?: []).each { Object recordObj ->
        if (!(recordObj instanceof Map)) return
        Map record = new LinkedHashMap((Map) recordObj)
        Map tx = (record.transaction instanceof Map) ? new LinkedHashMap((Map) record.transaction) : new LinkedHashMap(record)

        String txId = normalizeTxId(tx)
        String tranId = normalizeTranId(tx)
        String txType = normalize(tx.type ?: tx.transactionType ?: tx.recordType)
        String txDate = normalize(tx.date ?: tx.transactionDate ?: tx.trandate ?: tx.trxDate ?: tx.effectiveDate ?: tx.createdAt)
        String txQty = normalize(tx.qty ?: tx.quantity ?: tx.adjustmentQty ?: tx.qtyDelta)

        if (txId) tx.id = txId
        if (tranId) tx.tranid = tranId
        if (txType) tx.type = txType
        if (txDate) tx.date = txDate
        if (txQty) tx.qty = txQty
        if (!normalize(tx.itemId) && fallbackItemId) tx.itemId = fallbackItemId
        if (!normalize(tx.locationId) && fallbackLocationId) tx.locationId = fallbackLocationId

        record.transaction = tx
        if (!normalize(record.itemId) && fallbackItemId) record.itemId = fallbackItemId
        if (!normalize(record.locationId) && fallbackLocationId) record.locationId = fallbackLocationId
        normalizedRecords << record
    }
    return normalizedRecords
}

def buildResult = { Map pair, Map candidate ->
    Map out = [
            pairId      : pair.pairId,
            itemId      : pair.itemId,
            locationId  : pair.locationId,
            status      : "OK",
            errorCode   : null,
            errorMessage: null,
            retryable   : null,
            recordCount : 0,
            records     : [],
            warnings    : []
    ]
    if (candidate != null) {
        out.pairId = normalize(candidate.pairId) ?: out.pairId
        out.itemId = normalize(candidate.itemId) ?: out.itemId
        out.locationId = normalize(candidate.locationId) ?: out.locationId
        String statusRaw = normalize(candidate.status)
        if (!statusRaw && candidate.containsKey("ok")) {
            statusRaw = (candidate.ok in [true, "true", "Y", "y", 1]) ? "OK" : "ERROR"
        }
        out.status = statusRaw ?: "OK"
        out.errorCode = normalize(candidate.errorCode)
        out.errorMessage = normalize(candidate.errorMessage ?: candidate.error ?: candidate.message)
        if (candidate.containsKey("retryable")) out.retryable = candidate.retryable

        Object recordObj = candidate.records
        if (!(recordObj instanceof List)) recordObj = candidate.transactions
        if (!(recordObj instanceof List)) recordObj = candidate.data
        if (!(recordObj instanceof List)) recordObj = candidate.results
        if (!(recordObj instanceof List)) recordObj = candidate.items
        if (recordObj instanceof List) {
            out.records = normalizeNsRecordList((List) recordObj, out.itemId, out.locationId)
        } else if (candidate.containsKey("transaction")) {
            out.records = normalizeNsRecordList([[transaction: candidate.transaction]], out.itemId, out.locationId)
        }
        if (candidate.recordCount != null) {
            try {
                out.recordCount = (candidate.recordCount as BigDecimal).intValue()
            } catch (Exception ignored) {
                out.recordCount = out.records.size()
            }
        } else {
            out.recordCount = out.records.size()
        }
        if (candidate.warnings instanceof List) out.warnings = candidate.warnings
        if (out.status == "ERROR" && !out.errorMessage) out.errorMessage = "Unknown NetSuite pair error"
    }
    return out
}

Map<String, Map> pairById = normalizedPairs.collectEntries { Map pair -> [(pair.pairId): pair] }
Map<String, Map> resultByPairId = [:]

if (parsedBody instanceof Map && parsedBody.containsKey("ok") && !(parsedBody.ok in [true, "true", "Y", "y", 1])) {
    String globalErr = normalize(parsedBody.error ?: parsedBody.message ?: "Restlet returned ok=false")
    throw new IllegalArgumentException("NetSuite restlet response error for ${nsConfigId}: ${globalErr}")
}

List responseRows = []
if (parsedBody instanceof Map) {
    Map parsedMap = (Map) parsedBody
    if (parsedMap.results instanceof List) responseRows = (List) parsedMap.results
    else if (parsedMap.data instanceof List) responseRows = (List) parsedMap.data
    else if (parsedMap.items instanceof List) responseRows = (List) parsedMap.items
    else if (parsedMap.transactions instanceof List && normalizedPairs.size() == 1) {
        responseRows = [[
                pairId    : normalizedPairs[0].pairId,
                itemId    : parsedMap.itemId,
                locationId: parsedMap.locationId,
                status    : (parsedMap.ok in [false, "false", "N", "n", 0]) ? "ERROR" : "OK",
                records   : ((List) parsedMap.transactions).collect { tx ->
                    [
                            itemId    : parsedMap.itemId,
                            locationId: parsedMap.locationId,
                            from      : parsedMap.from,
                            to        : parsedMap.to,
                            asOf      : parsedMap.asOf,
                            balance   : parsedMap.balance,
                            page      : parsedMap.page,
                            transaction: tx
                    ]
                },
                recordCount: ((List) parsedMap.transactions).size(),
                errorMessage: parsedMap.error ?: parsedMap.message
        ]]
    }
} else if (parsedBody instanceof List && normalizedPairs.size() == 1) {
    responseRows = [[
            pairId     : normalizedPairs[0].pairId,
            itemId     : normalizedPairs[0].itemId,
            locationId : normalizedPairs[0].locationId,
            status     : "OK",
            records    : (List) parsedBody,
            recordCount: ((List) parsedBody).size()
    ]]
}

responseRows.each { Object rowObj ->
    if (!(rowObj instanceof Map)) return
    Map row = (Map) rowObj
    String pairId = normalize(row.pairId)
    Map pair = pairId ? pairById[pairId] : null
    if (!pair) {
        String rowItem = normalize(row.itemId)
        String rowLoc = normalize(row.locationId)
        pair = normalizedPairs.find { Map req -> req.itemId == rowItem && req.locationId == rowLoc }
    }
    if (!pair && normalizedPairs.size() == 1) pair = normalizedPairs[0]
    if (!pair) return

    Map built = buildResult(pair, row)
    resultByPairId[pair.pairId] = built
}

normalizedPairs.each { Map pair ->
    if (resultByPairId[pair.pairId]) return
    resultByPairId[pair.pairId] = buildResult(pair, [status: "ERROR", errorMessage: "No pair result returned by NetSuite restlet"])
}

pairResults = normalizedPairs.collect { Map pair -> resultByPairId[pair.pairId] }
totalPairs = normalizedPairs.size()
successPairCount = pairResults.count { Map pr -> normalize(pr.status) != "ERROR" }
failedPairCount = totalPairs - successPairCount
processingWarnings = warningList.collect { [warningMessage: it] }

logger.info("NetSuite bulk restlet success config={} pairCount={} successPairs={} failedPairs={}",
        nsConfigId, totalPairs, successPairCount, failedPairCount)
