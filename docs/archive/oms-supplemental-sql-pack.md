# OMS Supplemental SQL Pack (Inventory Reconciliation)

Use these extracts to add OMS/HC evidence that is currently missing from the reconciliation conclusions.

## Window
- Use `from_date = '2026-02-23'` (inclusive).
- Use `to_date` as needed (typically reconciliation run end date, inclusive).

## Output Columns (required across all exports)
Each export should include these keys so we can join to discrepancy pairs:
- `netsuite_product_id`
- `product_id`
- `facility_id`
- `external_facility_id`
- `event_date`

## Table Assumptions
These are **potential SQL templates** based on HotWax-style OMS schema conventions:
- `inventory_item_detail`
- `order_header`, `order_item`
- `shipment`, `shipment_item`, `shipment_receipt`
- `return_header`, `return_item`
- `payment_application`, `payment`, `payment_preference`
- `product_assoc`

If your DB uses different table/column names, keep the output aliases unchanged and map to your schema.

## Offset Management
- Every query below has a deterministic `ORDER BY`.
- For paged extraction, append `LIMIT :page_size OFFSET :offset` after the `ORDER BY`.

## 1) Transfer Lifecycle (TO shipped vs received)
```sql
WITH item_facility_map AS (
  SELECT DISTINCT
      iid.product_id,
      iid.netsuite_product_id,
      iid.facility_id,
      iid.external_facility_id
  FROM inventory_item_detail iid
  WHERE iid.effective_date >= :from_date
    AND iid.effective_date < DATE_ADD(:to_date, INTERVAL 1 DAY)
)
SELECT
    m.netsuite_product_id,
    m.product_id,
    m.facility_id,
    m.external_facility_id,
    COALESCE(s.estimated_ship_date, s.created_date) AS event_date,
    oh.order_id                                 AS transfer_order_id,
    oi.order_item_seq_id                        AS transfer_order_item_seq_id,
    oh.status_id                                AS transfer_order_status_id,
    s.shipment_id,
    s.status_id                                 AS shipment_status_id,
    s.origin_facility_id                        AS source_facility_id,
    s.destination_facility_id                   AS destination_facility_id,
    si.quantity                                 AS shipped_qty,
    COALESCE(SUM(sr.quantity_accepted), 0)      AS received_qty,
    MAX(sr.datetime_received)                   AS receipt_datetime,
    CASE
      WHEN COALESCE(SUM(sr.quantity_accepted), 0) >= COALESCE(si.quantity, 0) THEN 'RECEIVED'
      WHEN COALESCE(SUM(sr.quantity_accepted), 0) > 0 THEN 'PARTIALLY_RECEIVED'
      ELSE 'IN_TRANSIT'
    END                                         AS transfer_lifecycle_state
FROM item_facility_map m
JOIN order_item oi
  ON oi.product_id = m.product_id
JOIN order_header oh
  ON oh.order_id = oi.order_id
LEFT JOIN shipment_item si
  ON si.order_id = oi.order_id
 AND si.order_item_seq_id = oi.order_item_seq_id
LEFT JOIN shipment s
  ON s.shipment_id = si.shipment_id
LEFT JOIN shipment_receipt sr
  ON sr.shipment_id = si.shipment_id
 AND sr.shipment_item_seq_id = si.shipment_item_seq_id
WHERE oh.order_type_id IN ('TRANSFER_ORDER', 'TRANSFER')
  AND COALESCE(s.estimated_ship_date, s.created_date) >= :from_date
  AND COALESCE(s.estimated_ship_date, s.created_date) < DATE_ADD(:to_date, INTERVAL 1 DAY)
GROUP BY
    m.netsuite_product_id, m.product_id, m.facility_id, m.external_facility_id,
    event_date, oh.order_id, oi.order_item_seq_id, oh.status_id, s.shipment_id, s.status_id,
    s.origin_facility_id, s.destination_facility_id, si.quantity
ORDER BY
    event_date,
    m.external_facility_id,
    m.netsuite_product_id,
    oh.order_id,
    oi.order_item_seq_id,
    s.shipment_id;
```

