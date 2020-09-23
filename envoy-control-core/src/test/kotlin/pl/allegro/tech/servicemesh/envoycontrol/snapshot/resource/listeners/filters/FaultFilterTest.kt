package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters

import com.google.protobuf.util.Durations
import io.envoyproxy.envoy.config.filter.http.fault.v2.HTTPFault
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

private const val envoyFilterName = "envoy.filters.http.fault"

class FaultFilterTest {
    @Test
    fun `should create a proper fault http filter`() {
        // given
        val sampleClusterName = "cluster_name"
        val sampleDelay = 200L

        // when
        val faultFilter = FaultFilterFactory.fixedDelayFilter(sampleClusterName, sampleDelay)

        // then
        assertThat(faultFilter).isNotNull()
        assertThat(faultFilter.name).isEqualTo(envoyFilterName)
        assertThat(faultFilter.typedConfig.typeUrl.split('/').last())
            .isEqualTo(HTTPFault::class.qualifiedName?.split('.', limit = 3)?.last())

        val typedConfig = HTTPFault.parseFrom(faultFilter.typedConfig.value)
        assertThat(typedConfig.upstreamCluster).isEqualTo(sampleClusterName)
        assertThat(typedConfig.delay.fixedDelay).isEqualTo(Durations.fromMillis(sampleDelay))
        assertThat(typedConfig.delay.percentage.numerator).isEqualTo(100)
    }
}
