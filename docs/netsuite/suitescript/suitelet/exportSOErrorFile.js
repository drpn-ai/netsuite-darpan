/**
 * @NApiVersion 2.1
 * @NScriptType Suitelet
 */
define(['N/https', 'N/runtime'], (https, runtime) => {
  function stripTags(s) {
    return (s || '')
      .replace(/<[^>]*>/g, ' ')
      .replace(/&nbsp;/g, ' ')
      .replace(/&amp;/g, '&')
      .replace(/\s+/g, ' ')
      .trim();
  }

  function absoluteUrl(href, statusPageUrl) {
    if (!href) return '';
    if (/^https?:\/\//i.test(href)) return href;

    const m = statusPageUrl.match(/^(https?:\/\/[^/]+)/i);
    const base = m ? m[1] : '';
    return base + href;
  }

  function parseRows(html, statusPageUrl, jobSubstring) {
    const rows = [];
    const trRegex = /<tr\b[\s\S]*?<\/tr>/gi;
    const tdRegex = /<td\b[\s\S]*?>([\s\S]*?)<\/td>/gi;
    const hrefRegex = /<a[^>]+href="([^"]+)"[^>]*>\s*CSV Response\s*<\/a>/i;
    const msgRegex = /(\d+)\s+of\s+(\d+)\s+records imported successfully/i;

    const trMatches = html.match(trRegex) || [];

    trMatches.forEach((tr) => {
      const cells = [];
      let tdMatch;

      while ((tdMatch = tdRegex.exec(tr)) !== null) {
        cells.push(tdMatch[1]);
      }

      // Expected columns:
      // 0 Date, 1 Job Name, 2 Status, 3 Percent, 4 Message, 5 CSV Response, 6 Queue, 7 Cancel
      if (cells.length < 6) return;

      const date = stripTags(cells[0]);
      const jobName = stripTags(cells[1]);
      const status = stripTags(cells[2]);
      const percentComplete = stripTags(cells[3]);
      const message = stripTags(cells[4]);
      const queue = stripTags(cells[6] || '');

      if (!jobName.includes(jobSubstring)) return;

      const msgMatch = message.match(msgRegex);
      if (!msgMatch) return;

      const imported = Number(msgMatch[1]);
      const total = Number(msgMatch[2]);
      if (!(imported < total)) return;

      const hrefMatch = cells[5].match(hrefRegex);
      const csvResponseUrl = hrefMatch
        ? absoluteUrl(hrefMatch[1], statusPageUrl)
        : '';

      rows.push({
        date,
        jobName,
        status,
        percentComplete,
        imported,
        total,
        failed: total - imported,
        message,
        queue,
        csvResponseUrl
      });
    });

    return rows;
  }

  function onRequest(context) {
    const req = context.request;
    const res = context.response;

    // Best test: paste the exact CSV Import Status URL from your browser into statusUrl.
    const statusUrl =
      req.parameters.statusUrl ||
      runtime.getCurrentScript().getParameter({ name: 'custscript_csv_status_url' });

    const jobSubstring =
      req.parameters.jobContains ||
      runtime.getCurrentScript().getParameter({ name: 'custscript_job_contains' }) ||
      'CreateOrderItemsFeed';

    if (!statusUrl) {
      res.setHeader({
        name: 'Content-Type',
        value: 'application/json; charset=utf-8'
      });
      res.write(JSON.stringify({
        ok: false,
        message: 'Missing statusUrl. Pass the full CSV Import Status URL as a query parameter or script parameter.'
      }, null, 2));
      return;
    }

    try {
      const response = https.get({ url: statusUrl });
      const html = response.body || '';

      const matches = parseRows(html, statusUrl, jobSubstring);

      res.setHeader({
        name: 'Content-Type',
        value: 'application/json; charset=utf-8'
      });
      res.write(JSON.stringify({
        ok: true,
        statusUrl,
        jobContains: jobSubstring,
        count: matches.length,
        matches
      }, null, 2));
    } catch (e) {
      res.setHeader({
        name: 'Content-Type',
        value: 'application/json; charset=utf-8'
      });
      res.write(JSON.stringify({
        ok: false,
        error: e.name || 'ERROR',
        details: e.message || String(e)
      }, null, 2));
    }
  }

  return { onRequest };
});