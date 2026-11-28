package netsuite.reconciliation.orders

import darpan.facade.reconciliation.AutomationSourceOptionsSupport
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals

/**
 * DAR-UI-064. darpan offers a SuiteQL query's template fields to the run builder (AutomationSourceOptionsSupport
 * .SUITEQL_TEMPLATE_FIELDS) but cannot import this component, so it keeps its own list. This keeps the two equal: a field
 * the template adds but the builder does not offer cannot be keyed or compared; one offered but never added matches nothing.
 */
class NsSuiteQlTemplateFieldsSyncTests {

    @Test
    void theRunBuilderOffersExactlyTheFieldsTheTemplatesAdd() {
        // DAR-BE-318 (plan 3b-2b): plus the field SALES_LINES derives itself (nsUnkeyedLine), shown like any other (Aditi).
        Set<String> added = ((NsSuiteQlOrderSupport.TEMPLATE_RECORD_COLUMNS as Map<String, String>).values() +
                NsSuiteQlOrderSupport.DERIVED_SALES_LINES_FIELDS) as Set<String>
        Set<String> offered = AutomationSourceOptionsSupport.SUITEQL_TEMPLATE_FIELDS.values().flatten() as Set<String>
        assertEquals(added, offered)
    }
}
