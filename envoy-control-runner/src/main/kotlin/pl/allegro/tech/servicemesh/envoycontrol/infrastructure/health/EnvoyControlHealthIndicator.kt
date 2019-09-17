package pl.allegro.tech.servicemesh.envoycontrol.infrastructure.health

import org.springframework.boot.actuate.health.AbstractHealthIndicator
import org.springframework.boot.actuate.health.Health
import org.springframework.stereotype.Component
import pl.allegro.tech.servicemesh.envoycontrol.services.LocalServiceChanges

@Component
class EnvoyControlHealthIndicator(private val localServiceChanges: LocalServiceChanges) : AbstractHealthIndicator() {
    override fun doHealthCheck(builder: Health.Builder?) {
        if (localServiceChanges.isServiceStateLoaded()) {
            builder!!.up()
        } else {
            builder!!.down()
        }
    }
}
