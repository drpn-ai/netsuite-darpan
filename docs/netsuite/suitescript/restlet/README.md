# NetSuite Reconciliation Restlets

## Files
- `inventory_bulk_restlet.js`
- `inventory_detail_restlet.js`

## 1) Bulk Restlet (`inventory_bulk_restlet.js`)
Purpose: fetch primary transaction evidence for up to 100 `item + location` pairs.

### Input
```json
{
  "requestId": "run-20260318-01",
  "from": "2026-02-20",
  "to": "2026-03-18",
  "pairs": [
    {
      "pairId": "10455|175",
      "netsuite_product_id": "10455",
      "external_facility_id": "175"
    }
  ],
  "itemMap": {
    "SKU-ABC": "10455"
  },
  "facilityMap": {
    "FAC-NY-001": "175"
  }
}
```

### Notes
- `pairs` max is `100`.
- Date floor is enforced to **after 2026-02-23** (`minAllowedDate = 2026-02-24`).
- Returns transaction references with both:
  - `transaction.id` (internalid)
  - `transaction.tranid` (human-searchable doc number)

## 2) Detail Restlet (`inventory_detail_restlet.js`)
Purpose: fetch deeper evidence for specific suspect transactions and derive TO lifecycle support.

### Input
```json
{
  "requestId": "detail-20260318-01",
  "from": "2026-02-20",
  "to": "2026-03-18",
  "references": [
    {
      "referenceId": "r1",
      "pairId": "10455|175",
      "id": "1234567",
      "tranid": "TO12345",
      "netsuite_product_id": "10455",
      "external_facility_id": "175"
    }
  ],
  "itemMap": {
    "SKU-ABC": "10455"
  },
  "facilityMap": {
    "FAC-NY-001": "175"
  }
}
```

### Notes
- `references` max is `100`.
- Returns `detailRows[]` with `id`, `tranid`, `type`, `status`, `source/destination`, qty fields.
- Returns linked item receipts and `toLifecycle` summary.

## Deployment
1. Keep `@NApiVersion 2.0` and `@NScriptType Restlet` exactly as-is.
2. Deploy `inventory_bulk_restlet.js` to your existing bulk endpoint script/deployment.
3. Create a new Restlet script/deployment from `inventory_detail_restlet.js`.
4. Test each deployment with one pair/reference before enabling bulk calls.
