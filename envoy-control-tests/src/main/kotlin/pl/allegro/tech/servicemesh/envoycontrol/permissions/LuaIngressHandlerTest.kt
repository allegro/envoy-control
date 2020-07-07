package pl.allegro.tech.servicemesh.envoycontrol.permissions

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration.Companion.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.containers.LuaContainer
import pl.allegro.tech.servicemesh.envoycontrol.logger
import java.time.Duration

internal class LuaIngressHandlerTest {
    companion object {

        private val logger by logger()
        private val container = LuaContainer().withStartupTimeout(Duration.ofMinutes(2))

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
            val logs = container.getLogs()
            logger.info("\n\n" + logs)
            assertThat(logs.contains("Result: FASILED"))
        }
    }
}
