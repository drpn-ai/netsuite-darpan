# NetSuite Bulk Restlet Release Checklist

> Status: not live. Inventory reconciliation is not in production. The RESTlet services (`reconciliation.NetSuiteInventoryServices.*`) and `NsRestletConfig` are still in the code, so this page describes them as written, but no live run uses them. The live NetSuite path is SuiteQL: see [suiteql-orders-and-lifecycle.md](suiteql-orders-and-lifecycle.md).

## Sandbox Setup
1. Upload `inventory_bulk_restlet.js` to File Cabinet.
2. Create Script record (Restlet) and bind file.
3. Create Script Deployment and capture URL, script ID, deployment ID.
4. Assign integration role permissions for required inventory/transaction reads.
5. Configure Darpan `NsAuthConfig` + `NsRestletConfig` for sandbox.
6. Load setup data with the standard Moqui `load` task from `darpan-backend` (for example `./gradlew load -Ptypes=darpan-seed-initial,darpan-seed`). There is no `loadDarpanData` task, and this component ships no data files: the NetSuite entities and the `NETSUITE` / `NETSUITE_SUITEQL` connector rows live in the `darpan` component (`entity/ReconciliationEntities.xml`, `data/SourceSystemConnectorSeedData.xml`). Existing environments take upgrade data through `./gradlew :runtime:component:darpan:loadDarpanUpgradeData`.

## Sandbox Validation
1. Run 1-pair request.
2. Run 100-pair request.
3. Validate pair-level error response shape.
4. Validate auth failure handling.
5. Validate retry behavior from the `NetSuiteInventoryServices` retrieve path for transient failures.

## Production Promotion
1. Tag `netsuite-darpan` release.
2. Deploy script/deployment in production account.
3. Update production `NsRestletConfig.endpointUrl` and auth reference.
4. Run a single-chunk canary, then full run.

## Rollback
1. Keep previous deployment active until new flow passes.
2. Repoint `NsRestletConfig.endpointUrl` to previous deployment on failure.
3. Preserve failed request/response payload artifacts for analysis.
