package pl.allegro.tech.servicemesh.envoycontrol.infrastructure.health

import org.springframework.boot.actuate.health.AbstractHealthIndicator
import org.springframework.boot.actuate.health.Health
import org.springframework.stereotype.Component
import pl.allegro.tech.servicemesh.envoycontrol.services.LocalClusterStateChanges

@Component
class EnvoyControlHealthIndicator(private val localClusterStateChanges: LocalClusterStateChanges)
    : AbstractHealthIndicator() {
    override fun doHealthCheck(builder: Health.Builder?) {
        if (localClusterStateChanges.isInitialStateLoaded()) {
            builder!!.up()
        } else {
            builder!!.down()
        }
    }
}
