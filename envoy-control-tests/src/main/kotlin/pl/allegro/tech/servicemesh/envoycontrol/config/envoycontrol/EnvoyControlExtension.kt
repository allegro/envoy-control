package pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol

import org.assertj.core.api.Assertions
import org.awaitility.Awaitility
import org.junit.jupiter.api.extension.ExtensionContext
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.sharing.BeforeAndAfterAllOnce
import pl.allegro.tech.servicemesh.envoycontrol.logger
import java.util.concurrent.TimeUnit

interface EnvoyControlExtensionBase : BeforeAndAfterAllOnce {
     val app: EnvoyControlTestApp
}

class EnvoyControlExtension(private val consul: ConsulExtension, override val app: EnvoyControlTestApp)
    : EnvoyControlExtensionBase {

    private val logger by logger()

    constructor(consul: ConsulExtension, properties: Map<String, Any> = mapOf())
        : this(consul, EnvoyControlRunnerTestApp(
                    propertiesProvider = { properties },
                    consulPort = consul.server.port
        ))

    override fun beforeAllOnce(context: ExtensionContext) {
        consul.beforeAll(context)
        logger.info("Envoy control extension is starting.")
        app.run()
        waitUntilHealthy()
        logger.info("Envoy control extension started.")
    }

    private fun waitUntilHealthy() {
        Awaitility.await().atMost(30, TimeUnit.SECONDS).untilAsserted {
            Assertions.assertThat(app.isHealthy()).isTrue()
        }
    }

    override fun afterAllOnce(context: ExtensionContext) {
        app.stop()
    }

    override val ctx: BeforeAndAfterAllOnce.Context = BeforeAndAfterAllOnce.Context()
}
