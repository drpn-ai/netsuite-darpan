/**
 * @NApiVersion 2.0
 * @NScriptType Restlet
 *
 * Secondary NetSuite detail Restlet for discrepancy investigation.
 *
 * Request contract:
 * {
 *   requestId?: string,
 *   from?: 'yyyy-MM-dd',
 *   to?: 'yyyy-MM-dd',
 *   references: [
 *     {
 *       referenceId?: string,
 *       pairId?: string,
 *       id?: string,                  // transaction internalid
 *       tranid?: string,              // transaction tranid
 *       itemId?: string,
 *       locationId?: string,
 *       netsuite_product_id?: string,
 *       external_facility_id?: string
 *     }
 *   ],
 *   itemMap?: { [netsuite_product_id]: internalItemId },
 *   facilityMap?: { [external_facility_id]: internalLocationId }
 * }
 */
define(['N/search', 'N/runtime'], function(search, runtime) {
  var MAX_REFERENCES = 100;
  var MIN_ALLOWED_DATE = '2026-02-24';

  function toStr(value) {
    return value === null || value === undefined ? '' : String(value).trim();
  }

  function asObject(value) {
    if (!value || Object.prototype.toString.call(value) !== '[object Object]') return {};
    return value;
  }

  function firstNonBlank() {
    var i;
    for (i = 0; i < arguments.length; i += 1) {
      var value = toStr(arguments[i]);
      if (value) return value;
    }
    return '';
  }

  function toInt(value) {
    var num = Number(value);
    if (!isFinite(num)) return null;
    return num < 0 ? Math.ceil(num) : Math.floor(num);
  }

  function toNetSuiteDate(isoDate) {
    var match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(toStr(isoDate));
    if (!match) throw new Error('Date must be yyyy-MM-dd for NetSuite conversion');
    return String(parseInt(match[2], 10)) + '/' + String(parseInt(match[3], 10)) + '/' + match[1].slice(2);
  }

  function isIsoDate(value) {
    return /^\d{4}-\d{2}-\d{2}$/.test(toStr(value));
  }

  function pickMappedId(rawValue, mapObj) {
    var key = toStr(rawValue);
    if (!key) return '';
    if (Object.prototype.hasOwnProperty.call(mapObj, key) && toStr(mapObj[key])) {
      return toStr(mapObj[key]);
    }
    return key;
  }

  function isInvalidColumnError(errorObj) {
    var code = toStr(errorObj && errorObj.name).toUpperCase();
    var msg = toStr(errorObj && errorObj.message).toUpperCase();
    return code === 'SSS_INVALID_SRCH_COL' || msg.indexOf('INVALID') >= 0 && msg.indexOf('COLUMN') >= 0;
  }

  function runPagedRows(txnSearch) {
    var rows = [];
    var paged = txnSearch.runPaged({ pageSize: 1000 });
    paged.pageRanges.forEach(function(range) {
      var page = paged.fetch({ index: range.index });
      page.data.forEach(function(row) {
        rows.push(row);
      });
    });
    return rows;
  }

  function createColumns(columnDefs) {
    var columns = [];
    var i;
    for (i = 0; i < columnDefs.length; i += 1) {
      columns.push(search.createColumn({ name: columnDefs[i].name }));
    }
    return columns;
  }

  function normalizeRequest(payload) {
    var req = asObject(payload);
    var rawReferences = Array.isArray(req.references) ? req.references : [];

    if (!rawReferences.length) throw new Error('references is required and must contain at least one row');
    if (rawReferences.length > MAX_REFERENCES) throw new Error('references cannot exceed ' + MAX_REFERENCES);

    var fromDate = toStr(req.from);
    var toDate = toStr(req.to);
    if (fromDate || toDate) {
      if (!isIsoDate(fromDate)) throw new Error('from must be yyyy-MM-dd when provided');
      if (!isIsoDate(toDate)) throw new Error('to must be yyyy-MM-dd when provided');
      if (toDate < fromDate) throw new Error('to must be on or after from');
    }

    var itemMap = asObject(req.itemMap || req.netsuiteProductToItemMap || req.productMap);
    var facilityMap = asObject(req.facilityMap || req.externalFacilityToLocationMap || req.locationMap);

    var references = [];
    var i;
    for (i = 0; i < rawReferences.length; i += 1) {
      var row = asObject(rawReferences[i]);
      var txId = firstNonBlank(row.id, row.internalId, row.transactionId, row.tranInternalId);
      var txTranId = firstNonBlank(row.tranid, row.tranId, row.transactionNumber, row.docnum);
      if (!txId && !txTranId) {
        throw new Error('references[' + i + '] must provide id or tranid');
      }

      var rawItemKey = firstNonBlank(row.itemId, row.itemInternalId, row.netsuite_product_id, row.netsuiteProductId);
      var rawFacilityKey = firstNonBlank(row.locationId, row.locationInternalId, row.external_facility_id, row.externalFacilityId);
      var mappedItemId = pickMappedId(rawItemKey, itemMap);
      var mappedLocationId = pickMappedId(rawFacilityKey, facilityMap);
      var pairId = toStr(row.pairId) || (mappedItemId && mappedLocationId ? (mappedItemId + '|' + mappedLocationId) : ('ref-' + (i + 1)));

      references.push({
        referenceId: toStr(row.referenceId) || ('ref-' + (i + 1)),
        pairId: pairId,
        id: txId || null,
        tranid: txTranId || null,
        itemId: mappedItemId || null,
        locationId: mappedLocationId || null,
        netsuiteProductId: toStr(row.netsuite_product_id) || null,
        externalFacilityId: toStr(row.external_facility_id) || null
      });
    }

    return {
      requestId: toStr(req.requestId) || null,
      from: fromDate || null,
      to: toDate || null,
      references: references
    };
  }

  function buildFilters(ref, fromDate, toDate) {
    var filters = [];

    if (ref.id && ref.tranid) {
      filters.push([['internalid', 'anyof', ref.id], 'OR', ['tranid', 'is', ref.tranid]]);
    } else if (ref.id) {
      filters.push(['internalid', 'anyof', ref.id]);
    } else {
      filters.push(['tranid', 'is', ref.tranid]);
    }

    if (ref.itemId && toInt(ref.itemId) !== null) {
      filters.push('AND');
      filters.push(['item', 'anyof', String(toInt(ref.itemId))]);
    }
    if (ref.locationId && toInt(ref.locationId) !== null) {
      filters.push('AND');
      filters.push(['location', 'anyof', String(toInt(ref.locationId))]);
    }

    filters.push('AND');
    filters.push(['mainline', 'is', 'T']);

    if (fromDate && toDate) {
      var effectiveFrom = fromDate < MIN_ALLOWED_DATE ? MIN_ALLOWED_DATE : fromDate;
      if (toDate >= effectiveFrom) {
        filters.push('AND');
        filters.push(['trandate', 'within', toNetSuiteDate(effectiveFrom), toNetSuiteDate(toDate)]);
      }
    }

    return filters;
  }

  function queryTransactionRows(filters) {
    var columnSets = [
      [
        { key: 'internalid', name: 'internalid' },
        { key: 'tranid', name: 'tranid' },
        { key: 'trandate', name: 'trandate' },
        { key: 'type', name: 'type' },
        { key: 'statusref', name: 'statusref' },
        { key: 'status', name: 'status' },
        { key: 'item', name: 'item' },
        { key: 'location', name: 'location' },
        { key: 'transferlocation', name: 'transferlocation' },
        { key: 'createdfrom', name: 'createdfrom' },
        { key: 'quantity', name: 'quantity' },
        { key: 'quantityshiprecv', name: 'quantityshiprecv' },
        { key: 'memo', name: 'memo' }
      ],
      [
        { key: 'internalid', name: 'internalid' },
        { key: 'tranid', name: 'tranid' },
        { key: 'trandate', name: 'trandate' },
        { key: 'type', name: 'type' },
        { key: 'item', name: 'item' },
        { key: 'location', name: 'location' },
        { key: 'createdfrom', name: 'createdfrom' },
        { key: 'quantity', name: 'quantity' }
      ]
    ];

    var activeDefs = null;
    var rows = [];
    var warnings = [];
    var i;

    for (i = 0; i < columnSets.length; i += 1) {
      var defs = columnSets[i];
      try {
        var txnSearch = search.create({
          type: search.Type.TRANSACTION,
          filters: filters,
          columns: createColumns(defs)
        });
        rows = runPagedRows(txnSearch);
        activeDefs = defs;
        if (i > 0) warnings.push('Used fallback transaction column set #' + (i + 1) + '.');
        break;
      } catch (e) {
        if (!isInvalidColumnError(e) || i === columnSets.length - 1) throw e;
      }
    }

    return {
      rows: rows,
      defs: activeDefs,
      warnings: warnings
    };
  }

  function mapRow(row, defs, fallbackItemId, fallbackLocationId) {
    var values = {};
    var texts = {};
    var i;

    for (i = 0; i < defs.length; i += 1) {
      var def = defs[i];
      var col = search.createColumn({ name: def.name });
      values[def.key] = row.getValue(col);
      texts[def.key] = row.getText(col);
    }

    var typeText = firstNonBlank(texts.type, values.type);
    var txId = toStr(values.internalid);
    var tranid = toStr(values.tranid);
    var trandate = toStr(values.trandate);
    var qty = toStr(values.quantity);
    var qtyShipRecv = toStr(values.quantityshiprecv);

    return {
      id: txId || null,
      tranid: tranid || null,
      date: trandate || null,
      type: typeText || null,
      status: firstNonBlank(texts.status, values.status, texts.statusref, values.statusref) || null,
      statusRef: toStr(values.statusref) || null,
      itemId: firstNonBlank(values.item, fallbackItemId) || null,
      itemText: toStr(texts.item) || null,
      locationId: firstNonBlank(values.location, fallbackLocationId) || null,
      locationText: toStr(texts.location) || null,
      transferLocationId: toStr(values.transferlocation) || null,
      transferLocationText: toStr(texts.transferlocation) || null,
      createdFromId: toStr(values.createdfrom) || null,
      createdFromText: toStr(texts.createdfrom) || null,
      qty: qty || null,
      shippedQty: qtyShipRecv || null,
      receivedQty: qtyShipRecv || null,
      itemReceiptId: typeText === 'Item Receipt' ? (txId || null) : null,
      receiptDate: typeText === 'Item Receipt' ? (trandate || null) : null,
      memo: toStr(values.memo) || null
    };
  }

  function fetchLinkedReceipts(transferInternalId, fromDate, toDate) {
    if (!transferInternalId) return [];

    var filters = [
      ['createdfrom', 'anyof', transferInternalId], 'AND',
      ['mainline', 'is', 'T']
    ];

    if (fromDate && toDate) {
      var effectiveFrom = fromDate < MIN_ALLOWED_DATE ? MIN_ALLOWED_DATE : fromDate;
      if (toDate >= effectiveFrom) {
        filters.push('AND');
        filters.push(['trandate', 'within', toNetSuiteDate(effectiveFrom), toNetSuiteDate(toDate)]);
      }
    }

    var columns = [
      search.createColumn({ name: 'internalid' }),
      search.createColumn({ name: 'tranid' }),
      search.createColumn({ name: 'trandate' }),
      search.createColumn({ name: 'type' }),
      search.createColumn({ name: 'statusref' }),
      search.createColumn({ name: 'status' }),
      search.createColumn({ name: 'quantity' }),
      search.createColumn({ name: 'quantityshiprecv' }),
      search.createColumn({ name: 'location' })
    ];

    var txnSearch = search.create({
      type: search.Type.TRANSACTION,
      filters: filters,
      columns: columns
    });

    var rows = runPagedRows(txnSearch);
    var receipts = [];

    rows.forEach(function(row) {
      var typeText = firstNonBlank(row.getText(columns[3]), row.getValue(columns[3]));
      var lowerType = toStr(typeText).toLowerCase();
      if (lowerType.indexOf('item receipt') < 0) return;

      receipts.push({
        id: toStr(row.getValue(columns[0])) || null,
        tranid: toStr(row.getValue(columns[1])) || null,
        date: toStr(row.getValue(columns[2])) || null,
        type: typeText || null,
        status: firstNonBlank(row.getText(columns[5]), row.getValue(columns[5]), row.getText(columns[4]), row.getValue(columns[4])) || null,
        statusRef: toStr(row.getValue(columns[4])) || null,
        qty: toStr(row.getValue(columns[6])) || null,
        qtyShipRecv: toStr(row.getValue(columns[7])) || null,
        locationId: toStr(row.getValue(columns[8])) || null,
        locationText: toStr(row.getText(columns[8])) || null
      });
    });

    return receipts;
  }

  function toNumberOrZero(rawValue) {
    var num = Number(rawValue);
    return isFinite(num) ? num : 0;
  }

  function summarizeLifecycle(detailRows, linkedReceipts) {
    var transferRows = [];
    var receivedQty = 0;
    var transferredQty = 0;

    detailRows.forEach(function(row) {
      var rowType = toStr(row.type).toLowerCase();
      if (rowType.indexOf('transfer order') >= 0 || rowType.indexOf('inventory transfer') >= 0) {
        transferRows.push(row);
        transferredQty += toNumberOrZero(row.shippedQty || row.qty);
        receivedQty += toNumberOrZero(row.receivedQty);
      }
    });

    var hasTransferSignal = transferRows.length > 0;
    var hasReceiptSignal = linkedReceipts.length > 0 || detailRows.some(function(row) {
      return toStr(row.type).toLowerCase().indexOf('item receipt') >= 0;
    });

    var state = 'NOT_APPLICABLE';
    if (hasTransferSignal || hasReceiptSignal) {
      state = hasReceiptSignal ? 'RECEIVED' : 'IN_TRANSIT';
    }

    return {
      state: state,
      transferEventCount: transferRows.length,
      receiptEventCount: linkedReceipts.length,
      transferredQty: String(transferredQty),
      receivedQty: String(receivedQty)
    };
  }

  function buildNotFoundResult(ref, fromDate, toDate) {
    var effectiveFrom = fromDate && fromDate < MIN_ALLOWED_DATE ? MIN_ALLOWED_DATE : fromDate;
    return {
      referenceId: ref.referenceId,
      pairId: ref.pairId,
      itemId: ref.itemId,
      locationId: ref.locationId,
      lookup: { id: ref.id, tranid: ref.tranid },
      status: 'NOT_FOUND',
      errorCode: null,
      errorMessage: null,
      retryable: false,
      recordCount: 0,
      detailRows: [],
      linkedItemReceipts: [],
      toLifecycle: { state: 'NOT_APPLICABLE', transferEventCount: 0, receiptEventCount: 0, transferredQty: '0', receivedQty: '0' },
      warnings: [],
      dateRangeApplied: { from: effectiveFrom || null, to: toDate || null }
    };
  }

  function buildErrorResult(ref, code, message) {
    return {
      referenceId: ref.referenceId,
      pairId: ref.pairId,
      itemId: ref.itemId,
      locationId: ref.locationId,
      lookup: { id: ref.id, tranid: ref.tranid },
      status: 'ERROR',
      errorCode: code,
      errorMessage: message,
      retryable: false,
      recordCount: 0,
      detailRows: [],
      linkedItemReceipts: [],
      toLifecycle: { state: 'NOT_APPLICABLE', transferEventCount: 0, receiptEventCount: 0, transferredQty: '0', receivedQty: '0' },
      warnings: []
    };
  }

  function inspectReference(ref, fromDate, toDate) {
    var filters = buildFilters(ref, fromDate, toDate);
    var queried = queryTransactionRows(filters);
    var rows = queried.rows;

    if (!rows.length) {
      var notFound = buildNotFoundResult(ref, fromDate, toDate);
      notFound.warnings = queried.warnings;
      return notFound;
    }

    var detailRows = rows.map(function(row) {
      return mapRow(row, queried.defs, ref.itemId, ref.locationId);
    });

    var linkedReceipts = [];
    detailRows.forEach(function(detailRow) {
      var typeName = toStr(detailRow.type).toLowerCase();
      if (typeName.indexOf('transfer order') >= 0 && detailRow.id) {
        linkedReceipts = linkedReceipts.concat(fetchLinkedReceipts(detailRow.id, fromDate, toDate));
      }
    });

    var lifecycle = summarizeLifecycle(detailRows, linkedReceipts);
    var effectiveFrom = fromDate && fromDate < MIN_ALLOWED_DATE ? MIN_ALLOWED_DATE : fromDate;

    return {
      referenceId: ref.referenceId,
      pairId: ref.pairId,
      itemId: ref.itemId,
      locationId: ref.locationId,
      lookup: { id: ref.id, tranid: ref.tranid },
      status: 'OK',
      errorCode: null,
      errorMessage: null,
      retryable: false,
      recordCount: detailRows.length,
      detailRows: detailRows,
      linkedItemReceipts: linkedReceipts,
      toLifecycle: lifecycle,
      warnings: queried.warnings,
      dateRangeApplied: { from: effectiveFrom || null, to: toDate || null }
    };
  }

  function post(payload) {
    var req = normalizeRequest(payload);
    var results = [];
    var i;

    for (i = 0; i < req.references.length; i += 1) {
      var ref = req.references[i];
      try {
        results.push(inspectReference(ref, req.from, req.to));
      } catch (e) {
        results.push(buildErrorResult(ref, 'REFERENCE_PROCESSING_FAILED', (e && e.message) || 'Unexpected reference failure'));
      }
    }

    var successCount = 0;
    var errorCount = 0;
    var notFoundCount = 0;

    for (i = 0; i < results.length; i += 1) {
      if (results[i].status === 'OK') successCount += 1;
      if (results[i].status === 'ERROR') errorCount += 1;
      if (results[i].status === 'NOT_FOUND') notFoundCount += 1;
    }

    return {
      ok: true,
      requestId: req.requestId,
      environment: runtime.envType,
      summary: {
        requestedReferences: req.references.length,
        processedReferences: results.length,
        successReferences: successCount,
        notFoundReferences: notFoundCount,
        errorReferences: errorCount,
        minAllowedDate: MIN_ALLOWED_DATE
      },
      results: results,
      errors: []
    };
  }

  return { post: post };
});
