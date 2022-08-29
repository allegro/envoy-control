package pl.allegro.tech.servicemesh.envoycontrol.config.envoy

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.testcontainers.containers.Network
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isOk
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isRbacAccessLog
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.RandomConfigFile
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtensionBase
import pl.allegro.tech.servicemesh.envoycontrol.config.service.ServiceExtension
import pl.allegro.tech.servicemesh.envoycontrol.logger

class EnvoyExtension(
    private val envoyControl: EnvoyControlExtensionBase,
    private val localService: ServiceExtension<*>? = null,
    private val config: EnvoyConfig = RandomConfigFile
) : BeforeAllCallback, AfterAllCallback, AfterEachCallback {

    companion object {
        val logger by logger()
    }

    val container: EnvoyContainer = EnvoyContainer(
        config,
        { localService?.container()?.ipAddress() ?: "127.0.0.1" },
        envoyControl.app.grpcPort
    ).withNetwork(Network.SHARED)

    val ingressOperations: IngressOperations = IngressOperations(container)
    val egressOperations: EgressOperations = EgressOperations(container)

    override fun beforeAll(context: ExtensionContext) {
        localService?.beforeAll(context)
        envoyControl.beforeAll(context)

        try {
            container.start()
        } catch (e: Exception) {
            logger.error("Logs from failed container: ${container.logs}")
            throw e
        }
    }

    override fun afterAll(context: ExtensionContext) {
        container.stop()
    }

    override fun afterEach(context: ExtensionContext?) {
        container.admin().resetCounters()
    }

    fun waitForReadyServices(vararg serviceNames: String) {
        serviceNames.forEach {
            untilAsserted {
                egressOperations.callService(it).also {
                    assertThat(it).isOk()
                }
            }
        }
    }

    fun waitForAvailableEndpoints(vararg serviceNames: String) {
        val admin = container.admin()
        serviceNames.forEach {
            untilAsserted {
                assertThat(admin.numOfEndpoints(it)).isGreaterThan(0)
            }
        }
    }

    fun waitForNoAvailableEndpoints(vararg serviceNames: String) {
        val admin = container.admin()
        serviceNames.forEach {
            untilAsserted {
                assertThat(admin.numOfEndpoints(it)).isEqualTo(0)
            }
        }
    }

    fun recordRBACLogs() {
        container.logRecorder.recordLogs(::isRbacAccessLog)
    }

    fun stopRecordingRBAC() {
        container.logRecorder.stopRecording()
    }

}
