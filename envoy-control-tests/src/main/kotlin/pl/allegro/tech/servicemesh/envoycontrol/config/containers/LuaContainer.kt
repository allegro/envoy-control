package pl.allegro.tech.servicemesh.envoycontrol.config.containers

import org.testcontainers.containers.BindMode
import org.testcontainers.containers.GenericContainer as BaseGenericContainer

class LuaContainer : BaseGenericContainer<LuaContainer>("luatest:latest") {
    private val TEST_SCRIPT = "testcontainers/handler_test.lua"
    private val TEST_SCRIPT_DEST = "/lua/handler_test.lua"

    private val SCRIPT = "filters/ingress_handler.lua"
    private val SCRIPT_DEST = "/lua/ingress_handler.lua"
    override fun configure() {
            super.configure()
            withClasspathResourceMapping(TEST_SCRIPT, TEST_SCRIPT_DEST, BindMode.READ_ONLY)
        // TODO (awawrzyniak) use resource from envoy-control-core module
            withClasspathResourceMapping(SCRIPT, SCRIPT_DEST, BindMode.READ_ONLY)
            withWorkingDirectory("/lua")
            withCommand("prove", "-v", "--color", "handler_test.lua")
        }
    }
