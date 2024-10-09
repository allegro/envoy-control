package pl.allegro.tech.servicemesh.envoycontrol.metrics

import io.micrometer.core.instrument.Tag
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
        val metricNames = listOf("executor.completed", "executor.active", "executor.queued", "executor.pool.size")

        val executorNames = listOf(
            "grpc-server-worker",
            "grpc-worker-event-loop",
            "snapshot-update",
            "group-snapshot"
        ).associateWith { metricNames }

        assertThat(executorNames.entries).allSatisfy {
            assertThat(it.value.all { metricName ->
                meterRegistry.meters.any { meter ->
                    meter.id.name == metricName && meter.id.tags.contains(
                        Tag.of("executor", it.key)
                    )
                }
            }).isTrue()
        }

        // and
        controlPlane.close()
    }
}
