package netsuite.reconciliation.orders

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-053: vetted query templates, so a within-NetSuite chain check is expressible at all.
 *
 * <p><b>Why templates rather than a query column.</b> Every one of DAR-BE-045's six pairs is an
 * anti-join across {@code nexttransactionlink}, and the triage gate that takes 97 raw orphans down to 5
 * real findings is a second join to {@code transactionline}. The assembled-parts model had no slot for
 * either. But DAR-BE-044 refused raw query text for two live reasons that still hold: the window bounds
 * are interpolated as TEXT rather than bound as parameters, and {@code ORDER BY id} is what makes offset
 * paging safe. So the SHAPES are owned by code and configuration only picks one and names its record
 * types — every fragment stays identifier-checked and the ordering stays out of operator hands.</p>
 *
 * <p>Pure: no Moqui, no NetSuite account.</p>
 */
class NsSuiteQlQueryTemplateTest {

    private static Map<String, Object> spec(Map<String, Object> overrides, List<Map<String, Object>> moreFields = []) {
        Map<String, Object> query = [nsSuiteQlSourceQueryId: "Q1", recordType: "SalesOrd",
                                     fromTable: "transaction", dateColumn: "trandate"] + overrides
        int seq = 1
        return NsSuiteQlOrderSupport.normalizeSpec(query,
                [[recordFieldName: "tranId", sourceColumn: "tranid", sequenceNum: 1]] + moreFields.collect { it + [sequenceNum: ++seq] })
    }

    private static String query(Map<String, Object> overrides) {
        return NsSuiteQlOrderSupport.buildRecordsQuery(spec(overrides), "2026-08-01", "2026-09-01")
    }

    private static String queryAsOf(Map<String, Object> overrides, String asOfDate) {
        return NsSuiteQlOrderSupport.buildRecordsQuery(spec(overrides), "2026-08-01", "2026-09-01", asOfDate)
    }

    // ------------------------------------------------- STATE_CONTRADICTION (measured map)

    /**
     * The map is MEASURED on gorjana, not taken from the documented code list (2026-09-29, two days,
     * 8,882 orders): B carries 0 fulfilments and 0 invoices on both days — it means "nothing yet", so
     * absence is correct and only lateness is a defect. G is invoiced 100% of the time (6856/6856 and
     * 2026/2026) — it means Billed, and 53 + 26 of those had NO fulfillment, which is a contradiction
     * on its face. H closes with neither child 39 times out of 55: that is what cancellation looks
     * like, so it is excluded rather than reported.
     */
    private static Map<String, Object> stateSpec(Map<String, Object> overrides = [:]) {
        return spec([queryTemplate           : "STATE_CONTRADICTION",
                     pendingStates           : "B",
                     statesExpectingShipment : "D,E,F,G",
                     statesExpectingInvoice  : "D,G"] + overrides)
    }

    @Test
    void pendingStatesAreOnlyAFindingWhenOverdue() {
        String q = NsSuiteQlOrderSupport.buildRecordsQuery(stateSpec(), "2026-08-01", "2026-09-01", "2026-09-29")

        // B, with no shipment, past its promised date. Yesterday 1042 orders sat at B with 0 past due;
        // this clause is what makes that 0 rather than 1042.
        assertTrue(q.contains("t.status IN ('B')"), q)
        assertTrue(q.contains("t.shipdate < TO_DATE('2026-09-29', 'YYYY-MM-DD')"), q)
    }

    @Test
    void billedStatesAreAFindingWithNoShipmentRegardlessOfDate() {
        String q = NsSuiteQlOrderSupport.buildRecordsQuery(stateSpec(), "2026-08-01", "2026-09-01", "2026-09-29")

        assertTrue(q.contains("t.status IN ('D','E','F','G')"), q)
        // The shipment branch carries no date test: an order that reached billing without shipping is
        // wrong today, not wrong-in-two-weeks. Assert the branch WHOLE rather than searching for
        // "shipdate" near it — the same status list also appears in the CASE label up in the SELECT,
        // so any position-based search walks into the pending branch and its date.
        assertTrue(q.contains("(t.status IN ('D','E','F','G') AND NOT EXISTS (SELECT 1 FROM " +
                "nexttransactionlink l JOIN transaction c ON c.id = l.nextdoc " +
                "WHERE l.previousdoc = t.id AND c.type = 'ItemShip' AND c.status = 'C'))"), q)
    }

    @Test
    void invoiceExpectationIsItsOwnBranch() {
        String q = NsSuiteQlOrderSupport.buildRecordsQuery(stateSpec(), "2026-08-01", "2026-09-01", "2026-09-29")

        // The branch itself: these states with no invoice linked (S41b: the old check also passed on any 'CustInvc').
        assertTrue(q.contains("t.status IN ('D','G') AND NOT EXISTS (SELECT 1 FROM nexttransactionlink l2 " +
                "JOIN transaction c2 ON c2.id = l2.nextdoc WHERE l2.previousdoc = t.id AND c2.type = 'CustInvc')"), q)
    }

