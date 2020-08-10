package pl.allegro.tech.servicemesh.envoycontrol.synchronization

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import pl.allegro.tech.servicemesh.envoycontrol.services.LocalClusterStateChanges
import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceInstances
import pl.allegro.tech.servicemesh.envoycontrol.services.ServicesState

@RestController
class StateController(val localClusterStateChanges: LocalClusterStateChanges) {

    @GetMapping("/state")
    fun getState(): ServicesState = localClusterStateChanges.latestServiceState.get()

    @GetMapping("/state/{serviceName}")
    fun getStateByServiceName(@PathVariable("serviceName") serviceName: String): ServiceInstances? =
        localClusterStateChanges.latestServiceState.get()[serviceName]
}
