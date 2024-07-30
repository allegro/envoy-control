package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters

import com.google.protobuf.BoolValue
import com.google.protobuf.UInt32Value
import io.envoyproxy.envoy.config.core.v3.RuntimeFeatureFlag
import io.envoyproxy.envoy.config.core.v3.TypedExtensionConfig
import io.envoyproxy.envoy.config.filter.http.gzip.v2.Gzip.CompressionLevel.Enum.BEST_VALUE
import io.envoyproxy.envoy.extensions.compression.brotli.compressor.v3.Brotli
import io.envoyproxy.envoy.extensions.compression.gzip.compressor.v3.Gzip
import io.envoyproxy.envoy.extensions.filters.http.compressor.v3.Compressor
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotProperties

class CompressionFilterFactory(val properties: SnapshotProperties) {

    fun gzipCompressionFilter(group: Group): HttpFilter? {
        val compressionLevel = Gzip.CompressionLevel.forNumber(
            group.compressionConfig.gzip?.quality
                ?: properties.compression.gzip.quality
        ) ?: Gzip.CompressionLevel.forNumber(BEST_VALUE)
        return if (group.compressionConfig.gzip?.enabled == true) {
            compressionFilter(
                TypedExtensionConfig.newBuilder()
                    .setName("envoy.compression.gzip.compressor")
                    .setTypedConfig(
                        com.google.protobuf.Any.pack(
                            Gzip.newBuilder()
                                .setCompressionStrategy(Gzip.CompressionStrategy.DEFAULT_STRATEGY)
                                .setCompressionLevel(compressionLevel)
                                .build()
                        )
                    ),
                properties.compression.gzip.chooseFirst
            )
        } else null
    }

    fun brotliCompressionFilter(group: Group): HttpFilter? {
        val compressionLevel = group.compressionConfig.brotli?.quality ?: properties.compression.brotli.quality
        return if (group.compressionConfig.brotli?.enabled == true) {
            compressionFilter(
                TypedExtensionConfig.newBuilder()
                    .setName("envoy.compression.brotli.compressor")
                    .setTypedConfig(
                        com.google.protobuf.Any.pack(
                            Brotli.newBuilder()
                                .setQuality(UInt32Value.of(compressionLevel))
                                .build()
                        )
                    ),
                properties.compression.brotli.chooseFirst
            )
        } else null
    }

    private fun compressionFilter(library: TypedExtensionConfig.Builder, chooseFirst: Boolean) =
        HttpFilter.newBuilder()
            .setName("envoy.filters.http.compressor")
            .setTypedConfig(
                com.google.protobuf.Any.pack(
                    Compressor.newBuilder()
                        .setChooseFirst(chooseFirst)
                        .setRequestDirectionConfig(
                            Compressor.RequestDirectionConfig.newBuilder()
                                .setCommonConfig(
                                    commonDirectionConfig(
                                        "request_compressor_enabled",
                                        properties.compression.requestCompressionEnabled
                                    )
                                )
                        ).setResponseDirectionConfig(
                            Compressor.ResponseDirectionConfig.newBuilder()
                                .setCommonConfig(
                                    commonDirectionConfig(
                                        "response_compressor_enabled",
                                        properties.compression.responseCompressionEnabled
                                    )
                                )
                                .setDisableOnEtagHeader(properties.compression.disableOnEtagHeader)
                        )
                        .setCompressorLibrary(library)
                        .build()
                )
            ).build()

    private fun commonDirectionConfig(runtimeKey: String, defaultValue: Boolean) =
        Compressor.CommonDirectionConfig.newBuilder()
            .setEnabled(
                RuntimeFeatureFlag.newBuilder().setRuntimeKey(runtimeKey)
                    .setDefaultValue(BoolValue.of(defaultValue))
            )
            .setMinContentLength(UInt32Value.of(properties.compression.minContentLength))
}
