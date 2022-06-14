package pl.allegro.tech.servicemesh.envoycontrol.consul.services

import com.ecwid.consul.v1.agent.AgentConsulClient
import com.ecwid.consul.v1.agent.model.NewService
import com.pszymczyk.consul.ConsulStarterBuilder
import com.pszymczyk.consul.infrastructure.Ports
import com.pszymczyk.consul.junit.ConsulExtension
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import pl.allegro.tech.discovery.consul.recipes.ConsulRecipes
import reactor.test.StepVerifier
import java.net.URI
import java.util.UUID
import java.util.concurrent.Executors

class ConsulClusterStateChangesTest {

    companion object {
        private val consulHttpPort = Ports.nextAvailable()

        @JvmField
        @RegisterExtension
        val consul = ConsulExtension(
            ConsulStarterBuilder.consulStarter()
                .withHttpPort(consulHttpPort)
                .withConsulVersion("1.11.4")
                .build()
        )
    }

    private val watcher = ConsulRecipes
        .consulRecipes()
        .build()
        .consulWatcher(Executors.newFixedThreadPool(10))
        .withAgentUri(URI("http://localhost:${consul.httpPort}"))
        .build()
    private val changes = ConsulServiceChanges(watcher)
    private val client = AgentConsulClient("localhost", consul.httpPort)

    @BeforeEach
    fun reset() {
        watcher.close()
        consul.reset()
    }

    @Test
    fun `should watch changes of consul state`() {
        StepVerifier.create(changes.watchState())
            .expectNextCount(1) // events: add(consul) + change(consul) happened during graceful startup
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
            .then { registerService(id = "service3", name = "service3") }
            .thenRequest(1) // events: add(service3)
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
