import TrafficSplittingConstants.upstreamServiceName
import org.assertj.core.api.Assertions
import org.assertj.core.data.Percentage
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isFrom
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isOk
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.CallStats
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension

internal object TrafficSplittingConstants {
    const val upstreamServiceName = "service-1"
    const val serviceName = "echo2"
}

fun EnvoyExtension.verifyIsReachable(echoServiceExtension: EchoServiceExtension, service: String) {
    untilAsserted {
        this.egressOperations.callService(service).also {
            Assertions.assertThat(it).isOk().isFrom(echoServiceExtension)
        }
    }
}

fun CallStats.verifyCallsCountCloseTo(service: EchoServiceExtension, expectedCount: Int): CallStats {
    Assertions.assertThat(this.hits(service)).isCloseTo(expectedCount, Percentage.withPercentage(20.0))
    return this
}

fun CallStats.verifyCallsCountGreaterThan(service: EchoServiceExtension, hits: Int): CallStats {
    Assertions.assertThat(this.hits(service)).isGreaterThan(hits)
    return this
}

fun CallStats.verifyNoCalls(vararg services: EchoServiceExtension): CallStats {
    services.forEach {
        Assertions.assertThat(this.hits(it)).isEqualTo(0)
    }
    return this
}

fun EnvoyExtension.callUpstreamServiceRepeatedly(
    vararg services: EchoServiceExtension,
    numberOfCalls: Int = 100,
): CallStats {
    val stats = CallStats(services.asList())
    this.egressOperations.callServiceRepeatedly(
        service = upstreamServiceName,
        stats = stats,
        minRepeat = numberOfCalls,
        maxRepeat = numberOfCalls,
        repeatUntil = { true },
        headers = mapOf()
    )
    return stats
}
