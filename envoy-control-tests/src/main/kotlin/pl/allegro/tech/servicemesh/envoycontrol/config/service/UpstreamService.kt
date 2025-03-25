package pl.allegro.tech.servicemesh.envoycontrol.config.service

import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.ResponseWithBody

interface UpstreamService {
    fun id(): String
    fun isSourceOf(response: ResponseWithBody): Boolean
}
