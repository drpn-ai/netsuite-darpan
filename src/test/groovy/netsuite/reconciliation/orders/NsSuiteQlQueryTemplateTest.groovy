package netsuite.reconciliation.orders

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
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

    private static Map<String, Object> spec(Map<String, Object> overrides) {
        Map<String, Object> query = [nsSuiteQlSourceQueryId: "Q1", recordType: "SalesOrd",
                                     fromTable: "transaction", dateColumn: "trandate"] + overrides
        return NsSuiteQlOrderSupport.normalizeSpec(query,
                [[recordFieldName: "tranId", sourceColumn: "tranid", sequenceNum: 1]])
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
                "WHERE l.previousdoc = t.id AND c.type = 'ItemShip'))"), q)
    }

    @Test
    void invoiceExpectationIsItsOwnBranch() {
        String q = NsSuiteQlOrderSupport.buildRecordsQuery(stateSpec(), "2026-08-01", "2026-09-01", "2026-09-29")

        assertTrue(q.contains("t.status IN ('D','G')"), q)
        assertTrue(q.contains("c2.type = 'CustInvc'") || q.contains("'CustInvc'"), q)
    }

    /** A status in no list is not reported. H closes with neither child and that is legitimate. */
    @Test
    void unlistedStatesAreExcludedRatherThanReported() {
        String q = NsSuiteQlOrderSupport.buildRecordsQuery(stateSpec(), "2026-08-01", "2026-09-01", "2026-09-29")

        assertFalse(q.contains("'H'"), q)
    }

    /** Every finding says WHICH contradiction it is; a list of ids is not an actionable report. */
    @Test
    void everyFindingCarriesItsContradiction() {
        String q = NsSuiteQlOrderSupport.buildRecordsQuery(stateSpec(), "2026-08-01", "2026-09-01", "2026-09-29")

        assertTrue(q.contains("AS contradiction"), q)
        assertTrue(q.contains("OVERDUE_NOT_SHIPPED"), q)
        assertTrue(q.contains("NOT_SHIPPED"), q)
        assertTrue(q.contains("NOT_INVOICED"), q)
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
                "WHERE l.previousdoc = t.id AND c.type = 'ItemShip') " +
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
                "JOIN transaction c ON c.id = l.nextdoc WHERE l.previousdoc = t.id AND c.type = 'ItemShip')"), q)
        assertTrue(q.contains("FROM transaction t WHERE t.type = 'SalesOrd'"), q)
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
        assertTrue(q.contains("EXISTS (SELECT 1 FROM nexttransactionlink l " +
                "JOIN transaction c ON c.id = l.nextdoc WHERE l.previousdoc = t.id AND c.type = 'ItemShip')"), q)
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
}
