package pl.allegro.tech.servicemesh.envoycontrol.assertions

import org.assertj.core.api.Assertions
import org.awaitility.Awaitility
import org.awaitility.Duration
import java.util.concurrent.TimeUnit

fun <T> untilAsserted(wait: Duration = Duration(90, TimeUnit.SECONDS), fn: () -> (T)): T {
    var lastResult: T? = null
    Awaitility.await().atMost(wait).untilAsserted { lastResult = fn() }
    Assertions.assertThat(lastResult).isNotNull
    return lastResult!!
}