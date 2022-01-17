package pl.allegro.tech.servicemesh.envoycontrol.config.containers

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.testcontainers.containers.BindMode
import org.testcontainers.containers.GenericContainer as BaseGenericContainer

open class LuaTestsContainer : BaseGenericContainer<LuaTestsContainer>("vpanov/lua-busted@$hash") {

    companion object {
        const val hash = "sha256:21676428f30907d4081b5c02cae12e952b6de5bef544643c9eeaf2b416eccb70"
        private val mapper = ObjectMapper()
            .registerModule(KotlinModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    open val LUA_SRC_DIR = "lua"
    open val LUA_SRC_DIR_DEST = "/lua"
    open val LUA_SPEC_DIR = "lua_spec"
    open val LUA_SPEC_DIR_DST = "/lua_spec"

    override fun configure() {
        super.configure()
        withClasspathResourceMapping(LUA_SRC_DIR, LUA_SRC_DIR_DEST, BindMode.READ_ONLY)
        withClasspathResourceMapping(LUA_SPEC_DIR, LUA_SPEC_DIR_DST, BindMode.READ_ONLY)
        withWorkingDirectory(LUA_SPEC_DIR_DST)
        withCommand("sh", "-c", "sleep 180")
    }

    fun runLuaTests(): TestsResults = use {
        start()
        // if busted returns a non-zero code you can try to remove --outout=json (sometimes it hides the error),
        // set a breakpoint after this line and see what's in stdout / stderr
        val results = execInContainer("busted", "--output=json", "--lpath=$LUA_SRC_DIR_DEST/?.lua", LUA_SPEC_DIR_DST)
        // with testcontainers 1.15.0-rc2 combined with Docker 19.03.13 and MacOS 10.15.6
        // newlines started to appear in the output; as we expect JSON to be parsed by ObjectMapper,
        // we eliminate those newlines inserted by org.testcontainers.containers.output.ToStringConsumer
        val stdout = results.stdout.replace("\n", "")
        val output = kotlin.runCatching { mapper.readValue(stdout, TestsOutput::class.java) }
            .getOrElse { error -> invalidTestOutput(stdout, error) }
        return TestsResults(stdout = output, stderr = results.stderr, exitCodeSuccess = (results.exitCode == 0))
    }

    data class TestsResults(
        val stdout: TestsOutput,
        val stderr: String,
        val exitCodeSuccess: Boolean
    )

    data class TestsOutput(
        val successes: List<TestResult> = emptyList(),
        val failures: List<TestResult> = emptyList(),
        val errors: List<TestResult> = emptyList(),
        val pendings: List<TestResult> = emptyList()
    )

    private fun invalidTestOutput(output: String, error: Throwable) = TestsOutput(errors = listOf(TestResult(
        name = "LuaTests",
        message = "Invalid test output:\n$output\nerror:\n$error"
    )))

    data class TestResult(val name: String, val message: String = "")
}
