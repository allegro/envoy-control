package pl.allegro.tech.servicemesh.envoycontrol.server.callbacks

import io.envoyproxy.controlplane.server.DiscoveryServerCallbacks
import io.envoyproxy.envoy.api.v2.DiscoveryRequest
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest as v3DiscoveryRequest
import io.envoyproxy.envoy.api.v2.DiscoveryResponse
import org.slf4j.LoggerFactory

class LoggingDiscoveryServerCallbacks(
    private val logFullRequest: Boolean,
    private val logFullResponse: Boolean
) : DiscoveryServerCallbacks {
    private val logger = LoggerFactory.getLogger(LoggingDiscoveryServerCallbacks::class.java)

    override fun onStreamClose(streamId: Long, typeUrl: String?) {
        logger.debug("onStreamClose streamId: {} typeUrl: {}", streamId, typeUrl)
    }

    override fun onV3StreamRequest(streamId: Long, request: v3DiscoveryRequest?) {
        logger.debug("onV3StreamRequest streamId: {} request: {}", streamId, requestData(request))
    }

    override fun onStreamCloseWithError(streamId: Long, typeUrl: String?, error: Throwable?) {
        logger.debug("onStreamCloseWithError streamId: {}, typeUrl: {}", streamId, typeUrl, error)
    }

    override fun onStreamOpen(streamId: Long, typeUrl: String?) {
        logger.debug("onStreamOpen streamId: {}, typeUrl: {}", streamId, typeUrl)
    }

    override fun onV2StreamRequest(streamId: Long, request: DiscoveryRequest?) {
        logger.debug("onV2StreamRequest streamId: {} request: {}", streamId, requestData(request))
    }

    override fun onStreamResponse(
        streamId: Long,
        request: DiscoveryRequest?,
        response: DiscoveryResponse?
    ) {
        logger.debug(
            "onStreamResponse streamId: {}, request: {}, response: {}",
            streamId,
            requestData(request),
            responseData(response)
        )
    }

    private fun responseData(response: DiscoveryResponse?): String {
        return if (logFullResponse) {
            "$response"
        } else {
            "version: ${response?.versionInfo}, " + "type: ${response?.typeUrl}, responseNonce: ${response?.nonce}"
        }
    }

    private fun requestData(request: DiscoveryRequest?): String {
        return if (logFullRequest) {
            "$request"
        } else {
            "version: ${request?.versionInfo}, id: ${request?.node?.id}, cluster: ${request?.node?.cluster}, " +
                "type: ${request?.typeUrl}, responseNonce: ${request?.responseNonce}"
        }
    }

    private fun requestData(request: io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest?): String {
        return if (logFullRequest) {
            "$request"
        } else {
            "version: ${request?.versionInfo}, id: ${request?.node?.id}, cluster: ${request?.node?.cluster}, " +
                    "type: ${request?.typeUrl}, responseNonce: ${request?.responseNonce}"
        }
    }
}
