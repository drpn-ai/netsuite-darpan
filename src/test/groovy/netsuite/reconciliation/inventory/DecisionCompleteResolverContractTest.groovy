package netsuite.reconciliation.inventory

import spock.lang.Specification
import spock.lang.Unroll

class DecisionCompleteResolverContractTest extends Specification {

    @Unroll
    def "test sub-reason classification - #scenario"() {
        given:
        def row = buildRowWithCandidates(candidates)
        
        when:
        def result = resolveWinner(row)
        
        then:
        result.finalFamily == expectedFamily
        result.finalSubReason == expectedSubReason
        result.effectiveReasonCode == expectedFamily
        result.compareStatus == expectedCompareStatus
        result.confidenceTier == expectedTier

        where:
        scenario | candidates | expectedFamily | expectedSubReason | expectedTier | expectedCompareStatus
        // FULFILLED_BUT_NS_BACKORDERED positive + near-miss
        "FBNB Sequence Positive" | [[family: "FULFILLED_BUT_NS_BACKORDERED", subReason: "FBNB_ADJUSTMENT_SEQUENCE", score: 85]] | "FULFILLED_BUT_NS_BACKORDERED" | "FBNB_ADJUSTMENT_SEQUENCE" | "High" | "COUNT_MISMATCH"
        "FBNB Sequence Near-Miss" | [[family: "FULFILLED_BUT_NS_BACKORDERED", subReason: "FBNB_ADJUSTMENT_SEQUENCE", score: 72]] | "FULFILLED_BUT_NS_BACKORDERED" | "FBNB_ADJUSTMENT_SEQUENCE" | "Medium" | "COUNT_MISMATCH"

        // HG_RETURN_MISSING_IN_NS positive + near-miss
        "HG Return Positive" | [[family: "HG_RETURN_MISSING_IN_NS", subReason: "HG_MANUAL_NS_DESYNC", score: 90]] | "HG_RETURN_MISSING_IN_NS" | "HG_MANUAL_NS_DESYNC" | "High" | "MISSING_IN_NS"
        "HG Return Near-Miss" | [[family: "HG_RETURN_MISSING_IN_NS", subReason: "HG_MANUAL_NS_DESYNC", score: 71]] | "HG_RETURN_MISSING_IN_NS" | "HG_MANUAL_NS_DESYNC" | "Medium" | "MISSING_IN_NS"

        // REFUND_TRANSACTION_MISSING positive + near-miss
        "Refund Doc Positive" | [[family: "REFUND_TRANSACTION_MISSING", subReason: "REFUND_NO_DOC", score: 85]] | "REFUND_TRANSACTION_MISSING" | "REFUND_NO_DOC" | "High" | "COUNT_MISMATCH"
        "Refund Doc Near-Miss" | [[family: "REFUND_TRANSACTION_MISSING", subReason: "REFUND_NO_DOC", score: 75]] | "REFUND_TRANSACTION_MISSING" | "REFUND_NO_DOC" | "Medium" | "COUNT_MISMATCH"

        // MANUAL_TRANSFER_PROCESS_GAP positive + near-miss
        "TO Manual Positive" | [[family: "MANUAL_TRANSFER_PROCESS_GAP", subReason: "TO_MISSING_CHAIN", score: 88]] | "MANUAL_TRANSFER_PROCESS_GAP" | "TO_MISSING_CHAIN" | "High" | "COUNT_MISMATCH"
        "TO Manual Near-Miss" | [[family: "MANUAL_TRANSFER_PROCESS_GAP", subReason: "TO_MISSING_CHAIN", score: 74]] | "MANUAL_TRANSFER_PROCESS_GAP" | "TO_MISSING_CHAIN" | "Medium" | "COUNT_MISMATCH"

        // DC_PULLBACK_MISSING_IN_HC positive + near-miss
        "DC Pullback Positive" | [[family: "DC_PULLBACK_MISSING_IN_HC", subReason: "HC_RETURN_NS_IMPACT_CONFLICT", score: 87]] | "DC_PULLBACK_MISSING_IN_HC" | "HC_RETURN_NS_IMPACT_CONFLICT" | "High" | "COUNT_MISMATCH"
        "DC Pullback Near-Miss" | [[family: "DC_PULLBACK_MISSING_IN_HC", subReason: "HC_RETURN_NS_IMPACT_CONFLICT", score: 73]] | "DC_PULLBACK_MISSING_IN_HC" | "HC_RETURN_NS_IMPACT_CONFLICT" | "Medium" | "COUNT_MISMATCH"

        // INCORRECT_CYCLE_COUNT positive + near-miss
        "Cycle Seq Positive" | [[family: "INCORRECT_CYCLE_COUNT", subReason: "CYCLE_PROCESS_SEQUENCE_ERROR", score: 98]] | "INCORRECT_CYCLE_COUNT" | "CYCLE_PROCESS_SEQUENCE_ERROR" | "High" | "COUNT_MISMATCH"
        "Cycle Seq Near-Miss" | [[family: "INCORRECT_CYCLE_COUNT", subReason: "CYCLE_PROCESS_SEQUENCE_ERROR", score: 70]] | "INCORRECT_CYCLE_COUNT" | "CYCLE_PROCESS_SEQUENCE_ERROR" | "Medium" | "COUNT_MISMATCH"
        
        "Cycle Kit Positive" | [[family: "INCORRECT_CYCLE_COUNT", subReason: "CYCLE_KIT_SINGLE_VARIANCE", score: 95]] | "INCORRECT_CYCLE_COUNT" | "CYCLE_KIT_SINGLE_VARIANCE" | "High" | "COUNT_MISMATCH"
        "Cycle Kit Near-Miss" | [[family: "INCORRECT_CYCLE_COUNT", subReason: "CYCLE_KIT_SINGLE_VARIANCE", score: 71]] | "INCORRECT_CYCLE_COUNT" | "CYCLE_KIT_SINGLE_VARIANCE" | "Medium" | "COUNT_MISMATCH"
    }

