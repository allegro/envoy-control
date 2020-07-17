package pl.allegro.tech.servicemesh.envoycontrol.config.containers

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.testcontainers.containers.BindMode
import org.testcontainers.containers.GenericContainer as BaseGenericContainer

class LuaTestsContainer : BaseGenericContainer<LuaTestsContainer>("vpanov/lua-busted@$hash") {

    companion object {
        const val hash = "sha256:21676428f30907d4081b5c02cae12e952b6de5bef544643c9eeaf2b416eccb70"
        private val mapper = ObjectMapper()
            .registerModule(KotlinModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    private val LUA_SRC_DIR = "lua"
    private val LUA_SRC_DIR_DEST = "/lua"
    private val LUA_SPEC_DIR = "lua_spec"
    private val LUA_SPEC_DIR_DST = "/lua_spec"

    override fun configure() {
        super.configure()
        withClasspathResourceMapping(LUA_SRC_DIR, LUA_SRC_DIR_DEST, BindMode.READ_ONLY)
        withClasspathResourceMapping(LUA_SPEC_DIR, LUA_SPEC_DIR_DST, BindMode.READ_ONLY)
        withWorkingDirectory(LUA_SPEC_DIR_DST)
        withCommand("sh", "-c", "sleep 180")
    }

    fun runLuaTests(): TestsResults = use {
        start()
        val results = execInContainer("busted", "--output=json", "--lpath=$LUA_SRC_DIR_DEST/?.lua", LUA_SPEC_DIR_DST)
        val output = mapper.readValue(results.stdout, TestsOutput::class.java)
        return TestsResults(stdout = output, stderr = results.stderr, exitCodeSuccess = (results.exitCode == 0))
    }

    data class TestsResults(
        val stdout: TestsOutput,
        val stderr: String,
        val exitCodeSuccess: Boolean
    )

    data class TestsOutput(
        val successes: List<TestResult>,
        val failures: List<TestResult>,
        val errors: List<TestResult>,
        val pendings: List<TestResult>
    )

    data class TestResult(val name: String, val message: String = "")
}
