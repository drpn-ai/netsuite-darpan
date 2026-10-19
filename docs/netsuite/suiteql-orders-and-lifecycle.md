# NetSuite SuiteQL extraction and lifecycle checks

How Darpan reads NetSuite today: SuiteQL over NetSuite REST Web Services, authenticated with an
OAuth 2.0 machine-to-machine (M2M) client assertion. This describes `main`. None of it is in a tagged
release yet: `v0.2.0` of this component carries only the inventory RESTlet services, which are not live
(see [restlet-inventory-bulk.md](restlet-inventory-bulk.md)).

## Where things live

| Piece | Location |
| --- | --- |
| Extract service `extract#NetSuiteOrders` | `service/reconciliation/NetSuiteOrderExtractionServices.xml` → `src/main/groovy/netsuite/reconciliation/orders/extractNetSuiteOrders.groovy` |
| Query building, paging, mapping, exclusions | `src/main/groovy/netsuite/reconciliation/orders/NsSuiteQlOrderSupport.groovy` |
| Client assertion (JWT) | `src/main/groovy/netsuite/reconciliation/orders/NsOAuthTokenMinter.groovy` |
| Token cache (JVM-wide) | `src/main/groovy/netsuite/reconciliation/inventory/NsTokenCache.groovy` |
| One-read-per-run lifecycle snapshot | `src/main/groovy/netsuite/reconciliation/orders/NsLifecycleSnapshot.groovy` |
| Check definitions (list/get/save) | `service/facade/NetSuiteQueryFacadeServices.xml` → `src/main/groovy/netsuite/facade/NsSuiteQlQueryFacadeSupport.groovy` |
| Lifecycle builder options | `service/facade/NetSuiteLifecycleFacadeServices.xml` → `src/main/groovy/netsuite/facade/NsLifecycleOptionsSupport.groovy`, `NsSuiteQlClient.groovy` |

Entities are not defined here. `NsAuthConfig`, `NsSuiteQlSourceQuery`, `NsSuiteQlSourceQueryField` and
`NsRestletConfig` live in the `darpan` component's `entity/ReconciliationEntities.xml`.
`entity/NetSuiteConfigEntities.xml` in this component is intentionally empty: a definition added there
would silently overwrite the canonical one. The `NETSUITE_SUITEQL` connector row is seeded by darpan's
`data/SourceSystemConnectorSeedData.xml`.

Lifecycle authoring (`save#Lifecycle`, `save#LifecycleStages`, stage exceptions, findings,
`import#NetSuiteChecksAsRuns`) is in darpan's `facade.ReconciliationFacadeServices`
(`darpan.reconciliation.flowchart.LifecycleSupport`, `LifecycleStageCompiler`). Those services write the
`NsSuiteQlSourceQuery` rows this component executes.

## extract#NetSuiteOrders

`reconciliation.NetSuiteOrderExtractionServices.extract#NetSuiteOrders` is the getter the
`NETSUITE_SUITEQL` connector dispatches to. It has the same in/out contract and the same on-disk file
shape as the OMS and Shopify getters, so the automation edge and the Spark compare stage treat it like any
other source. It is internal (`allow-remote="false"`).

Inputs that matter:

- `nsSuiteQlSourceQueryId` (required): the `NsSuiteQlSourceQuery` row that says which NetSuite population
  this side reads and how its columns map to record fields. It also names the `NsAuthConfig` to use.
- `windowStart` / `windowEnd` (required): reduced to whole dates and applied half-open, `[start, end)`, on
  the query's date column (default `trandate`). `trandate` is date-granular in the account's timezone, so
  give day-aligned windows; a sub-day window does not narrow the result.
- `sourceFilters`: exclusion rules, applied client-side after mapping (SuiteQL knows nothing about tenant
  rules). Per-rule counts are reported in `metadata.configuredExclusions`; excluded records are written to a
  sidecar file beside the extract.
- `keepRecordFields`: optional projection, applied after exclusions so narrowing the output cannot disable
  a rule.
- `pageSize`: capped at 1000, NetSuite's SuiteQL page ceiling.
- `companyUserGroupId`: set by automations. When present, access is checked against that tenant; otherwise
  against the session's active tenant.

Access checks:

- The query row must belong to the tenant (owned outright, like `DatabaseSourceQuery`) and be active.
- The `NsAuthConfig` goes through the shared-config gate, so a credential shared to the tenant by grant is
  usable. An inaccessible credential reports "not found", not "forbidden".

Output: a JSON file `{"records": [...], "metadata": {...}}` under
`runtime://datamanager/reconciliation-runs/{automationExecutionId}/{timestamp}/`, default name
`netsuite-orders-{from}-{thru}.json`. It is written to a `.partial` file and moved into place only when
complete. `metadata` carries `source: NETSUITE_SUITEQL`, the record type, the exact query text, the window,
page and record counts, and `excludedCount`. The service returns `dataAvailable`, `fileLocation`,
`recordCount`, `requestMetadata` (endpoint, window, pages, and `answeredFrom`: `NetSuite` or
`run snapshot`), `warnings` and `errors`.

