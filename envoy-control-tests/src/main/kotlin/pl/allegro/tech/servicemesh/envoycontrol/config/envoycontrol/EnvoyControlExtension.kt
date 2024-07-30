package pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol

import org.assertj.core.api.Assertions
import org.awaitility.Awaitility
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.logger
import java.util.concurrent.TimeUnit

interface EnvoyControlExtensionBase : BeforeAllCallback, AfterAllCallback {
     val app: EnvoyControlTestApp
}

class EnvoyControlExtension(private val consul: ConsulExtension, override val app: EnvoyControlTestApp)
    : EnvoyControlExtensionBase {

    private var started = false
    private val logger by logger()

    constructor(consul: ConsulExtension, properties: Map<String, Any> = mapOf())
        : this(consul, EnvoyControlRunnerTestApp(
                    propertiesProvider = { properties },
                    consulPort = consul.server.port
        ))

    override fun beforeAll(context: ExtensionContext) {
        if (started) {
            return
        }

        consul.beforeAll(context)
        logger.info("Envoy control extension is starting.")
        app.run()
        waitUntilHealthy()
        started = true
        logger.info("Envoy control extension started.")
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
