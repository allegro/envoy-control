package pl.allegro.tech.servicemesh.envoycontrol.server.callbacks

import io.envoyproxy.controlplane.server.DiscoveryServerCallbacks
import io.envoyproxy.controlplane.server.exception.RequestException
import io.envoyproxy.envoy.api.v2.DeltaDiscoveryRequest
import io.micrometer.core.instrument.MeterRegistry
import pl.allegro.tech.servicemesh.envoycontrol.logger
import io.envoyproxy.envoy.api.v2.DiscoveryRequest as DiscoveryRequestV2
import io.envoyproxy.envoy.api.v2.DiscoveryResponse as DiscoveryResponseV2
import io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryRequest as DeltaDiscoveryRequestV3
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest as DiscoveryRequestV3
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse as DiscoveryResponseV3

class CompositeException(exceptions: List<java.lang.Exception>) :
    RuntimeException("Composite exception: " + exceptions.map { it.message }.joinToString(",", "[", "]"))

class CompositeDiscoveryServerCallbacks(
    val meterRegistry: MeterRegistry,
    vararg val delegate: DiscoveryServerCallbacks
) : DiscoveryServerCallbacks {
    private val logger by logger()

    override fun onStreamCloseWithError(streamId: Long, typeUrl: String?, error: Throwable?) {
        runCallbacks {
            it.onStreamCloseWithError(streamId, typeUrl, error)
        }
    }

    override fun onStreamClose(streamId: Long, typeUrl: String?) {
        runCallbacks {
            it.onStreamClose(streamId, typeUrl)
        }
    }

    override fun onStreamOpen(streamId: Long, typeUrl: String?) {
        runCallbacks {
            it.onStreamOpen(streamId, typeUrl)
        }
    }

    override fun onV2StreamRequest(streamId: Long, request: DiscoveryRequestV2?) {
        runCallbacks {
            it.onV2StreamRequest(streamId, request)
        }
    }

    override fun onV3StreamRequest(streamId: Long, request: DiscoveryRequestV3?) {
        runCallbacks {
            it.onV3StreamRequest(streamId, request)
        }
    }

    override fun onV2StreamDeltaRequest(streamId: Long, request: DeltaDiscoveryRequest?) {
        runCallbacks {
            it.onV2StreamDeltaRequest(streamId, request)
        }
    }

    override fun onV3StreamDeltaRequest(
        streamId: Long,
        request: DeltaDiscoveryRequestV3?
    ) {
        runCallbacks {
            it.onV3StreamDeltaRequest(streamId, request)
        }
    }

    override fun onStreamResponse(
        streamId: Long,
        request: DiscoveryRequestV2?,
        response: DiscoveryResponseV2?
    ) {
        runCallbacks {
            it.onStreamResponse(streamId, request, response)
        }
    }

    override fun onV3StreamResponse(
        streamId: Long,
        request: DiscoveryRequestV3?,
        response: DiscoveryResponseV3?
    ) {
        runCallbacks {
            it.onV3StreamResponse(streamId, request, response)
        }
    }

    private fun runCallbacks(fn: (DiscoveryServerCallbacks) -> Unit) {
        val exceptions = mutableListOf<Exception>()
        for (callback in delegate) {
            try {
                fn(callback)
            } catch (e: Exception) {
                meterRegistry.counter("callbacks.errors").increment()
                logger.warn(e.message, e)
                when (e) {
                    // stop callback processing and throw RequestException without wrapping,
                    // to notify client with proper message
                    is RequestException -> throw e
                    else -> exceptions.add(e)
                }
            }
        }
        if (exceptions.isNotEmpty()) {
            throw CompositeException(exceptions)
        }
    }
}
