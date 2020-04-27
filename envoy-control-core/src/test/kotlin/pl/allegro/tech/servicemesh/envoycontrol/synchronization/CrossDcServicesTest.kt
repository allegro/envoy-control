package pl.allegro.tech.servicemesh.envoycontrol.synchronization

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.services.Locality
import pl.allegro.tech.servicemesh.envoycontrol.services.LocalityAwareServicesState
import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceInstance
import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceInstances
import pl.allegro.tech.servicemesh.envoycontrol.services.ServicesState
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.net.URI
import java.time.Duration

class CrossDcServicesTest {
    @Test
    fun `should collect responses from all DCs`() {
        val service = CrossDcServices(asyncClient(), SimpleMeterRegistry(), fetcher(), listOf("dc1", "dc2"))

        val result = service
            .getChanges(1)
            .blockFirst()
            ?: emptySet()

        assertThat(result).hasSize(2)
        assertThat(result.map { it.zone }.toSet()).isEqualTo(setOf("dc1", "dc2"))
    }

    @Test
    fun `should ignore DC without service instances`() {
        val service = CrossDcServices(asyncClient(), SimpleMeterRegistry(), fetcher(), listOf("dc1", "dc2/noinstances"))

        val result = service
            .getChanges(1)
            .blockFirst()
            ?: emptySet()

        assertThat(result).hasSize(1)
        assertThat(result.map { it.zone }).contains("dc1")
    }

    @Test
    fun `should skip failing responses if not cached`() {
        val service = CrossDcServices(
            asyncClient(),
            SimpleMeterRegistry(),
            fetcher(),
            listOf("dc1", "dc2/error", "dc3", "dc4/error")
        )

        val result = service
            .getChanges(1)
            .blockFirst()
            ?: emptySet()

        assertThat(result).isNotEmpty
        assertThat(result.flatMap { it.servicesState.serviceNames() }.toSet()).isEqualTo(setOf("dc1", "dc3"))
    }

    @Test
    fun `should serve cached responses when a cross dc request fails`() {
        // given
        val service = CrossDcServices(asyncClient(), SimpleMeterRegistry(), fetcher(), listOf("dc1/successful-states", "dc2/second-request-failing"))

        val successfulResult = service
            .getChanges(1)
            .blockFirst()
            ?: emptySet()

        assertThat(successfulResult).containsExactlyInAnyOrder(*(expectedSuccessfulState.toTypedArray()))

        val oneInstanceFailing = service
            .getChanges(1)
            .blockFirst()
            ?: emptySet()

        assertThat(oneInstanceFailing).containsExactlyInAnyOrder(*(expectedStateWithOneRequestFailing.toTypedArray()))
    }

    @Test
    fun `should not emit a value when all requests fail`() {
        // given
        val service = CrossDcServices(asyncClient(), SimpleMeterRegistry(), fetcher(), listOf("dc2/error"))
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

    private fun fetcher(): ControlPlaneInstanceFetcher {
        return object : ControlPlaneInstanceFetcher {
            override fun instances(dc: String): List<URI> {
                val uri = URI.create("http://$dc")
                if (uri.path == "/noinstances") {
                    return emptyList()
                }
                return listOf(uri)
            }
        }
    }

    private val servicesState1 = ServicesState(
        serviceNameToInstances = mapOf(
            "service-a" to ServiceInstances(
                "service-a",
                setOf(ServiceInstance("1", setOf(), "localhost", 80))
            )
        )
    )
    private val servicesState2 = ServicesState(
        serviceNameToInstances = mapOf(
            "service-a" to ServiceInstances(
                "service-a",
                setOf(ServiceInstance("2", setOf(), "localhost", 8080))
            )
        )
    )

    private val servicesState3 = ServicesState(
        serviceNameToInstances = mapOf(
            "service-b" to ServiceInstances(
                "service-b",
                setOf(ServiceInstance("3", setOf(), "localhost", 81))
            )
        )
    )

    private val expectedSuccessfulState = setOf(
        LocalityAwareServicesState(servicesState1, Locality.REMOTE, "dc1/successful-states"),
        LocalityAwareServicesState(servicesState3, Locality.REMOTE, "dc2/second-request-failing")
    )

    private val expectedStateWithOneRequestFailing = setOf(
        LocalityAwareServicesState(servicesState2, Locality.REMOTE, "dc1/successful-states"),
        LocalityAwareServicesState(servicesState3, Locality.REMOTE, "dc2/second-request-failing")
    )

    private val successfulStatesSequence = listOf(
        Mono.just(servicesState1), Mono.just(servicesState2)
    ).iterator()

    private val secondStateFailingSequence = listOf(
        Mono.just(servicesState3), Mono.error(RuntimeException("Error fetching from dc2"))
    ).iterator()

    private fun asyncClient(): AsyncControlPlaneClient {
        return object : AsyncControlPlaneClient {
            override fun getState(uri: URI): Mono<ServicesState> = when {
                uri.path == "/error" -> Mono.error(RuntimeException(uri.toString()))
                uri.path == "/empty" -> Mono.empty()
                uri.path == "/successful-states" -> successfulStatesSequence.next()
                uri.path == "/second-request-failing" -> secondStateFailingSequence.next()
                else -> Mono.just(
                    ServicesState(
                        serviceNameToInstances = mapOf(
                            uri.authority to ServiceInstances(
                                uri.authority,
                                emptySet()
                            )
                        )
                    )
                )
            }
        }
    }
}
