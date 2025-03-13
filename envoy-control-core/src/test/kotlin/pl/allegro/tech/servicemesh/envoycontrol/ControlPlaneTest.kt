package pl.allegro.tech.servicemesh.envoycontrol

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.server.ExecutorType
import reactor.core.publisher.Flux

class ControlPlaneTest {

    @Test
    fun shouldUseSimpleCacheWithInitialResourcesHandling() {
        val meterRegistry = SimpleMeterRegistry()
        val envoyControlProperties = EnvoyControlProperties().also {
            it.server.executorGroup.type = ExecutorType.PARALLEL
            it.server.groupSnapshotUpdateScheduler.type = ExecutorType.PARALLEL
            it.server.enableInitialResourcesHandling = true
        }

        val controlPlane = ControlPlane.builder(envoyControlProperties, meterRegistry).build(Flux.empty())
        assertThat(controlPlane.cache).isInstanceOf(SimpleCache::class.java)
    }

    @Test
    fun shouldUseSimpleCacheWithoutInitialResourcesHandling() {
        val meterRegistry = SimpleMeterRegistry()
        val envoyControlProperties = EnvoyControlProperties().also {
            it.server.executorGroup.type = ExecutorType.PARALLEL
            it.server.groupSnapshotUpdateScheduler.type = ExecutorType.PARALLEL
            it.server.enableInitialResourcesHandling = false
        }

        val controlPlane = ControlPlane.builder(envoyControlProperties, meterRegistry).build(Flux.empty())
        assertThat(controlPlane.cache).isInstanceOf(SimpleCacheNoInitialResourcesHandling::class.java)
    }
}
