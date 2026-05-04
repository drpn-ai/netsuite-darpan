/**
 * @NApiVersion 2.0
 * @NScriptType Restlet
 *
 * Bulk inventory transaction extraction for reconciliation.
 *
 * - Max 100 pairs per request.
 * - Supports pair keys from either internal IDs or external IDs + map objects.
 * - Returns transaction.id and transaction.tranid for easy NetSuite follow-up.
 * - Enforces date floor of strictly after 2026-02-23 (effective from 2026-02-24).
 */
define(['N/search', 'N/runtime'], function(search, runtime) {
  var MAX_PAIRS = 100;
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

  function isIsoDate(value) {
    return /^\d{4}-\d{2}-\d{2}$/.test(toStr(value));
  }

  function toNetSuiteDate(isoDate) {
    var match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(toStr(isoDate));
    if (!match) throw new Error('Date must be yyyy-MM-dd for NetSuite conversion');
    var month = String(parseInt(match[2], 10));
    var day = String(parseInt(match[3], 10));
    var year2 = match[1].slice(2);
    return month + '/' + day + '/' + year2;
  }

  function pickMappedId(rawValue, mapObj) {
    var key = toStr(rawValue);
    if (!key) return '';
    if (Object.prototype.hasOwnProperty.call(mapObj, key) && toStr(mapObj[key])) {
      return toStr(mapObj[key]);
    }
    return key;
  }

  function buildError(pair, code, message, retryable) {
    return {
      pairId: pair.pairId,
      itemId: pair.itemId,
      locationId: pair.locationId,
      netsuiteProductId: pair.netsuiteProductId || null,
      externalFacilityId: pair.externalFacilityId || null,
      status: 'ERROR',
      errorCode: code,
      errorMessage: message,
      retryable: !!retryable,
      recordCount: 0,
      records: [],
      warnings: []
    };
  }

  function normalizeRequest(payload) {
    var req = asObject(payload);

    var fromDate = toStr(req.from);
    var toDate = toStr(req.to);
    if (!isIsoDate(fromDate)) throw new Error('from must be yyyy-MM-dd');
    if (!isIsoDate(toDate)) throw new Error('to must be yyyy-MM-dd');
    if (toDate < fromDate) throw new Error('to must be on or after from');

    var rawPairs = Array.isArray(req.pairs) ? req.pairs : [];
    if (!rawPairs.length) throw new Error('pairs is required and must contain at least one row');
    if (rawPairs.length > MAX_PAIRS) throw new Error('pairs cannot exceed ' + MAX_PAIRS);

    var itemMap = asObject(req.itemMap || req.netsuiteProductToItemMap || req.productMap);
    var facilityMap = asObject(req.facilityMap || req.externalFacilityToLocationMap || req.locationMap);

    var seen = {};
    var pairs = [];
    var i;
    for (i = 0; i < rawPairs.length; i += 1) {
      var row = asObject(rawPairs[i]);
      var rawItemKey = firstNonBlank(row.itemId, row.itemInternalId, row.netsuite_product_id, row.netsuiteProductId);
      var rawFacilityKey = firstNonBlank(row.locationId, row.locationInternalId, row.external_facility_id, row.externalFacilityId);

      if (!rawItemKey) throw new Error('pairs[' + i + '] missing itemId/itemInternalId/netsuite_product_id');
      if (!rawFacilityKey) throw new Error('pairs[' + i + '] missing locationId/locationInternalId/external_facility_id');

      var mappedItemId = pickMappedId(rawItemKey, itemMap);
      var mappedLocationId = pickMappedId(rawFacilityKey, facilityMap);
      if (!mappedItemId) throw new Error('pairs[' + i + '] could not resolve itemId from key ' + rawItemKey);
      if (!mappedLocationId) throw new Error('pairs[' + i + '] could not resolve locationId from key ' + rawFacilityKey);

      var pairId = toStr(row.pairId) || (mappedItemId + '|' + mappedLocationId);
      if (seen[pairId]) throw new Error('Duplicate pairId: ' + pairId);
      seen[pairId] = true;

      pairs.push({
        pairId: pairId,
        itemId: mappedItemId,
        locationId: mappedLocationId,
        netsuiteProductId: toStr(row.netsuite_product_id) || null,
        externalFacilityId: toStr(row.external_facility_id) || null,
        sourceItemKey: rawItemKey,
        sourceFacilityKey: rawFacilityKey
      });
    }

    return {
      requestId: toStr(req.requestId) || null,
      from: fromDate,
      to: toDate,
      pairs: pairs,
      options: asObject(req.options)
    };
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

  function createSearchColumns(columnDefs) {
    var columns = [];
    var i;
    for (i = 0; i < columnDefs.length; i += 1) {
      columns.push(search.createColumn({ name: columnDefs[i].name }));
    }
    return columns;
  }

  function fetchTransactions(pair, fromDate, toDate) {
    var warnings = [];
    var itemIdNum = toInt(pair.itemId);
    var locationIdNum = toInt(pair.locationId);
    if (itemIdNum === null || locationIdNum === null) {
      return buildError(pair, 'INVALID_KEY', 'Resolved item/location internal IDs must be numeric', false);
    }

    var effectiveFromDate = fromDate < MIN_ALLOWED_DATE ? MIN_ALLOWED_DATE : fromDate;
    if (toDate < effectiveFromDate) {
      return {
        pairId: pair.pairId,
        itemId: pair.itemId,
        locationId: pair.locationId,
        netsuiteProductId: pair.netsuiteProductId || null,
        externalFacilityId: pair.externalFacilityId || null,
        status: 'OK',
        errorCode: null,
        errorMessage: null,
        retryable: false,
        recordCount: 0,
        records: [],
        warnings: ['No rows returned because to < enforced minimum date floor (' + MIN_ALLOWED_DATE + ').'],
        dateRangeApplied: { from: effectiveFromDate, to: toDate }
      };
    }

    var nsFrom = toNetSuiteDate(effectiveFromDate);
    var nsTo = toNetSuiteDate(toDate);

    var filters = [
      ['item', 'anyof', String(itemIdNum)], 'AND',
      ['location', 'anyof', String(locationIdNum)], 'AND',
      ['trandate', 'within', nsFrom, nsTo]
    ];

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
        { key: 'quantityshiprecv', name: 'quantityshiprecv' }
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
      ],
      [
        { key: 'internalid', name: 'internalid' },
        { key: 'tranid', name: 'tranid' },
        { key: 'trandate', name: 'trandate' },
        { key: 'type', name: 'type' },
        { key: 'quantity', name: 'quantity' }
      ]
    ];

    var activeDefs = null;
    var rows = [];
    var c;
    for (c = 0; c < columnSets.length; c += 1) {
      var defs = columnSets[c];
      try {
        var txnSearch = search.create({
          type: search.Type.TRANSACTION,
          filters: filters,
          columns: createSearchColumns(defs)
        });
        rows = runPagedRows(txnSearch);
        activeDefs = defs;
        if (c > 0) warnings.push('Used fallback column set #' + (c + 1) + ' due to NetSuite search column compatibility.');
        break;
      } catch (e) {
        if (!isInvalidColumnError(e) || c === columnSets.length - 1) {
          throw e;
        }
      }
    }

    var records = [];
    rows.forEach(function(row) {
      var valueByKey = {};
      var textByKey = {};
      var i;
      for (i = 0; i < activeDefs.length; i += 1) {
        var def = activeDefs[i];
        var col = search.createColumn({ name: def.name });
        valueByKey[def.key] = row.getValue(col);
        textByKey[def.key] = row.getText(col);
      }

      var typeText = toStr(textByKey.type) || toStr(valueByKey.type);
      var txId = toStr(valueByKey.internalid);
      var tranId = toStr(valueByKey.tranid);
      var txDate = toStr(valueByKey.trandate);
      var qty = toStr(valueByKey.quantity);
      var qtyShipRecv = toStr(valueByKey.quantityshiprecv);

      records.push({
        transaction: {
          id: txId || null,
          tranid: tranId || null,
          date: txDate || null,
          type: typeText || null,
          status: firstNonBlank(textByKey.status, valueByKey.status, textByKey.statusref, valueByKey.statusref) || null,
          statusRef: toStr(valueByKey.statusref) || null,
          itemId: firstNonBlank(valueByKey.item, pair.itemId) || null,
          itemText: toStr(textByKey.item) || null,
          locationId: firstNonBlank(valueByKey.location, pair.locationId) || null,
          locationText: toStr(textByKey.location) || null,
          transferLocationId: toStr(valueByKey.transferlocation) || null,
          transferLocationText: toStr(textByKey.transferlocation) || null,
          createdFromId: toStr(valueByKey.createdfrom) || null,
          createdFromText: toStr(textByKey.createdfrom) || null,
          qty: qty || null,
          qtyShipped: qtyShipRecv || null,
          qtyReceived: qtyShipRecv || null,
          itemReceiptId: typeText === 'Item Receipt' ? (txId || null) : null,
          receiptDate: typeText === 'Item Receipt' ? (txDate || null) : null
        }
      });
    });

    return {
      pairId: pair.pairId,
      itemId: pair.itemId,
      locationId: pair.locationId,
      netsuiteProductId: pair.netsuiteProductId || null,
      externalFacilityId: pair.externalFacilityId || null,
      status: 'OK',
      errorCode: null,
      errorMessage: null,
      retryable: false,
      recordCount: records.length,
      records: records,
      warnings: warnings,
      dateRangeApplied: { from: effectiveFromDate, to: toDate }
    };
  }

  function post(payload) {
    var req = normalizeRequest(payload);
    var results = [];
    var i;

    for (i = 0; i < req.pairs.length; i += 1) {
      var pair = req.pairs[i];
      try {
        results.push(fetchTransactions(pair, req.from, req.to));
      } catch (e) {
        results.push(buildError(pair, 'PAIR_PROCESSING_FAILED', (e && e.message) || 'Unexpected pair failure', false));
      }
    }

    var successPairs = 0;
    for (i = 0; i < results.length; i += 1) {
      if (results[i].status !== 'ERROR') successPairs += 1;
    }

    return {
      ok: true,
      requestId: req.requestId,
      environment: runtime.envType,
      summary: {
        requestedPairs: req.pairs.length,
        processedPairs: results.length,
        successPairs: successPairs,
        errorPairs: results.length - successPairs,
        minAllowedDate: MIN_ALLOWED_DATE
      },
      results: results,
      errors: []
    };
  }

  return { post: post };
});
