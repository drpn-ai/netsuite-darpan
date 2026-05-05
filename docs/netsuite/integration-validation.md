# Integration Validation Checklist & Report Template

## Objectives
- Confirm the `netsuite-darpan` service contracts are standalone and do not depend on the removed `darpan` inventory wrappers.
- Validate one full E2E chunked run (two-CSV ingestion) that exercises chunking, reason explainability, and telemetry before the release gate opens.

## Checklist
1. **Deployment sanity**: Ensure `netsuite-darpan` is deployed with `service/reconciliation/NetSuiteInventoryServices.xml` and callers use the NetSuite service namespace directly.
2. **Configuration parity**: Verify `NsRestletConfig`/`NsAuthConfig` entries are unchanged; the new component should honor the same `nsRestletConfigId`, `from`, `to`, and header settings as the legacy path.
3. **Chunk gate**: Run `retrieve#InventoryAdjustmentsByReference` in two-CSV mode with >100 unique `<itemId, locationId>` pairs to force multi-chunk execution. Confirm `summary` output records `nsChunkCount > 1`, `nsChunkSuccessCount + nsChunkFailureCount == nsChunkCount`, and `nsTotalRetryCount` matches any retry attempts.
4. **Reason explainability**: Each row in `itemResults` must retain the `compareStatusField` value, a non-empty `reasonCode`, descriptive `reasonText`, and a list of `_matchedRuleIds`. Refer to the [rule explainability contract](../../../darpan/docs/reconciliation/rule-explainability.md) for field expectations.
5. **Telemetry alignment**: Confirm `nsChunkResults` entries include `chunkIndex`, `pairIds`, `attempts`, and `status`. Cross-reference the telemetry parity test from [bulk contract](bulk-wrapper-contract.md) to ensure the component surfaces every chunk detail to the summary JSON.
6. **HC CSV + connector readiness**: For HC data ingested via CSV, verify the `summaryLocation` fields are written to runtime/tmp and the CSV-to-chunk matchers behave identically to the legacy flow. Record in this report when the future connector is gated so the team can re-run the checklist once it ships.

## Report template
| Field | Description | Value |
| --- | --- | --- |
| Run ID | Value of `runId` from the summary document | |
| Reference locations | `referenceFileLocation` + `omsDetailFileLocation` used in the run | |
| Chunk summary | `nsChunkCount`, `nsChunkSuccessCount`, `nsChunkFailureCount`, `nsTotalRetryCount` | |
| Chunk failures | List `chunkIndex` values with `status=FAILED` and their `error` text | |
| Reason coverage | `processedItemCount`, `explainedItemCount`, `unexplainedItemCount`, `reasonCounts` | |
| Output URLs | `nsOutputLocation`, `readDbOutputLocation`, `summaryLocation` | |
| Connector readiness | Note whether the legacy CSV drop is still in use or if the future connector has already been wired in; mention required follow-up once the connector flows through the same chunked gate. | |
| Sign-off | Stakeholder/owner who confirms the summary matches expectations | |

## E2E chunked run gate
- Require at least one successful gated run whose summary JSON contains `nsChunkResults`, `summary`, and `itemResults`. Block the gate if there are any chunk failures without a documented retry path.
- Archive the run artifacts (`ns`, `read-db`, `summary`) for later debugging and link them in this report. Use the statuses defined in [bulk contract](bulk-wrapper-contract.md) and the [rule explainability contract](../../../darpan/docs/reconciliation/rule-explainability.md) to justify the gate closure.
