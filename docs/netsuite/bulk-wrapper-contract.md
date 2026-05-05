# Bulk Contract: NetSuite Inventory Retrieval

## Overview
- The public NetSuite inventory retrieval surface is `reconciliation.NetSuiteInventoryServices.*` inside the `netsuite-darpan` component.
- The bulk implementation enforces deterministic chunking, backoff-aware retries, and telemetry so that downstream reconciliation flows can reason about NetSuite ingestion health.

## Service contracts
| Name | Location | Description |
| --- | --- | --- |
| `reconciliation.NetSuiteInventoryServices.fetch#NsInventoryAdjustmentsBulk` | `component://netsuite-darpan/service/reconciliation/NetSuiteInventoryServices.xml` + `netsuite/reconciliation/inventory/fetchNsInventoryAdjustmentsBulk.groovy` | Handles Restlet authentication, validates `NsRestletConfig`, and surfaces bulk results (`pairResults`, `totalPairs`, `successPairCount`, `failedPairCount`, etc.) to callers. |

### Inputs
- `nsRestletConfigId`, `itemPairs`, `from`, `to` (required). `itemPairs` must include `pairId`, `itemId`, and `locationId` for each entry.
- `strictMaxPairs` defaults to `true`; the implementation throws if there are more than 100 pairs when this flag is enabled.

### Outputs
- `statusCode`, `totalPairs`, `successPairCount`, `failedPairCount`, and a `pairResults` list where each entry pulls `status`, `records`, `errorCode`, `errorMessage`, and `retryable` flags from NetSuite.
- `processingWarnings` surfaces implementation-side diagnostics (headers, JWT notes, chunk retries) for the caller.

## Deterministic chunking algorithm
- Chunk size is configurable via `nsChunkSize` but capped between 1 and 100. The default is 100 to stay within NetSuite request limits while maximizing throughput.
- Pair ordering is stable: items are collected from the discrepancy reference dataset, distincted by `<itemId, locationId>`, sorted (`orderBy("ns_item_id", "ns_location_id")`), and then split into chunks using `itemPairs.collate(chunkSize)`. Every release uses the same deterministic ordering so chunk replay is reliable.
- Each chunk is submitted as an atomic HTTP call to the NetSuite RESTlet with `strictMaxPairs=true`. The chunk-level `pairIds` list is recorded in telemetry for traceability.

## Retry/backoff telemetry
- `nsChunkResults` (see the summary document produced by `NetSuiteInventoryServices.retrieve#InventoryAdjustmentsByReference`) is a list of chunk-level status objects. Each entry includes `chunkIndex`, `pairCount`, `attempts`, `status` (`SUCCESS`/`FAILED`), `error`, and the list of `pairIds` that were part of that chunk.
- Retry policy:
  1. Attempt counter starts at 1 and increments until `maxRetries` (default 2) is reached.
  2. Sleep time between retries is computed as `retryBackoffMs * backoffMultiplier^(attempt-1)` where `retryBackoffMs` defaults to 1000ms and `backoffMultiplier` defaults to 2.0.
  3. Retryable failures are re-run via `isRetryableFailure(e.message)` before the policy is applied.
- Aggregate telemetry fields written to the summary output include `nsChunkCount`, `nsChunkSuccessCount`, `nsChunkFailureCount`, and `nsTotalRetryCount`. These appear alongside `processedItemCount`, `nsRecordCount`, and the reason explainability fields so operators can correlate chunk behavior with reconciliation outcomes.

## Test matrix
| Test | Goal | Acceptance |
| --- | --- | --- |
| Chunk boundary (≤100 pairs) | Ensure single-chunk execution and success without exceeding NetSuite limits. | `nsChunkCount == 1`, `failedCount == 0`, `pairResults.size() == input pairs`, `summary` has no chunk failure entries. |
| Multi-chunk determinism (>100 pairs) | Verify that chunk ordering matches sorted `<itemId, locationId>` order and that chunk indexes are sequential. | `itemResults` order matches the sorted source, `nsChunkResults[*].chunkIndex` is contiguous, and `nsChunkResults[*].pairCount == chunk size except final chunk`. |
| Retry/backoff exercise | Force a retryable HTTP failure (e.g., transient 5xx) on a chunk and confirm `nsTotalRetryCount` increments and `nsChunkResults` captures multiple attempts with `status` eventually `SUCCESS` or `FAILED`. | `nsTotalRetryCount >= 1`, last `nsChunkResults` entry `attempts > 1`, backoff interval honored (observed via log timestamps). |
| Telemetry parity | Confirm the retrieve service surfaces chunk metadata for downstream DIFF / truth-set comparisons. | Summary JSON contains `nsChunkResults`, `nsChunkSuccessCount`, `nsChunkFailureCount`, and each `itemResults` row includes `_matchedRuleIds` plus the reason fields described in the [rule explainability contract](../../../darpan/docs/reconciliation/rule-explainability.md). |