Failure behaviour: any non-200 page (after the 401 retry below) or a request timeout fails the whole
extract; nothing partial is kept. Each page request has a 120 s timeout. Month-scale windows can hit it
(backlog DAR-BE-056, open).

### Paging

- Offset paging, `limit` up to 1000, until NetSuite reports `hasMore=false`.
- Every query ends in `ORDER BY id` (`ORDER BY t.id, tl.id` for line-grain queries). The order is fixed in
  code, not configuration, because offset paging without a total order can repeat or skip rows.
- A safety ceiling of 2000 pages stops a source stuck on `hasMore=true`; hitting it adds a warning.
- Each request sends `Prefer: transient`, which NetSuite requires for SuiteQL.
- Progress heartbeats go to the run's observability row (at most every 2 s) when
  `reconciliationRunResultId` is given.

Measured on a live account (2026-09-02): 1000 rows per page at about 880 rows/s, so a month of about
160k sales orders reads in about three minutes. No SuiteScript deployment in the client's account is
needed.

## OAuth 2.0 M2M token minting

The credential is an `NsAuthConfig` row: `tokenUrl`, `clientId`, `certId`, `privateKeyPem` (encrypted at
rest) and `scope` (default `restlets rest_webservices`).

1. `NsOAuthTokenMinter.buildClientAssertion` signs a JWT with the private key. `iss` is the integration's
   Client ID, `kid` the Certificate ID, `aud` the token URL, and the assertion lives 55 minutes (NetSuite
   refuses more than an hour).
2. The signing algorithm follows the key: RSA keys sign PS256; EC keys sign ES256, ES384 or ES512 by
   curve size. The PEM must be PKCS#8; a PEM pasted with literal `\n` sequences is normalised.
3. The assertion is exchanged at `tokenUrl` with `grant_type=client_credentials`.
4. The access token is cached JVM-wide in `NsTokenCache`, keyed by the `NsAuthConfig` id, and refreshed a
   minute before `expires_in`.
5. A 401 from SuiteQL evicts the cached token, mints a new one and retries once. A second 401 is reported
   as an error.

NetSuite answers every bad assertion (wrong algorithm, broken PEM, swapped ids) with the same opaque
HTTP 500 `server_error`. The error Darpan raises names the most common cause: Client ID and Certificate ID
entered in each other's fields. The Client ID is 64 hex characters; the Certificate ID is 43 base64url
characters.

The SuiteQL URL is derived from the token URL's host, so the account id is configured once.
`suiteQlUrlFromTokenUrl` accepts only `*.suitetalk.api.netsuite.com` and `*.app.netsuite.com` hosts, so a
token cannot be sent to another host.

## NsSuiteQlSourceQuery: a check definition

One row says what one side of a NetSuite comparison reads. It holds no raw SQL. The window bounds are
interpolated as text, so free-form query text would put injection and paging order in operator hands.
Instead a row picks a vetted template and names its parts:

| Field(s) | Meaning |
| --- | --- |
| `nsAuthConfigId` | The NetSuite account (credential). |
| `recordType`, `fromTable`, `dateColumn` | The population: e.g. `SalesOrd` from `transaction`, windowed on `trandate`. |
| `queryTemplate` | One of the shapes below. Default `RECORDS`. |
| `absentChildRecordType`, `presentChildRecordType` | The linked record types a link template tests for. |
| `requireFulfillableOpenLine`, `requireOverdueShipDate`, `overdueExcludedShipMethods` | Triage gates: only orders with a fulfillable open line; only orders past their promised ship date. |
| `pendingStates`, `statesExpectingShipment`, `statesExpectingInvoice`, `disallowedStates` | Status codes for `STATE_CONTRADICTION`. |
| `requireParentRecordType`, `amountColumn` + `minAmount` | Further narrowing. |
| `joinKeyFieldName`, `originFieldName` | The record field that joins to the other side; an optional field marking each record `HOTWAX` or `NETSUITE_NATIVE`. |
| `lineKeyColumn` | For `SALES_LINES`, the line key column. |
| `waiveRules`, `matchRules`, `stageReach` | JSON written by the lifecycle builder: stage exceptions, written findings, and stages reached by a shared field instead of the link table. |
| `NsSuiteQlSourceQueryField` rows | The SELECT list: `recordFieldName` ← `sourceColumn` (a column or a single function call, optionally aliased), in `sequenceNum` order. `LINE_SUM(col) AS name` sums a column over the record's item lines. |

Templates (`NsSuiteQlOrderSupport.QUERY_TEMPLATES`):

- `RECORDS`: every record of the type in the window.
- `LINKED_CHILD_ABSENT`: records with no linked child of a type ("orders that never shipped").
- `LINKED_CHILD_PRESENT_ABSENT`: has one child type, lacks another ("shipped, never billed").
- `LINKED_CHILD_PRESENT`: records with a linked child of a type.
- `LINKED_CHILD_EXCUSED`: records missing a stage that a stage exception excuses.
- `RECORDS_MATCHING`: records whose fields meet a written finding's conditions.
- `STATE_CONTRADICTION`: the order's status disagrees with its transaction chain (pending past its ship
  date, a status that expects a shipment or invoice with none recorded, or a status it should never rest
  in). Each finding carries `contradiction` and `contradictionBrief`.
