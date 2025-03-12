package pl.allegro.tech.servicemesh.envoycontrol.config.envoy

import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.testcontainers.containers.Network
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isOk
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isRbacAccessLog
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyConfig
import pl.allegro.tech.servicemesh.envoycontrol.config.RandomConfigFile
import pl.allegro.tech.servicemesh.envoycontrol.config.envoycontrol.EnvoyControlExtensionBase
import pl.allegro.tech.servicemesh.envoycontrol.config.service.ServiceExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.sharing.BeforeAndAfterAllOnce
import pl.allegro.tech.servicemesh.envoycontrol.config.sharing.ContainerExtension
import pl.allegro.tech.servicemesh.envoycontrol.logger
import java.time.Duration
import java.util.concurrent.TimeUnit

class EnvoyExtension(
    private val envoyControl: EnvoyControlExtensionBase,
    private val localService: ServiceExtension<*>? = null,
    config: EnvoyConfig = RandomConfigFile,
    private val wrapperService: ServiceExtension<*>? = null
) : ContainerExtension() , AfterEachCallback {

    companion object {
        val logger by logger()
    }

    override val container: EnvoyContainer = EnvoyContainer(
        config,
        { localService?.container()?.ipAddress() ?: "127.0.0.1" },
        envoyControl.app.grpcPort,
        wrapperServiceIp = { wrapperService?.container()?.ipAddress() ?: "127.0.0.1" },
    ).withNetwork(Network.SHARED)

    val ingressOperations: IngressOperations = IngressOperations(container)
    val egressOperations: EgressOperations = EgressOperations(container)

    override fun preconditions(context: ExtensionContext) {
        localService?.beforeAll(context)
        wrapperService?.beforeAll(context)
        envoyControl.beforeAll(context)
    }

    override fun waitUntilHealthy() {
        Awaitility.await().atMost(1, TimeUnit.MINUTES).untilAsserted {
            assertThat(container.admin().isIngressReady())
        }
    }

    override fun afterAllOnce(context: ExtensionContext) {
        container.stop()
    }

    override val ctx: BeforeAndAfterAllOnce.Context = BeforeAndAfterAllOnce.Context()

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

    fun waitForClusterEndpointHealthy(
        cluster: String,
        endpointIp: String
    ) {
        untilAsserted(wait = Duration.ofSeconds(5)) {
            assertThat(container.admin().isEndpointHealthy(cluster, endpointIp))
                .withFailMessage {
                    "Expected to see healthy endpoint of cluster '$cluster' with address " +
                        "'$endpointIp' in envoy ${container.adminUrl()}/clusters, " +
                        "but it's not present. Found following endpoints: " +
                        "${container.admin().endpointsAddress(cluster)}"
                }
                .isTrue()
        }
    }

    fun waitForClusterEndpointNotHealthy(
        cluster: String,
        endpointIp: String
    ) {
        untilAsserted(wait = Duration.ofSeconds(5)) {
            assertThat(container.admin().isEndpointHealthy(cluster, endpointIp))
                .withFailMessage {
                    "Expected to not see endpoint of cluster '$cluster' with address " +
                        "'$endpointIp' in envoy ${container.adminUrl()}/clusters, " +
                        "but it's still present. Found following endpoints: " +
                        "${container.admin().endpointsAddress(cluster)}"
                }
                .isFalse()
        }
    }

    fun recordRBACLogs() {
        container.logRecorder.recordLogs(::isRbacAccessLog)
    }

    fun stopRecordingRBAC() {
        container.logRecorder.stopRecording()
    }
}
