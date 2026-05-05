# NetSuite Split Migration Guide

## Context
- The `netsuite-darpan` component owns NetSuite fetching and two-CSV inventory reconciliation. The old Darpan inventory wrapper surface has been removed.
- This guide documents the artifact map, the recommended cutover sequence, and the rollback checklist that align with the `NetSuiteInventoryServices` implementation described in the [bulk contract](bulk-wrapper-contract.md).

## Artifact ownership map
| Asset | Current owner | Notes |
| --- | --- | --- |
| `NetSuiteInventoryServices.fetch#NsInventoryAdjustments` | `netsuite-darpan` | Single-pair helper that delegates to `fetch#NsInventoryAdjustmentsBulk`. |
| `NetSuiteInventoryServices.fetch#NsInventoryAdjustmentsBulk` | `netsuite-darpan` | Bulk Restlet fetch defined in `component://netsuite-darpan/service/reconciliation/NetSuiteInventoryServices.xml`; enforces deterministic chunking. |
| `NetSuiteInventoryServices.retrieve#InventoryAdjustmentsByReference` | `netsuite-darpan` | Reads discrepancy and OMS CSV/JSON files, performs chunked NetSuite enrichment, evaluates the configured RuleSet directly, and writes `nsChunkResults`, `summary`, and `itemResults`. |

## Cutover sequence
1. Deploy the release that contains the `netsuite-darpan` component, ensuring the new `service/reconciliation/NetSuiteInventoryServices.xml` and the `netsuite/reconciliation/inventory/*.groovy` scripts are on the classpath.
2. Verify that callers use `reconciliation.NetSuiteInventoryServices.*` directly; the old Darpan bridge is no longer supported.
3. Update runtime deployments to include the `netsuite-darpan` component; no configuration changes are required beyond the existing `NsRestletConfig`/`NsAuthConfig` records.
4. Run an integration validation pass using the checklist in [integration-validation.md](integration-validation.md), ensuring the chunked run has populated `nsChunkResults`, `reasonCode`/`reasonText`, and `_matchedRuleIds` as expected.
5. Once the validation pass shows green, flip the release pointer (or CI tag) that references the new component set, then monitor `summaryLocation` reports for stable chunk success ratios.

## Rollback checklist
- If a regression occurs before cutover completion, roll back to the previous release that still contained the legacy Darpan inventory service surface.
- Confirm that the rollback bundle contains the older NetSuite bulk logic and Darpan inventory contracts before using former service names.
- Re-run smoke tests with the legacy release; verify the legacy output files (NS, read-db, summary) are still written to the expected runtime/tmp folder.
- After stability is restored, analyze the `summaryLocation` report from the failed run to identify chunk failures (look for `nsChunkFailureCount` and the `nsChunkResults` entries) before attempting a new cutover.

## HC data roadmap note
- HC reconciliation feeds currently use the two-CSV upload model documented here; the NetSuite connector that will eventually replace those CSV uploads is still scheduled for a later release. Continue to land HC detail via CSV files while the connector is gated.
- When the connector work begins, extend this guide to cover the validation of connector-delivered CSV/JSON assets and how they feed into the same chunked run gate.
