package pl.allegro.tech.servicemesh.envoycontrol

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.fail
import pl.allegro.tech.servicemesh.envoycontrol.config.containers.LuaTestsContainer

/**
 * TODO(https://github.com/allegro/envoy-control/issues/140): upgrade  to Gradle 6.5.* to see Lua test names in the output
 */
internal class LuaTest {

    val logger by logger()

    @TestFactory
    fun luaTests(): List<DynamicTest> {
        val luaContainer = LuaTestsContainer()

        val results = luaContainer.runLuaTests()
        val output = results.stdout

        val passedTests = output.successes.map { passedTest(it.name) }
        val failedTests = (output.failures + output.errors + output.pendings).map { failedTest(it.message, it.name) }

        val additionalErrors = mutableListOf<DynamicTest>()
        if (failedTests.isEmpty() && !results.exitCodeSuccess) {
            additionalErrors.add(failedTest("Lua tests failed"))
        }
        if (passedTests.isEmpty() && failedTests.isEmpty()) {
            additionalErrors.add(failedTest("no tests executed"))
        }

        logger.info("Lua tests stderr output:\n${results.stderr}")

        return passedTests + failedTests + additionalErrors
    }

    private fun passedTest(name: String) = dynamicTest(name) {}
    private fun failedTest(message: String, name: String = "") = dynamicTest(
        name.ifEmpty { "LuaTest" }
    ) { fail("$name:\n$message") }
}
