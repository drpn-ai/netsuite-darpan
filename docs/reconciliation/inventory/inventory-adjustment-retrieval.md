# NetSuite Inventory Adjustment Retrieval

This flow compares a discrepancy file against an OMS detail file, enriches each `<itemId, locationId>` pair with NetSuite inventory adjustment details, and evaluates a configured RuleSet for explainability.

The public service surface is `reconciliation.NetSuiteInventoryServices.*` in `netsuite-darpan`. The old Darpan inventory wrapper surface has been removed.

## Inputs

- `referenceFileLocation`: discrepancy CSV or JSON.
- `omsDetailFileLocation`: OMS detail CSV or JSON. This is required; one-file legacy mode is no longer supported.
- `nsRestletConfigId`: NetSuite endpoint config backed by `darpan.reconciliation.NsRestletConfig` and `NsAuthConfig`.
- `comparisonRuleSetId`: RuleSet used to classify each enriched pair.
- `discrepancyNsItemIdField`, `discrepancyLocationIdField`, `omsItemIdField`, and `omsLocationIdField`: field mappings for the two files.
- `omsTxnDateField` and `omsQuantityField`: optional OMS detail fields used for timelines and quantity evidence.
- `persistedNsOutputLocation`: optional prior NS output JSON. When set, the service reuses that payload and only fetches missing pairs if `fetchMissingNsPairs=true`.

## Services

- `reconciliation.NetSuiteInventoryServices.fetch#NsInventoryAdjustments`
  - Single-pair helper for `itemId`, `locationId`, `from`, and `to`.
  - Delegates to `fetch#NsInventoryAdjustmentsBulk`.
- `reconciliation.NetSuiteInventoryServices.fetch#NsInventoryAdjustmentsBulk`
  - Bulk Restlet call for up to 100 item/location pairs per request when `strictMaxPairs=true`.
  - Emits `pairResults`, `successPairCount`, `failedPairCount`, and `processingWarnings`.
- `reconciliation.NetSuiteInventoryServices.retrieve#InventoryAdjustmentsByReference`
  - Reads the discrepancy and OMS files, chunks NetSuite fetches, applies `ReconciliationRuleEngineServices.execute#RuleSet`, and writes `ns`, `ns-detail-cache`, `read-db`, `review`, and `summary` artifacts.
  - The `read-db` artifact name is retained for output compatibility, but its contents are the OMS detail side of the two-file comparison.

## Example Call

```xml
<service-call name="reconciliation.NetSuiteInventoryServices.retrieve#InventoryAdjustmentsByReference">
    <parameter name="referenceFileLocation" value="runtime://tmp/reconciliation/inventory/input/discrepancy-superset-clean.json"/>
    <parameter name="referenceFileType" value="JSON"/>
    <parameter name="omsDetailFileLocation" value="runtime://tmp/reconciliation/inventory/input/oms-iid-merged-dedup.csv"/>
    <parameter name="omsDetailFileType" value="CSV"/>
    <parameter name="omsDetailHasHeader" value="true"/>
    <parameter name="discrepancyNsItemIdField" value="netsuite_product_id"/>
    <parameter name="discrepancyLocationIdField" value="facility_id"/>
    <parameter name="omsItemIdField" value="netsuite_product_id"/>
    <parameter name="omsLocationIdField" value="facility_id"/>
    <parameter name="omsTxnDateField" value="EFFECTIVE_DATE"/>
    <parameter name="omsQuantityField" value="QUANTITY_ON_HAND_DIFF"/>
    <parameter name="from" value="2026-02-22"/>
    <parameter name="to" value="2026-03-17"/>
    <parameter name="nsRestletConfigId" value="Gorjana_Prod_IID"/>
    <parameter name="comparisonRuleSetId" value="DARPAN_TEST_COMPARE_RS"/>
</service-call>
```

## Persisted Re-Run

After a first run, pass `persistedNsOutputLocation` from the returned `nsOutputLocation` to reuse NetSuite data:

```xml
<service-call name="reconciliation.NetSuiteInventoryServices.retrieve#InventoryAdjustmentsByReference">
    <parameter name="referenceFileLocation" value="runtime://tmp/reconciliation/inventory/input/discrepancy-superset-clean.json"/>
    <parameter name="referenceFileType" value="JSON"/>
    <parameter name="omsDetailFileLocation" value="runtime://tmp/reconciliation/inventory/input/oms-iid-merged-dedup.csv"/>
    <parameter name="omsDetailFileType" value="CSV"/>
    <parameter name="discrepancyNsItemIdField" value="netsuite_product_id"/>
    <parameter name="discrepancyLocationIdField" value="facility_id"/>
    <parameter name="omsItemIdField" value="netsuite_product_id"/>
    <parameter name="omsLocationIdField" value="facility_id"/>
    <parameter name="from" value="2026-02-22"/>
    <parameter name="to" value="2026-03-17"/>
    <parameter name="nsRestletConfigId" value="Gorjana_Prod_IID"/>
    <parameter name="persistedNsOutputLocation" value="runtime://tmp/reconciliation/inventory/retrieval/ns-inventory-adjustments-20260317-123000.json"/>
    <parameter name="fetchMissingNsPairs" value="false"/>
    <parameter name="comparisonRuleSetId" value="DARPAN_TEST_COMPARE_RS"/>
</service-call>
```

## Outputs

- `summaryLocation`: summary JSON with counts, warnings, chunk telemetry, reason counts, and `itemResults`.
- `nsOutputLocation`: normalized NetSuite payload JSON.
- `nsDetailOutputLocation`: secondary NetSuite detail cache JSON.
- `readDbOutputLocation`: OMS-side payload JSON retained under the historical output name.
- `reviewCsvLocation`: review CSV with reason and evidence columns.
- `nsChunkResults`: chunk-level telemetry with `chunkIndex`, `pairCount`, `attempts`, `status`, `error`, and `pairIds`.

## RuleSet Expectations

The service passes enriched item maps to `reconciliation.ReconciliationRuleEngineServices.execute#RuleSet`. Rules should set `compareStatus`, `reasonCode`, `reasonText`, and `_matchedRuleIds` when applicable.

Fact keys available to rules include:

- `itemId`, `locationId`, `pairId`
- `nsItemId`, `nsLocationId`, `nsStatus`, `nsRecordCount`, `nsError`, `nsRecords`
- `readDbItemId`, `readDbLocationId`, `readDbStatus`, `readDbRecordCount`, `readDbError`, `readDbRecords`
- `omsDetailRows`, `omsSupplementalRows`, `signalFlags`, `candidates`, and `matchedRuleIds`

## Operational Notes

- `omsDetailFileLocation` is required. The removed legacy mode no longer delegates to the Darpan inventory wrapper.
- `nsChunkSize` is capped at 100.
- `nsMaxRetries`, `nsRetryBackoffMs`, and `nsBackoffMultiplier` control retry behavior for retryable NetSuite failures.
- `outputLocation` defaults to `runtime://tmp/reconciliation/inventory/retrieval`.
- Maintain RuleSet and Rule data through the Rule Engine UI or seed data; this service does not create rule data.
