package pl.allegro.tech.servicemesh.envoycontrol.assertions

import org.assertj.core.api.Assertions
import org.awaitility.Awaitility
import java.time.Duration

fun <T> untilAsserted(wait: Duration = Duration.ofSeconds(90), fn: () -> (T)): T {
    var lastResult: T? = null
    Awaitility.await().atMost(wait).untilAsserted { lastResult = fn() }
    Assertions.assertThat(lastResult).isNotNull
    return lastResult!!
}
