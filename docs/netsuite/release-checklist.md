# NetSuite Bulk Restlet Release Checklist

## Sandbox Setup
1. Upload `inventory_bulk_restlet.js` to File Cabinet.
2. Create Script record (Restlet) and bind file.
3. Create Script Deployment and capture URL, script ID, deployment ID.
4. Assign integration role permissions for required inventory/transaction reads.
5. Configure Darpan `NsAuthConfig` + `NsRestletConfig` for sandbox.
6. Run `./gradlew loadDarpanData` from `darpan-backend` after deploying setup seed changes so the `netsuite-seed` reader type loads after the Darpan setup data.

## Sandbox Validation
1. Run 1-pair request.
2. Run 100-pair request.
3. Validate pair-level error response shape.
4. Validate auth failure handling.
5. Validate retry behavior from Darpan wrapper for transient failures.

## Production Promotion
1. Tag `netsuite-darpan` release.
2. Deploy script/deployment in production account.
3. Update production `NsRestletConfig.endpointUrl` and auth reference.
4. Run a single-chunk canary, then full run.

## Rollback
1. Keep previous deployment active until new flow passes.
2. Repoint `NsRestletConfig.endpointUrl` to previous deployment on failure.
3. Preserve failed request/response payload artifacts for analysis.
