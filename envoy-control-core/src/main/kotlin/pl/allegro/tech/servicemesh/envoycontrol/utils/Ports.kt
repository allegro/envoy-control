package pl.allegro.tech.servicemesh.envoycontrol.utils

import java.net.ServerSocket

object Ports {
    fun nextAvailable(): Int {
        ServerSocket(0).use {
            return it.localPort
        }
    }
}
