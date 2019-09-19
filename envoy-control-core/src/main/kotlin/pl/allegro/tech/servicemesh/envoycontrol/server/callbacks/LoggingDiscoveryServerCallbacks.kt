package pl.allegro.tech.servicemesh.envoycontrol.server.callbacks

import io.envoyproxy.envoy.api.v2.DiscoveryRequest
import io.envoyproxy.envoy.api.v2.DiscoveryResponse
import io.envoyproxy.controlplane.server.DiscoveryServerCallbacks
import org.slf4j.LoggerFactory

class LoggingDiscoveryServerCallbacks : DiscoveryServerCallbacks {
    private val logger = LoggerFactory.getLogger(LoggingDiscoveryServerCallbacks::class.java)

    override fun onStreamClose(streamId: Long, typeUrl: String?) {
        logger.debug("onStreamClose streamId: {} typeUrl: {}", streamId, typeUrl)
    }

    override fun onStreamCloseWithError(streamId: Long, typeUrl: String?, error: Throwable?) {
        logger.debug("onStreamCloseWithError streamId: {}, typeUrl: {}", streamId, typeUrl, error)
    }

    override fun onStreamOpen(streamId: Long, typeUrl: String?) {
        logger.debug("onStreamOpen streamId: {}, typeUrl: {}", streamId, typeUrl)
    }

    override fun onStreamRequest(streamId: Long, request: DiscoveryRequest?) {
        logger.debug("onStreamRequest streamId: {} request: {}", streamId, request)
    }

    override fun onStreamResponse(
        streamId: Long,
        request: DiscoveryRequest?,
        response: DiscoveryResponse?
    ) {
        logger.debug("onStreamResponse streamId: {}, request: {}, response: {}", streamId, request, response)
    }
}
