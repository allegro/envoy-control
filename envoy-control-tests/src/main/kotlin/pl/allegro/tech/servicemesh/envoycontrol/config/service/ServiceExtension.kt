package pl.allegro.tech.servicemesh.envoycontrol.config.service

import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback

interface ServiceExtension<T : ServiceContainer> : BeforeAllCallback, AfterAllCallback {

    fun container(): T
}
