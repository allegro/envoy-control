package pl.allegro.tech.servicemesh.envoycontrol.synchronization

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import pl.allegro.tech.servicemesh.envoycontrol.services.LocalClusterStateChanges
import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceInstances
import pl.allegro.tech.servicemesh.envoycontrol.utils.GzipUtils

@RestController
@ConditionalOnProperty(name = ["envoy-control.sync.gzip.enabled"], havingValue = "true", matchIfMissing = false)
class GzipStateController(val localClusterStateChanges: LocalClusterStateChanges, val gzipUtils: GzipUtils) {

    @GetMapping("/state")
    fun getState(): ByteArray = gzipUtils.gzip(localClusterStateChanges.latestServiceState.get())

    @GetMapping("/state/{serviceName}")
    fun getStateByServiceName(@PathVariable("serviceName") serviceName: String): ServiceInstances? =
        localClusterStateChanges.latestServiceState.get()[serviceName]
}