## 2) Return + Refund Linkage
```sql
WITH item_facility_map AS (
  SELECT DISTINCT
      iid.product_id,
      iid.netsuite_product_id,
      iid.facility_id,
      iid.external_facility_id
  FROM inventory_item_detail iid
  WHERE iid.effective_date >= :from_date
    AND iid.effective_date < DATE_ADD(:to_date, INTERVAL 1 DAY)
)
SELECT
    m.netsuite_product_id,
    m.product_id,
    m.facility_id,
    m.external_facility_id,
    COALESCE(rh.entry_date, rh.last_updated_stamp) AS event_date,
    rh.return_id,
    ri.return_item_seq_id,
    rh.status_id                                   AS return_status_id,
    ri.return_reason_id                            AS return_reason_id,
    pa.payment_application_id,
    pa.payment_id,
    pa.amount_applied                              AS refund_amount,
    p.status_id                                    AS payment_status_id,
    p.payment_method_type_id,
    p.effective_date                               AS refund_effective_date,
    pp.payment_preference_id,
    pp.status_id                                   AS payment_preference_status_id
FROM item_facility_map m
JOIN return_item ri
  ON ri.product_id = m.product_id
JOIN return_header rh
  ON rh.return_id = ri.return_id
LEFT JOIN payment_application pa
  ON pa.return_id = rh.return_id
LEFT JOIN payment p
  ON p.payment_id = pa.payment_id
LEFT JOIN payment_preference pp
  ON pp.order_id = ri.order_id
 AND pp.order_item_seq_id = ri.order_item_seq_id
WHERE COALESCE(rh.entry_date, rh.last_updated_stamp) >= :from_date
  AND COALESCE(rh.entry_date, rh.last_updated_stamp) < DATE_ADD(:to_date, INTERVAL 1 DAY)
ORDER BY
    event_date,
    m.external_facility_id,
    m.netsuite_product_id,
    rh.return_id,
    ri.return_item_seq_id,
    pa.payment_id;
```

## 3) Order Fulfillment + Backorder Evidence
```sql
WITH item_facility_map AS (
  SELECT DISTINCT
      iid.product_id,
      iid.netsuite_product_id,
      iid.facility_id,
      iid.external_facility_id
  FROM inventory_item_detail iid
  WHERE iid.effective_date >= :from_date
    AND iid.effective_date < DATE_ADD(:to_date, INTERVAL 1 DAY)
)
SELECT
    m.netsuite_product_id,
    m.product_id,
    m.facility_id,
    m.external_facility_id,
    COALESCE(oh.order_date, oh.entry_date, oh.last_updated_stamp) AS event_date,
    oh.order_id,
    oi.order_item_seq_id,
    oh.status_id                                   AS order_status_id,
    oi.status_id                                   AS order_item_status_id,
    oi.quantity                                    AS ordered_qty,
    COALESCE(oi.cancel_quantity, 0)                AS cancelled_qty,
    COALESCE(SUM(ii.quantity), 0)                  AS issued_qty,
    (COALESCE(oi.quantity, 0) - COALESCE(oi.cancel_quantity, 0) - COALESCE(SUM(ii.quantity), 0))
                                                   AS open_unissued_qty,
    CASE
      WHEN (COALESCE(oi.quantity, 0) - COALESCE(oi.cancel_quantity, 0) - COALESCE(SUM(ii.quantity), 0)) > 0
      THEN 'BACKORDER_OR_OPEN'
      ELSE 'FULLY_ISSUED'
    END                                            AS fulfillment_state
FROM item_facility_map m
JOIN order_item oi
  ON oi.product_id = m.product_id
JOIN order_header oh
  ON oh.order_id = oi.order_id
LEFT JOIN item_issuance ii
  ON ii.order_id = oi.order_id
 AND ii.order_item_seq_id = oi.order_item_seq_id
WHERE COALESCE(oh.order_date, oh.entry_date, oh.last_updated_stamp) >= :from_date
  AND COALESCE(oh.order_date, oh.entry_date, oh.last_updated_stamp) < DATE_ADD(:to_date, INTERVAL 1 DAY)
GROUP BY
    m.netsuite_product_id, m.product_id, m.facility_id, m.external_facility_id,
    event_date, oh.order_id, oi.order_item_seq_id, oh.status_id, oi.status_id, oi.quantity, oi.cancel_quantity
ORDER BY
    event_date,
    m.external_facility_id,
    m.netsuite_product_id,
    oh.order_id,
    oi.order_item_seq_id;
```

