package pl.allegro.tech.servicemesh.envoycontrol.synchronization

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.services.MultiClusterState
import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceInstance
import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceInstances
import pl.allegro.tech.servicemesh.envoycontrol.services.ServicesState
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.net.URI
import java.time.Duration

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
        assertThat(result.map { it.cluster }.toSet()).isEqualTo(setOf("dc1", "dc2"))

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
        assertThat(result.map { it.cluster }).contains("dc1")
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
        assertThat(result.map { it.cluster }).contains("dc1", "dc2")
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

        assertThat(result).isNotEmpty
        assertThat(result.flatMap { it.servicesState.serviceNames() }.toSet()).isEqualTo(setOf("dc1", "dc3"))
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

        assertThat(successfulResult.flatMap { it.servicesState.serviceNames() }.toSet()).isEqualTo(
            setOf(
                "service-a",
                "service-c"
            )
        )

        val oneInstanceFailing = service
            .getChanges(1)
            .blockFirst()
            ?: MultiClusterState.empty()

        assertThat(oneInstanceFailing.flatMap { it.servicesState.serviceNames() }.toSet()).isEqualTo(
            setOf(
                "service-b",
                "service-c"
            )
        )
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

    class CControlPlaneInstanceFetcher(private val clusterWithNoInstance: List<String>) : ControlPlaneInstanceFetcher {
        override fun instances(cluster: String): List<URI> {
            val uri = URI.create("http://$cluster")
            if (clusterWithNoInstance.contains(cluster)) {
                return emptyList()
            }
            return listOf(uri)
        }
    }

    private fun fetcher(clusterWithNoInstance: List<String> = emptyList()): ControlPlaneInstanceFetcher {
        return CControlPlaneInstanceFetcher(clusterWithNoInstance)
    }

    private fun MultiClusterState.doesntHaveServiceWithEmptyInstances(
        cluster: String,
        serviceName: String
    ): MultiClusterState {
        val clusterState = this.first { it.cluster == cluster }
        assertThat(clusterState).isNotNull
        assertThat(clusterState.servicesState.serviceNameToInstances.keys).doesNotContain(serviceName)
        return this
    }


    data class ServiceState(val service: String,val withoutInstances: Boolean = false)
    class FakeAsyncControlPlane : AsyncControlPlaneClient {
        class ClusterScope(private val clusterName: String) {
            var responses = mutableListOf<Mono<ServicesState>>()

            fun state(vararg services: ServiceState) {
                responses.add(
                    Mono.just(
                        ServicesState(
                            serviceNameToInstances = services.map { toState(it.service,it.withoutInstances) }.toMap()
                        )
                    )
                )
            }

            private fun toState(service: String, withoutInstances: Boolean): Pair<String, ServiceInstances> {
                val instances = if (withoutInstances) emptySet() else setOf(
                    ServiceInstance(
                        "1", setOf(), "localhost", 80
                    )
                )
                return service to ServiceInstances(service, instances)
            }

            fun stateError() {
                responses.add(Mono.error(RuntimeException("Error fetching from $clusterName")))
            }
        }

        val map = mutableMapOf<String, () -> Mono<ServicesState>>()

        override fun getState(uri: URI): Mono<ServicesState> {
            return map.getValue(uri.host)()
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
