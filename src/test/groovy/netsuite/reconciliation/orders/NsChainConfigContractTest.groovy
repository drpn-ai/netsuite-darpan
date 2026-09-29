package netsuite.reconciliation.orders

import org.junit.jupiter.api.Test

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * Guards the ONE property of the gorjana config file that no other test can see: that it is not
 * seed data.
 *
 * Moqui skips an entity-facade-xml file whose declared type is absent from the loader's -Ptypes set
 * (EntityDataLoaderImpl.startElement -> TypeToSkipException). That cuts both ways, and both edges
 * have already drawn blood on this file:
 *
 *   type="seed"  meant a deploy's seed load would have taken one tenant's rows into EVERY
 *                environment, had the file ever been moved into a component data/ directory.
 *   -Ptypes=none in the documented recipe meant the intended load skipped every row and reported
 *                success, because "none" does not contain "seed".
 *
 * The file lives outside any component, so nothing compiles or loads it in CI — this test is the
 * only thing standing between a careless edit and a silent reintroduction.
 */
class NsChainConfigContractTest {

    private static String configXml() {
        Path p = repoRoot().resolve("tools/netsuite-chain-config/gorjana-chain-pairs.xml")
        assertTrue(Files.exists(p), "gorjana config not found at ${p}")
        return Files.readString(p)
    }

    /**
     * The header is a long comment that quotes the very strings these tests look for — an example
     * companyUserGroupId, and the -Ptypes=none recipe it warns against. Counting raw text made both
     * assertions measure the prose instead of the rows, which is how a green contract test ends up
     * guarding nothing.
     */
    private static String rowsOnly() {
        return configXml().replaceAll(/(?s)<!--.*?-->/, "")
    }

    @Test
    void configIsDeclaredClientConfigNotSeed() {
        String xml = rowsOnly()

        assertTrue(xml.contains('<entity-facade-xml type="client-config">'), "config must declare client-config")
        assertFalse(xml.contains('<entity-facade-xml type="seed">'), "config must not be seed data")
    }

    /** The documented recipe has to name the same type, or the load is a silent no-op. */
    @Test
    void documentedLoadRecipeMatchesTheDeclaredType() {
        List<String> recipes = configXml().readLines().findAll { it.contains("gradlew load") }

        assertFalse(recipes.isEmpty(), "the file must document how to load it")
        recipes.each { String line ->
            assertTrue(line.contains("-Ptypes=client-config"),
                    "every documented load must pass the declared type, got: ${line.trim()}")
        }
    }

    /**
     * Every row names the tenant. A row without companyUserGroupId is not scoped to gorjana, and a
     * mis-scoped NsSuiteQlSourceQuery is a credentialed query another tenant can run.
     */
    @Test
    void everyQueryRowIsTenantScoped() {
        String xml = rowsOnly()

        int queries = xml.count('<NsSuiteQlSourceQuery ')
        int scoped = xml.count('companyUserGroupId="GORJANA"')
        assertTrue(queries > 0, "no query rows found")
        assertEquals(queries, scoped, "every NsSuiteQlSourceQuery row must name its tenant")
        // Guards the guard: if the comment stripper ever stops working, `queries` picks up nothing
        // extra but `scoped` would, so a mismatch is the signal rather than a silent pass.
    }

    private static Path repoRoot() {
        Path p = Paths.get("").toAbsolutePath()
        while (p != null) {
            if (Files.exists(p.resolve("tools/netsuite-chain-config"))) return p
            p = p.parent
        }
        throw new IllegalStateException("no repo root above ${Paths.get('').toAbsolutePath()}")
    }
}
