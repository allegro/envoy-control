package pl.allegro.tech.servicemesh.envoycontrol.metrics

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.ControlPlane
import pl.allegro.tech.servicemesh.envoycontrol.EnvoyControlProperties
import pl.allegro.tech.servicemesh.envoycontrol.server.ExecutorType
import reactor.core.publisher.Flux

class ThreadPoolMetricTest {

    @Test
    fun `should bind metrics for default executors`() {
        // given
        val meterRegistry = SimpleMeterRegistry()
        val envoyControlProperties = EnvoyControlProperties().also {
            it.server.executorGroup.type = ExecutorType.PARALLEL
            it.server.groupSnapshotUpdateScheduler.type = ExecutorType.PARALLEL
        }

        val controlPlane = ControlPlane.builder(envoyControlProperties, meterRegistry).build(Flux.empty())

        // when
        controlPlane.start()

        // then
        val allMeterNames = meterRegistry.meters.map { it.id.name }
        val requiredMeterNames = listOf("grpc-server-worker", "grpc-worker-event-loop", "snapshot-update", "group-snapshot").flatMap {
            listOf("$it.executor.completed", "$it.executor.active", "$it.executor.queued", "$it.executor.pool.size")
        }

        assertThat(allMeterNames).containsAll(requiredMeterNames)

        // and
        controlPlane.close()
    }
}
