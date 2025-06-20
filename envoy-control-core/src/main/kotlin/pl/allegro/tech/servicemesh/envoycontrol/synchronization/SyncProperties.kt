@file:Suppress("MagicNumber")

package pl.allegro.tech.servicemesh.envoycontrol.synchronization

import java.time.Duration

class SyncProperties {
    var enabled = false
    var pollingInterval: Long = 1
    var cacheDuration = Duration.ofMinutes(2)
    var connectionTimeout: Duration = Duration.ofMillis(1000)
    var readTimeout: Duration = Duration.ofMillis(500)
    var envoyControlAppName = "envoy-control"
    var combineServiceChangesExperimentalFlow = false
    var blackListedRemoteClusters: Set<String> = setOf()
}
