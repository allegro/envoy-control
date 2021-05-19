package pl.allegro.tech.servicemesh.envoycontrol.server.callbacks

import io.envoyproxy.controlplane.server.DiscoveryServerCallbacks
import org.slf4j.LoggerFactory
import io.envoyproxy.envoy.api.v2.DiscoveryRequest as DiscoveryRequestV2
import io.envoyproxy.envoy.api.v2.DiscoveryResponse as DiscoveryResponseV2
import io.envoyproxy.envoy.api.v2.DeltaDiscoveryRequest as DeltaDiscoveryRequestV2
import io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryRequest as DeltaDiscoveryRequestV3
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest as DiscoveryRequestV3
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse as DiscoveryResponseV3

class LoggingDiscoveryServerCallbacks(
    private val logFullRequest: Boolean,
    private val logFullResponse: Boolean
) : DiscoveryServerCallbacks {
    private val logger = LoggerFactory.getLogger(LoggingDiscoveryServerCallbacks::class.java)

    override fun onStreamClose(streamId: Long, typeUrl: String?) {
        logger.debug("onStreamClose streamId: {} typeUrl: {}", streamId, typeUrl)
    }

    override fun onV3StreamRequest(streamId: Long, request: DiscoveryRequestV3?) {
        logger.debug("onV3StreamRequest streamId: {} request: {}", streamId, requestData(request))
    }

    override fun onV2StreamDeltaRequest(streamId: Long, request: DeltaDiscoveryRequestV2?) {
        logger.debug("onV2StreamDeltaRequest streamId: {} request: {}", streamId, requestData(request))
    }

    override fun onV3StreamDeltaRequest(
        streamId: Long,
        request: DeltaDiscoveryRequestV3?
    ) {
        logger.debug("onV3StreamDeltaRequest streamId: {} request: {}", streamId, requestData(request))
    }

    override fun onStreamCloseWithError(streamId: Long, typeUrl: String?, error: Throwable?) {
        logger.debug("onStreamCloseWithError streamId: {}, typeUrl: {}", streamId, typeUrl, error)
    }

    override fun onStreamOpen(streamId: Long, typeUrl: String?) {
        logger.debug("onStreamOpen streamId: {}, typeUrl: {}", streamId, typeUrl)
    }

    override fun onV2StreamRequest(streamId: Long, request: DiscoveryRequestV2?) {
        logger.debug("onV2StreamRequest streamId: {} request: {}", streamId, requestData(request))
    }

    override fun onStreamResponse(
        streamId: Long,
        request: DiscoveryRequestV2?,
        response: DiscoveryResponseV2?
    ) {
        logger.debug(
            "onStreamResponseV2 streamId: {}, request: {}, response: {}",
            streamId,
            requestData(request),
            responseData(response)
        )
    }

    override fun onV3StreamResponse(
        streamId: Long,
        request: DiscoveryRequestV3?,
        response: DiscoveryResponseV3?
    ) {
        logger.debug(
            "onStreamResponseV3 streamId: {}, request: {}, response: {}",
            streamId,
            requestData(request),
            responseData(response)
        )
    }

    private fun requestData(request: DeltaDiscoveryRequestV3?): String {
        return if (logFullRequest) {
            "$request"
        } else {
            "id: ${request?.node?.id}, cluster: ${request?.node?.cluster}, " +
                "type: ${request?.typeUrl}, responseNonce: ${request?.responseNonce}"
        }
    }

    private fun requestData(request: DeltaDiscoveryRequestV2?): String {
        return if (logFullRequest) {
            "$request"
        } else {
            "id: ${request?.node?.id}, cluster: ${request?.node?.cluster}, " +
                "type: ${request?.typeUrl}, responseNonce: ${request?.responseNonce}"
        }
    }

    private fun requestData(request: DiscoveryRequestV2?): String {
        return if (logFullRequest) {
            "$request"
        } else {
            "version: ${request?.versionInfo}, id: ${request?.node?.id}, cluster: ${request?.node?.cluster}, " +
                "type: ${request?.typeUrl}, responseNonce: ${request?.responseNonce}"
        }
    }

    private fun responseData(response: DiscoveryResponseV2?): String {
        return if (logFullResponse) {
            "$response"
        } else {
            "version: ${response?.versionInfo}, " + "type: ${response?.typeUrl}, responseNonce: ${response?.nonce}"
        }
    }

    private fun requestData(request: DiscoveryRequestV3?): String {
        return if (logFullRequest) {
            "$request"
        } else {
            "version: ${request?.versionInfo}, id: ${request?.node?.id}, cluster: ${request?.node?.cluster}, " +
                "type: ${request?.typeUrl}, responseNonce: ${request?.responseNonce}"
        }
    }

    private fun responseData(response: DiscoveryResponseV3?): String {
        return if (logFullResponse) {
            "$response"
        } else {
            "version: ${response?.versionInfo}, " + "type: ${response?.typeUrl}, responseNonce: ${response?.nonce}"
        }
    }
}
