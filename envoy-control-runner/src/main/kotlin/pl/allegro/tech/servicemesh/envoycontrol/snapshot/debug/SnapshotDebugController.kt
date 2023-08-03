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
import io.envoyproxy.envoy.config.rbac.v3.RBAC
import io.envoyproxy.envoy.extensions.filters.http.header_to_metadata.v3.Config
import io.envoyproxy.envoy.extensions.filters.http.lua.v3.Lua
import io.envoyproxy.envoy.extensions.filters.http.router.v3.Router
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager
import io.envoyproxy.envoy.extensions.retry.host.omit_canary_hosts.v3.OmitCanaryHostsPredicate
import io.envoyproxy.envoy.extensions.retry.host.omit_host_metadata.v3.OmitHostMetadataConfig
import io.envoyproxy.envoy.extensions.retry.host.previous_hosts.v3.PreviousHostsPredicate
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
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group
import io.envoyproxy.envoy.config.core.v3.Node as NodeV3
import io.envoyproxy.envoy.extensions.filters.http.rbac.v3.RBAC as RBACFilter

@RestController
class SnapshotDebugController(val debugService: SnapshotDebugService) {

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
        return ResponseEntity(
            debugService.snapshot(node),
            HttpStatus.OK
        )
    }

    @GetMapping("/groups")
    fun globalSnapshot(): ResponseEntity<Collection<Group>> {
        return ResponseEntity(
            debugService.groups(),
            HttpStatus.OK
        )
    }

    @GetMapping("/snapshot-global")
    fun globalSnapshot(@RequestParam(defaultValue = "false") xds: Boolean): ResponseEntity<SnapshotDebugInfo> {
        return ResponseEntity(
            debugService.globalSnapshot(xds),
            HttpStatus.OK
        )
    }

    @GetMapping("/snapshot-global/{service}")
    fun globalSnapshot(
        @PathVariable service: String,
        @RequestParam dc: String?,
        @RequestParam(defaultValue = "false") xds: Boolean
    ): ResponseEntity<EndpointInfoList> {
        return ResponseEntity(
            debugService.globalSnapshot(service, dc, xds),
            HttpStatus.OK
        )
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
            .add(Router.getDescriptor())
            .add(PreviousHostsPredicate.getDescriptor())
            .add(OmitCanaryHostsPredicate.getDescriptor())
            .add(OmitHostMetadataConfig.getDescriptor())
            .add(Tap.getDescriptor())
            .add(UpstreamTlsContext.getDescriptor())
            .add(Lua.getDescriptor())
            .build()

        val printer: JsonFormat.Printer = JsonFormat.printer().usingTypeRegistry(typeRegistry)

        override fun serialize(message: Message, gen: JsonGenerator, serializers: SerializerProvider) {
            gen.writeRawValue(printer.print(message))
        }
    }

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
}