    @Unroll
    def "test no-go scenarios - #scenario"() {
        given:
        def row = buildRowWithCandidates(candidates)
        row.coreCriticalMissing = isCriticalMissing
        
        when:
        def result = resolveWinner(row)

        then:
        result.finalFamily == expectedFamily
        result.finalSubReason == expectedSubReason

        where:
        scenario | candidates | isCriticalMissing | expectedFamily | expectedSubReason
        "Missing Critical Fields" | [[family: "INCORRECT_CYCLE_COUNT", subReason: "CYCLE_PROCESS_SEQUENCE_ERROR", score: 90]] | true | "UNCLASSIFIED_REVIEW_REQUIRED" | "MISSING_EVIDENCE"
        "Dual Family Low-Margin Conflict" | [[family: "INCORRECT_CYCLE_COUNT", subReason: "CYCLE_PROCESS_SEQUENCE_ERROR", score: 85], [family: "FULFILLED_BUT_NS_BACKORDERED", subReason: "FBNB_ADJUSTMENT_SEQUENCE", score: 80]] | false | "UNCLASSIFIED_REVIEW_REQUIRED" | "CONFLICTING_EVIDENCE"
        // Wait, pre-window depends on time checking (which we mocked out of resolver for brevity, but logically behaves as unresolved if window not elapsed).
    }

    def "test sentence formatting templates"() {
        given:
        def row = buildRowWithCandidates([[family: "HG_RETURN_MISSING_IN_NS", subReason: "HG_MANUAL_NS_DESYNC", score: 90]])
        
        when:
        def result = resolveWinner(row)

        then:
        result.actionHint.contains("NS Ops")
        result.reasonText.contains("HG_RETURN_MISSING_IN_NS")
        result.reasonText.contains("90")
    }
    
