# Inventory Discrepancy Scenario Patterns

## Purpose
Convert business scenarios into deterministic, data-detectable patterns so reconciliation can draw clear conclusions (`reasonCode` / `reasonText`) from current inventory files.

## Data Basis
- Source run file:
  - `runtime/tmp/reconciliation/inventory/retrieval/summary-inventory-adjustments-scenario-rules-20260318-021018.json`
- Run metadata:
  - `runId`: `ns-run-20260318-021005-c858e587`
  - Generated: `2026-03-18 00:10:18.401`
  - Items: `263`

## Fields Used
- Core aggregate fields per pair:
  - `compareStatus`, `nsRecordCount`, `readDbRecordCount`, `omsRecordCount`
  - `omsQuantityTotal`, `nsQuantityTotal`, `quantityDelta`, `recordCountDelta`
  - `nsStatus`, `readDbStatus`
- Nested evidence fields:
  - `nsRecords[].transaction.type`
  - `omsDetailRows[].RETURN_ID`
  - `omsDetailRows[].REASON_ENUM_ID`
  - `omsDetailRows[].SHIPMENT_ID`

## Pattern Precedence (Top to Bottom)
Apply first-match-wins in this exact order to prevent overlap drift.

1. `ORDER_NOT_FULFILLED_BACKORDER`
2. `HG_RETURN_MISSING_IN_NS`
3. `REFUND_TRANSACTION_MISSING`
4. `DC_PULLBACK_MISSING_IN_HC`
5. `MANUAL_TRANSFER_PROCESS_GAP`
6. `INCORRECT_CYCLE_COUNT` (strict cycle-count only)
7. `COUNT_MISMATCH_REVIEW_REQUIRED` (post-rule guard fallback)

## Deterministic Pattern Definitions

### 1) ORDER_NOT_FULFILLED_BACKORDER
- Pattern:
  - `compareStatus == "COUNT_MISMATCH"`
  - `omsQuantityTotal < 0`
  - `nsQuantityTotal >= 0`
  - `nsRecords` contains `Sales Order`
  - `nsRecords` does **not** contain `Item Fulfillment`
- Conclusion:
  - `reasonCode = ORDER_NOT_FULFILLED_BACKORDER`
  - `reasonText = OMS decremented inventory but NS remained non-negative with open sales-order signal and no fulfillment signal.`
- Observed in current data: `92` pairs

### 2) HG_RETURN_MISSING_IN_NS
- Pattern:
  - `nsRecordCount == 0`
  - OMS return signal exists:
    - `RETURN_ID` present, **or**
    - `REASON_ENUM_ID` starts with `RTN_`
- Conclusion:
  - `reasonCode = HG_RETURN_MISSING_IN_NS`
  - `reasonText = Return signal exists in OMS/HC but NS has no corresponding inventory-side records.`
- Observed in current data: `9` pairs

### 3) REFUND_TRANSACTION_MISSING
- Pattern:
  - `nsRecordCount > 0`
  - OMS return signal exists (`RETURN_ID` present or `REASON_ENUM_ID` like `RTN_%`)
  - NS does **not** contain `Credit Memo` transaction type
- Conclusion:
  - `reasonCode = REFUND_TRANSACTION_MISSING`
  - `reasonText = Return signal is present but NS lacks refund/credit transaction evidence.`
- Observed in current data: `49` pairs

### 4) DC_PULLBACK_MISSING_IN_HC
- Pattern:
  - `readDbRecordCount == 0`
  - NS contains movement signal:
    - `Transfer Order`, `Inventory Transfer`, or `Inventory Adjustment`
- Conclusion:
  - `reasonCode = DC_PULLBACK_MISSING_IN_HC`
  - `reasonText = NS has pullback/transfer/adjustment movement but HC/OMS detail is missing.`
- Observed in current data: `1` pair

### 5) MANUAL_TRANSFER_PROCESS_GAP
- Pattern:
  - `readDbRecordCount > 0`
  - NS contains transfer signal (`Transfer Order` or `Inventory Transfer`)
  - OMS transfer markers are absent across detail rows:
    - `SHIPMENT_ID` empty
    - `RETURN_ID` empty
    - `REASON_ENUM_ID` empty
- Conclusion:
  - `reasonCode = MANUAL_TRANSFER_PROCESS_GAP`
  - `reasonText = Transfer-like NS activity is not traceable through expected OMS process markers.`
- Observed in current data: `4` pairs

### 6) INCORRECT_CYCLE_COUNT (Strict)
- Pattern:
  - `compareStatus == "COUNT_MISMATCH"`
  - none of scenarios 1-5 matched
  - strict cycle-count evidence exists:
    - physical inventory marker in OMS (`PHYSICAL_INVENTORY_ID`), or
    - NS contains `Inventory Adjustment`, or
    - kit/single narrative signal: NS has both `Sales Order` + `Item Fulfillment`, no return/transfer markers, and discrepancy `QOH DIFF = ±1`.
- Conclusion:
  - `reasonCode = INCORRECT_CYCLE_COUNT`
  - `reasonText = Cycle-count mismatch supported by strict evidence (physical count, adjustment, or kit/single signal).`

### 7) COUNT_MISMATCH_REVIEW_REQUIRED
- Pattern:
  - `reasonCode` was `INCORRECT_CYCLE_COUNT` from rules
  - strict cycle-count evidence is absent in post-rule checks
- Conclusion:
  - `reasonCode = COUNT_MISMATCH_REVIEW_REQUIRED`
  - `reasonText = Mismatch needs analyst review; strict cycle-count evidence is not present.`

## Current Data Distribution (with precedence)
- `ORDER_NOT_FULFILLED_BACKORDER`: `92`
- `HG_RETURN_MISSING_IN_NS`: `9`
- `REFUND_TRANSACTION_MISSING`: `49`
- `DC_PULLBACK_MISSING_IN_HC`: `1`
- `MANUAL_TRANSFER_PROCESS_GAP`: `4`
- `INCORRECT_CYCLE_COUNT`: depends on strict-cycle checks (`summary.strictCycleConfirmedCount`)
- `COUNT_MISMATCH_REVIEW_REQUIRED`: depends on post-rule strict guard (`summary.strictCycleGuardReclassifiedCount`)
- Total: `263`

## Notes
- These patterns are intentionally based only on fields already present in current NS/OMS reconciliation outputs.
- `REFUND_TRANSACTION_MISSING` is inferred from absence of `Credit Memo` in NS transaction types; if financial tables become available later, replace this with direct transaction-join evidence.
