package pl.allegro.tech.servicemesh.envoycontrol.infrastructure.health

import org.springframework.boot.actuate.health.AbstractHealthIndicator
import org.springframework.boot.actuate.health.Health
import org.springframework.stereotype.Component
import pl.allegro.tech.servicemesh.envoycontrol.services.LocalZoneStateChanges

@Component
class EnvoyControlHealthIndicator(private val localZoneStateChanges: LocalZoneStateChanges)
    : AbstractHealthIndicator() {
    override fun doHealthCheck(builder: Health.Builder?) {
        if (localZoneStateChanges.isInitialStateLoaded()) {
            builder!!.up()
        } else {
            builder!!.down()
        }
    }
}
