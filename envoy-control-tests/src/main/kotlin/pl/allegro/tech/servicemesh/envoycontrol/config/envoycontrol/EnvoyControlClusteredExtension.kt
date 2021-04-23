package pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol

import org.assertj.core.api.Assertions
import org.awaitility.Awaitility
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulClusterSetup
import java.util.*
import java.util.concurrent.TimeUnit

class EnvoyControlClusteredExtension(
    private val consul: ConsulClusterSetup,
    override val app: EnvoyControlTestApp,
    private val dependencies: List<ExtensionDependency> = listOf()
) :
    EnvoyControlExtensionBase {

    private var started = false

    constructor(
        consul: ConsulClusterSetup,
        propertiesProvider: () -> Map<String, Any> = { mapOf() },
        dependencies: List<ExtensionDependency> = listOf()
    ) : this(
        consul,
        EnvoyControlRunnerTestApp(propertiesProvider = propertiesProvider, consulPort = consul.port),
        dependencies
    )

    override fun beforeAll(context: ExtensionContext) {
        if (started) {
            return
        }


        dependencies.forEach { it.beforeAll(context) }
        app.run()
        waitUntilHealthy()
        val id = UUID.randomUUID().toString()
        consul.operations.registerService(id, app.appName, "localhost", app.appPort)
        started = true
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

typealias  ExtensionDependency = BeforeAllCallback
