package pl.allegro.tech.servicemesh.envoycontrol.snapshot.debug

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializerProvider
import com.google.protobuf.Any
import com.google.protobuf.BoolValue
import com.google.protobuf.Duration
import com.google.protobuf.Message
import com.google.protobuf.Struct
import com.google.protobuf.Value
import com.google.protobuf.util.JsonFormat
import com.google.protobuf.util.JsonFormat.TypeRegistry
import io.envoyproxy.controlplane.cache.NodeGroup
import io.envoyproxy.controlplane.cache.SnapshotCache
import io.envoyproxy.controlplane.cache.v3.Snapshot
import io.envoyproxy.envoy.config.cluster.v3.Cluster
import io.envoyproxy.envoy.config.rbac.v3.RBAC
import io.envoyproxy.envoy.extensions.filters.http.header_to_metadata.v3.Config
import io.envoyproxy.envoy.extensions.filters.http.lua.v3.Lua
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager
import io.envoyproxy.envoy.extensions.transport_sockets.tap.v3.Tap
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.UpstreamTlsContext
import io.envoyproxy.envoy.type.matcher.PathMatcher
import io.envoyproxy.envoy.type.matcher.StringMatcher
import org.springframework.boot.jackson.JsonComponent
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.bind.annotation.RestController
import pl.allegro.tech.servicemesh.envoycontrol.ControlPlane
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group
import pl.allegro.tech.servicemesh.envoycontrol.logger
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.GlobalSnapshot
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotUpdater
import io.envoyproxy.envoy.config.core.v3.Node as NodeV3
import io.envoyproxy.envoy.extensions.filters.http.rbac.v3.RBAC as RBACFilter

@RestController
class SnapshotDebugController(controlPlane: ControlPlane) {
    val cache: SnapshotCache<Group, Snapshot> = controlPlane.cache
    val nodeGroup: NodeGroup<Group> = controlPlane.nodeGroup
    val snapshotUpdater: SnapshotUpdater = controlPlane.snapshotUpdater

    /**
     * Returns a textual representation of the snapshot for debugging purposes.
     * It contains the versions of XDS resources and the contents for a provided node JSON
     * extracted from Envoy's config_dump endpoint.
     */
    @PostMapping(
        value = ["/snapshot"],
        consumes = ["application/json"],
        produces = ["application/v3+json", "application/json"]
    )
    fun snapshot(@RequestBody node: NodeV3): ResponseEntity<SnapshotDebugInfo> {
        val nodeHash = nodeGroup.hash(node)
        val snapshot = cache.getSnapshot(nodeHash)
        return if (snapshot == null) {
            throw SnapshotNotFoundException()
        } else {
            ResponseEntity(
                SnapshotDebugInfo(snapshot),
                HttpStatus.OK
            )
        }
    }

    @GetMapping("/snapshot-global")
    fun globalSnapshot(@RequestParam xds: Boolean?): ResponseEntity<SnapshotDebugInfo> {
        val globalSnapshot = snapshotUpdater.getGlobalSnapshot()
        if (xds == true) {
            return if (globalSnapshot?.xdsSnapshot == null) {
                throw GlobalSnapshotNotFoundException("Xds global snapshot missing")
            } else {
                ResponseEntity(
                    SnapshotDebugInfo(globalSnapshot.xdsSnapshot!!),
                    HttpStatus.OK
                )
            }
        }
        return if (globalSnapshot?.adsSnapshot == null) {
            throw GlobalSnapshotNotFoundException("Ads global snapshot missing")
        } else {
            ResponseEntity(
                SnapshotDebugInfo(globalSnapshot.adsSnapshot!!),
                HttpStatus.OK
            )
        }
    }

    @GetMapping("/snapshot-global/{service}")
    fun serviceSnapshot(
        @PathVariable service: String,
        @RequestParam dc: String?,
        @RequestParam(defaultValue = "false") xds: Boolean
    ): ResponseEntity<GlobalSnapshotInfo> {
        val updateResult = snapshotUpdater.getGlobalSnapshot()

        val globalSnapshot = if (xds) {
            updateResult?.xdsSnapshot
        } else {
            updateResult?.adsSnapshot
        }

        val cluster = extractCluster(globalSnapshot, service)

        if (cluster == null) {
            return ResponseEntity(HttpStatus.NOT_FOUND)
        }

        val clusterInfos = cluster.loadAssignment
            .endpointsList
            .filter { dc == null || it.locality.zone.endsWith(dc, true) }
            .flatMap { locality ->
                locality.lbEndpointsList.map {
                    val socketAddress = it.endpoint.address.socketAddress
                    EndpointInfo(locality.locality.zone, socketAddress.address, socketAddress.portValue)
                }
            }
        return ResponseEntity(GlobalSnapshotInfo(clusterInfos), HttpStatus.OK)
    }

    private fun extractCluster(globalSnapshot: GlobalSnapshot?, service: String): Cluster? {
        if (globalSnapshot == null) {
            logger.warn("Global snapshot is missing")
            return null
        }
        val cluster = globalSnapshot.clusters
            .resources()[service]
        if (cluster == null) {
            logger.warn("Can not find $service in global snapshot")
        }
        return cluster
    }

    @JsonComponent
    class ProtoSerializer : JsonSerializer<Message>() {
        final val typeRegistry: TypeRegistry = TypeRegistry.newBuilder()
            .add(HttpConnectionManager.getDescriptor())
            .add(Config.getDescriptor())
            .add(BoolValue.getDescriptor())
            .add(Duration.getDescriptor())
            .add(Struct.getDescriptor())
            .add(Value.getDescriptor())
            .add(RBAC.getDescriptor())
            .add(RBACFilter.getDescriptor())
            .add(Any.getDescriptor())
            .add(PathMatcher.getDescriptor())
            .add(StringMatcher.getDescriptor())
            .add(Tap.getDescriptor())
            .add(UpstreamTlsContext.getDescriptor())
            .add(Lua.getDescriptor())
            .build()

        val printer: JsonFormat.Printer = JsonFormat.printer().usingTypeRegistry(typeRegistry)

        override fun serialize(message: Message, gen: JsonGenerator, serializers: SerializerProvider) {
            gen.writeRawValue(printer.print(message))
        }
    }

    class SnapshotNotFoundException : RuntimeException("snapshot missing")
    class GlobalSnapshotNotFoundException(message: String) : RuntimeException(message)

    @ExceptionHandler
    @ResponseBody
    fun handleSnapshotMissing(exception: SnapshotNotFoundException): ResponseEntity<String> = ResponseEntity
        .status(HttpStatus.NOT_FOUND)
        .body(exception.message)

    @ExceptionHandler
    @ResponseBody
    fun handleGlobalSnapshotMissing(exception: GlobalSnapshotNotFoundException): ResponseEntity<String> = ResponseEntity
        .status(HttpStatus.NOT_FOUND)
        .body(exception.message)

    private companion object {
        val logger by logger()
    }
}
