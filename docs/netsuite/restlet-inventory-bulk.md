# NetSuite Bulk Inventory Restlet Contract

## Purpose
- Accept up to 100 item/location pairs per request.
- Return per-pair results so Darpan can continue processing even when some pairs fail.

## Request
```json
{
  "requestId": "ns-run-20260317-001-chunk-02",
  "from": "2026-03-01",
  "to": "2026-03-17",
  "pairs": [
    { "pairId": "21036|39", "itemId": "21036", "locationId": "39" }
  ],
  "options": {}
}
```

Rules:
- `pairs.length` must be `1..100`.
- `pairId` must be unique inside request (defaults to `itemId|locationId` if omitted).
- `from` and `to` must be `yyyy-MM-dd`.

## Response
```json
{
  "ok": true,
  "requestId": "ns-run-20260317-001-chunk-02",
  "summary": {
    "requestedPairs": 100,
    "processedPairs": 100,
    "successPairs": 96,
    "errorPairs": 4
  },
  "results": [
    {
      "pairId": "21036|39",
      "itemId": "21036",
      "locationId": "39",
      "status": "OK",
      "errorCode": null,
      "errorMessage": null,
      "retryable": false,
      "recordCount": 14,
      "records": []
    }
  ],
  "errors": []
}
```

## Failure Taxonomy
- Validation/config/auth failures: fail the HTTP call; callers should retry only transient transport/HTTP failures.
- Pair-level business failures: return `status=ERROR` in `results[]`; do not fail entire response.

## Caller Expectations
- `NetSuiteInventoryServices.retrieve#InventoryAdjustmentsByReference` chunks deterministically (sorted pairs, max 100).
- The retrieve service retries transient chunk failures with backoff.
- The retrieve service persists per-chunk telemetry and per-pair results in output files.
