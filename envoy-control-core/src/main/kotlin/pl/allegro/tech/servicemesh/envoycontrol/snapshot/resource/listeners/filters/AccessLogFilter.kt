package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters

import com.google.protobuf.Any
import com.google.protobuf.Struct
import com.google.protobuf.Value
import io.envoyproxy.envoy.config.accesslog.v3.AccessLog
import io.envoyproxy.envoy.config.accesslog.v3.AccessLogFilter
import io.envoyproxy.envoy.config.accesslog.v3.ComparisonFilter
import io.envoyproxy.envoy.config.accesslog.v3.DurationFilter
import io.envoyproxy.envoy.config.accesslog.v3.HeaderFilter
import io.envoyproxy.envoy.config.accesslog.v3.NotHealthCheckFilter
import io.envoyproxy.envoy.config.accesslog.v3.ResponseFlagFilter
import io.envoyproxy.envoy.config.accesslog.v3.StatusCodeFilter
import io.envoyproxy.envoy.config.accesslog.v3.TraceableFilter
import io.envoyproxy.envoy.config.core.v3.RuntimeUInt32
import io.envoyproxy.envoy.config.route.v3.HeaderMatcher
import io.envoyproxy.envoy.extensions.access_loggers.file.v3.FileAccessLog
import pl.allegro.tech.servicemesh.envoycontrol.groups.AccessLogFilterSettings
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotProperties
import pl.allegro.tech.servicemesh.envoycontrol.utils.ComparisonFilterSettings
import pl.allegro.tech.servicemesh.envoycontrol.utils.HeaderFilterSettings

class AccessLogFilter(
    snapshotProperties: SnapshotProperties
) {
    private val accessLog = snapshotProperties.dynamicListeners.httpFilters.accessLog
    private val accessLogTimeFormat = stringValue(accessLog.timeFormat)
    private val accessLogMessageFormat = stringValue(accessLog.messageFormat)
    private val accessLogLevel = stringValue(accessLog.level)
    private val accessLogLogger = stringValue(accessLog.logger)

    fun createFilter(
        accessLogPath: String,
        accessLogType: String,
        accessLogFilterSettings: AccessLogFilterSettings?
    ): AccessLog {
        val builder = AccessLog.newBuilder().setName("envoy.file_access_log")

        accessLogFilterSettings?.let { settings ->
            builder.buildFromSettings(settings)
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
                            .putAllFields(accessLog.customFields.mapValues { stringValue(it.value) })
                            .build()
                    )
                    .build()
            )
        )
            .build()
    }

    private fun AccessLog.Builder.buildFromSettings(settings: AccessLogFilterSettings) {
        val accessLogFilterBuilder = AccessLogFilter.newBuilder()
        settings.statusCodeFilterSettings?.let {
                createStatusCodeFilter(it, accessLogFilterBuilder)
        }
        settings.durationFilterSettings?.let {
                createDurationFilter(it, accessLogFilterBuilder)
        }
        settings.notHealthCheckFilter?.let {
            if (it) {
                    accessLogFilterBuilder.setNotHealthCheckFilter(
                        NotHealthCheckFilter.newBuilder()
                )
            }
        }
        settings.traceableFilter?.let {
            if (it) {
                    accessLogFilterBuilder.setTraceableFilter(
                        TraceableFilter.newBuilder()
                )
            }
        }
        settings.responseFlagFilter?.let {
                createResponseFlagFilter(it, accessLogFilterBuilder)
        }
        settings.headerFilter?.let {
                createHeaderFilter(it, accessLogFilterBuilder)
        }
        this.setFilter(accessLogFilterBuilder)
    }

    private fun createResponseFlagFilter(flags: Iterable<String>, accessLogFilterBuilder: AccessLogFilter.Builder) {
        accessLogFilterBuilder.responseFlagFilter = ResponseFlagFilter.newBuilder()
            .addAllFlags(flags)
            .build()
    }

    private fun createDurationFilter(
        settings: ComparisonFilterSettings,
        accessLogFilterBuilder: AccessLogFilter.Builder
    ) {
        accessLogFilterBuilder.durationFilter = DurationFilter.newBuilder()
            .setComparison(
                createComparison(settings, "access_log_filter_duration")
            )
            .build()
    }

    private fun createStatusCodeFilter(
        settings: ComparisonFilterSettings,
        accessLogFilterBuilder: AccessLogFilter.Builder
    ) {
        accessLogFilterBuilder.statusCodeFilter = StatusCodeFilter.newBuilder()
            .setComparison(
                createComparison(settings, "access_log_filter_http_code")
            )
            .build()
    }

    private fun createHeaderFilter(settings: HeaderFilterSettings, accessLogFilterBuilder: AccessLogFilter.Builder) {
        accessLogFilterBuilder.setHeaderFilter(HeaderFilter.newBuilder()
            .setHeader(
                HeaderMatcher.newBuilder()
                    .setName(settings.headerName)
                    .setSafeRegexMatch(settings.regex)
                    .build()
            )
            .build())
    }

    private fun createComparison(settings: ComparisonFilterSettings, runtimeKey: String): ComparisonFilter {
        return ComparisonFilter.newBuilder()
            .setOp(settings.comparisonOperator)
            .setValue(
                RuntimeUInt32.newBuilder()
                    .setDefaultValue(settings.comparisonCode)
                    .setRuntimeKey("access_log_filter_http_code")
                    .build()
            )
            .build()
    }

    private fun stringValue(value: String) = Value.newBuilder().setStringValue(value).build()
}
