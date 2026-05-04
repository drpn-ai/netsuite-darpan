/**
 * @NApiVersion 2.1
 * @NScriptType Restlet
 */
define(['N/https', 'N/url'], (https, url) => {
    const DEFAULTS = {
        contains: 'CreateOrderItemsFeed',
        maxJobs: 200,
        dateFrom: null, // e.g. 3/17/2026
        dateTo: null,   // e.g. 3/30/2026
        includeAllMatches: false // false = only failed jobs
    };

    function get(params) {
        const p = Object.assign({}, DEFAULTS, params || {});
        const statusUrl = buildStatusUrl(p);
        const statusResp = https.get({ url: statusUrl });

        if (!statusResp || !statusResp.body) {
            throw new Error('Unable to load CSV job status page.');
        }

        const jobs = parseStatusPage(statusResp.body, p.contains);

        const filteredJobs = jobs.filter(job => {
            if (p.includeAllMatches) return true;
            return (job.failedCount || 0) > 0;
        }).slice(0, p.maxJobs);

        const enriched = filteredJobs.map(job => {
            let responseDetails = {
                ok: false,
                failedRows: [],
                rawRowCount: 0
            };

            if (job.csvResponseUrl) {
                try {
                    const resp = https.get({ url: job.csvResponseUrl });
                    responseDetails = parseCsvResponsePage(resp.body);
                } catch (e) {
                    responseDetails = {
                        ok: false,
                        error: 'Failed to fetch CSV Response: ' + stringifyError(e),
                        failedRows: [],
                        rawRowCount: 0
                    };
                }
            }

            return Object.assign({}, job, {
                csvResponse: responseDetails
            });
        });

        return {
            ok: true,
            filters: p,
            statusUrl,
            count: enriched.length,
            jobs: enriched
        };
    }

    function buildStatusUrl(params) {
        const host = url.resolveDomain({
            hostType: url.HostType.APPLICATION
        });

        const qs = {
            daterange: 'CUSTOM',
            datemodi: 'WITHIN',
            date: 'CUSTOM',
            sortcol: 'dcreated_sort',
            sortdir: 'DESC',
            csv: 'HTML',
            OfficeXML: 'F',
            pdf: '',
            size: String(params.maxJobs || 200)
        };

        if (params.dateFrom) qs.datefrom = params.dateFrom;
        if (params.dateTo) qs.dateto = params.dateTo;

        return 'https://' + host + '/app/setup/upload/csv/csvstatus.nl?' + toQueryString(qs);
    }

    function parseStatusPage(html, containsText) {
        const decoded = decodeHtml(html);
        const rows = extractTableRows(decoded);
        const jobs = [];

        for (let i = 0; i < rows.length; i++) {
            const rowHtml = rows[i];
            const cells = extractCells(rowHtml);

            // expected columns:
            // Date | Job Name | Status | Percent Complete | Message | CSV Response | Queue | Cancel
            if (cells.length < 6) continue;

            const date = cleanText(cells[0]);
            const jobName = cleanText(cells[1]);
            const status = cleanText(cells[2]);
            const percentComplete = cleanText(cells[3]);
            const message = cleanText(cells[4]);
            const queue = cells[6] ? cleanText(cells[6]) : null;

            if (!jobName || jobName.indexOf(containsText) === -1) {
                continue;
            }

            const csvResponseUrl = extractFirstHref(cells[5]);
            const wqid = extractQueryParam(csvResponseUrl, 'wqid');
            const counts = extractCounts(message);

            jobs.push({
                date,
                jobName,
                status,
                percentComplete,
                message,
                queue,
                csvResponseUrl,
                wqid,
                successCount: counts.successCount,
                totalCount: counts.totalCount,
                failedCount: counts.failedCount
            });
        }

        return jobs;
    }

    function parseCsvResponsePage(html) {
        if (!html) {
            return {
                ok: false,
                error: 'Empty CSV Response page',
                failedRows: [],
                rawRowCount: 0
            };
        }

        const decoded = decodeHtml(html);
        const tableRows = extractTableRows(decoded);

        if (!tableRows.length) {
            return {
                ok: false,
                error: 'No table rows found in CSV Response page',
                failedRows: [],
                rawRowCount: 0
            };
        }

        const parsedRows = [];
        for (let i = 0; i < tableRows.length; i++) {
            const cells = extractCells(tableRows[i]).map(cleanText);
            if (cells.length) {
                parsedRows.push(cells);
            }
        }

        if (!parsedRows.length) {
            return {
                ok: false,
                error: 'Could not parse CSV Response rows',
                failedRows: [],
                rawRowCount: 0
            };
        }

        const header = parsedRows[0];
        const bodyRows = parsedRows.slice(1);

        const failedRows = bodyRows
            .map(row => rowToObject(header, row))
            .filter(isFailureRow);

        return {
            ok: true,
            header,
            rawRowCount: bodyRows.length,
            failedRows
        };
    }

    function isFailureRow(rowObj) {
        const joined = Object.keys(rowObj)
            .map(k => String(rowObj[k] || ''))
            .join(' | ')
            .toLowerCase();

        return (
            joined.indexOf('error') !== -1 ||
            joined.indexOf('fail') !== -1 ||
            joined.indexOf('invalid') !== -1 ||
            joined.indexOf('not imported') !== -1 ||
            joined.indexOf('duplicate') !== -1 ||
            joined.indexOf('missing') !== -1
        );
    }

    function rowToObject(header, row) {
        const obj = {};
        const max = Math.max(header.length, row.length);

        for (let i = 0; i < max; i++) {
            const key = header[i] ? normalizeHeader(header[i]) : 'col_' + (i + 1);
            obj[key] = row[i] || '';
        }

        return obj;
    }

    function normalizeHeader(text) {
        return String(text || '')
            .trim()
            .toLowerCase()
            .replace(/[^a-z0-9]+/g, '_')
            .replace(/^_+|_+$/g, '') || 'column';
    }

    function extractCounts(message) {
        const out = {
            successCount: null,
            totalCount: null,
            failedCount: null
        };

        if (!message) return out;

        const m = String(message).match(/(\d+)\s+of\s+(\d+)\s+records?\s+imported\s+successfully/i);
        if (!m) return out;

        out.successCount = Number(m[1]);
        out.totalCount = Number(m[2]);
        out.failedCount = Math.max(out.totalCount - out.successCount, 0);
        return out;
    }

    function extractTableRows(html) {
        return html.match(/<tr\b[\s\S]*?<\/tr>/gi) || [];
    }

    function extractCells(rowHtml) {
        const cells = [];
        const regex = /<t[dh]\b[^>]*>([\s\S]*?)<\/t[dh]>/gi;
        let match;

        while ((match = regex.exec(rowHtml)) !== null) {
            cells.push(match[1]);
        }
        return cells;
    }

    function extractFirstHref(html) {
        if (!html) return null;
        const m = html.match(/href=["']([^"']+)["']/i);
        return m ? m[1] : null;
    }

    function extractQueryParam(urlStr, paramName) {
        if (!urlStr) return null;
        const re = new RegExp('[?&]' + escapeRegex(paramName) + '=([^&#]+)', 'i');
        const m = urlStr.match(re);
        return m ? decodeURIComponent(m[1]) : null;
    }

    function cleanText(str) {
        return decodeHtml(
            String(str || '')
                .replace(/<br\s*\/?>/gi, '\n')
                .replace(/<[^>]+>/g, ' ')
        )
            .replace(/\u00a0/g, ' ')
            .replace(/\s+/g, ' ')
            .trim();
    }

    function decodeHtml(str) {
        return String(str || '')
            .replace(/&nbsp;/gi, ' ')
            .replace(/&amp;/gi, '&')
            .replace(/&lt;/gi, '<')
            .replace(/&gt;/gi, '>')
            .replace(/&quot;/gi, '"')
            .replace(/&#39;/g, "'")
            .replace(/&#x27;/gi, "'");
    }

    function toQueryString(obj) {
        const parts = [];
        Object.keys(obj).forEach(key => {
            const val = obj[key];
            if (val !== null && val !== undefined && val !== '') {
                parts.push(encodeURIComponent(key) + '=' + encodeURIComponent(val));
            }
        });
        return parts.join('&');
    }

    function escapeRegex(text) {
        return String(text).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    }

    function stringifyError(e) {
        if (!e) return 'Unknown error';
        return e.name && e.message ? (e.name + ': ' + e.message) : String(e);
    }

    return { get };
});