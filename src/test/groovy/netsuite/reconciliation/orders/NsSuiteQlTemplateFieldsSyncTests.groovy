package netsuite.reconciliation.orders

import darpan.facade.reconciliation.AutomationFacadeSupport
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals

/**
 * DAR-UI-064. darpan offers a SuiteQL query's template fields to the run builder (AutomationFacadeSupport
 * .SUITEQL_TEMPLATE_FIELDS) but cannot import this component, so it keeps its own list. This keeps the two equal: a field
 * the template adds but the builder does not offer cannot be keyed or compared; one offered but never added matches nothing.
 */
class NsSuiteQlTemplateFieldsSyncTests {

    @Test
    void theRunBuilderOffersExactlyTheFieldsTheTemplatesAdd() {
        Set<String> added = (NsSuiteQlOrderSupport.TEMPLATE_RECORD_COLUMNS as Map<String, String>).values() as Set<String>
        Set<String> offered = AutomationFacadeSupport.SUITEQL_TEMPLATE_FIELDS.values().flatten() as Set<String>
        assertEquals(added, offered)
    }
}