    /** A status in no list is not reported. H closes with neither child and that is legitimate. */
    @Test
    void unlistedStatesAreExcludedRatherThanReported() {
        String q = NsSuiteQlOrderSupport.buildRecordsQuery(stateSpec(), "2026-08-01", "2026-09-01", "2026-09-29")

        // Every status the query names is one the map lists (S41b: H was never configured, so its absence proved nothing).
        Set<String> named = (q =~ /t\.status IN \(([^)]*)\)/).collect { List m -> ((String) m[1]).split(",")*.trim()*.replace("'", "") }
                .flatten() as Set<String>
        assertEquals(["B", "D", "E", "F", "G"] as Set, named, q)
    }

    /** Every finding says WHICH contradiction it is; a list of ids is not an actionable report. */
    @Test
    void everyFindingCarriesItsContradiction() {
        String q = NsSuiteQlOrderSupport.buildRecordsQuery(stateSpec(), "2026-08-01", "2026-09-01", "2026-09-29")

        assertTrue(q.contains("AS contradiction"), q)
        // Quoted: "NOT_SHIPPED" alone is also inside OVERDUE_NOT_SHIPPED and would pass without its own branch (S41b).
        assertTrue(q.contains("'OVERDUE_NOT_SHIPPED'"), q)
        assertTrue(q.contains("'NOT_SHIPPED'"), q)
        assertTrue(q.contains("'NOT_INVOICED'"), q)
    }

    /**
     * The brief is frozen into the run document by the QUERY rather than composed in the UI. A result
     * re-opened months later then says what it said the day it ran — the property that matters for
     * something an operator acted on and may have to justify.
     */
    @Test
    void everyFindingCarriesAWrittenBrief() {
        String q = NsSuiteQlOrderSupport.buildRecordsQuery(stateSpec(), "2026-08-01", "2026-09-01", "2026-09-29")

        assertTrue(q.contains("AS contradiction_brief"), q)
        assertTrue(q.contains("'Past its ship date, not shipped'"), q)
        assertTrue(q.contains("'Status expects a shipment; none recorded'"), q)
        assertTrue(q.contains("'Status expects an invoice; none recorded'"), q)
    }

    /**
     * The wording stays generic per status LIST. "Billed, never shipped" reads better and is only
     * true for D and G; on E and F — which expect a shipment but are not billed — it would be a lie,
     * and a report that misstates the reason is worse than one that states it plainly.
     */
    @Test
    void theBriefDoesNotClaimBilledForStatesThatAreNot() {
        String q = NsSuiteQlOrderSupport.buildRecordsQuery(stateSpec(), "2026-08-01", "2026-09-01", "2026-09-29")

        assertFalse(q.toLowerCase().contains("billed, never shipped"), q)
    }

    /** The template's own columns reach the RECORD, not just the SELECT list. */
    @Test
    void templateColumnsAreMappedOntoTheRecord() {
        Map<String, Object> spec = stateSpec()
        Map<String, Object> record = NsSuiteQlOrderSupport.mapRowToRecord(spec,
                [tranid: "SO6960046", contradiction: "NOT_SHIPPED",
                 contradiction_brief: "Status expects a shipment; none recorded"])

        assertEquals("NOT_SHIPPED", record.contradiction)
        assertEquals("Status expects a shipment; none recorded", record.contradictionBrief)
        assertEquals("SO6960046", record.tranId)
    }

    /** A template that contributes no such columns leaves the record exactly as configured. */
    @Test
    void recordsFromOtherTemplatesGainNothing() {
        Map<String, Object> record = NsSuiteQlOrderSupport.mapRowToRecord(
                spec([queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "ItemShip"]),
                [tranid: "SO6960046"])

        assertFalse(record.containsKey("contradiction"), record.toString())
        assertFalse(record.containsKey("contradictionBrief"), record.toString())
    }

    /**
     * An order whose lines can never ship was never going to have a fulfillment, whatever its status
     * says. Measured on gorjana 2026-08-01: all 53 "billed, not shipped" findings were gift-card
     * orders (SKU GFT-CP, 54 lines over 53 orders) — and status G reported open_fulfillable_line on
     * 6803 of 6856, the 53 difference being exactly them. NetSuite's own fulfillable flag already
     * identifies this class, so the gate reads the flag rather than hardcoding a SKU that will change.
     */
    @Test
    void theShipmentBranchRequiresSomethingActuallyShippable() {
        String q = NsSuiteQlOrderSupport.buildRecordsQuery(
                stateSpec([requireFulfillableOpenLine: "Y"]), "2026-08-01", "2026-09-01", "2026-09-29")

        assertTrue(q.contains("(t.status IN ('D','E','F','G') AND NOT EXISTS (SELECT 1 FROM " +
                "nexttransactionlink l JOIN transaction c ON c.id = l.nextdoc " +
                "WHERE l.previousdoc = t.id AND c.type = 'ItemShip' AND c.status = 'C') " +
                "AND EXISTS (SELECT 1 FROM transactionline tl WHERE tl.transaction = t.id " +
                "AND tl.fulfillable = 'T' AND tl.isclosed = 'F'))"), q)
    }

    /**
     * The gate belongs to the SHIPMENT question only. A gift card is still invoiced, so applying it to
     * the invoice branch would hide genuine revenue leaks behind an unshippable line.
     */
    @Test
    void theInvoiceBranchIsNotGatedOnShippability() {
        String q = NsSuiteQlOrderSupport.buildRecordsQuery(
                stateSpec([requireFulfillableOpenLine: "Y"]), "2026-08-01", "2026-09-01", "2026-09-29")

        String invoiceBranch = q.substring(q.indexOf("(t.status IN ('D','G')"))
        assertFalse(invoiceBranch.contains("fulfillable"), invoiceBranch)
    }

    /** Pending orders are judged on their date, not on whether anything is shippable yet. */
    @Test
    void thePendingBranchIsUnchangedByTheGate() {
        String q = NsSuiteQlOrderSupport.buildRecordsQuery(
                stateSpec([requireFulfillableOpenLine: "Y"]), "2026-08-01", "2026-09-01", "2026-09-29")

        int pendingStart = q.indexOf("AND ((t.status IN ('B')")
        String pendingBranch = q.substring(pendingStart, q.indexOf(" OR (t.status IN ('D','E','F','G')", pendingStart))
        assertFalse(pendingBranch.contains("fulfillable"), pendingBranch)
    }

    /**
     * An order collected in store has no ship deadline to miss. Measured on gorjana 2026-08-01: of the
     * 4 overdue findings, 3 were Standard and 1 was Store Pickup — and that one DOES carry a shipdate,
     * so the existing "shipdate IS NOT NULL" clause does not exclude it. Unlike gift cards, which
     * NetSuite's own fulfillable flag separates, nothing structural marks a method as non-shipping:
     * which methods mean "collected" is a business fact, so it is configuration.
     */
    @Test
    void theOverdueBranchCanExcludeCollectionMethods() {
        String q = NsSuiteQlOrderSupport.buildRecordsQuery(
                stateSpec([overdueExcludedShipMethods: "Store Pickup"]), "2026-08-01", "2026-09-01", "2026-09-29")

        assertTrue(q.contains("NOT EXISTS (SELECT 1 FROM shipitem sm WHERE sm.id = t.shipmethod " +
                "AND sm.itemid IN ('Store Pickup'))"), q)
    }

    /** Only the overdue branch. A collected order is still fulfilled and still invoiced. */
    @Test
    void collectionMethodsAreStillHeldToShipmentAndInvoice() {
        String q = NsSuiteQlOrderSupport.buildRecordsQuery(
                stateSpec([overdueExcludedShipMethods: "Store Pickup"]), "2026-08-01", "2026-09-01", "2026-09-29")

        String afterPending = q.substring(q.indexOf(" OR (t.status IN ('D','E','F','G')"))
        assertFalse(afterPending.contains("shipitem"), afterPending)
    }

    @Test
    void severalCollectionMethodsAreAccepted() {
        String q = NsSuiteQlOrderSupport.buildRecordsQuery(
                stateSpec([overdueExcludedShipMethods: "Store Pickup, Will Call"]), "2026-08-01", "2026-09-01", "2026-09-29")

        assertTrue(q.contains("sm.itemid IN ('Store Pickup','Will Call')"), q)
    }

    /** Unset changes nothing — no join, no subquery, byte-identical to before. */
    @Test
    void noExclusionMeansNoShipMethodPredicate() {
        String q = NsSuiteQlOrderSupport.buildRecordsQuery(stateSpec(), "2026-08-01", "2026-09-01", "2026-09-29")

        assertFalse(q.contains("shipitem"), q)
    }

    /**
     * Method NAMES carry spaces, so the identifier check the status codes use would refuse them. They
     * still reach interpolated SQL, so the looser rule is still a whitelist — letters, digits, spaces
     * and dashes — and a quote or semicolon is refused rather than escaped.
     */
    @Test
    void shipMethodNamesRefuseQuotesAndPunctuation() {
        assertThrows(IllegalArgumentException) { stateSpec([overdueExcludedShipMethods: "Store' OR '1'='1"]) }
        assertThrows(IllegalArgumentException) { stateSpec([overdueExcludedShipMethods: "Pickup; DROP"]) }
    }

    /**
     * Some statuses are not a disagreement with the chain — they are a state the business says should
     * never persist at all. "Nothing should sit in Pending Billing" is true whether or not an invoice
     * exists, so this branch tests the status ALONE. Adding it to the same template rather than
     * minting another keeps one query per question rather than one query shape per question.
     */
    @Test
    void disallowedStatesAreAFindingOnTheirOwn() {
        String q = NsSuiteQlOrderSupport.buildRecordsQuery(
                spec([queryTemplate: "STATE_CONTRADICTION", disallowedStates: "F"]),
                "2026-08-01", "2026-09-01", "2026-09-29")

        assertTrue(q.contains("(t.status IN ('F'))"), q)
        // No chain test on this branch: the status itself is the finding.
        assertFalse(q.contains("(t.status IN ('F') AND"), q)
    }

    @Test
    void disallowedStatesCarryTheirOwnBrief() {
        String q = NsSuiteQlOrderSupport.buildRecordsQuery(
                spec([queryTemplate: "STATE_CONTRADICTION", disallowedStates: "E,F"]),
                "2026-08-01", "2026-09-01", "2026-09-29")

        assertTrue(q.contains("'STATUS_NOT_ALLOWED'"), q)
        assertTrue(q.contains("'In a status it should never rest in'"), q)
    }

    /** It composes with the other branches rather than replacing them. */
    @Test
    void disallowedStatesSitAlongsideTheChainBranches() {
        String q = NsSuiteQlOrderSupport.buildRecordsQuery(
                stateSpec([disallowedStates: "F"]), "2026-08-01", "2026-09-01", "2026-09-29")

        assertTrue(q.contains("t.status IN ('B')"), q)
        assertTrue(q.contains("t.status IN ('D','E','F','G')"), q)
        assertTrue(q.contains("(t.status IN ('F'))"), q)
    }

    /** A disallowed-states-only query is valid: it needs no chain expectation to mean something. */
    @Test
    void disallowedStatesAloneSatisfyTheTemplate() {
        Map<String, Object> built = spec([queryTemplate: "STATE_CONTRADICTION", disallowedStates: "F"])

        assertEquals(["F"], built.get("disallowedStates"))
    }

    /**
     * "An invoice CREATED FROM a sales order should never be Open" reads the link the opposite way
     * from every other template here: those ask what a record produced, this asks what produced it.
     * Same table, swapped columns — nextdoc is the record under test, previousdoc is its parent.
     */
    @Test
    void parentRecordTypeReadsTheLinkBackwards() {
        String q = NsSuiteQlOrderSupport.buildRecordsQuery(
                spec([queryTemplate: "STATE_CONTRADICTION", recordType: "CustInvc",
                      disallowedStates: "A", requireParentRecordType: "SalesOrd"]),
                "2026-08-01", "2026-09-01", "2026-09-29")

        assertTrue(q.contains("p.id = l0.previousdoc"), q)
        assertTrue(q.contains("l0.nextdoc = t.id"), q)
        assertTrue(q.contains("p.type = 'SalesOrd'"), q)
        // Not the child direction, which would silently ask the opposite question and still run.
        assertFalse(q.contains("l0.previousdoc = t.id"), q)
    }

    /**
     * A scope qualifier, not a branch condition: it narrows WHICH records the check is about, so it
     * ANDs with the whole disjunction rather than joining one arm of it. Written as a branch, an
     * order-less invoice would still be reported via any other branch.
     */
    @Test
    void parentRecordTypeNarrowsEveryBranch() {
        String q = NsSuiteQlOrderSupport.buildRecordsQuery(
                stateSpec([requireParentRecordType: "SalesOrd"]), "2026-08-01", "2026-09-01", "2026-09-29")

        int parentAt = q.indexOf("l0.nextdoc = t.id")
        int branchesAt = q.indexOf("AND ((t.status IN")
        assertTrue(parentAt > 0 && branchesAt > 0, q)
        assertTrue(parentAt < branchesAt, "parent scope must precede the branch disjunction: ${q}")
    }

    /** Its alias cannot collide with the child predicates, which already use l, c, l2 and c2. */
    @Test
    void parentPredicateAliasDoesNotCollideWithChildPredicates() {
        String q = NsSuiteQlOrderSupport.buildRecordsQuery(
                stateSpec([requireParentRecordType: "SalesOrd"]), "2026-08-01", "2026-09-01", "2026-09-29")

        assertTrue(q.contains("l0.nextdoc = t.id"), q)
        assertTrue(q.contains("l.previousdoc = t.id"), q)
        assertTrue(q.contains("l2.previousdoc = t.id"), q)
    }

    /**
     * Refused rather than ignored, the same call taken for requireOverdueShipDate: silently dropping
     * a scope qualifier is how an operator comes to believe a run is narrowed when it is not.
     */
    @Test
    void parentRecordTypeIsRejectedOnTemplatesThatCannotUseIt() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, {
            spec([queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "ItemShip",
                  requireParentRecordType: "SalesOrd"])
        })

        assertTrue(e.message.contains("requireParentRecordType"), e.message)
    }

    /** It reaches interpolated SQL, so it takes the record-type format check, not escaping. */
    @Test
    void parentRecordTypeIsFormatChecked() {
        assertThrows(IllegalArgumentException.class, {
            spec([queryTemplate: "STATE_CONTRADICTION", disallowedStates: "A",
                  requireParentRecordType: "SalesOrd' OR '1'='1"])
        })
    }

    /**
     * The briefs name no record-type noun. This template ran only on sales orders until
     * NS_INVOICE_OPEN configured it against CustInvc, and the STATUS_NOT_ALLOWED brief then read
     * "Order is in a status ..." on an invoice — wrong in the one place an operator actually reads.
     */
    @Test
    void briefsNameNoRecordTypeTheConfigCanContradict() {
        String q = NsSuiteQlOrderSupport.buildRecordsQuery(
                stateSpec([disallowedStates: "F"]), "2026-08-01", "2026-09-01", "2026-09-29")

        int briefAt = q.indexOf("AS contradiction_brief")
        assertTrue(briefAt > 0, q)
        String briefs = q.substring(0, briefAt)
        assertFalse(briefs.contains("'Order"), briefs)
        assertFalse(briefs.contains("'Invoice"), briefs)
    }

    /**
     * A sub-dollar balance is rounding residue, not an unpaid invoice — the same false-positive
     * floor the exchange exclusion has. Measured on gorjana 2026-09-01: 257 of 272 open invoices
     * owed less than $1, so this gate is the difference between a 15-row report and a 272-row one.
     */
    @Test
    void minAmountGatesOnTheConfiguredColumn() {
        String q = NsSuiteQlOrderSupport.buildRecordsQuery(
                spec([queryTemplate: "STATE_CONTRADICTION", recordType: "CustInvc",
                      disallowedStates: "A", amountColumn: "foreignamountunpaid", minAmount: "1"]),
                "2026-08-01", "2026-09-01", "2026-09-29")

        assertTrue(q.contains("t.foreignamountunpaid >= 1"), q)
    }

    /**
     * NULL is excluded EXPLICITLY rather than by three-valued logic. `NULL >= 1` is unknown and so
     * already drops the row, but writing it out means a reader does not have to know that to see
     * which way the gate falls — and gorjana has no null balances today, so the behaviour would
     * otherwise be untested and silently discovered.
     */
    @Test
    void minAmountStatesItsNullHandling() {
        String q = NsSuiteQlOrderSupport.buildRecordsQuery(
                spec([queryTemplate: "STATE_CONTRADICTION", recordType: "CustInvc",
                      disallowedStates: "A", amountColumn: "foreignamountunpaid", minAmount: "1"]),
                "2026-08-01", "2026-09-01", "2026-09-29")

        assertTrue(q.contains("t.foreignamountunpaid IS NOT NULL"), q)
    }

    /** A scope qualifier, so it narrows the whole disjunction rather than one arm of it. */
    @Test
    void minAmountNarrowsEveryBranch() {
        String q = NsSuiteQlOrderSupport.buildRecordsQuery(
                stateSpec([amountColumn: "foreigntotal", minAmount: "1"]),
                "2026-08-01", "2026-09-01", "2026-09-29")

        int gateAt = q.indexOf("t.foreigntotal >= 1")
        int branchesAt = q.indexOf("AND ((t.status IN")
        assertTrue(gateAt > 0 && branchesAt > 0, q)
        assertTrue(gateAt < branchesAt, "amount gate must precede the branch disjunction: ${q}")
    }

    /** The pair is meaningless half-configured: a threshold with no column silently does nothing. */
    @Test
    void minAmountAndItsColumnAreRequiredTogether() {
        IllegalArgumentException a = assertThrows(IllegalArgumentException.class, {
            spec([queryTemplate: "STATE_CONTRADICTION", disallowedStates: "A", minAmount: "1"])
        })
        assertTrue(a.message.contains("amountColumn"), a.message)

        IllegalArgumentException b = assertThrows(IllegalArgumentException.class, {
            spec([queryTemplate: "STATE_CONTRADICTION", disallowedStates: "A",
                  amountColumn: "foreignamountunpaid"])
        })
        assertTrue(b.message.contains("minAmount"), b.message)
    }

    /** Both reach interpolated SQL. The column takes the identifier check; the threshold a numeric one. */
    @Test
    void minAmountIsFormatChecked() {
        assertThrows(IllegalArgumentException.class, {
            spec([queryTemplate: "STATE_CONTRADICTION", disallowedStates: "A",
                  amountColumn: "foreignamountunpaid", minAmount: "1 OR 1=1"])
        })
        assertThrows(IllegalArgumentException.class, {
            spec([queryTemplate: "STATE_CONTRADICTION", disallowedStates: "A",
                  amountColumn: "foreignamountunpaid; DROP", minAmount: "1"])
        })
        // A negative floor would report nothing it does not already report, so it is a config error
        // rather than a no-op to be honoured.
        assertThrows(IllegalArgumentException.class, {
            spec([queryTemplate: "STATE_CONTRADICTION", disallowedStates: "A",
                  amountColumn: "foreignamountunpaid", minAmount: "-1"])
        })
    }

    /** Decimal thresholds are the realistic case — "at least fifty cents", not just whole dollars. */
    @Test
    void minAmountAcceptsDecimals() {
        String q = NsSuiteQlOrderSupport.buildRecordsQuery(
                spec([queryTemplate: "STATE_CONTRADICTION", recordType: "CustInvc",
                      disallowedStates: "A", amountColumn: "foreignamountunpaid", minAmount: "0.50"]),
                "2026-08-01", "2026-09-01", "2026-09-29")

        assertTrue(q.contains("t.foreignamountunpaid >= 0.50"), q)
    }

    /** Status codes reach interpolated SQL, so they are format-enforced rather than escaped. */
    @Test
    void statusListsRefuseAnythingButPlainCodes() {
        assertThrows(IllegalArgumentException) { stateSpec([pendingStates: "B' OR '1'='1"]) }
        assertThrows(IllegalArgumentException) { stateSpec([statesExpectingInvoice: "G;DROP"]) }
    }

    /** Pending states need a run date for the same reason the overdue gate does. */
    @Test
    void stateTemplateNeedsARunDateWhenItHasPendingStates() {
        assertThrows(IllegalArgumentException) {
            NsSuiteQlOrderSupport.buildRecordsQuery(stateSpec(), "2026-08-01", "2026-09-01", null)
        }
    }

    /** With no expectations at all the template would select every order; refuse it. */
    @Test
    void stateTemplateRefusesAnEmptyMap() {
        def e = assertThrows(IllegalArgumentException) {
            spec([queryTemplate: "STATE_CONTRADICTION"])
        }
        assertTrue(e.message.contains("STATE_CONTRADICTION"), e.message)
    }

    // ---------------------------------------------------------------- the overdue gate

    /**
     * "No fulfillment" and "late" are different questions, and only distance made them look alike.
     * Run over Aug 1 from late September the answer was 4; run over YESTERDAY it was 1042, every one
     * status B — a full day's order book, none of it late. The measured lag is p50 0 / p95 2 / max 15
     * days, so any recent window is dominated by orders that simply have not shipped yet.
     */
    @Test
    void overdueGateComparesShipDateAgainstTheRunDate() {
        String q = queryAsOf([queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "ItemShip",
                              requireOverdueShipDate: "Y"], "2026-09-29")

        assertTrue(q.contains("t.shipdate IS NOT NULL"), q)
        assertTrue(q.contains("t.shipdate < TO_DATE('2026-09-29', 'YYYY-MM-DD')"), q)
    }

    /** An order NetSuite never promised a date for cannot be judged late. */
    @Test
    void overdueGateExcludesOrdersWithNoPromisedShipDate() {
        String q = queryAsOf([queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "ItemShip",
                              requireOverdueShipDate: "Y"], "2026-09-29")

        assertTrue(q.contains("t.shipdate IS NOT NULL AND t.shipdate <"), q)
    }

    /** Unset leaves the query byte-identical — every existing row keeps its proven shape. */
    @Test
    void overdueGateIsAbsentUnlessAskedFor() {
        String q = query([queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "ItemShip"])

        assertFalse(q.contains("shipdate"), q)
    }

    /** "N" is a non-empty String and truthy in Groovy; the flag is read as a value, not for truth. */
    @Test
    void overdueGateOffIsNotMistakenForOn() {
        String q = queryAsOf([queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "ItemShip",
                              requireOverdueShipDate: "N"], "2026-09-29")

        assertFalse(q.contains("shipdate"), q)
    }

    /**
     * The run date is interpolated into SuiteQL text like every other bound, so it is format-enforced
     * rather than escaped. Asking for the gate without a date is a programming error, not a silent
     * "no gate" — that would quietly restore the 1042.
     */
    @Test
    void overdueGateRefusesToRunWithoutARunDate() {
        def e = assertThrows(IllegalArgumentException) {
            NsSuiteQlOrderSupport.buildRecordsQuery(
                    spec([queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "ItemShip",
                          requireOverdueShipDate: "Y"]), "2026-08-01", "2026-09-01", null)
        }
        assertTrue(e.message.toLowerCase().contains("asofdate") || e.message.contains("shipdate"), e.message)
    }

    @Test
    void overdueGateRefusesAMalformedRunDate() {
        assertThrows(IllegalArgumentException) {
            queryAsOf([queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "ItemShip",
                       requireOverdueShipDate: "Y"], "29-09-2026")
        }
    }

    /** The overdue gate is refused on RECORDS, the same way the child types are. */
    @Test
    void overdueGateIsOnlyMeaningfulOnALinkedChildTemplate() {
        def e = assertThrows(IllegalArgumentException) { spec([requireOverdueShipDate: "Y"]) }
        assertTrue(e.message.contains("requireOverdueShipDate"), e.message)
    }

    // ---------------------------------------------------------------- the default must not move

    @Test
    void absentTemplateIsRecordsAndTheQueryIsUnchanged() {
        // Byte-identical to what shipped for DAR-BE-032/044. Every existing NsSuiteQlSourceQuery row
        // stores no template, so a default that altered the query would silently change the OMS
        // order-presence extract that is already proven against gorjana production.
        assertEquals("SELECT tranid FROM transaction WHERE type = 'SalesOrd' " +
                "AND trandate >= TO_DATE('2026-08-01', 'YYYY-MM-DD') " +
                "AND trandate < TO_DATE('2026-09-01', 'YYYY-MM-DD') ORDER BY id", query([:]))
        assertEquals(NsSuiteQlOrderSupport.TEMPLATE_RECORDS, spec([:]).get("queryTemplate"))
    }

    @Test
    void anUnknownTemplateThrowsRatherThanFallingBackToRecords() {
        def e = assertThrows(IllegalArgumentException) { spec([queryTemplate: "LINKED_CHILD_MISSING"]) }
        assertTrue(e.message.contains("LINKED_CHILD_MISSING"), e.message)
        assertTrue(e.message.contains(NsSuiteQlOrderSupport.TEMPLATE_RECORDS), e.message)
    }

    // ---------------------------------------------------------------- the anti-join

    @Test
    void linkedChildAbsentEmitsTheAntiJoinOverTheLinkTable() {
        String q = query([queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "ItemShip"])
        assertTrue(q.contains("NOT EXISTS (SELECT 1 FROM nexttransactionlink l " +
                "JOIN transaction c ON c.id = l.nextdoc WHERE l.previousdoc = t.id AND c.type = 'ItemShip' AND c.status = 'C')"), q)
        assertTrue(q.contains("FROM transaction t WHERE t.type = 'SalesOrd'"), q)
    }

    /**
     * DAR-UI-050 bug. A fulfillment is made at the pick, so a Picked (A) or Packed (B) one sits on a Pending Fulfillment
     * order (gorjana Oct 12-21: all 17 such orders, linktype PickPack). Only a Shipped (C) one reaches the stage.
     */
    @Test
    void aFulfillmentStageCountsOnlyAShippedFulfillment() {
        String shipped = "WHERE l.previousdoc = t.id AND c.type = 'ItemShip' AND c.status = 'C')"
        String present = query([queryTemplate: "LINKED_CHILD_PRESENT_ABSENT", presentChildRecordType: "ItemShip",
                                absentChildRecordType: "CustInvc"])
        assertTrue(present.contains(shipped), present)
        assertTrue(present.contains("WHERE l2.previousdoc = t.id AND c2.type = 'CustInvc')"), present)
        String missing = NsSuiteQlOrderSupport.buildConditionIdQuery(spec([queryTemplate: "LINKED_CHILD_ABSENT",
                absentChildRecordType: "ItemShip"]), NsSuiteQlOrderSupport.CONDITION_MISSING, "ItemShip", "2026-10-12", "2026-10-15")
        assertTrue(missing.contains("NOT " + "EXISTS (SELECT 1 FROM nexttransactionlink l JOIN transaction c ON c.id = l.nextdoc " + shipped), missing)
    }

    @Test
    void theAntiJoinIsNotWindowedOnTheChild() {
        // The child transaction is dated LATER than its parent (measured: fulfillment p99 4 days,
        // invoice p99 36). Bounding the child by the same window would report every order near the end
        // of it as unfulfilled, which is the false positive the whole feature exists to avoid.
        String q = query([queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "ItemShip"])
        int subqueryStart = q.indexOf("NOT EXISTS")
        assertFalse(q.substring(subqueryStart).contains("TO_DATE"), q.substring(subqueryStart))
    }

    @Test
    void theAntiJoinDoesNotFilterOnLinktype() {
        // Deliberate: a stage is reachable by SEVERAL linktypes — gorjana's fulfillments arrive as both
        // ShipRcpt and KitShip — so pinning one would silently drop 48 kit shipments a day. The question
        // is "is there ANY link to a child of this type".
        String q = query([queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "ItemShip"])
        assertFalse(q.contains("linktype"), q)
    }

    @Test
    void orderByStaysOnTheParentIdSoOffsetPagingIsStillSafe() {
        String q = query([queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "ItemShip"])
        assertTrue(q.endsWith("ORDER BY t.id"), q)
    }

    @Test
    void linkedChildAbsentRequiresItsChildType() {
        def e = assertThrows(IllegalArgumentException) { spec([queryTemplate: "LINKED_CHILD_ABSENT"]) }
        assertTrue(e.message.contains("absentChildRecordType"), e.message)
    }

    // ---------------------------------------------------------------- present AND absent

    @Test
    void presentAbsentEmitsBothHalves() {
        // "Shipped but never billed" — the revenue leak. Needs both, or it reports every unshipped order.
        String q = query([queryTemplate: "LINKED_CHILD_PRESENT_ABSENT",
                          presentChildRecordType: "ItemShip", absentChildRecordType: "CustInvc"])
        // "AND EXISTS": a bare "EXISTS (" also matches inside "NOT EXISTS (" (S41b).
        assertTrue(q.contains("AND EXISTS (SELECT 1 FROM nexttransactionlink l " +
                "JOIN transaction c ON c.id = l.nextdoc WHERE l.previousdoc = t.id AND c.type = 'ItemShip' AND c.status = 'C')"), q)
        assertTrue(q.contains("NOT EXISTS (SELECT 1 FROM nexttransactionlink l2 " +
                "JOIN transaction c2 ON c2.id = l2.nextdoc WHERE l2.previousdoc = t.id AND c2.type = 'CustInvc')"), q)
    }

    @Test
    void presentAbsentRequiresBothChildTypes() {
        assertTrue(assertThrows(IllegalArgumentException) {
            spec([queryTemplate: "LINKED_CHILD_PRESENT_ABSENT", absentChildRecordType: "CustInvc"])
        }.message.contains("presentChildRecordType"))
        assertTrue(assertThrows(IllegalArgumentException) {
            spec([queryTemplate: "LINKED_CHILD_PRESENT_ABSENT", presentChildRecordType: "ItemShip"])
        }.message.contains("absentChildRecordType"))
    }

    // DAR-UI-050: the lifecycle builder gives every place its own question, so "has it reached X?" must be
    // askable on its own: its rows are the records that DID reach X ("shipped, never billed" under no-invoice).
    @Test
    void presentEmitsOnlyTheExistsHalf() {
        String q = query([queryTemplate: "LINKED_CHILD_PRESENT", presentChildRecordType: "ItemShip"])
        assertTrue(q.contains("AND EXISTS (SELECT 1 FROM nexttransactionlink l " +
                "JOIN transaction c ON c.id = l.nextdoc WHERE l.previousdoc = t.id AND c.type = 'ItemShip' AND c.status = 'C')"), q)
        assertFalse(q.contains("NOT EXISTS"), q)
        assertTrue(q.endsWith("ORDER BY t.id"), q)
    }

    @Test
    void presentRequiresItsChildTypeAndRefusesAnAbsentOne() {
        assertTrue(assertThrows(IllegalArgumentException) {
            spec([queryTemplate: "LINKED_CHILD_PRESENT"])
        }.message.contains("presentChildRecordType"))
        assertTrue(assertThrows(IllegalArgumentException) {
            spec([queryTemplate: "LINKED_CHILD_PRESENT", presentChildRecordType: "ItemShip", absentChildRecordType: "CustInvc"])
        }.message.contains("absentChildRecordType"))
        assertThrows(IllegalArgumentException) {
            spec([queryTemplate: "LINKED_CHILD_PRESENT", presentChildRecordType: "ItemShip' OR '1'='1"])
        }
    }

    // DAR-UI-050: a stage exception. Store Pickup orders never ship, so for them Fulfillment is not expected: an absent
    // check does not find them, and a present check counts them as having the stage.
    @Test
    void anExceptionWaivesTheStageOnBothSidesOfTheCheck() {
        String absent = query([queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "ItemShip",
                               waiveRules: "[{\"fieldId\": \"shipmethod\", \"values\": [\"Store Pickup\"]}]"])
        assertTrue(absent.contains("AND NOT (EXISTS (SELECT 1 FROM nexttransactionlink l JOIN transaction c ON c.id = l.nextdoc " +
                "WHERE l.previousdoc = t.id AND c.type = 'ItemShip' AND c.status = 'C') OR (BUILTIN.DF(t.shipmethod) IS NOT NULL AND BUILTIN.DF(t.shipmethod) IN ('Store Pickup')))"), absent)
        String present = query([queryTemplate: "LINKED_CHILD_PRESENT", presentChildRecordType: "ItemShip",
                                waiveRules: "[{\"fieldId\": \"shipmethod\", \"values\": [\"Store Pickup\", \"In Studio\"]}]"])
        assertTrue(present.contains("AND (EXISTS (SELECT 1 FROM nexttransactionlink l JOIN transaction c ON c.id = l.nextdoc " +
                "WHERE l.previousdoc = t.id AND c.type = 'ItemShip' AND c.status = 'C') OR (BUILTIN.DF(t.shipmethod) IS NOT NULL AND BUILTIN.DF(t.shipmethod) IN ('Store Pickup', 'In Studio')))"), present)
        // A custom text field is compared as the text it holds.
        String custom = query([queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "ItemShip",
                               waiveRules: "[{\"fieldId\": \"custbody_hc_sales_channel\", \"values\": [\"POS Channel\"]}]"])
        assertTrue(custom.contains("OR (t.custbody_hc_sales_channel IS NOT NULL AND t.custbody_hc_sales_channel IN ('POS Channel')))"), custom)
    }

    // DAR-UI-050: an exception by item. A gift card (GFT-CP) never ships, so an order holding one and nothing else
    // that ships is not owed a fulfillment; one that also holds a necklace still is.
    @Test
    void anItemExceptionWaivesOnlyRecordsWithNothingElseToShip() {
        String absent = query([queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "ItemShip",
                               waiveRules: "[{\"fieldId\": \"item\", \"values\": [\"GFT-CP\"]}]"])
        assertTrue(absent.contains("WHERE l.previousdoc = t.id AND c.type = 'ItemShip' AND c.status = 'C') OR (EXISTS (SELECT 1 FROM transactionline wl " +
                "JOIN item wi ON wi.id = wl.item WHERE wl.transaction = t.id AND wi.itemid IN ('GFT-CP')) " +
                "AND NOT EXISTS (SELECT 1 FROM transactionline wo JOIN item woi ON woi.id = wo.item WHERE wo.transaction = t.id " +
                "AND wo.mainline = 'F' AND woi.itemtype IN ('InvtPart', 'Kit', 'Assembly') AND woi.itemid NOT IN ('GFT-CP'))))"), absent)
        assertTrue(absent.contains("AND NOT (EXISTS"), absent)
    }

    // DAR-UI-050: the records a stage's exceptions excuse, missing the stage, are found on their own; with no exception, none.
    @Test
    void excusedRecordsAreTheOnesMissingTheStageThatAnExceptionNames() {
        String excused = query([queryTemplate: "LINKED_CHILD_EXCUSED", absentChildRecordType: "ItemShip",
                                waiveRules: [[fieldId: "shipmethod", values: ["Store Pickup"]], [fieldId: "item", values: ["GFT-CP"]]]])
        assertTrue(excused.contains("AND NOT EXISTS (SELECT 1 FROM nexttransactionlink l JOIN transaction c ON c.id = l.nextdoc " +
                "WHERE l.previousdoc = t.id AND c.type = 'ItemShip' AND c.status = 'C') AND ((BUILTIN.DF(t.shipmethod) IS NOT NULL AND BUILTIN.DF(t.shipmethod) IN ('Store Pickup')) OR (EXISTS"), excused)
        String none = query([queryTemplate: "LINKED_CHILD_EXCUSED", absentChildRecordType: "ItemShip"])
        assertTrue(none.contains("c.type = 'ItemShip' AND c.status = 'C') AND 1 = 0"), none)
    }

    // A stage may hold exceptions on several fields: a record any of them names is waived.
    @Test
    void aStageHoldsExceptionsOnSeveralFields() {
        String absent = query([queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "ItemShip",
                               waiveRules: [[fieldId: "shipmethod", values: ["Store Pickup"]], [fieldId: "item", values: ["GFT-CP"]]]])
        assertTrue(absent.contains("c.type = 'ItemShip' AND c.status = 'C') OR (BUILTIN.DF(t.shipmethod) IS NOT NULL AND BUILTIN.DF(t.shipmethod) IN ('Store Pickup')) OR (EXISTS (SELECT 1 FROM transactionline wl"), absent)
        assertThrows(IllegalArgumentException) { spec([queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "ItemShip", waiveRules: "{\"fieldId\":\"item\"}"]) }
    }

    // DAR-UI-050: a record shows a list field by the name people use, so BUILTIN.DF (a dotted function) is a column.
    @Test
    void aListFieldIsShownByItsDisplayName() {
        Map<String, Object> spec = NsSuiteQlOrderSupport.normalizeSpec([nsSuiteQlSourceQueryId: "Q1", recordType: "SalesOrd",
                fromTable: "transaction", dateColumn: "trandate", queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "ItemShip"],
                [[recordFieldName: "status", sourceColumn: "BUILTIN.DF(status) AS status", sequenceNum: 1],
                 [recordFieldName: "hcOrderId", sourceColumn: "custbody_hc_order_id AS detail1", sequenceNum: 2]])
        String sql = NsSuiteQlOrderSupport.buildRecordsQuery(spec, "2026-08-01", "2026-09-01")
        assertTrue(sql.startsWith("SELECT BUILTIN.DF(status) AS status, custbody_hc_order_id AS detail1 FROM transaction t "), sql)
        assertThrows(IllegalArgumentException) {
            NsSuiteQlOrderSupport.normalizeSpec([nsSuiteQlSourceQueryId: "Q1", recordType: "SalesOrd", fromTable: "transaction", dateColumn: "trandate"],
                    [[recordFieldName: "x", sourceColumn: "A.B.C(status) AS s", sequenceNum: 1]])
        }
    }

    // DAR-UI-050: a record shows its items on backorder, its lines added up, whichever way the query names the record.
    @Test
    void aLineSumIsTheRecordsLinesAddedUp() {
        List<Map<String, Object>> fields = [[recordFieldName: "internalId", sourceColumn: "id", sequenceNum: 1],
                                            [recordFieldName: "itemsBackordered", sourceColumn: "LINE_SUM(quantitybackordered) AS backordered", sequenceNum: 2]]
        String linked = NsSuiteQlOrderSupport.buildRecordsQuery(NsSuiteQlOrderSupport.normalizeSpec([nsSuiteQlSourceQueryId: "Q1", recordType: "SalesOrd",
                fromTable: "transaction", dateColumn: "trandate", queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "ItemShip"], fields), "2026-08-01", "2026-09-01")
        assertTrue(linked.startsWith("SELECT id, (SELECT SUM(bl.quantitybackordered) FROM transactionline bl WHERE bl.transaction = t.id " +
                "AND bl.mainline = 'F') AS backordered FROM transaction t "), linked)
        String records = NsSuiteQlOrderSupport.buildRecordsQuery(NsSuiteQlOrderSupport.normalizeSpec([nsSuiteQlSourceQueryId: "Q1", recordType: "SalesOrd",
                fromTable: "transaction", dateColumn: "trandate"], fields), "2026-08-01", "2026-09-01")
        assertTrue(records.contains("WHERE bl.transaction = transaction.id AND bl.mainline = 'F') AS backordered FROM transaction WHERE"), records)
    }

    // DAR-UI-050 (Aditi: "a nested JSON that has the SKU and quantity"): a record lists its backordered lines.
    @Test
    void lineItemsListEachBackorderedLineBySkuAndQuantity() {
        List<Map<String, Object>> fields = [[recordFieldName: "internalId", sourceColumn: "id", sequenceNum: 1],
                                            [recordFieldName: "itemsBackordered", sourceColumn: "LINE_ITEMS(quantitybackordered) AS backordered", sequenceNum: 2]]
        Map<String, Object> linkedSpec = NsSuiteQlOrderSupport.normalizeSpec([nsSuiteQlSourceQueryId: "Q1", recordType: "SalesOrd",
                fromTable: "transaction", dateColumn: "trandate", queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "ItemShip"], fields)
        String linked = NsSuiteQlOrderSupport.buildRecordsQuery(linkedSpec, "2026-08-01", "2026-09-01")
        assertTrue(linked.startsWith("SELECT id, (SELECT LISTAGG(bi.itemid || ':' || TO_CHAR(bl.quantitybackordered), '|') " +
                "WITHIN GROUP (ORDER BY bl.id) FROM transactionline bl JOIN item bi ON bi.id = bl.item WHERE bl.transaction = t.id " +
                "AND bl.mainline = 'F' AND bl.quantitybackordered <> 0) AS backordered FROM transaction t "), linked)
        String records = NsSuiteQlOrderSupport.buildRecordsQuery(NsSuiteQlOrderSupport.normalizeSpec([nsSuiteQlSourceQueryId: "Q1", recordType: "SalesOrd",
                fromTable: "transaction", dateColumn: "trandate"], fields), "2026-08-01", "2026-09-01")
        assertTrue(records.contains("WHERE bl.transaction = transaction.id AND bl.mainline = 'F' AND bl.quantitybackordered <> 0) AS backordered"), records)

        assertEquals([[sku: "221-102-171-G", quantity: "1"], [sku: "235-109-G", quantity: "2"]],
                NsSuiteQlOrderSupport.mapRowToRecord(linkedSpec, [id: "1", backordered: "221-102-171-G:1|235-109-G:2"]).itemsBackordered)
        assertEquals([], NsSuiteQlOrderSupport.mapRowToRecord(linkedSpec, [id: "2", backordered: null]).itemsBackordered)
        assertEquals([[sku: "KIT:A", quantity: "3"]], NsSuiteQlOrderSupport.lineItems("KIT:A:3"))
    }

    // DAR-UI-050 (Aditi: Pending Billing at a total of 0, "check if the customer deposit exists... T/F").
    @Test
    void depositWhenAsksForADepositOnlyOfTheNamedStatusAndTotal() {
        List<Map<String, Object>> fields = [[recordFieldName: "internalId", sourceColumn: "id", sequenceNum: 1],
                                            [recordFieldName: "customerDeposit", sourceColumn: "DEPOSIT_WHEN(SalesOrd, F, 0) AS deposit", sequenceNum: 2]]
        String linked = NsSuiteQlOrderSupport.buildRecordsQuery(NsSuiteQlOrderSupport.normalizeSpec([nsSuiteQlSourceQueryId: "Q1", recordType: "SalesOrd",
                fromTable: "transaction", dateColumn: "trandate", queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "CustInvc"], fields), "2026-08-01", "2026-09-01")
        assertTrue(linked.startsWith("SELECT id, CASE WHEN t.type = 'SalesOrd' AND t.status = 'F' AND t.foreigntotal = 0 THEN CASE WHEN EXISTS " +
                "(SELECT 1 FROM nexttransactionlink dk JOIN transaction dd ON dd.id = dk.nextdoc WHERE dk.previousdoc = t.id " +
                "AND dd.type = 'CustDep') THEN 'T' ELSE 'F' END END AS deposit FROM transaction t "), linked)
        String records = NsSuiteQlOrderSupport.buildRecordsQuery(NsSuiteQlOrderSupport.normalizeSpec([nsSuiteQlSourceQueryId: "Q1", recordType: "SalesOrd",
                fromTable: "transaction", dateColumn: "trandate"], fields), "2026-08-01", "2026-09-01")
        assertTrue(records.contains("CASE WHEN transaction.type = 'SalesOrd' AND transaction.status = 'F'"), records)
        assertTrue(records.contains("WHERE dk.previousdoc = transaction.id AND dd.type = 'CustDep')"), records)
    }

    // DAR-UI-080 (Aditi: "customer deposit info from NS", its number, amount and status): a sales order lists its deposits.
    @Test
    void linkedRecordsListEachDepositByNumberAmountAndStatus() {
        List<Map<String, Object>> fields = [[recordFieldName: "internalId", sourceColumn: "id", sequenceNum: 1],
                                            [recordFieldName: "deposits", sourceColumn: "LINKED_RECORDS(CustDep) AS deposits", sequenceNum: 2]]
        Map<String, Object> spec = NsSuiteQlOrderSupport.normalizeSpec([nsSuiteQlSourceQueryId: "Q1", recordType: "SalesOrd",
                fromTable: "transaction", dateColumn: "trandate"], fields)
        String records = NsSuiteQlOrderSupport.buildRecordsQuery(spec, "2026-08-01", "2026-09-01")
        assertTrue(records.startsWith("SELECT id, (SELECT LISTAGG(lr_deposits.tranid || '^' || TO_CHAR(lr_deposits.foreigntotal) || '^' || " +
                "BUILTIN.DF(lr_deposits.status), '|') WITHIN GROUP (ORDER BY lr_deposits.id) FROM nexttransactionlink lk_deposits " +
                "JOIN transaction lr_deposits ON lr_deposits.id = lk_deposits.nextdoc WHERE lk_deposits.previousdoc = transaction.id " +
                "AND lr_deposits.type = 'CustDep') AS deposits FROM transaction "), records)

        assertEquals([[tranId: "CD10422", amount: "118", status: "Customer Deposit : Deposited"], [tranId: "CD10500", amount: "20.5", status: "Customer Deposit : Fully Applied"]],
                NsSuiteQlOrderSupport.mapRowToRecord(spec, [id: "1", deposits: "CD10422^118^Customer Deposit : Deposited|CD10500^20.5^Customer Deposit : Fully Applied"]).deposits)
        assertEquals([[tranId: "CD6559352", amount: "0.83", status: "Customer Deposit : Not Deposited"]],
                NsSuiteQlOrderSupport.linkedRecords("CD6559352^.83^Customer Deposit : Not Deposited"))
        assertEquals([], NsSuiteQlOrderSupport.mapRowToRecord(spec, [id: "2", deposits: null]).deposits)

        // R1 reads sales order LINES: each line carries its order's deposits, read in the same query.
        String lines = NsSuiteQlOrderSupport.buildRecordsQuery(NsSuiteQlOrderSupport.normalizeSpec([nsSuiteQlSourceQueryId: "Q2", recordType: "SalesOrd",
                fromTable: "transaction", dateColumn: "trandate", queryTemplate: "SALES_LINES", lineKeyColumn: "custcol_hc_order_line_id",
                joinKeyFieldName: "orderId"], [[recordFieldName: "orderId", sourceColumn: "custbody_hc_order_id", sequenceNum: 1],
                                               [recordFieldName: "deposits", sourceColumn: "LINKED_RECORDS(CustDep) AS deposits", sequenceNum: 2]]),
                "2026-08-01", "2026-09-01")
        assertTrue(lines.startsWith("SELECT t.custbody_hc_order_id AS custbody_hc_order_id, (SELECT LISTAGG(lr_deposits.tranid"), lines)
        assertTrue(lines.contains("WHERE lk_deposits.previousdoc = t.id AND lr_deposits.type = 'CustDep') AS deposits, tl.custcol_hc_order_line_id"), lines)
    }

    // DAR-UI-081 (Aditi: "add the IF id"): a sales order line names the item fulfillments that shipped it, by the line link.
    @Test
    void lineLinkedListsTheFulfillmentsOfEachLine() {
        Map<String, Object> spec = NsSuiteQlOrderSupport.normalizeSpec([nsSuiteQlSourceQueryId: "Q2", recordType: "SalesOrd",
                fromTable: "transaction", dateColumn: "trandate", queryTemplate: "SALES_LINES", lineKeyColumn: "custcol_hc_order_line_id",
                joinKeyFieldName: "orderId"], [[recordFieldName: "orderId", sourceColumn: "custbody_hc_order_id", sequenceNum: 1],
                                               [recordFieldName: "fulfillments", sourceColumn: "LINE_LINKED(ItemShip) AS fulfillments", sequenceNum: 2]])
        String lines = NsSuiteQlOrderSupport.buildRecordsQuery(spec, "2026-08-01", "2026-09-01")
        assertTrue(lines.contains("(SELECT LISTAGG(lr_fulfillments.tranid, '|') WITHIN GROUP (ORDER BY lr_fulfillments.id) " +
                "FROM nexttransactionlinelink ll_fulfillments JOIN transaction lr_fulfillments ON lr_fulfillments.id = ll_fulfillments.nextdoc " +
                "WHERE ll_fulfillments.previousdoc = t.id AND ll_fulfillments.previousline = tl.id AND lr_fulfillments.type = 'ItemShip') AS fulfillments"), lines)
        // One fulfillment reached under two link types is listed once.
        assertEquals(["IF1", "IF2"], NsSuiteQlOrderSupport.mapRowToRecord(spec, [custbody_hc_order_id: "M1", fulfillments: "IF1|IF1|IF2"]).fulfillments)
        assertEquals([], NsSuiteQlOrderSupport.mapRowToRecord(spec, [custbody_hc_order_id: "M1", fulfillments: null]).fulfillments)
        assertThrows(IllegalArgumentException) {
            NsSuiteQlOrderSupport.normalizeSpec([nsSuiteQlSourceQueryId: "Q3", recordType: "SalesOrd", fromTable: "transaction", dateColumn: "trandate"],
                    [[recordFieldName: "fulfillments", sourceColumn: "LINE_LINKED(ItemShip) AS fulfillments", sequenceNum: 1]])
        }
    }

    // DAR-BE-344 (Malbon R4, Aditi: "backordered item in NS.. we should combine them"): every line of a sales order carries
    // the order's backorder, so a line held by a backordered sibling concludes with it. The sub-select is over the order's
    // lines (t.id), not this line, and an ordinary header column is still read straight off the order.
    @Test
    void salesLinesCarryTheOrdersLineSumsAndItems() {
        Map<String, Object> spec = NsSuiteQlOrderSupport.normalizeSpec([nsSuiteQlSourceQueryId: "Q2", recordType: "SalesOrd",
                fromTable: "transaction", dateColumn: "trandate", queryTemplate: "SALES_LINES", lineKeyColumn: "custcol_hc_order_line_id",
                joinKeyFieldName: "orderId"], [[recordFieldName: "orderId", sourceColumn: "custbody_hc_order_id", sequenceNum: 1],
                                               [recordFieldName: "orderBackordered", sourceColumn: "LINE_SUM(quantitybackordered) AS order_backordered", sequenceNum: 2],
                                               [recordFieldName: "backorderedItems", sourceColumn: "LINE_ITEMS(quantitybackordered) AS backordered_items", sequenceNum: 3]])
        String lines = NsSuiteQlOrderSupport.buildRecordsQuery(spec, "2026-11-01", "2026-12-01")
        assertTrue(lines.startsWith("SELECT t.custbody_hc_order_id AS custbody_hc_order_id, " +
                "(SELECT SUM(bl.quantitybackordered) FROM transactionline bl WHERE bl.transaction = t.id AND bl.mainline = 'F') AS order_backordered, " +
                "(SELECT LISTAGG(bi.itemid || ':' || TO_CHAR(bl.quantitybackordered), '|') WITHIN GROUP (ORDER BY bl.id) " +
                "FROM transactionline bl JOIN item bi ON bi.id = bl.item WHERE bl.transaction = t.id AND bl.mainline = 'F' " +
                "AND bl.quantitybackordered <> 0) AS backordered_items, tl.custcol_hc_order_line_id"), lines)
        Map<String, Object> record = NsSuiteQlOrderSupport.mapRowToRecord(spec,
                [custbody_hc_order_id: "123631", order_backordered: "1", backordered_items: "MW-0359-IVY-S:1"])
        assertEquals("1", record.orderBackordered)
        assertEquals([[sku: "MW-0359-IVY-S", quantity: "1"]], record.backorderedItems)
    }

    // DAR-BE-094 R7: a Happiness Guarantee credit memo posts to account 42065.
    @Test
    void accountUsedAsksWhetherAnyLineOfTheRecordPostsToTheAccount() {
        List<Map<String, Object>> fields = [[recordFieldName: "tranId", sourceColumn: "tranid", sequenceNum: 1],
                                            [recordFieldName: "onHgAccount", sourceColumn: "ACCOUNT_USED(42065) AS on_hg_account", sequenceNum: 2]]
        String records = NsSuiteQlOrderSupport.buildRecordsQuery(NsSuiteQlOrderSupport.normalizeSpec([nsSuiteQlSourceQueryId: "Q1", recordType: "CustCred",
                fromTable: "transaction", dateColumn: "trandate"], fields), "2026-10-01", "2026-10-23")
        assertTrue(records.startsWith("SELECT tranid, CASE WHEN EXISTS (SELECT 1 FROM transactionaccountingline au JOIN account aa ON aa.id = au.account " +
                "WHERE au.transaction = transaction.id AND aa.acctnumber = '42065') THEN 'T' ELSE 'F' END AS on_hg_account FROM transaction "), records)
        assertThrows(IllegalArgumentException) {
            NsSuiteQlOrderSupport.normalizeSpec([nsSuiteQlSourceQueryId: "Q1", recordType: "CustCred", fromTable: "transaction", dateColumn: "trandate"],
                    [[recordFieldName: "x", sourceColumn: "ACCOUNT_USED(42065 OR 1=1) AS x", sequenceNum: 1]])
        }
    }

    // DAR-BE-149 R21: a Happiness Guarantee memo left open by its POS Tax Variance line (item 34838). The line, and the
    // line less the memo's open amount, which is what the line should read once corrected (CM7661593: 49.95 - 49.92).
    @Test
    void itemAmountAddsUpOneItemsLinesAndCanTakeAColumnOff() {
        List<Map<String, Object>> fields = [[recordFieldName: "tranId", sourceColumn: "tranid", sequenceNum: 1],
                                            [recordFieldName: "posTaxVariance", sourceColumn: "ITEM_AMOUNT(34838) AS pos_tax_variance", sequenceNum: 2],
                                            [recordFieldName: "lineAfterFix", sourceColumn: "ITEM_AMOUNT(34838 - foreignpaymentamountunused) AS line_after_fix", sequenceNum: 3]]
        String sql = NsSuiteQlOrderSupport.buildRecordsQuery(NsSuiteQlOrderSupport.normalizeSpec([nsSuiteQlSourceQueryId: "Q1", recordType: "CustCred",
                fromTable: "transaction", dateColumn: "trandate", queryTemplate: "RECORDS_MATCHING",
                matchRules: [[fieldId: "custbody_hc_return_channel", op: "IN", values: ["HAPPINESS_GUARANTEE"]]]], fields), "2026-10-01", "2026-10-28")
        String line = "(SELECT SUM(ia.foreignamount) FROM transactionline ia WHERE ia.transaction = t.id AND ia.item = 34838 AND ia.mainline = 'F')"
        assertTrue(sql.startsWith("SELECT tranid, ${line} AS pos_tax_variance, ${line} - t.foreignpaymentamountunused AS line_after_fix FROM transaction t"), sql)
        assertTrue(sql.contains("t.custbody_hc_return_channel IN ('HAPPINESS_GUARANTEE')"), sql)
        ["ITEM_AMOUNT(34838 OR 1=1) AS x", "ITEM_AMOUNT(34838 - 1) AS x"].each { String bad ->
            String expanded = NsSuiteQlOrderSupport.selectColumns([fields: [[sourceColumn: bad]]])[0]
            assertFalse(expanded.contains("transactionline"), "${bad} is not the macro: ${expanded}")
        }
    }

    // DAR-BE-111: an order never shipped (no Shipped fulfillment) and never billed (no invoice or cash sale), as two flags
    // on EVERY order, so a fixed order is still read and its audit finding closes.
    @Test
    void linkedFlagsWhetherARecordOfTheTypesFollowsAndAFulfillmentOnlyOnceShipped() {
        List<Map<String, Object>> fields = [[recordFieldName: "tranId", sourceColumn: "tranid", sequenceNum: 1],
                                            [recordFieldName: "shipped", sourceColumn: "LINKED(ItemShip) AS shipped", sequenceNum: 2],
                                            [recordFieldName: "billed", sourceColumn: "LINKED(CustInvc, CashSale) AS billed", sequenceNum: 3]]
        String records = NsSuiteQlOrderSupport.buildRecordsQuery(NsSuiteQlOrderSupport.normalizeSpec([nsSuiteQlSourceQueryId: "Q1", recordType: "SalesOrd",
                fromTable: "transaction", dateColumn: "trandate"], fields), "2026-10-01", "2026-10-20")
        assertTrue(records.startsWith("SELECT tranid, CASE WHEN EXISTS (SELECT 1 FROM nexttransactionlink lk_shipped JOIN transaction lc_shipped " +
                "ON lc_shipped.id = lk_shipped.nextdoc WHERE lk_shipped.previousdoc = transaction.id AND ((lc_shipped.type = 'ItemShip' " +
                "AND lc_shipped.status = 'C'))) THEN 'T' ELSE 'F' END AS shipped, CASE WHEN EXISTS (SELECT 1 FROM nexttransactionlink " +
                "lk_billed JOIN transaction lc_billed ON lc_billed.id = lk_billed.nextdoc WHERE lk_billed.previousdoc = transaction.id AND " +
                "((lc_billed.type = 'CustInvc') OR (lc_billed.type = 'CashSale'))) THEN 'T' ELSE 'F' END AS billed FROM transaction "), records)
        assertThrows(IllegalArgumentException) {
            NsSuiteQlOrderSupport.normalizeSpec([nsSuiteQlSourceQueryId: "Q1", recordType: "SalesOrd", fromTable: "transaction", dateColumn: "trandate"],
                    [[recordFieldName: "x", sourceColumn: "LINKED(ItemShip OR 1=1) AS x", sequenceNum: 1]])
        }
    }

    // DAR-BE-095 R12: a warranty replacement's invoice is the one whose reference starts WTY-; the filter belongs in SuiteQL
    // (reading every invoice to drop 95% of them ran a 512 MB JVM out of heap in the conclusion step).
    @Test
    void startsWithMatchesAPrefixAndRefusesLikeWildcards() {
        String sql = query([queryTemplate: "RECORDS_MATCHING", matchRules: [[fieldId: "otherrefnum", op: "STARTS_WITH", values: ["WTY-"]]]])
        assertTrue(sql.contains("AND ((t.otherrefnum LIKE 'WTY-%')) ORDER BY t.id"), sql)
        assertThrows(IllegalArgumentException) { spec([queryTemplate: "RECORDS_MATCHING",
                matchRules: [[fieldId: "otherrefnum", op: "STARTS_WITH", values: ["W%"]]]]) }
        assertThrows(IllegalArgumentException) { spec([queryTemplate: "RECORDS_MATCHING",
                matchRules: [[fieldId: "otherrefnum", op: "STARTS_WITH", values: ["W_Y"]]]]) }
    }

    /**
     * DAR-BE-118 / DAR-BE-119: a read by key asks for the records it names, by one of the query's own fields and with no
     * date window (an April order behind an October return is still its order), keeping the row's own conditions. The key
     * is the field's own column: a conclusion lookup asks by internal id, a re-check of an older finding by its join key.
     */
    @Test
    void aReadByKeyAsksForTheNamedRecordsOnlyAndKeepsTheRowsConditions() {
        Map<String, Object> s = NsSuiteQlOrderSupport.normalizeSpec([nsSuiteQlSourceQueryId: "Q1", recordType: "SalesOrd",
                fromTable: "transaction", dateColumn: "trandate", queryTemplate: "RECORDS_MATCHING",
                matchRules: [[fieldId: "itemsbackordered", op: "GT", values: ["0"]]]],
                [[recordFieldName: "internalId", sourceColumn: "id", sequenceNum: 1],
                 [recordFieldName: "returnId", sourceColumn: "REPLACE(custbody_hc_return_id, 'HG-', '') AS return_id", sequenceNum: 2]])
        s.lookupKeyField = "internalId"
        s.lookupKeys = NsSuiteQlOrderSupport.lookupKeyList(["80970426", "76168721"], s)
        String sql = NsSuiteQlOrderSupport.buildRecordsQuery(s, null, null)
        assertTrue(sql.contains("WHERE t.type = 'SalesOrd' AND t.id IN ('80970426', '76168721') AND ((SELECT SUM(mb.quantitybackordered)"), sql)
        assertFalse(sql.contains("TO_DATE"), "no window: the keys bound the read")
        assertThrows(IllegalArgumentException) { NsSuiteQlOrderSupport.lookupKeyList(["80970426", "1' OR '1'='1"], s) }
        assertThrows(IllegalArgumentException) { NsSuiteQlOrderSupport.lookupKeyList((1..1001).collect { "${it}" }, s) }
        assertThrows(IllegalArgumentException, { NsSuiteQlOrderSupport.lookupKeyList(["1"], s + [lookupKeyField: "nope"]) },
                "only a field the query reads")
        // An older R8 finding: its RMA, by the return id the query derives (an expression keeps its own columns).
        Map<String, Object> byReturn = s + [lookupKeyField: "returnId"]
        byReturn.lookupKeys = NsSuiteQlOrderSupport.lookupKeyList(["M259415"], byReturn)
        assertTrue(NsSuiteQlOrderSupport.buildRecordsQuery(byReturn, null, null)
                .contains("AND REPLACE(custbody_hc_return_id, 'HG-', '') IN ('M259415') AND"))
    }

    /**
     * DAR-BE-156: an audit re-check reads its older findings' records by key AND within the check's own window, so it reads
     * what the full read would, restricted to the keys. With the keys alone, gorjana's SALES_LINES matched custbody_hc_order_id
     * across all of NetSuite's history: 99-121 s a page, and its linked-record columns timed out (3 x 180 s), every morning.
     */
    @Test
    void aReadByKeyWithinTheWindowKeepsTheDateBoundsAsWellAsTheKeys() {
        Map<String, Object> s = NsSuiteQlOrderSupport.normalizeSpec([nsSuiteQlSourceQueryId: "NS_SO_SHIPPED_LINES", recordType: "SalesOrd",
                fromTable: "transaction", dateColumn: "trandate", queryTemplate: "SALES_LINES", lineKeyColumn: "custcol_hc_order_line_id",
                joinKeyFieldName: "orderId"],
                [[recordFieldName: "orderId", sourceColumn: "custbody_hc_order_id", sequenceNum: 1],
                 [recordFieldName: "internalId", sourceColumn: "id", sequenceNum: 2]])
        s.lookupKeyField = "orderId"
        s.lookupKeys = NsSuiteQlOrderSupport.lookupKeyList(["M1079504", "M1079893"], s)
        s.lookupWithinWindow = true
        String sql = NsSuiteQlOrderSupport.buildRecordsQuery(s, "2026-09-28", "2026-10-30")
        assertTrue(sql.contains("AND t.custbody_hc_order_id IN ('M1079504', 'M1079893') AND t.trandate >= TO_DATE('2026-09-28', 'YYYY-MM-DD') " +
                "AND t.trandate < TO_DATE('2026-10-30', 'YYYY-MM-DD') "), sql)
        s.lookupWithinWindow = false
        assertFalse(NsSuiteQlOrderSupport.buildRecordsQuery(s, null, null).contains("TO_DATE"), "a conclusion lookup keeps no window")
    }

    // DAR-BE-095: "has a value" without reading it as a number. GT 0 on a free-text custom field makes NetSuite convert it,
    // and any scanned row that is not a number fails the whole search (400 "Invalid or unsupported search").
    @Test
    void notEmptyMatchesARecordWithAnyValueAndTakesNoValues() {
        String sql = query([queryTemplate: "RECORDS_MATCHING", matchRules: [[fieldId: "custbody_hc_exchange_credit_memo_id", op: "NOT_EMPTY"]]])
        assertTrue(sql.contains("AND (t.custbody_hc_exchange_credit_memo_id IS NOT NULL) ORDER BY t.id"), sql)
        assertThrows(IllegalArgumentException) { spec([queryTemplate: "RECORDS_MATCHING",
                matchRules: [[fieldId: "custbody_hc_exchange_credit_memo_id", op: "NOT_EMPTY", values: ["x"]]]]) }
    }

    // DAR-UI-050: a finding a person writes: records whose fields match, every condition holding.
    @Test
    void aWrittenFindingIsTheRecordsWhoseFieldsMatch() {
        String sql = query([queryTemplate: "RECORDS_MATCHING", matchRules: [[fieldId: "itemsbackordered", op: "GT", values: ["0"]],
                [fieldId: "status", op: "IN", values: ["Sales Order : Partially Fulfilled"]], [fieldId: "custbody_hc_order_id", values: ["NO_VALUE"]]]])
        assertTrue(sql.contains("FROM transaction t WHERE t.type = 'SalesOrd' "), sql)
        assertTrue(sql.contains("AND ((SELECT SUM(mb.quantitybackordered) FROM transactionline mb WHERE mb.transaction = t.id AND mb.mainline = 'F') > 0 " +
                "AND BUILTIN.DF(t.status) IN ('Sales Order : Partially Fulfilled') AND t.custbody_hc_order_id IS NULL) ORDER BY t.id"), sql)
        assertFalse(sql.contains("nexttransactionlink"), sql)
        // Nothing that could break the query, and no finding without a condition.
        assertThrows(IllegalArgumentException) { spec([queryTemplate: "RECORDS_MATCHING"]) }
        assertThrows(IllegalArgumentException) { spec([queryTemplate: "RECORDS_MATCHING", matchRules: [[fieldId: "id; DROP", values: ["1"]]]]) }
        assertThrows(IllegalArgumentException) { spec([queryTemplate: "RECORDS_MATCHING", matchRules: [[fieldId: "total", op: "GT", values: ["1 OR 1=1"]]]]) }
        assertThrows(IllegalArgumentException) { spec([queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "ItemShip", matchRules: [[fieldId: "total", op: "GT", values: ["1"]]]]) }
    }

    @Test
    void anExceptionThatCouldBreakTheQueryIsRefused() {
        assertThrows(IllegalArgumentException) {
            spec([queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "ItemShip", waiveRules: "[{\"fieldId\": \"shipmethod\", \"values\": [\"Store' OR '1'='1\"]}]"])
        }
        assertThrows(IllegalArgumentException) {
            spec([queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "ItemShip", waiveRules: "[{\"fieldId\": \"ship method\", \"values\": [\"Store Pickup\"]}]"])
        }
        assertThrows(IllegalArgumentException) {
            spec([queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "ItemShip", waiveRules: "[{\"fieldId\": \"shipmethod\", \"values\": []}]"])
        }
        // Only a stage check has a stage to waive.
        assertThrows(IllegalArgumentException) { spec([waiveRules: "[{\"fieldId\": \"shipmethod\", \"values\": [\"Store Pickup\"]}]"]) }
    }

    @Test
    void childTypesAreRejectedOnTemplatesThatCannotUseThem() {
        // Silently ignoring them is how an operator ends up believing a filter is applied that is not.
        assertTrue(assertThrows(IllegalArgumentException) {
            spec([absentChildRecordType: "ItemShip"])
        }.message.contains(NsSuiteQlOrderSupport.TEMPLATE_RECORDS))
        assertTrue(assertThrows(IllegalArgumentException) {
            spec([queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "ItemShip",
                  presentChildRecordType: "CustInvc"])
        }.message.contains("presentChildRecordType"))
    }

    // ---------------------------------------------------------------- the triage gate

    @Test
    void requireFulfillableOpenLineAddsTheLineLevelGate() {
        // THE DIFFERENCE BETWEEN 97 FINDINGS AND 5. Without it the report is dominated by orders that
        // can never have a fulfillment (service/non-inventory) and by deliberately closed lines.
        String q = query([queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "ItemShip",
                          requireFulfillableOpenLine: "Y"])
        assertTrue(q.contains("EXISTS (SELECT 1 FROM transactionline tl WHERE tl.transaction = t.id " +
                "AND tl.fulfillable = 'T' AND tl.isclosed = 'F')"), q)
    }

    @Test
    void theTriageGateIsOffUnlessAskedFor() {
        assertFalse(query([queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "ItemShip"])
                .contains("transactionline"))
        assertFalse(query([:]).contains("transactionline"))
    }

    // ---------------------------------------------------------------- the fences that must survive

    @Test
    void childTypesMustBePlainIdentifiers() {
        // Anything that could LEAVE the quotes it is interpolated into.
        ["ItemShip' OR '1'='1", "ItemShip; DROP TABLE x", "ItemShip--", "Item Ship", "ItemShip'",
         "/*x*/ItemShip"].each { String hostile ->
            assertThrows(IllegalArgumentException, {
                spec([queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: hostile])
            }, "must reject: ${hostile}")
        }
    }

    @Test
    void aKeywordShapedIdentifierIsAccepted_becauseItCannotLeaveTheQuotes() {
        // Documented rather than blacklisted. Once the value is required to be a bare identifier it can
        // contain no quote, space or semicolon, so `c.type = 'SELECT'` is an inert literal that matches
        // no record type. A keyword check here would imply the keyword is what makes a value dangerous,
        // which is exactly the misreading that let `ItemShip' OR '1'='1` through the old fragment check.
        String q = query([queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "SELECT"])
        assertTrue(q.contains("c.type = 'SELECT'"), q)
        assertFalse(q.contains("' OR "), q)
    }

    @Test
    void recordTypeItselfRejectsTheSameInjection() {
        // PRE-EXISTING HOLE, found by the child-type test above and fixed in the same change.
        // requireSafeFragment allows BALANCED quotes and does not forbid OR, so this passed every check
        // and widened `type = '<recordType>'` to every transaction type in the account.
        assertThrows(IllegalArgumentException, { spec([recordType: "SalesOrd' OR '1'='1"]) })
        assertThrows(IllegalArgumentException, { spec([recordType: "SalesOrd' OR type = 'ItemShip"]) })
        // and still accepts every real NetSuite type code
        ["SalesOrd", "ItemShip", "CustInvc", "RtnAuth", "CustCred", "CustRfnd", "CashSale", "ItemRcpt"]
                .each { String t -> assertTrue(query([recordType: t]).contains("type = '${t}'")) }
    }

    @Test
    void linkTemplatesRequireTheTransactionTable() {
        // nexttransactionlink relates TRANSACTIONS; pointing a link template at another table would
        // assemble a query that is syntactically fine and semantically meaningless.
        def e = assertThrows(IllegalArgumentException) {
            spec([queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "ItemShip", fromTable: "item"])
        }
        assertTrue(e.message.contains("transaction"), e.message)
    }

    @Test
    void theWindowIsStillHalfOpenAndStillFormatChecked() {
        String q = query([queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "ItemShip"])
        assertTrue(q.contains("t.trandate >= TO_DATE('2026-08-01', 'YYYY-MM-DD')"), q)
        assertTrue(q.contains("t.trandate < TO_DATE('2026-09-01', 'YYYY-MM-DD')"), q)
        assertThrows(IllegalArgumentException) {
            NsSuiteQlOrderSupport.buildRecordsQuery(
                    spec([queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "ItemShip"]),
                    "2026-8-1", "2026-09-01")
        }
    }

    // ------------------------------------------------------------------ SALES_LINES (DAR-BE tri-system D6)

    /**
     * The gorjana NetSuite leg of the tri-system order runs, at LINE grain: the header join key is the
     * OMS orderId (custbody_hc_order_id) and the line key is the OMS orderItemSeqId
     * (custcol_hc_order_line_id, "01", "02", ...). Configured fields are header columns only; the
     * template contributes the line columns itself.
     */
    private static Map<String, Object> salesLinesSpec(Map<String, Object> overrides = [:],
                                                      List<Map<String, Object>> fields = null) {
        Map<String, Object> q = [nsSuiteQlSourceQueryId: "NS_SALES_LINES", recordType: "SalesOrd",
                                 fromTable: "transaction", dateColumn: "trandate",
                                 queryTemplate: "SALES_LINES", lineKeyColumn: "custcol_hc_order_line_id",
                                 joinKeyFieldName: "orderId", originFieldName: "orderOrigin"] + overrides
        return NsSuiteQlOrderSupport.normalizeSpec(q, fields ?:
                [[recordFieldName: "orderId", sourceColumn: "custbody_hc_order_id", sequenceNum: 1],
                 [recordFieldName: "netsuiteOrderId", sourceColumn: "tranid", sequenceNum: 2]])
    }

    @Test
    void salesLinesEmitsTheLineGrainQueryExactly() {
        String q = NsSuiteQlOrderSupport.buildRecordsQuery(salesLinesSpec(), "2026-09-15", "2026-09-18")
        assertEquals("SELECT t.custbody_hc_order_id AS custbody_hc_order_id, t.tranid AS tranid, " +
                "tl.custcol_hc_order_line_id AS order_line_id, tl.quantityshiprecv AS quantity_shipped, " +
                "tl.quantitybackordered AS quantity_backordered, tl.isclosed AS line_closed, tl.id AS line_id, " +
                "tl.itemtype AS item_type, tl.quantitybilled AS quantity_billed, " +
                "CASE WHEN tl.quantitybilled <> 0 THEN 'BILLED' WHEN tl.isclosed = 'T' THEN 'CLOSED' " +
                "WHEN tl.quantityshiprecv <> 0 THEN 'SHIPPED' ELSE 'OPEN' END AS line_state, " +
                "tl.location AS line_location, BUILTIN.DF(tl.item) AS item_name, (-1 * tl.netamount) AS line_amount " +
                "FROM transaction t JOIN transactionline tl ON tl.transaction = t.id " +
                "WHERE t.type = 'SalesOrd' " +
                "AND t.trandate >= TO_DATE('2026-09-15', 'YYYY-MM-DD') " +
                "AND t.trandate < TO_DATE('2026-09-18', 'YYYY-MM-DD') " +
                "AND tl.mainline = 'F' " +
                "AND (tl.custcol_hc_order_line_id IS NOT NULL OR tl.itemtype = 'Payment') AND t.custbody_hc_order_id IS NOT NULL " +
                "ORDER BY t.id, tl.id", q)
    }

    @Test
    void salesLinesMapsTheTemplateLineColumnsAndStampsOrigin() {
        Map<String, Object> record = NsSuiteQlOrderSupport.mapRowToRecord(salesLinesSpec(),
                [custbody_hc_order_id: "M100", tranid: "SO1", order_line_id: "01", quantity_shipped: "1",
                 quantity_backordered: "0", line_closed: "F", line_id: "7", item_type: "InvtPart",
                 quantity_billed: "1", line_state: "BILLED", line_location: "12", links: []])
        assertEquals([orderId: "M100", netsuiteOrderId: "SO1", orderLineId: "01", quantityShipped: "1",
                      quantityBackordered: "0", lineClosed: "F", lineId: "7", itemType: "InvtPart",
                      quantityBilled: "1", lineState: "BILLED", lineLocation: "12", orderOrigin: "HOTWAX"], record)
    }

    /** DAR-BE-318 (plan 3b-2b): every line is written (Keep runs in Spark), paged, and keepFields still projects. */
    @Test
    void salesLinesPagesEveryLineAndKeepFieldsStillProjects() {
        StringWriter out = new StringWriter()
        List<Integer> offsets = []
        // DAR-OPS-029 S41a (OC109): paged by (order, line) key, so the fake answers the rows after the last key, offset 0
        List<Map> rows = [[t: 1, l: 11, custbody_hc_order_id: "M1", order_line_id: "01", quantity_shipped: "1"],
                          [t: 1, l: 12, custbody_hc_order_id: "M1", order_line_id: "02", quantity_shipped: "0"],
                          [t: 2, l: 21, custbody_hc_order_id: "M2", order_line_id: "01", quantity_shipped: "2"]]
        Map result = NsSuiteQlOrderSupport.streamOrdersToWriter(out,
                [spec: salesLinesSpec(), fromDate: "2026-09-15", toDate: "2026-09-18", pageSize: 2,
                 keepFields: ["orderId", "orderLineId", "quantityShipped"]],
                { String q, int limit, int offset ->
                    offsets << offset
                    SuiteQlKeysetFake.page(rows, q, limit, { Map r -> [r.t as Long, r.l as Long] })
                })
        assertEquals([0, 0], offsets)
        assertEquals(3, result.recordCount)
        Map doc = (Map) new groovy.json.JsonSlurper().parseText(out.toString())
        assertEquals([[orderId: "M1", orderLineId: "01", quantityShipped: "1"],
                      [orderId: "M1", orderLineId: "02", quantityShipped: "0"],
                      [orderId: "M2", orderLineId: "01", quantityShipped: "2"]], doc.records)
    }

    @Test
    void salesLinesRequiresALineKeyAndAHeaderJoinKey() {
        def noLine = assertThrows(IllegalArgumentException) { salesLinesSpec([lineKeyColumn: null]) }
        assertTrue(noLine.message.contains("lineKeyColumn"), noLine.message)
        def noJoin = assertThrows(IllegalArgumentException) {
            salesLinesSpec([joinKeyFieldName: null, originFieldName: null])
        }
        assertTrue(noJoin.message.contains("joinKeyFieldName"), noJoin.message)
    }

    @Test
    void salesLinesLineKeyMustBeAPlainIdentifier() {
        ["custcol_x' OR '1'='1", "custcol_x; DROP", "TO_CHAR(custcol_x)", "custcol x"].each { String hostile ->
            assertThrows(IllegalArgumentException, { salesLinesSpec([lineKeyColumn: hostile]) },
                    "must reject: ${hostile}")
        }
    }

    @Test
    void salesLinesRequiresTheTransactionTable() {
        def e = assertThrows(IllegalArgumentException) { salesLinesSpec([fromTable: "item"]) }
        assertTrue(e.message.contains("transaction"), e.message)
    }

    @Test
    void salesLinesRefusesFunctionCallColumns() {
        // Measured on gorjana: a named column list carrying BUILTIN.DISPLAY(...) failed with an opaque
        // 400 where plain columns worked, and a t.-prefixed expression is not what the template emits.
        def e = assertThrows(IllegalArgumentException) {
            salesLinesSpec([:], [[recordFieldName: "orderId", sourceColumn: "custbody_hc_order_id", sequenceNum: 1],
                                 [recordFieldName: "orderDate", sourceColumn: "TO_CHAR(trandate, 'YYYY-MM-DD') AS trandate",
                                  sequenceNum: 2]])
        }
        assertTrue(e.message.contains("SALES_LINES"), e.message)
    }

    @Test
    void salesLinesRefusesTheOtherTemplatesFlags() {
        [[absentChildRecordType: "ItemShip"], [presentChildRecordType: "ItemShip"],
         [requireFulfillableOpenLine: "Y"], [requireOverdueShipDate: "Y"], [pendingStates: "B"],
         [statesExpectingShipment: "G"], [requireParentRecordType: "SalesOrd"],
         [amountColumn: "foreignamountunpaid", minAmount: "1"],
         [overdueExcludedShipMethods: "Store Pickup"]].each { Map<String, Object> flag ->
            assertThrows(IllegalArgumentException, { salesLinesSpec(flag) }, "must refuse: ${flag}")
        }
    }

    @Test
    void lineKeyColumnIsRefusedOnEveryOtherTemplate() {
        [[:], [queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "ItemShip"],
         [queryTemplate: "STATE_CONTRADICTION", disallowedStates: "E"]].each { Map<String, Object> base ->
            def e = assertThrows(IllegalArgumentException) {
                spec(base + [lineKeyColumn: "custcol_hc_order_line_id"])
            }
            assertTrue(e.message.contains("lineKeyColumn"), e.message)
        }
    }

    /**
     * The row may be a Moqui EntityValue, whose get() THROWS on a field the entity does not declare —
     * and lineKeyColumn is not declared until the entity change lands. containsKey answers false
     * there, so a row without the field must normalise exactly as before.
     */
    @Test
    void aRowThatCannotAnswerLineKeyColumnStillNormalises() {
        Map<String, Object> strict = new LinkedHashMap<String, Object>([nsSuiteQlSourceQueryId: "Q1",
                recordType: "SalesOrd", fromTable: "transaction", dateColumn: "trandate"]) {
            @Override Object get(Object key) {
                if (key == "lineKeyColumn") throw new IllegalStateException("undeclared field lineKeyColumn")
                return super.get(key)
            }
        }
        Map<String, Object> s = NsSuiteQlOrderSupport.normalizeSpec(strict,
                [[recordFieldName: "tranId", sourceColumn: "tranid", sequenceNum: 1]])
        assertEquals("SELECT tranid FROM transaction WHERE type = 'SalesOrd' " +
                "AND trandate >= TO_DATE('2026-08-01', 'YYYY-MM-DD') " +
                "AND trandate < TO_DATE('2026-09-01', 'YYYY-MM-DD') ORDER BY id",
                NsSuiteQlOrderSupport.buildRecordsQuery(s, "2026-08-01", "2026-09-01"))
    }

    // ------------------------------------------------- ACTIVE_ITEMS (DAR-BE-157)

    // The item read: every non-inactive stock item, kit and non-inventory item. Whether NetSuite used it for HotWax is read
    // apart (buildActiveItemsUsedQuery) and joined in the extract: as one query, the per-item test timed out at 180 s a page.
    // No stock: nothing reads it since live stopped counting it, and its per-item sub-select was a quarter of the read.
    private static final String ACTIVE_ITEMS_SQL = "SELECT i.id, i.itemid, i.itemtype, TO_CHAR(i.createddate, 'YYYY-MM-DD') AS created " +
            "FROM item i WHERE i.isinactive = 'F' AND i.itemtype IN ('InvtPart', 'Kit', 'NonInvtPart') "

    private static Map<String, Object> activeItemsSpec(Map<String, Object> overrides = [:], List<String> columns = ["id", "itemid", "live"]) {
        int seq = 0
        return NsSuiteQlOrderSupport.normalizeSpec([nsSuiteQlSourceQueryId: "Q1", recordType: "item", fromTable: "item",
                queryTemplate: "ACTIVE_ITEMS", joinKeyFieldName: "field1"] + overrides,
                columns.collect { [recordFieldName: "field${++seq}".toString(), sourceColumn: it, sequenceNum: seq] as Map<String, Object> })
    }

    @Test
    void activeItemsQueryIsTheProvenText() {
        assertEquals(ACTIVE_ITEMS_SQL + "ORDER BY i.id", NsSuiteQlOrderSupport.buildRecordsQuery(activeItemsSpec(), null, null, "2026-10-28"))
    }

    @Test
    void activeItemsNeedsNoWindow() {
        String sql = NsSuiteQlOrderSupport.buildRecordsQuery(activeItemsSpec(), null, null, "2026-10-28")
        assertFalse(sql.contains("'null'"), sql)
        assertThrows(IllegalArgumentException, { NsSuiteQlOrderSupport.buildRecordsQuery(activeItemsSpec(), null, null, null) },
                "the 90 days count back from the tenant's today, so asOfDate is required")
    }

    @Test
    void activeItemsReadByKey() {
        Map<String, Object> s = activeItemsSpec()
        s.lookupKeyField = "field1"
        s.lookupKeys = NsSuiteQlOrderSupport.lookupKeyList(["43429", "42808"], s)
        assertEquals(ACTIVE_ITEMS_SQL + "AND i.id IN ('43429', '42808') ORDER BY i.id",
                NsSuiteQlOrderSupport.buildRecordsQuery(s, null, null, "2026-10-28"))
    }

    @Test
    void activeItemsRefusesAnotherTable() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException) { activeItemsSpec([fromTable: "transaction"]) }
        assertTrue(e.message.contains("ACTIVE_ITEMS reads the item table, so fromTable must be item"), e.message)
    }

    // Aditi 2026-10-29 on 193-102-G: gorjana's own "Active Status" (custitem1) says Discontinued. A tenant's item column rides
    // along after the template's columns; the shapes the vetted SQL uses stay fixed.
    @Test
    void activeItemsCarriesATenantsOwnItemColumns() {
        String sql = NsSuiteQlOrderSupport.buildRecordsQuery(activeItemsSpec([:], ["id", "itemid", "live", "BUILTIN.DF(custitem1) AS activestatus"]),
                null, null, "2026-10-28")
        assertTrue(sql.contains("AS created, BUILTIN.DF(i.custitem1) AS activestatus FROM item i"), sql)
        String bare = NsSuiteQlOrderSupport.buildRecordsQuery(activeItemsSpec([:], ["id", "custitem1"]), null, null, "2026-10-28")
        assertTrue(bare.contains("AS created, i.custitem1 FROM item i"), bare)
    }

    @Test
    void activeItemsStillRefusesAnUnsafeColumn() {
        assertThrows(IllegalArgumentException) { activeItemsSpec([:], ["id", "custitem1) OR (1=1"]) }
        assertThrows(IllegalArgumentException) { activeItemsSpec([:], ["id", "(SELECT 1 FROM employee)"]) }
    }

    // Aditi 2026-10-29: "the item must have been used within the last year in NS", on what HotWax sees ("The order you found
    // is not expected to sync to HW... so the item will never come to HW either": PKG109, a gifting sales order). Proven on
    // gorjana prod: 3,757 items, four pages, each well inside the 180 s limit.
    @Test
    void theUsedItemsAreHotWaxOrderAndTransferOrderLinesOfTheYear() {
        assertEquals("SELECT DISTINCT tl.item AS item FROM transactionline tl JOIN transaction t ON t.id = tl.transaction " +
                "WHERE t.trandate >= TO_DATE('2025-10-28', 'YYYY-MM-DD') " +
                "AND ((t.type = 'SalesOrd' AND t.custbody_hc_order_id IS NOT NULL) OR t.type IN ('TrnfrOrd', 'PurchOrd')) " +
                // DAR-OPS-029 S41a (QA D5): no line without an item, so the read's rows do not depend on its page size
                "AND tl.item IS NOT NULL ORDER BY tl.item",
                NsSuiteQlOrderSupport.buildActiveItemsUsedQuery(activeItemsSpec(), "2026-10-28"))
        Map<String, Object> s = activeItemsSpec()
        s.lookupKeyField = "field1"
        s.lookupKeys = NsSuiteQlOrderSupport.lookupKeyList(["43429"], s)
        assertTrue(NsSuiteQlOrderSupport.buildActiveItemsUsedQuery(s, "2026-10-28").contains("AND tl.item IN ('43429') ORDER BY"))
    }

    // Aditi 2026-10-29 (the daily run must not take long): the year of HotWax sales-order lines is 447 s of a 13-minute run
    // on gorjana; transfer and purchase orders alone are 19 s. An item on a HotWax sales order was in HotWax (HotWax made the
    // order), so the daily run's question, "never reached HotWax", loses nothing without them.
    @Test
    void aQueryMayCountUseOnSomeOrderTypesOnly() {
        String used = NsSuiteQlOrderSupport.buildActiveItemsUsedQuery(activeItemsSpec([usedTransactionTypes: "TrnfrOrd, PurchOrd"]), "2026-10-28")
        assertTrue(used.contains("WHERE t.trandate >= TO_DATE('2025-10-28', 'YYYY-MM-DD') AND t.type IN ('TrnfrOrd', 'PurchOrd') AND tl.item IS NOT NULL ORDER BY tl.item"), used)
        assertTrue(NsSuiteQlOrderSupport.buildActiveItemsUsedQuery(activeItemsSpec([usedTransactionTypes: "SalesOrd"]), "2026-10-28")
                .contains("AND ((t.type = 'SalesOrd' AND t.custbody_hc_order_id IS NOT NULL)) AND tl.item IS NOT NULL ORDER BY"), "a sales order still counts only when HotWax sent it")
        assertEquals(NsSuiteQlOrderSupport.buildActiveItemsUsedQuery(activeItemsSpec(), "2026-10-28"),
                NsSuiteQlOrderSupport.buildActiveItemsUsedQuery(activeItemsSpec([usedTransactionTypes: "SalesOrd,TrnfrOrd,PurchOrd"]), "2026-10-28"),
                "blank means all three")
        IllegalArgumentException e = assertThrows(IllegalArgumentException) { activeItemsSpec([usedTransactionTypes: "CashSale"]) }
        assertTrue(e.message.contains("usedTransactionTypes"), e.message)
        assertThrows(IllegalArgumentException) { activeItemsSpec([usedTransactionTypes: "TrnfrOrd') OR ('1'='1"]) }
        IllegalArgumentException other = assertThrows(IllegalArgumentException) {
            NsSuiteQlOrderSupport.normalizeSpec([nsSuiteQlSourceQueryId: "Q1", recordType: "SalesOrd", fromTable: "transaction",
                    dateColumn: "trandate", usedTransactionTypes: "TrnfrOrd"], [[recordFieldName: "id", sourceColumn: "id", sequenceNum: 1]])
        }
        assertTrue(other.message.contains("only meaningful on ACTIVE_ITEMS"), other.message)
    }

    @Test
    void theStreamMarksAnItemLiveWhenNetSuiteUsedItForHotWax() {
        Map<String, Object> spec = activeItemsSpec([:], ["id", "itemid", "live"])
        List<String> asked = []
        StringWriter out = new StringWriter()
        NsSuiteQlOrderSupport.streamOrdersToWriter(out, [spec: spec, asOfDate: "2026-10-28"],
                { String q, int limit, int offset ->
                    asked << q
                    return q.startsWith("SELECT DISTINCT tl.item")
                            ? [items: [[item: "42808"]], hasMore: false]
                            : [items: [[id: "42808", itemid: "PKG403", created: "2026-07-07"], [id: "10211", itemid: "PKG109", created: "2017-12-26"],
                                       [id: "43649", itemid: "271-010-185-G", created: "2026-10-27"]], hasMore: false]
                })
        Map doc = (Map) new groovy.json.JsonSlurper().parseText(out.toString())
        // Aditi 2026-10-29: "dont miss out on recent new products in NS... new products in NS didnt make it HC in time and the
        // PO came first": an item created in the last NEW_ITEM_DAYS is live before anything uses it.
        assertEquals([[field1: "42808", field2: "PKG403", field3: "Y"], [field1: "10211", field2: "PKG109", field3: "N"],
                      [field1: "43649", field2: "271-010-185-G", field3: "Y"]], doc.records,
                "PKG109 is not live; a run's filter on field3 sets it aside in Spark")
        assertTrue(asked[0].startsWith("SELECT DISTINCT tl.item"), "the used items are read first")
    }

    // ------------------------------------------------- DAR-BE-318 plan 3b-1: a kept copy read by what changed (Task 5)

    /**
     * The RECORDS query as it shipped for DAR-BE-032/044 (absentTemplateIsRecordsAndTheQueryIsUnchanged pins the same text).
     * A dataset read changes it only when it says so; a run's read never does.
     */
    private static final String RECORDS_SQL = "SELECT tranid FROM transaction WHERE type = 'SalesOrd' " +
            "AND trandate >= TO_DATE('2026-08-01', 'YYYY-MM-DD') " +
            "AND trandate < TO_DATE('2026-09-01', 'YYYY-MM-DD') ORDER BY id"

    /** The two stamp columns a dataset read adds (ruling P3b1-12), on the record as `outer` names it. */
    private static String stampColumns(String outer) {
        String header = outer == "t" ? "t.lastmodifieddate" : "lastmodifieddate"
        return "TO_CHAR(${header}, 'YYYY-MM-DD HH24:MI:SS') AS darpan_header_stamp, " +
                "(SELECT TO_CHAR(MAX(sl.linelastmodifieddate), 'YYYY-MM-DD HH24:MI:SS') FROM transactionline sl " +
                "WHERE sl.transaction = ${outer}.id) AS darpan_line_stamp"
    }

    /**
     * The line stamp exactly as SuiteQL accepted it (read-only probe, gorjana, 2026-11-20 19:35): TO_CHAR INSIDE the
     * sub-select. TO_CHAR wrapped AROUND the sub-select is refused (400 "Invariant check for stage 'INPUT' failed"), aliased
     * or not, so neither query may ever send that shape.
     */
    @Test
    void theLineStampIsFormattedInsideItsSubSelect() {
        Map<String, Object> records = NsSuiteQlOrderSupport.applyReadMode(spec([:]), "darpanStamp", true)
        String r = NsSuiteQlOrderSupport.buildRecordsQuery(records, "2026-11-18", null)
        assertTrue(r.contains("(SELECT TO_CHAR(MAX(sl.linelastmodifieddate), 'YYYY-MM-DD HH24:MI:SS') FROM transactionline sl " +
                "WHERE sl.transaction = transaction.id) AS darpan_line_stamp"), r)
        Map<String, Object> lines = NsSuiteQlOrderSupport.applyReadMode(salesLinesSpec(), "trandate", true)
        String l = NsSuiteQlOrderSupport.buildRecordsQuery(lines, "2026-11-18", NsSuiteQlOrderSupport.readThruDate(lines, "2026-11-20"))
        assertTrue(l.contains("(SELECT TO_CHAR(MAX(sl.linelastmodifieddate), 'YYYY-MM-DD HH24:MI:SS') FROM transactionline sl " +
                "WHERE sl.transaction = t.id) AS darpan_line_stamp"), l)
        [r, l].each { String q -> assertFalse(q.contains("TO_CHAR((SELECT"), "TO_CHAR around a sub-select is refused by SuiteQL: ${q}") }
    }

    /** Every template, configured as a run would configure it. */
    private static Map<String, Map<String, Object>> everyTemplate() {
        return [RECORDS                    : spec([:]),
                LINKED_CHILD_ABSENT        : spec([queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "ItemShip"]),
                LINKED_CHILD_PRESENT       : spec([queryTemplate: "LINKED_CHILD_PRESENT", presentChildRecordType: "ItemShip"]),
                LINKED_CHILD_PRESENT_ABSENT: spec([queryTemplate: "LINKED_CHILD_PRESENT_ABSENT", presentChildRecordType: "ItemShip",
                                                   absentChildRecordType: "CustInvc"]),
                LINKED_CHILD_EXCUSED       : spec([queryTemplate: "LINKED_CHILD_EXCUSED", absentChildRecordType: "ItemShip",
                                                   waiveRules: [[fieldId: "shipmethod", values: ["Store Pickup"]]]]),
                RECORDS_MATCHING           : spec([queryTemplate: "RECORDS_MATCHING", matchRules: [[fieldId: "otherrefnum", op: "STARTS_WITH", values: ["WTY-"]]]]),
                STATE_CONTRADICTION        : stateSpec(),
                SALES_LINES                : salesLinesSpec(),
                ACTIVE_ITEMS               : activeItemsSpec()] as Map<String, Map<String, Object>>
    }

    private static String sql(Map<String, Object> s, String from = "2026-08-01", String thru = "2026-09-01") {
        return NsSuiteQlOrderSupport.buildRecordsQuery(s, from, thru, "2026-09-02")
    }

    /**
     * Review Focus: with windowFieldName absent, or the row's own dateColumn, every query is the one a run sends today, to
     * the byte: RECORDS (its promise above), every other template, and the lifecycle condition-id query.
     */
    @Test
    void recordsQueryIsByteIdenticalWithoutTheParameter() {
        assertEquals(RECORDS_SQL, sql(NsSuiteQlOrderSupport.applyReadMode(spec([:]), null, null)))
        assertEquals(RECORDS_SQL, sql(NsSuiteQlOrderSupport.applyReadMode(spec([:]), "trandate", false)))
        assertEquals(RECORDS_SQL, sql(NsSuiteQlOrderSupport.applyReadMode(spec([:]), " ", false)))
        everyTemplate().each { String template, Map<String, Object> s ->
            String before = sql(s)
            Map<String, Object> absent = NsSuiteQlOrderSupport.applyReadMode(new LinkedHashMap(s), null, null)
            Map<String, Object> ownDate = NsSuiteQlOrderSupport.applyReadMode(new LinkedHashMap(s), s.dateColumn ?: "trandate", false)
            assertEquals(s, absent, "${template}: no read-mode keys are added")
            assertEquals(s, ownDate, "${template}: no read-mode keys are added")
            assertEquals(before, sql(absent), template)
            assertEquals(before, sql(ownDate), template)
        }
        Map<String, Object> waived = spec([queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "ItemShip",
                                           waiveRules: [[fieldId: "shipmethod", values: ["Store Pickup"]]]])
        String ids = NsSuiteQlOrderSupport.buildConditionIdQuery(waived, "MISSING", "ItemShip", "2026-08-01", "2026-09-01")
        assertEquals(ids, NsSuiteQlOrderSupport.buildConditionIdQuery(
                NsSuiteQlOrderSupport.applyReadMode(new LinkedHashMap(waived), "trandate", null), "MISSING", "ItemShip", "2026-08-01", "2026-09-01"))
        // a run's record carries no stamp
        assertFalse(NsSuiteQlOrderSupport.mapRowToRecord(spec([:]), [tranid: "SO1"]).containsKey("darpanStamp"))
    }

    /**
     * Review Focus 4: a line edited while its order's header stamp stays still. The changed-since read asks for the order
     * whose header moved OR that has any line whose own stamp moved, so the next read picks the line edit up. RECORDS has no
     * alias, so the line half is an uncorrelated id IN (ruling 3b1-5, the shape the gate ran).
     */
    @Test
    void changedSinceRecordsCatchesALineEditWithAStillHeader() {
        Map<String, Object> s = NsSuiteQlOrderSupport.applyReadMode(spec([:]), "darpanStamp", true)
        String q = NsSuiteQlOrderSupport.buildRecordsQuery(s, "2026-11-18", NsSuiteQlOrderSupport.readThruDate(s, "2026-11-20"))
        assertEquals("SELECT tranid, ${stampColumns('transaction')} FROM transaction WHERE type = 'SalesOrd' ".toString() +
                "AND (lastmodifieddate >= TO_DATE('2026-11-18', 'YYYY-MM-DD') " +
                "OR id IN (SELECT cl.transaction FROM transactionline cl WHERE cl.linelastmodifieddate >= TO_DATE('2026-11-18', 'YYYY-MM-DD'))) " +
                "ORDER BY id", q)
        assertFalse(q.contains("trandate"), "a changed-since read is not bounded by the transaction date: ${q}")
    }

    /** SALES_LINES joins its lines itself; the clause sits on its header t, and every line of a changed order comes back. */
    @Test
    void changedSinceSalesLinesUsesTheClauseOnT() {
        Map<String, Object> s = NsSuiteQlOrderSupport.applyReadMode(salesLinesSpec(), "darpanStamp", true)
        String q = NsSuiteQlOrderSupport.buildRecordsQuery(s, "2026-11-18", NsSuiteQlOrderSupport.readThruDate(s, "2026-11-20"))
        assertEquals("SELECT t.custbody_hc_order_id AS custbody_hc_order_id, t.tranid AS tranid, " +
                "tl.custcol_hc_order_line_id AS order_line_id, tl.quantityshiprecv AS quantity_shipped, " +
                "tl.quantitybackordered AS quantity_backordered, tl.isclosed AS line_closed, tl.id AS line_id, " +
                "tl.itemtype AS item_type, tl.quantitybilled AS quantity_billed, " +
                "CASE WHEN tl.quantitybilled <> 0 THEN 'BILLED' WHEN tl.isclosed = 'T' THEN 'CLOSED' " +
                "WHEN tl.quantityshiprecv <> 0 THEN 'SHIPPED' ELSE 'OPEN' END AS line_state, " +
                "tl.location AS line_location, BUILTIN.DF(tl.item) AS item_name, (-1 * tl.netamount) AS line_amount, " +
                "${stampColumns('t')} ".toString() +
                "FROM transaction t JOIN transactionline tl ON tl.transaction = t.id " +
                "WHERE t.type = 'SalesOrd' " +
                "AND (t.lastmodifieddate >= TO_DATE('2026-11-18', 'YYYY-MM-DD') " +
                "OR t.id IN (SELECT cl.transaction FROM transactionline cl WHERE cl.linelastmodifieddate >= TO_DATE('2026-11-18', 'YYYY-MM-DD'))) " +
                "AND tl.mainline = 'F' " +
                "AND (tl.custcol_hc_order_line_id IS NOT NULL OR tl.itemtype = 'Payment') AND t.custbody_hc_order_id IS NOT NULL " +
                "ORDER BY t.id, tl.id", q)
    }

    /**
     * Ruling 3b1-6: a changed-since read sends no upper bound, so what changed today (after the readThru day's midnight in the
     * session zone) is read too. The day the dispatcher sent as the end is dropped, and the written file says so.
     */
    @Test
    void changedSinceSendsNoUpperBound() {
        Map<String, Object> s = NsSuiteQlOrderSupport.applyReadMode(spec([:]), "darpanStamp", true)
        assertNull(NsSuiteQlOrderSupport.readThruDate(s, "2026-11-20"))
        String q = NsSuiteQlOrderSupport.buildRecordsQuery(s, "2026-11-18", null)
        assertFalse(q.contains(" < "), q)
        assertFalse(q.contains("2026-11-20"), q)
        StringWriter out = new StringWriter()
        List<String> sent = []
        NsSuiteQlOrderSupport.streamOrdersToWriter(out, [spec: s, fromDate: "2026-11-18", toDate: null], { String query, int limit, int offset ->
            sent << query
            return [items: [[tranid: "SO1", darpan_header_stamp: "2026-11-20 09:15:00"]], hasMore: false]
        })
        assertEquals([q], sent.collect { SuiteQlKeysetFake.baseQuery(it) }, "one page, the query as built plus its page key (OC109)")
        Map doc = (Map) new groovy.json.JsonSlurper().parseText(out.toString())
        assertNull(doc.metadata.windowThru)
        assertEquals(true, doc.metadata.changedSince)
        assertEquals([[tranId: "SO1", darpanStamp: "2026-11-20T17:15:00Z"]], doc.records, "today's change is read, stamped as an instant")
    }

    /**
     * Ruling 3b1-6: a dataset's FULL read windows on the transaction date with tomorrow as its end, so today's transactions
     * are in it; a run's read, sent the same days, keeps its own end.
     */
    @Test
    void aFullReadIncludesToday() {
        Map<String, Object> full = NsSuiteQlOrderSupport.applyReadMode(spec([:]), "trandate", true)
        assertEquals("2026-11-21", NsSuiteQlOrderSupport.readThruDate(full, "2026-11-20"))
        String q = NsSuiteQlOrderSupport.buildRecordsQuery(full, "2026-09-19", NsSuiteQlOrderSupport.readThruDate(full, "2026-11-20"))
        assertEquals("SELECT tranid, ${stampColumns('transaction')} FROM transaction WHERE type = 'SalesOrd' ".toString() +
                "AND trandate >= TO_DATE('2026-09-19', 'YYYY-MM-DD') " +
                "AND trandate < TO_DATE('2026-11-21', 'YYYY-MM-DD') ORDER BY id", q)
        Map<String, Object> lines = NsSuiteQlOrderSupport.applyReadMode(salesLinesSpec(), "trandate", true)
        assertTrue(NsSuiteQlOrderSupport.buildRecordsQuery(lines, "2026-09-19", NsSuiteQlOrderSupport.readThruDate(lines, "2026-11-20"))
                .contains("AND t.trandate < TO_DATE('2026-11-21', 'YYYY-MM-DD') "))
        // a run's read: the end it was sent
        Map<String, Object> run = NsSuiteQlOrderSupport.applyReadMode(spec([:]), null, null)
        assertEquals("2026-11-20", NsSuiteQlOrderSupport.readThruDate(run, "2026-11-20"))
        assertNull(NsSuiteQlOrderSupport.readThruDate(full, null), "a read with no window keeps none")
    }

    /**
     * Ruling P3b1-12: both stamps come back as session-zone wall clock to the second (TO_CHAR; the raw columns are day-only);
     * darpanStamp is the later one as a UTC instant, so the kept copy compares instants.
     */
    @Test
    void darpanStampIsTheLaterOfHeaderAndNewestLine() {
        Map<String, Object> s = NsSuiteQlOrderSupport.applyReadMode(spec([:]), "darpanStamp", true)
        // the gate's own pair (2026-11-18, Pacific standard time, UTC-8): a line a second after its header
        assertEquals("2026-11-18T10:01:59Z", NsSuiteQlOrderSupport.mapRowToRecord(s,
                [tranid: "SO1", darpan_header_stamp: "2026-11-18 02:01:58", darpan_line_stamp: "2026-11-18 02:01:59"]).darpanStamp)
        // a header edited after its newest line
        assertEquals("2026-11-19T00:30:00Z", NsSuiteQlOrderSupport.mapRowToRecord(s,
                [tranid: "SO2", darpan_header_stamp: "2026-11-18 16:30:00", darpan_line_stamp: "2026-11-17 08:00:00"]).darpanStamp)
        // daylight time (UTC-7), and an order with no lines (SuiteQL leaves a null column out of the row)
        assertEquals("2026-07-01T19:00:00Z", NsSuiteQlOrderSupport.mapRowToRecord(s,
                [tranid: "SO3", darpan_header_stamp: "2026-07-01 12:00:00"]).darpanStamp)
        // the stamp columns themselves are not record fields
        assertEquals([tranId: "SO1", darpanStamp: "2026-11-18T10:01:59Z"], NsSuiteQlOrderSupport.mapRowToRecord(s,
                [tranid: "SO1", darpan_header_stamp: "2026-11-18 02:01:58", darpan_line_stamp: "2026-11-18 02:01:59", links: []]))
        assertEquals("2026-11-18T10:01:59Z", NsSuiteQlOrderSupport.changeStamp("2026-11-18 02:01:58", "2026-11-18 02:01:59"))
        // a stamp read wrongly would merge a change as older than what the copy holds: the read fails instead
        assertThrows(IllegalStateException) { NsSuiteQlOrderSupport.changeStamp("11/18/2026", null) }
        assertThrows(IllegalStateException) { NsSuiteQlOrderSupport.changeStamp(null, null) }
    }

    /**
     * Review Focus 5 / ruling P3b1-14: a template whose records depend on other transactions (or that has no window) cannot
     * be kept by its own stamps. Its dataset read, changed-since or full, is refused with a message naming the two that can.
     */
    @Test
    void linkedChildTemplatesRefuseChangedSince() {
        everyTemplate().findAll { k, v -> !(k in ["RECORDS", "SALES_LINES"]) }.each { String template, Map<String, Object> s ->
            [["darpanStamp", true], ["darpanStamp", null], [s.dateColumn ?: "trandate", true]].each { List mode ->
                IllegalArgumentException e = assertThrows(IllegalArgumentException, {
                    NsSuiteQlOrderSupport.applyReadMode(new LinkedHashMap(s), mode[0], mode[1])
                }, "${template} ${mode}")
                assertTrue(e.message.contains(template), e.message)
                assertTrue(e.message.contains("RECORDS") && e.message.contains("SALES_LINES"), e.message)
            }
            // the query builder refuses too, however the spec came to be marked, and for the same reason
            IllegalArgumentException built = assertThrows(IllegalArgumentException, { sql(s + [changedSince: true, stamped: true]) }, template)
            assertTrue(built.message.contains(template), built.message)
        }
        // RECORDS on a table with no lines has no line stamp to read
        Map<String, Object> customers = NsSuiteQlOrderSupport.normalizeSpec([nsSuiteQlSourceQueryId: "Q1", recordType: "Customer",
                fromTable: "customer", dateColumn: "datecreated"], [[recordFieldName: "id", sourceColumn: "id", sequenceNum: 1]])
        assertThrows(IllegalArgumentException) { NsSuiteQlOrderSupport.applyReadMode(customers, "darpanStamp", true) }
    }

    /**
     * DAR-BE-318 I2: a kept template whose values move while neither stamp does cannot be kept either. A field read off
     * linked transactions, or the HotWax status or shipments, would never be re-read by a changed-since read, and the weekly
     * full read would count each moved value as a miss. Its dataset read is refused naming why; a run read is untouched.
     */
    @Test
    void valuesThatMoveWithoutAStampRefuseAKeptCopy() {
        Map<String, Map<String, Object>> refused = [
                "LINKED_RECORDS(CustDep)"  : spec([:], [[recordFieldName: "deposits", sourceColumn: "LINKED_RECORDS(CustDep) AS deposits"]]),
                "LINKED_LOCATIONS(ItemShip)": spec([:], [[recordFieldName: "shipments", sourceColumn: "LINKED_LOCATIONS(ItemShip) AS shipments"]]),
                "LINKED(ItemShip)"         : spec([:], [[recordFieldName: "shipped", sourceColumn: "LINKED(ItemShip) AS shipped"]]),
                "DEPOSIT_WHEN(SalesOrd, F, 0)": spec([:], [[recordFieldName: "deposit", sourceColumn: "DEPOSIT_WHEN(SalesOrd, F, 0) AS deposit"]]),
                "LINE_LINKED(ItemShip)"    : salesLinesSpec([:], [[recordFieldName: "orderId", sourceColumn: "custbody_hc_order_id", sequenceNum: 1],
                                                                  [recordFieldName: "fulfillments", sourceColumn: "LINE_LINKED(ItemShip) AS fulfillments", sequenceNum: 2]]),
                "omsStatusConfigId"        : spec([omsStatusConfigId: "OMS_X", omsStatusKeyField: "tranId"]),
                "omsShipGroupsField"       : spec([omsStatusConfigId: "OMS_X", omsStatusKeyField: "tranId", omsShipGroupsField: "shipments"],
                        [[recordFieldName: "shipments", sourceColumn: "LINKED_LOCATIONS(ItemShip) AS shipments"]]),
        ]
        refused.each { String named, Map<String, Object> s ->
            [["darpanStamp", true], ["darpanStamp", null], ["trandate", true]].each { List mode ->
                IllegalArgumentException e = assertThrows(IllegalArgumentException, {
                    NsSuiteQlOrderSupport.applyReadMode(new LinkedHashMap(s), mode[0], mode[1])
                }, "${named} ${mode}")
                assertTrue(e.message.contains(named) && e.message.contains("cannot be kept"), e.message)
            }
            IllegalArgumentException built = assertThrows(IllegalArgumentException, { sql(s + [changedSince: true, stamped: true]) }, named)
            assertTrue(built.message.contains(named) && built.message.contains("cannot be kept"), built.message)
            // a run read sends the same query as before
            assertFalse(NsSuiteQlOrderSupport.applyReadMode(new LinkedHashMap(s), "trandate", false).containsKey("stamped"), named)
        }
        // a field read from the record's own lines moves the line stamp: kept
        assertTrue(NsSuiteQlOrderSupport.applyReadMode(spec([:], [[recordFieldName: "backordered",
                sourceColumn: "LINE_SUM(quantitybackordered) AS backordered"]]), "darpanStamp", true).stamped as boolean)
    }

    /** Ruling 3b1-4: the window field is the row's dateColumn or the changed-since stamp; any other is refused by name. */
    @Test
    void anyOtherWindowFieldIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException) {
            NsSuiteQlOrderSupport.applyReadMode(spec([:]), "lastmodifieddate", null)
        }
        assertTrue(e.message.contains("lastmodifieddate") && e.message.contains("trandate") && e.message.contains("darpanStamp"), e.message)
        assertThrows(IllegalArgumentException) { NsSuiteQlOrderSupport.applyReadMode(spec([:]), "createdAt", true) }
    }

    /**
     * DAR-BE-320 (Aditi 2026-11-26: "no runs pull month to date on a daily run"). After its recent read an audit asks for the
     * window's records that changed since that read started: the transaction-date window stays (an October order edited
     * today is no November record) and the changed-since clause is added, so a November 3 order edited today is read.
     */
    @Test
    void anAuditsChangedReadKeepsTheWindowAndAsksWhatChanged() {
        String changed = "AND (lastmodifieddate >= TO_DATE('2026-11-24', 'YYYY-MM-DD') " +
                "OR id IN (SELECT cl.transaction FROM transactionline cl WHERE cl.linelastmodifieddate >= TO_DATE('2026-11-24', 'YYYY-MM-DD')) " +
                "OR trandate >= TO_DATE('2026-11-24', 'YYYY-MM-DD')) "
        Map<String, Object> records = NsSuiteQlOrderSupport.applyChangedSince(spec([:]), java.sql.Timestamp.valueOf("2026-11-24 00:00:00"))
        assertEquals("2026-11-24", records.changedFrom)
        assertFalse(records.containsKey("stamped"), "an audit read carries no dataset stamp")
        assertEquals("SELECT tranid FROM transaction WHERE type = 'SalesOrd' " +
                "AND trandate >= TO_DATE('2026-11-01', 'YYYY-MM-DD') AND trandate < TO_DATE('2026-11-27', 'YYYY-MM-DD') " +
                changed + "ORDER BY id", sql(records, "2026-11-01", "2026-11-27"))
        String lines = sql(NsSuiteQlOrderSupport.applyChangedSince(salesLinesSpec(), "2026-11-24"), "2026-11-01", "2026-11-27")
        assertTrue(lines.contains("WHERE t.type = 'SalesOrd' AND t.trandate >= TO_DATE('2026-11-01', 'YYYY-MM-DD') " +
                "AND t.trandate < TO_DATE('2026-11-27', 'YYYY-MM-DD') AND (t.lastmodifieddate >= TO_DATE('2026-11-24', 'YYYY-MM-DD') " +
                "OR t.id IN (SELECT cl.transaction FROM transactionline cl WHERE cl.linelastmodifieddate >= TO_DATE('2026-11-24', 'YYYY-MM-DD')) " +
                "OR t.trandate >= TO_DATE('2026-11-24', 'YYYY-MM-DD')) " +
                "AND tl.mainline = 'F' "), lines)
        // none sent: the spec and the query are a run's, to the byte
        assertEquals(spec([:]), NsSuiteQlOrderSupport.applyChangedSince(spec([:]), null))
        assertEquals(RECORDS_SQL, sql(NsSuiteQlOrderSupport.applyChangedSince(spec([:]), " ")))
    }

    /**
     * DAR-BE-320. A query whose records move while neither NetSuite stamp does is refused by name before any request, so the
     * audit reads that check's whole window: a line HotWax cancelled today (omsStatusConfigId, gorjana R10/R11) changes no
     * NetSuite stamp, and a template that reads other transactions or has no window cannot say what changed either.
     */
    @Test
    void anAuditsChangedReadIsRefusedWhereNoStampMoves() {
        Map<String, Map<String, Object>> refused = [
                "LINKED_CHILD_ABSENT": spec([queryTemplate: "LINKED_CHILD_ABSENT", absentChildRecordType: "ItemShip"]),
                "RECORDS_MATCHING"   : spec([queryTemplate: "RECORDS_MATCHING", matchRules: [[fieldId: "otherrefnum", op: "STARTS_WITH", values: ["WTY-"]]]]),
                "ACTIVE_ITEMS"       : activeItemsSpec(),
                "omsStatusConfigId"  : salesLinesSpec([omsStatusConfigId: "HOTWAX_OMS_REST:x", omsStatusKeyField: "orderId",
                                                       omsStatusLineKeyField: "orderLineId"]),
        ]
        refused.each { String named, Map<String, Object> s ->
            IllegalArgumentException e = assertThrows(IllegalArgumentException, {
                NsSuiteQlOrderSupport.applyChangedSince(new LinkedHashMap(s), "2026-11-24")
            }, named)
            assertTrue(e.message.startsWith("Not read by what changed: ") && e.message.contains(named), e.message)
            IllegalArgumentException built = assertThrows(IllegalArgumentException, { sql(new LinkedHashMap(s) + [changedFrom: "2026-11-24"]) },
                    "${named}: however the spec was marked")
            assertTrue(built.message.startsWith("Not read by what changed: ") && built.message.contains(named), built.message)
        }
        Map<String, Object> byKey = spec([:]) + [lookupKeys: ["SO1"], lookupKeyField: "tranId"]
        assertThrows(IllegalArgumentException) { NsSuiteQlOrderSupport.applyChangedSince(byKey, "2026-11-24") }
        Map<String, Object> stamped = NsSuiteQlOrderSupport.applyReadMode(spec([:]), "darpanStamp", true)
        assertThrows(IllegalArgumentException) { NsSuiteQlOrderSupport.applyChangedSince(stamped, "2026-11-24") }
    }

    /** gorjana's R10/R11/R12 query shape: SALES_LINES on createddate, each line with its HotWax item's status. */
    private static Map<String, Object> enrichedLinesSpec(Map<String, Object> overrides = [:], List<Map<String, Object>> fields = null) {
        return salesLinesSpec([dateColumn: "createddate", omsStatusConfigId: "HOTWAX_OMS_REST:gorjana_oms", omsStatusKeyField: "orderId",
                               omsStatusLineKeyField: "orderLineId", omsChangedStatusIds: "ITEM_CANCELLED", liveLineStates: "OPEN"] + overrides, fields)
    }

    /**
     * DAR-BE-320 (Aditi 2026-11-26: design approved). gorjana R10/R11/R12 read 197k November lines each morning to judge the
     * ~10k open and ~2k closed ones. Read by change, the query takes whole orders that could have moved since the recent start:
     * changed in NetSuite, created since, with an open line (an approved line HotWax moved to another facility moves no stamp
     * anywhere), or one HotWax cancelled a line of (a cancel on a billed line moves no NetSuite stamp).
     */
    @Test
    void aHotwaxEnrichedLinesQueryReadByChangeTakesLiveLinesAndTheOrdersHotwaxChanged() {
        Map<String, Object> s = NsSuiteQlOrderSupport.applyChangedSince(enrichedLinesSpec(), "2026-11-25")
        NsSuiteQlOrderSupport.applyChangedOrderIds(s, ["M1214814", "M1231905", "M1214814"])
        assertEquals(["ITEM_CANCELLED"], s.omsChangedStatusIds)
        assertEquals(["OPEN"], s.liveLineStates)
        assertEquals(["M1214814", "M1231905"], s.changedOrderIds, "each order once")
        String q = sql(s, "2026-11-01", "2026-11-26")
        String d = "TO_DATE('2026-11-25', 'YYYY-MM-DD')"
        assertTrue(q.contains("WHERE t.type = 'SalesOrd' AND t.createddate >= TO_DATE('2026-11-01', 'YYYY-MM-DD') " +
                "AND t.createddate < TO_DATE('2026-11-26', 'YYYY-MM-DD') " +
                "AND (t.lastmodifieddate >= ${d} " +
                "OR t.id IN (SELECT cl.transaction FROM transactionline cl WHERE cl.linelastmodifieddate >= ${d}) " +
                "OR t.createddate >= ${d} " +
                "OR t.id IN (SELECT ll.transaction FROM transactionline ll WHERE ll.mainline = 'F' AND ll.custcol_hc_order_line_id IS NOT NULL " +
                "AND CASE WHEN ll.quantitybilled <> 0 THEN 'BILLED' WHEN ll.isclosed = 'T' THEN 'CLOSED' WHEN ll.quantityshiprecv <> 0 " +
                "THEN 'SHIPPED' ELSE 'OPEN' END IN ('OPEN')) " +
                "OR t.custbody_hc_order_id IN ('M1214814','M1231905')) AND tl.mainline = 'F' "), q)
        // no changed orders: the IN term is left out, nothing else moves
        String none = sql(NsSuiteQlOrderSupport.applyChangedOrderIds(NsSuiteQlOrderSupport.applyChangedSince(enrichedLinesSpec(), "2026-11-25"), []),
                "2026-11-01", "2026-11-26")
        assertFalse(none.contains("custbody_hc_order_id IN"), none)
        // a run's read of the same query is untouched
        assertFalse(sql(enrichedLinesSpec(), "2026-11-01", "2026-11-26").contains("lastmodifieddate"))
    }

    /**
     * DAR-BE-320. A HotWax-enriched query is read by change only when it says what brings an order back; shipments never
     * (nothing says when they change); every refusal starts with the words darpan reads as "read the whole window".
     */
    @Test
    void aHotwaxEnrichedQueryIsReadByChangeOnlyWhenItNamesWhatMovesIt() {
        IllegalArgumentException noStatuses = assertThrows(IllegalArgumentException) {
            NsSuiteQlOrderSupport.applyChangedSince(enrichedLinesSpec([omsChangedStatusIds: null]), "2026-11-25")
        }
        assertTrue(noStatuses.message.startsWith(NsSuiteQlOrderSupport.CHANGED_READ_REFUSED + ": ") &&
                noStatuses.message.contains("omsChangedStatusIds"), noStatuses.message)
        assertEquals(darpan.audit.AuditRecentReadSupport.CHANGED_READ_REFUSED, NsSuiteQlOrderSupport.CHANGED_READ_REFUSED,
                "darpan reads a refusal by these words")
        Map<String, Object> shipGroups = NsSuiteQlOrderSupport.normalizeSpec([nsSuiteQlSourceQueryId: "NS_SHIP", recordType: "SalesOrd",
                fromTable: "transaction", dateColumn: "trandate", joinKeyFieldName: "orderId", omsStatusConfigId: "HOTWAX_OMS_REST:x",
                omsStatusKeyField: "orderId", omsShipGroupsField: "fulfillments"],
                [[recordFieldName: "orderId", sourceColumn: "custbody_hc_order_id", sequenceNum: 1],
                 [recordFieldName: "fulfillments", sourceColumn: "LINKED_LOCATIONS(ItemShip) AS fulfillments", sequenceNum: 2]])
        IllegalArgumentException shipments = assertThrows(IllegalArgumentException) { NsSuiteQlOrderSupport.applyChangedSince(shipGroups, "2026-11-25") }
        assertTrue(shipments.message.startsWith(NsSuiteQlOrderSupport.CHANGED_READ_REFUSED), shipments.message)

        List<String> many = (1..1001).collect { "M${it}".toString() }
        IllegalArgumentException tooMany = assertThrows(IllegalArgumentException) {
            NsSuiteQlOrderSupport.applyChangedOrderIds(enrichedLinesSpec(), many)
        }
        assertTrue(tooMany.message.startsWith(NsSuiteQlOrderSupport.CHANGED_READ_REFUSED) && tooMany.message.contains("1001"), tooMany.message)
        assertEquals(1000, ((List) NsSuiteQlOrderSupport.applyChangedOrderIds(enrichedLinesSpec(), many.take(1000)).changedOrderIds).size())
        IllegalArgumentException quote = assertThrows(IllegalArgumentException) {
            NsSuiteQlOrderSupport.applyChangedOrderIds(enrichedLinesSpec(), ["M1' OR '1'='1"])
        }
        assertTrue(quote.message.startsWith(NsSuiteQlOrderSupport.CHANGED_READ_REFUSED), quote.message)
        // a lines query never names the HotWax order by an expression: refused as the query is read, before any read by change
        IllegalArgumentException expression = assertThrows(IllegalArgumentException) {
            enrichedLinesSpec([:], [[recordFieldName: "orderId", sourceColumn: "BUILTIN.DF(custbody_hc_order_id) AS orderId", sequenceNum: 1]])
        }
        assertTrue(expression.message.contains("bare header columns"), expression.message)
    }

    /** DAR-BE-320. The two declarations are checked when the query is read, before anything reaches SuiteQL. */
    @Test
    void theChangeDeclarationsAreCheckedBeforeAnyRead() {
        assertThrows(IllegalArgumentException, { enrichedLinesSpec([omsStatusLineKeyField: null]) },
                "item statuses need each record's item")
        assertThrows(IllegalArgumentException, { enrichedLinesSpec([omsChangedStatusIds: "ORDER_CANCELLED"]) }, "item statuses only")
        assertThrows(IllegalArgumentException, { enrichedLinesSpec([liveLineStates: "OPEN,PENDING"]) }, "the four line states only")
        assertThrows(IllegalArgumentException, { spec([liveLineStates: "OPEN"]) }, "line states are a SALES_LINES matter")
        assertEquals(["ITEM_CANCELLED", "ITEM_COMPLETED"], enrichedLinesSpec([omsChangedStatusIds: " item_cancelled, ITEM_COMPLETED ,"]).omsChangedStatusIds)
        assertEquals([], salesLinesSpec().liveLineStates, "absent: nothing is read every morning")
    }
}
