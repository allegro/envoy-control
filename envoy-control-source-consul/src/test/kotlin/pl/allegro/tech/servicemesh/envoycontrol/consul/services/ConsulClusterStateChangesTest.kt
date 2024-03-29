package pl.allegro.tech.servicemesh.envoycontrol.consul.services

import com.ecwid.consul.v1.agent.AgentConsulClient
import com.ecwid.consul.v1.agent.model.NewService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.mockito.Mockito
import org.mockito.Mockito.verify
import pl.allegro.tech.discovery.consul.recipes.ConsulRecipes
import pl.allegro.tech.servicemesh.envoycontrol.config.consul.ConsulExtension
import pl.allegro.tech.servicemesh.envoycontrol.server.ReadinessStateHandler
import reactor.test.StepVerifier
import java.net.URI
import java.util.UUID
import java.util.concurrent.Executors

class ConsulClusterStateChangesTest {

    companion object {

        @JvmField
        @RegisterExtension
        val consulExtension = ConsulExtension()
    }

    private val watcher = ConsulRecipes
        .consulRecipes()
        .build()
        .consulWatcher(Executors.newFixedThreadPool(10))
        .withAgentUri(URI("http://localhost:${consulExtension.server.port}"))
        .build()
    private val readinessStateHandler = Mockito.spy(ReadinessStateHandler::class.java)
    private val serviceWatchPolicy = Mockito.mock(ServiceWatchPolicy::class.java)
    private val changes = ConsulServiceChanges(
        watcher = watcher,
        readinessStateHandler = readinessStateHandler,
        serviceWatchPolicy = serviceWatchPolicy
    )
    private val client = AgentConsulClient("localhost", consulExtension.server.port)

    @BeforeEach
    fun reset() {
        watcher.close()
        Mockito.`when`(serviceWatchPolicy.shouldBeWatched(Mockito.anyString(), Mockito.anyList())).thenReturn(true)
    }

    @Test
    fun `should watch changes of consul state`() {
        StepVerifier.create(changes.watchState())
            .expectNextCount(1) // events: add(consul) + change(consul) happened during graceful startup
            .then { verify(readinessStateHandler).ready() }
            .then { registerService(id = "123", name = "abc") }
            .expectNextMatches { it.hasService("abc") }
            .assertNext {
                assertThat(it.hasService("consul")).isTrue()
                assertThat(it.hasService("abc")).isTrue()
                assertThat(it["abc"]).isNotNull()
                assertThat(it["abc"]!!.instances).hasSize(1)
                it["abc"]!!.instances.first().run {
                    assertThat(id).isEqualTo("123")
                    assertThat(address).isEqualTo("localhost")
                    assertThat(port).isEqualTo(1234)
                    assertThat(tags).containsExactly("a")
                }
            }
            .then { deregisterService(id = "123") }
            // two separated events are generated and consumed in random order.
            // one with empty instances list and other with no service.
            .expectNextMatches { it["abc"]?.instances.isNullOrEmpty() }
            .thenCancel()
            .verify()
    }

    @Test
    fun `should produce first event with all services`() {
        registerService(id = "service1", name = "service1")
        registerService(id = "service2", name = "service2")

        StepVerifier.create(changes.watchState())
            .expectNextMatches { it.serviceNames() == setOf("consul", "service1", "service2") }
            .then { verify(readinessStateHandler).ready() }
            .then { registerService(id = "service3", name = "service3") }
            .thenRequest(1) // events: add(service3)
            .expectNextMatches { it.serviceNames() == setOf("consul", "service1", "service2", "service3") }
            .thenCancel()
            .verify()
    }

    @Test
    fun `should produce event with only matching services`() {
        // given
        val filteredServiceName = "serviceFiltered"
        Mockito.`when`(serviceWatchPolicy.shouldBeWatched(eq(filteredServiceName), Mockito.anyList())).thenReturn(false)
        registerService(id = "service1", name = "service1")
        registerService(id = "service2", name = "service2")

        // when
        StepVerifier.create(changes.watchState())
            // then
            .expectNextMatches { it.serviceNames() == setOf("consul", "service1", "service2") }
            .then { verify(readinessStateHandler).ready() }
            // when
            .then { registerService(id = "service3", name = "service3") }
            .thenRequest(1) // events: add(service3)
            // then
            .expectNextMatches { it.serviceNames() == setOf("consul", "service1", "service2", "service3") }
            // when
            .then { registerService(id = filteredServiceName, name = filteredServiceName) }
            .thenRequest(1) // events: add(filteredService)
            // then
            .expectNextMatches { it.serviceNames() == setOf("consul", "service1", "service2", "service3") }
            .thenCancel()
            .verify()
    }

    private fun registerService(
        id: String = UUID.randomUUID().toString(),
        name: String = "sample"
    ): String {
        val service = NewService().also {
            it.id = id
            it.name = name
            it.address = "localhost"
            it.port = 1234
            it.tags = listOf("a")
        }
        client.agentServiceRegister(service)
        return service.id
    }

    private fun deregisterService(id: String) {
        client.agentServiceDeregister(id)
    }
}

private fun <T> eq(value: T): T = Mockito.eq(value)