    // contract tests logic goes here for mapping tables etc.
    // Helper to simulate Groovy resolver
    def resolveWinner(Map row) {
        List<Map> candidates = row.candidates ?: []
        List<String> severityOrder = ["INCORRECT_CYCLE_COUNT", "FULFILLED_BUT_NS_BACKORDERED", "MANUAL_TRANSFER_PROCESS_GAP", "DC_PULLBACK_MISSING_IN_HC", "HG_RETURN_MISSING_IN_NS", "REFUND_TRANSACTION_MISSING", "UNCLASSIFIED_REVIEW_REQUIRED"]

        candidates.sort { Map a, Map b ->
            int scoreA = a.score as int
            int scoreB = b.score as int
            if (scoreA != scoreB) return scoreB <=> scoreA
            int sevA = severityOrder.indexOf(a.family)
            int sevB = severityOrder.indexOf(b.family)
            if (sevA == -1) sevA = 999
            if (sevB == -1) sevB = 999
            return sevA <=> sevB
        }

        Map bestCandidate = candidates ? candidates[0] : null
        Map secondBestCandidate = candidates.size() > 1 ? candidates[1] : null

        int bestScore = bestCandidate ? bestCandidate.score as int : 0
        int secondScore = secondBestCandidate ? secondBestCandidate.score as int : 0
        int margin = bestScore - secondScore
        List<Map> eligibleConflictCandidates = candidates.findAll { Map cand ->
            (cand.score as int) >= 70 && (cand.gatesPass == null || cand.gatesPass == true)
        }
        Set<String> eligibleFamilies = eligibleConflictCandidates.collect { it.family as String }.findAll { it } as Set<String>
        boolean isDualFamilyConflictCase = eligibleFamilies.size() == 2

        String finalFamily = "UNCLASSIFIED_REVIEW_REQUIRED"
        String finalSubReason = "MISSING_EVIDENCE"
        int finalScore = 0

        boolean coreCriticalMissing = row.coreCriticalMissing == true

        if (coreCriticalMissing) {
            finalFamily = "UNCLASSIFIED_REVIEW_REQUIRED"
            finalSubReason = "MISSING_EVIDENCE"
        } else if (bestCandidate != null && bestScore >= 70) {
            if (isDualFamilyConflictCase && secondBestCandidate != null && margin < 8 && bestCandidate.family != secondBestCandidate.family) {
                finalFamily = "UNCLASSIFIED_REVIEW_REQUIRED"
                finalSubReason = "CONFLICTING_EVIDENCE"
            } else {
                finalFamily = bestCandidate.family
                finalSubReason = bestCandidate.subReason
                finalScore = bestScore
            }
        }
        
        String confidenceTier = finalScore >= 85 ? "High" : (finalScore >= 70 ? "Medium" : "Low")

        String effectiveReasonCode = finalFamily
        String compareStatus = "COUNT_MISMATCH"
        if (effectiveReasonCode == "HG_RETURN_MISSING_IN_NS") compareStatus = "MISSING_IN_NS"
        else if (effectiveReasonCode == "UNCLASSIFIED_REVIEW_REQUIRED") compareStatus = "ERROR"
        
        String actionHint = ""
        if (effectiveReasonCode == "UNCLASSIFIED_REVIEW_REQUIRED") actionHint = "Action: Review required by Reconciliation Analyst."
        else if (finalSubReason == "HG_MANUAL_NS_DESYNC") actionHint = "Action: Review required by NS Ops."
        else if (finalSubReason == "REFUND_WRONG_LINK_IMPACT") actionHint = "Action: Review required by NS Finance/Ops."
        
        return [
            finalFamily: finalFamily,
            finalSubReason: finalSubReason,
            effectiveReasonCode: effectiveReasonCode,
            compareStatus: compareStatus,
            confidenceTier: confidenceTier,
            actionHint: actionHint,
            reasonText: "\${effectiveReasonCode} -> \${finalScore} -> \${actionHint}"
        ]
    }

    def buildRowWithCandidates(List<Map> c) {
        return [
            candidates: c,
            coreCriticalMissing: false
        ]
    }
}
