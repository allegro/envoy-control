package pl.allegro.tech.servicemesh.envoycontrol.config

import org.assertj.core.api.Assertions
import org.awaitility.Awaitility
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import java.util.concurrent.TimeUnit

class EnvoyControlExtension(val consul: ConsulExtension, properties: Map<String, Any> = mapOf())
    : BeforeAllCallback, AfterAllCallback {

    val app: EnvoyControlTestApp = EnvoyControlRunnerTestApp(
            properties = properties,
            consulPort = consul.server.port
    )

    override fun beforeAll(context: ExtensionContext) {
        if (!consul.started) {
            consul.beforeAll(context)
        }
        app.run()
        waitUntilHealthy()
    }

    private fun waitUntilHealthy() {
        Awaitility.await().atMost(30, TimeUnit.SECONDS).untilAsserted {
            Assertions.assertThat(app.isHealthy()).isTrue()
        }
    }

    override fun afterAll(context: ExtensionContext) {
        app.stop()
    }

}