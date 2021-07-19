package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters

import com.google.protobuf.Any
import com.google.protobuf.Duration
import com.google.protobuf.Struct
import com.google.protobuf.Value
import io.envoyproxy.envoy.config.accesslog.v3.AccessLog
import io.envoyproxy.envoy.config.accesslog.v3.AccessLogFilter
import io.envoyproxy.envoy.config.accesslog.v3.ComparisonFilter
import io.envoyproxy.envoy.config.accesslog.v3.StatusCodeFilter
import io.envoyproxy.envoy.config.core.v3.RuntimeUInt32
import io.envoyproxy.envoy.extensions.access_loggers.file.v3.FileAccessLog
import pl.allegro.tech.servicemesh.envoycontrol.groups.AccessLogFilterSettings
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.util.StatusCodeFilterSettings

class AccessLogFilter(
    snapshotProperties: SnapshotProperties
) {
    private val listenersFactoryProperties = snapshotProperties.dynamicListeners
    private val accessLogTimeFormat = stringValue(listenersFactoryProperties.httpFilters.accessLog.timeFormat)
    private val accessLogMessageFormat = stringValue(listenersFactoryProperties.httpFilters.accessLog.messageFormat)
    private val accessLogLevel = stringValue(listenersFactoryProperties.httpFilters.accessLog.level)
    private val accessLogLogger = stringValue(listenersFactoryProperties.httpFilters.accessLog.logger)

    fun createFilter(
        accessLogPath: String,
        accessLogType: String,
        accessLogFilterSettings: AccessLogFilterSettings?
    ): AccessLog {
        val builder = AccessLog.newBuilder().setName("envoy.file_access_log")

        accessLogFilterSettings?.let { settings ->
            settings.statusCodeFilterSettings?.let {
                builder.buildFromSettings(it)
            }
        }

        return builder.setTypedConfig(
            Any.pack(
                FileAccessLog.newBuilder()
                    .setPath(accessLogPath)
                    .setJsonFormat(
                        Struct.newBuilder()
                            .putFields("time", accessLogTimeFormat)
                            .putFields("message", accessLogMessageFormat)
                            .putFields("level", accessLogLevel)
                            .putFields("logger", accessLogLogger)
                            .putFields("access_log_type", stringValue(accessLogType))
                            .putFields("request_protocol", stringValue("%PROTOCOL%"))
                            .putFields("request_method", stringValue("%REQ(:METHOD)%"))
                            .putFields(
                                "request_authority",
                                stringValue("%REQ(:authority)%")
                            )
                            .putFields("request_path", stringValue("%REQ(:PATH)%"))
                            .putFields("response_code", stringValue("%RESPONSE_CODE%"))
                            .putFields("response_flags", stringValue("%RESPONSE_FLAGS%"))
                            .putFields("bytes_received", stringValue("%BYTES_RECEIVED%"))
                            .putFields("bytes_sent", stringValue("%BYTES_SENT%"))
                            .putFields("duration_ms", stringValue("%DURATION%"))
                            .putFields(
                                "downstream_remote_address",
                                stringValue("%DOWNSTREAM_REMOTE_ADDRESS%")
                            )
                            .putFields("upstream_host", stringValue("%UPSTREAM_HOST%"))
                            .putFields("user_agent", stringValue("%REQ(USER-AGENT)%"))
                            .build()
                    )
                    .build()
            )
        )
            .build()
    }

    private fun AccessLog.Builder.buildFromSettings(settings: StatusCodeFilterSettings) {
        this.setFilter(
            AccessLogFilter.newBuilder().setStatusCodeFilter(
                StatusCodeFilter.newBuilder()
                    .setComparison(
                        ComparisonFilter.newBuilder()
                            .setOp(settings.comparisonOperator)
                            .setValue(
                                RuntimeUInt32.newBuilder()
                                    .setDefaultValue(settings.comparisonCode)
                                    .setRuntimeKey("access_log_filter_http_code")
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
        )
    }

    private fun stringValue(value: String) = Value.newBuilder().setStringValue(value).build()

    private fun durationInSeconds(value: Long) = Duration.newBuilder().setSeconds(value).build()
}
