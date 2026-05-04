package netsuite.reconciliation.inventory

import spock.lang.Specification
import org.moqui.Moqui
import org.moqui.context.ExecutionContext

class ReconciliationExecutionTest extends Specification {
    def "run full inventory reconciliation with persisted data"() {
        setup:
        ExecutionContext ec = Moqui.getExecutionContext()
        ec.user.loginUser("john.doe", "moqui")
        ec.artifactExecution.disableAuthz()

        when:
        ec.entity.makeDataLoader().location("component://netsuite-darpan/data/NetsuiteReconRulesData.xml").load()

        def params = [
            referenceFileLocation: "component://netsuite-darpan/data/sample/janShopifyData.json",
            referenceFileType: "JSON",
            omsDetailFileLocation: "runtime://tmp/reconciliation/inventory/input/oms-iid-merged-dedup.csv",
            omsDetailHasHeader: true,
            omsDetailFileType: "CSV",
            discrepancyNsItemIdField: "netsuite_product_id",
            discrepancyLocationIdField: "facility_id",
            omsItemIdField: "netsuite_product_id",
            omsLocationIdField: "facility_id",
            omsTxnDateField: "EFFECTIVE_DATE",
            omsQuantityField: "QUANTITY_ON_HAND_DIFF",
            from: "2026-02-22",
            to: "2026-03-17",
            nsRestletConfigId: "Gorjana_Prod_IID",
            persistedNsOutputLocation: "runtime://tmp/reconciliation/inventory/retrieval/ns-inventory-adjustments-live-newrestlet-v2-20260319-043633.json",
            fetchMissingNsPairs: false,
            comparisonRuleSetId: "INVENTORY_RECON_TARGET_RS"
        ]

        def overrides = "runtime://tmp/reconciliation/inventory/input/discrepancy-superset-clean.json"
        if (new File(ec.factory.runtimePath + "/tmp/reconciliation/inventory/input/discrepancy-superset-clean.json").exists()) {
            params.referenceFileLocation = overrides
        }

        def result = ec.service.sync().name("reconciliation.ReconciliationInventoryServices.retrieve#InventoryAdjustmentsByReference")
                .parameters(params)
                .call()

        println "==================== RECONCILIATION RESULT ===================="
        println "Summary Writer: \${result.summaryLocation}"
        println "Processed Pairs: \${result.processedItemCount}"
        println "Explained Items: \${result.explainedItemCount}"
        println "Unexplained Items: \${result.unexplainedItemCount}"
        println "Missing in NS: \${result.missingInNsCount}"
        println "Count Mismatch: \${result.countMismatchCount}"
        println "Detailed Reason Counts: \${result.reasonCounts}"
        println "==============================================================="

        then:
        result.summaryLocation != null
    }
}