- `SALES_LINES`: one record per keyed sales-order line, with `orderLineId`, quantities shipped, billed and
  backordered, `itemType`, and one `lineState` per line (`BILLED`, `CLOSED`, `SHIPPED` or `OPEN`, in that
  precedence).

Links between transactions are read from NetSuite's `nexttransactionlink` table (there is no `createdfrom`
column on `transaction`). Link predicates do not filter by link type, because one stage can be reached by
several, and the child side is not windowed, because a child is dated after its parent.

Validation: every configured fragment is checked before it reaches query text. Record types and table
names must be identifiers; columns must be an identifier or one function call; status codes are
alphanumeric lists; ship-method names refuse quotes and semicolons; statement breaks, comments and SQL
keywords are refused. `normalizeSpec` is the single statement of what a valid row is.

### Check-definition services

`facade.NetSuiteQueryFacadeServices` (remote, authenticated, in the `DARPAN_APP` artifact group via the
`facade.` prefix):

- `list#NsSuiteQlSourceQueries`: the active tenant's checks, paged (`pageIndex`, `pageSize`, default 20),
  with each row's account, record type, template and field count.
- `get#NsSuiteQlSourceQuery`: one check with its full column projection, for editing.
- `save#NsSuiteQlSourceQuery`: create or update a check and its projection. It builds the same spec the
  extractor builds and runs `normalizeSpec` over it before writing, so a row that saves is a row that runs.
  It does not take `waiveRules`, `matchRules` or `stageReach`; the lifecycle services write those.

The join key for the OMS order-presence pair is `custbody_hc_order_id`, which the HotWax integration
writes on the NetSuite order. Orders keyed directly in NetSuite have no value there; they keep a null join
key and are removed by a configured exclusion rule rather than dropped silently.

## Lifecycle checks

A lifecycle is a start record type and its stages in order, for example `SalesOrd` → `ItemShip` →
`CustInvc`. The run asks, stage by stage, which records are missing the next stage, which are excused,
and which match a written finding. Each question is an `NsSuiteQlSourceQuery` row plus a rule, arranged
as a question tree (a flowchart run). Supported record types are those in
`LifecycleStageCompiler.RECORD_TYPES`: `SalesOrd`, `ItemShip`, `CustInvc`, `CashSale`, `RtnAuth`,
`ItemRcpt`, `CustCred`, `CustRfnd`.

Builder options come from the account itself (`facade.NetSuiteLifecycleFacadeServices`):

- `get#LifecycleOptions`: record types with records in the last 30 days, most first; given a record type,
  the types that follow it in the link table over a settled week (21 to 14 days ago), with how many
  records reached each and the share done by day 0, 1, 2, 3, 5, 7, 14 and 30.
- `get#LifecycleRuleOptions`: the text fields on a record type and, for one field, its values over the
  last 30 days, most common first. Scope `STAGE` adds standard list fields such as shipping method.

Both use `NsSuiteQlClient`: one page of SuiteQL on the same token path, refusing a result that would need a
second page.

One read per run (`NsLifecycleSnapshot`): inside a run's walk, the window's start records are read once
to a file, and each distinct condition (missing a stage, waived, matching) is read once as a list of ids.
Every question whose template and columns the snapshot covers is answered from that read; anything else
reads live. `requestMetadata.answeredFrom` says which. The snapshot is discarded when the walk ends. Set
`-Ddarpan.reconciliation.runSnapshot=off` to make every question read live. One known difference from the
live SQL: an empty exception field does not excuse a record here, so the record is reported as missing the
stage instead of falling in no place.

Status of this work, from `roadmap/BACKLOG.md` on 2026-10-19:

- The SuiteQL extractor, configurable queries, anti-join templates and `SALES_LINES` (DAR-BE-032, -044,
  -053, -062) are ready to ship and waiting on deploy.
- Authoring checks from the UI (DAR-BE-058), checks as runs (DAR-UI-048), the lifecycle builder
  (DAR-UI-050), one read per run and per-stage reach by field (DAR-BE-073), and the within-NetSuite chain
  checks (DAR-BE-045) are active and still changing.
- The return lifecycle (DAR-UI-052), line-to-fulfillment-line checks (DAR-BE-047) and the month-scale
  timeout (DAR-BE-056) are not started.

## Tests

Unit tests run without a NetSuite account (`src/test/groovy/netsuite/...`): `NsSuiteQlOrderSupportTest`,
`NsSuiteQlQueryTemplateTest`, `NsStageReachQueryTest`, `NsLifecycleSnapshotTest`, `NsOAuthTokenMinterTest`,
`NsChainConfigContractTest`, `NsSuiteQlQueryFacadeSupportTests`, `NsLifecycleOptionsSupportTests` and
`NetSuiteOrderExtractionServiceContractTests`. Run one with
`./gradlew :runtime:component:netsuite-darpan:test --tests <FQCN>` from `darpan-backend`.