## 4) Cycle Count / Physical Inventory Audit Trail
```sql
SELECT
    iid.netsuite_product_id,
    iid.product_id,
    iid.facility_id,
    iid.external_facility_id,
    iid.effective_date                              AS event_date,
    iid.inventory_item_id,
    iid.inventory_item_detail_seq_id,
    iid.physical_inventory_id,
    iid.reason_enum_id,
    iid.description,
    iid.quantity_on_hand_diff,
    iid.available_to_promise_diff,
    iid.accounting_quantity_diff,
    iid.last_quantity_on_hand,
    iid.created_stamp,
    iid.last_updated_stamp
FROM inventory_item_detail iid
WHERE iid.effective_date >= :from_date
  AND iid.effective_date < DATE_ADD(:to_date, INTERVAL 1 DAY)
  AND (
    iid.physical_inventory_id IS NOT NULL
    OR iid.reason_enum_id IS NOT NULL
    OR iid.description LIKE '%cycle%'
    OR iid.description LIKE '%count%'
  )
ORDER BY
    event_date,
    iid.external_facility_id,
    iid.netsuite_product_id,
    iid.inventory_item_id,
    iid.inventory_item_detail_seq_id;
```

## 5) Kit/Single Mapping (for strict cycle storyline)
```sql
SELECT
    p.product_id                                    AS kit_product_id,
    p.internal_name                                 AS kit_product_name,
    pa.product_id_to                                AS component_product_id,
    pa.quantity                                     AS component_qty,
    pa.from_date                                    AS assoc_from_date,
    pa.thru_date                                    AS assoc_thru_date
FROM product p
JOIN product_assoc pa
  ON pa.product_id = p.product_id
WHERE pa.product_assoc_type_id IN ('PRODUCT_COMPONENT', 'MANUF_COMPONENT')
  AND (pa.thru_date IS NULL OR pa.thru_date >= :from_date)
ORDER BY
    pa.from_date,
    p.product_id,
    pa.product_id_to;
```

## 6) Optional Facility/Product Mapping Snapshot
```sql
SELECT DISTINCT
    iid.product_id,
    iid.netsuite_product_id,
    iid.facility_id,
    iid.external_facility_id
FROM inventory_item_detail iid
WHERE iid.effective_date >= :from_date
  AND iid.effective_date < DATE_ADD(:to_date, INTERVAL 1 DAY)
ORDER BY
    iid.external_facility_id,
    iid.netsuite_product_id,
    iid.facility_id,
    iid.product_id;
```

## Expected CSV Names
- `oms_transfer_lifecycle_20260223_plus.csv`
- `oms_return_refund_linkage_20260223_plus.csv`
- `oms_order_fulfillment_backorder_20260223_plus.csv`
- `oms_cycle_count_audit_20260223_plus.csv`
- `oms_kit_component_map.csv`
- `oms_item_facility_mapping_20260223_plus.csv`

## Drop Location
Put exports here:
- `runtime://tmp/reconciliation/inventory/input/oms-supplemental`
- Absolute path:
  - `/Users/aditipatel/sandbox/darpan-master/darpan-backend/runtime/tmp/reconciliation/inventory/input/oms-supplemental`
