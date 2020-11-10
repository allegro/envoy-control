package pl.allegro.tech.servicemesh.envoycontrol

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.ObjectAssert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.boot.actuate.health.Status
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.Health
import pl.allegro.tech.servicemesh.envoycontrol.services.ServicesState

class HealthIndicatorTest {

    companion object {

        @JvmField
        @RegisterExtension
        val consul = ConsulExtension()

        @JvmField
        @RegisterExtension
        val envoyControl = EnvoyControlExtension(consul, mapOf("management.endpoint.health.show-details" to "ALWAYS"))
    }

    @Test
    fun `should application state be healthy after state of applications is loaded from consul`() {
        // when
        untilAsserted {
            assertThat(envoyControl.app.getState()).hasServiceStateChanged()
        }

        // then
        val healthStatus = envoyControl.app.getHealthStatus()
        assertThat(healthStatus).isStatusHealthy().hasEnvoyControlCheckPassed()
    }

    fun ObjectAssert<ServicesState>.hasServiceStateChanged(): ObjectAssert<ServicesState> {
        matches { it.serviceNames().isNotEmpty() }
        return this
    }

    fun ObjectAssert<Health>.isStatusHealthy(): ObjectAssert<Health> {
        matches { it.status == Status.UP }
        return this
    }

    fun ObjectAssert<Health>.hasEnvoyControlCheckPassed(): ObjectAssert<Health> {
        matches { it.components.get("envoyControl")?.status == Status.UP }
        return this
    }
}
