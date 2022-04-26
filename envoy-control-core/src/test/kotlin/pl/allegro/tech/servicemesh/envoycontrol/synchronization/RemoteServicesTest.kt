package pl.allegro.tech.servicemesh.envoycontrol.synchronization

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.services.MultiClusterState
import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceInstance
import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceInstances
import pl.allegro.tech.servicemesh.envoycontrol.services.ServicesState
import reactor.test.StepVerifier
import java.net.URI
import java.time.Duration
import java.util.concurrent.CompletableFuture

class RemoteServicesTest {
    @Test
    fun `should collect responses from all clusters`() {
        val controlPlaneClient = FakeAsyncControlPlane()
        controlPlaneClient.forCluster("dc1") {
            state(ServiceState(service = "service-1"))
        }
        controlPlaneClient.forCluster("dc2") {
            state(ServiceState(service = "service-1"))
        }
        val service = RemoteServices(controlPlaneClient, SimpleMeterRegistry(), fetcher(), listOf("dc1", "dc2"))

        val result = service
            .getChanges(1)
            .blockFirst()
            ?: MultiClusterState.empty()

        assertThat(result).hasSize(2)
        assertThat(result.singleOrNull { it.cluster == "dc1" }?.servicesState?.serviceNames()).containsExactly("service-1")
        assertThat(result.singleOrNull { it.cluster == "dc2" }?.servicesState?.serviceNames()).containsExactly("service-1")
    }

    @Test
    fun `should ignore cluster without service instances`() {
        val controlPlaneClient = FakeAsyncControlPlane()
        controlPlaneClient.forCluster("dc1") {
            state(ServiceState(service = "service-1"))
        }
        controlPlaneClient.forCluster("dc2") {
            state(ServiceState(service = "service-1"))
        }
        val service =
            RemoteServices(
                controlPlaneClient,
                SimpleMeterRegistry(),
                fetcher(clusterWithNoInstance = listOf("dc2")),
                listOf("dc1", "dc2")
            )

        val result = service
            .getChanges(1)
            .blockFirst()
            ?: MultiClusterState.empty()

        assertThat(result).hasSize(1)
        assertThat(result.singleOrNull { it.cluster == "dc1" }?.servicesState?.serviceNames()).containsExactly("service-1")
    }

    @Test
    fun `should ignore services without instances`() {

        val controlPlaneClient = FakeAsyncControlPlane()
        controlPlaneClient.forCluster("dc1") {
            state(ServiceState(service = "service-1"))
        }
        controlPlaneClient.forCluster("dc2") {
            state(ServiceState(service = "service-c", withoutInstances = true))
        }
        val service = RemoteServices(controlPlaneClient, SimpleMeterRegistry(), fetcher(), listOf("dc1", "dc2"))

        val result = service
            .getChanges(1)
            .blockFirst()
            ?: MultiClusterState.empty()

        assertThat(result).hasSize(2)
        assertThat(result.singleOrNull { it.cluster == "dc1" }?.servicesState?.serviceNames()).containsExactly("service-1")
        result.doesntHaveServiceWithEmptyInstances("dc2", "service-c")
    }

    @Test
    fun `should skip failing responses if not cached`() {

        val controlPlaneClient = FakeAsyncControlPlane()
        controlPlaneClient.forCluster("dc1") {
            state(ServiceState(service = "dc1"))
        }
        controlPlaneClient.forCluster("dc2") {
            stateError()
        }
        controlPlaneClient.forCluster("dc3") {
            state(ServiceState(service = "dc3"))
        }
        controlPlaneClient.forCluster("dc4") {
            stateError()
        }

        val service = RemoteServices(
            controlPlaneClient,
            SimpleMeterRegistry(),
            fetcher(),
            listOf("dc1", "dc2", "dc3", "dc4")
        )

        val result = service
            .getChanges(1)
            .blockFirst()
            ?: MultiClusterState.empty()

        assertThat(result).hasSize(2)
        assertThat(result.singleOrNull { it.cluster == "dc1" }?.servicesState?.serviceNames()).containsExactly("dc1")
        assertThat(result.singleOrNull { it.cluster == "dc3" }?.servicesState?.serviceNames()).containsExactly("dc3")
    }

