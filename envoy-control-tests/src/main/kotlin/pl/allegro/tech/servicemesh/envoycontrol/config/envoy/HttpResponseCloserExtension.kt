package pl.allegro.tech.servicemesh.envoycontrol.config.envoy

import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.ExtensionContext

class HttpResponseCloserExtension : AfterEachCallback {
    override fun afterEach(context: ExtensionContext?) {
        HttpResponseCloser.closeResponses()
    }
}
