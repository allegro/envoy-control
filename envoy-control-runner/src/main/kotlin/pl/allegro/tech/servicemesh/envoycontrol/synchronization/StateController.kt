package pl.allegro.tech.servicemesh.envoycontrol.synchronization

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import pl.allegro.tech.servicemesh.envoycontrol.services.LocalZoneStateChanges
import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceInstances
import pl.allegro.tech.servicemesh.envoycontrol.services.ServicesState

@RestController
class StateController(val localZoneStateChanges: LocalZoneStateChanges) {

    @GetMapping("/state")
    fun getState(): ServicesState = localZoneStateChanges.latestServiceState.get()

    @GetMapping("/state/{serviceName}")
    fun getStateByServiceName(@PathVariable("serviceName") serviceName: String): ServiceInstances? =
        localZoneStateChanges.latestServiceState.get()[serviceName]
}
