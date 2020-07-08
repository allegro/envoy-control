package pl.allegro.tech.servicemesh.envoycontrol.permissions

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration.Companion.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.containers.LuaContainer
import pl.allegro.tech.servicemesh.envoycontrol.logger
import java.time.Duration

// TODO(mfalkowski): I think it should be an unit test (unless it's hard to move it there)
// TODO(mfalkowski): I reveive an error launching this test:
//    "pull access denied for luatest, repository does not exist or may require 'docker login': denied: requested access to the resource is denied"
internal class LuaIngressHandlerTest {
    companion object {
        private val logger by logger()
        private val container = LuaContainer()
                .withStartupTimeout(Duration.ofMinutes(1))

        @JvmStatic
        @BeforeAll
        fun setupTest() {
            container.start()
        }

        @JvmStatic
        @AfterAll
        fun tearDownTest() {
            container.stop()
        }
    }

    @Test
    fun `should execute ingress handler tests`() {
        untilAsserted {
            container.logs.isNotEmpty()
        }
        logger.info("\n\n%s".format(container.logs))
        assertThat(container.logs).contains("Result: PASS")
    }
}
