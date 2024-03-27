import TrafficSplitting.DELTA_PERCENTAGE
import TrafficSplitting.UPSTREAM_SERVICE_NAME
import org.assertj.core.api.Assertions
import org.assertj.core.data.Percentage
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isFrom
import pl.allegro.tech.servicemesh.envoycontrol.assertions.isOk
import pl.allegro.tech.servicemesh.envoycontrol.assertions.untilAsserted
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.CallStats
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyExtension
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoServiceExtension

internal object TrafficSplitting {
    const val UPSTREAM_SERVICE_NAME = "service-1"
    const val SERVICE_NAME = "echo2"
    const val DELTA_PERCENTAGE = 20.0
    const val FORCE_TRAFFIC_ZONE = "dc2"
    val DEFAULT_PRIORITIES = mapOf(
        "dc1" to mapOf(
            "dc1" to 0,
            "dc2" to 1,
            "dc3" to 2,
        ),
        "dc2" to mapOf(
            "dc1" to 1,
            "dc2" to 0,
            "dc3" to 2,
        ),
        "dc3" to mapOf(
            "dc1" to 2,
            "dc2" to 1,
            "dc3" to 0,
        )
    )
}

fun EnvoyExtension.verifyIsReachable(echoServiceExtension: EchoServiceExtension, service: String) {
    untilAsserted {
        this.egressOperations.callService(service).also {
            Assertions.assertThat(it).isOk().isFrom(echoServiceExtension)
        }
    }
}

fun CallStats.verifyCallsCountCloseTo(service: EchoServiceExtension, expectedCount: Int): CallStats {
    Assertions.assertThat(this.hits(service)).isCloseTo(expectedCount, Percentage.withPercentage(DELTA_PERCENTAGE))
    return this
}

fun CallStats.verifyCallsCountEq(service: EchoServiceExtension, expectedCount: Int): CallStats {
    Assertions.assertThat(this.hits(service)).isEqualTo(expectedCount)
    return this
}

fun EnvoyExtension.callUpstreamServiceRepeatedly(
    vararg services: EchoServiceExtension,
    numberOfCalls: Int = 100,
): CallStats {
    val stats = CallStats(services.asList())
    this.egressOperations.callServiceRepeatedly(
        service = UPSTREAM_SERVICE_NAME,
        stats = stats,
        minRepeat = numberOfCalls,
        maxRepeat = numberOfCalls,
        repeatUntil = { true },
        headers = mapOf()
    )
    return stats
}

fun EnvoyExtension.callUpstreamServiceRepeatedly(
    vararg services: EchoServiceExtension,
    numberOfCalls: Int = 100,
    tag: String?
): CallStats {
    val stats = CallStats(services.asList())
    this.egressOperations.callServiceRepeatedly(
        service = UPSTREAM_SERVICE_NAME,
        stats = stats,
        minRepeat = numberOfCalls,
        maxRepeat = numberOfCalls,
        repeatUntil = { true },
        headers = tag?.let { mapOf("x-service-tag" to it) } ?: emptyMap(),
    )
    return stats
}