    @Test
    fun `should serve cached responses when a cross cluster request fails`() {
        // given
        val controlPlaneClient = FakeAsyncControlPlane()
        controlPlaneClient.forClusterWithChangableState("dc1") {
            state(ServiceState(service = "service-a"))
            state(ServiceState(service = "service-b"))
        }
        controlPlaneClient.forClusterWithChangableState("dc2") {
            state(ServiceState(service = "service-c"))
            stateError()
        }
        val service = RemoteServices(controlPlaneClient, SimpleMeterRegistry(), fetcher(), listOf("dc1", "dc2"))

        val successfulResult = service
            .getChanges(1)
            .blockFirst()
            ?: MultiClusterState.empty()

        assertThat(successfulResult.singleOrNull { it.cluster == "dc1" }?.servicesState?.serviceNames())
            .containsExactly("service-a")
        assertThat(successfulResult.singleOrNull { it.cluster == "dc2" }?.servicesState?.serviceNames())
            .containsExactly("service-c")

        val oneInstanceFailing = service
            .getChanges(1)
            .blockFirst()
            ?: MultiClusterState.empty()

        assertThat(oneInstanceFailing.singleOrNull { it.cluster == "dc1" }?.servicesState?.serviceNames())
            .containsExactly("service-b")
        assertThat(oneInstanceFailing.singleOrNull { it.cluster == "dc2" }?.servicesState?.serviceNames())
            .containsExactly("service-c")
    }

    @Test
    fun `should not emit a value when all requests fail`() {
        // given

        val controlPlaneClient = FakeAsyncControlPlane()
        controlPlaneClient.forCluster("dc2") {
            stateError()
        }

        val service = RemoteServices(controlPlaneClient, SimpleMeterRegistry(), fetcher(), listOf("dc2"))
        val duration = 1L

        StepVerifier.create(
            service
                // when
                .getChanges(duration)
        )
            // then
            .expectSubscription()
            .expectNoEvent(Duration.ofSeconds(duration * 2))
            .thenCancel()
            .verify()
    }

    class FakeControlPlaneInstanceFetcher(private val clusterWithNoInstance: List<String>) :
        ControlPlaneInstanceFetcher {
        override fun instances(cluster: String): List<URI> {
            val uri = URI.create("http://$cluster")
            if (clusterWithNoInstance.contains(cluster)) {
                return emptyList()
            }
            return listOf(uri)
        }
    }

    private fun fetcher(clusterWithNoInstance: List<String> = emptyList()): ControlPlaneInstanceFetcher {
        return FakeControlPlaneInstanceFetcher(clusterWithNoInstance)
    }

    private fun MultiClusterState.doesntHaveServiceWithEmptyInstances(
        cluster: String,
        serviceName: String
    ): MultiClusterState {
        val clusterState = this.first { it.cluster == cluster }
        assertThat(clusterState).isNotNull
        assertThat(clusterState.servicesState.serviceNames()).doesNotContain(serviceName)
        return this
    }

    data class ServiceState(val service: String, val withoutInstances: Boolean = false)
    class FakeAsyncControlPlane : ControlPlaneClient {
        class ClusterScope(private val clusterName: String) {
            var responses = mutableListOf<WrappedServiceState>()

            fun state(vararg services: ServiceState) {
                responses.add {
                    ServicesState(
                        serviceNameToInstances = services.associate {
                            toState(it.service, it.withoutInstances)
                        }.toMutableMap()
                    )
                }
            }

            private fun toState(service: String, withoutInstances: Boolean): Pair<String, ServiceInstances> {
                val instances = if (withoutInstances) emptySet() else setOf(
                    ServiceInstance(
                        "1", setOf(), "localhost", 80
                    )
                )
                return service to ServiceInstances(service, instances)
            }

            @Suppress("TooGenericExceptionThrown")
            fun stateError() {
                responses.add { throw RuntimeException("Error fetching from $clusterName") }
            }
        }

        val map = mutableMapOf<String, () -> WrappedServiceState>()

        override fun getState(uri: URI): CompletableFuture<ServicesState> {
            return CompletableFuture.supplyAsync(map.getValue(uri.host)())
        }

        fun forCluster(name: String, function: ClusterScope.() -> Unit) {
            val scope = ClusterScope(name).apply(function)
            map[name] = { scope.responses.first() }
        }

        fun forClusterWithChangableState(path: String, function: ClusterScope.() -> Unit) {
            val scope = ClusterScope(path).apply(function)
            val iterator = scope.responses.iterator()
            map[path] = { iterator.next() }
        }
    }
}
private typealias WrappedServiceState = () -> ServicesState
