package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters

import com.google.protobuf.Any
import com.google.protobuf.Struct
import com.google.protobuf.Value
import io.envoyproxy.envoy.config.accesslog.v3.AccessLog
import io.envoyproxy.envoy.config.accesslog.v3.AccessLogFilter
import io.envoyproxy.envoy.config.accesslog.v3.AndFilter
import io.envoyproxy.envoy.config.accesslog.v3.ComparisonFilter
import io.envoyproxy.envoy.config.accesslog.v3.DurationFilter
import io.envoyproxy.envoy.config.accesslog.v3.HeaderFilter
import io.envoyproxy.envoy.config.accesslog.v3.NotHealthCheckFilter
import io.envoyproxy.envoy.config.accesslog.v3.ResponseFlagFilter
import io.envoyproxy.envoy.config.accesslog.v3.StatusCodeFilter
import io.envoyproxy.envoy.config.core.v3.RuntimeUInt32
import io.envoyproxy.envoy.config.route.v3.HeaderMatcher
import io.envoyproxy.envoy.extensions.access_loggers.file.v3.FileAccessLog
import io.envoyproxy.envoy.type.matcher.v3.RegexMatcher
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
        val accessLogFilters = mutableListOf<AccessLogFilter?>()
        settings.statusCodeFilterSettings?.let {
            accessLogFilters.add(createStatusCodeFilter(it))
        }
        settings.durationFilterSettings?.let {
            accessLogFilters.add(createDurationFilter(it))
        }
        settings.notHealthCheckFilter?.let {
            if (it) {
                accessLogFilters.add(
                    AccessLogFilter.newBuilder().setNotHealthCheckFilter(NotHealthCheckFilter.newBuilder()).build()
                )
            }
        }
        settings.responseFlagFilter?.let {
            accessLogFilters.add(createResponseFlagFilter(it))
        }
        settings.headerFilter?.let {
            accessLogFilters.add(createHeaderFilter(it))
        }

        val filter = createFilterForAccessLog(accessLogFilters)
        filter?.let { this.setFilter(it) }
    }

    private fun createFilterForAccessLog(accessLogFilters: List<AccessLogFilter?>): AccessLogFilter? {
        return when {
            accessLogFilters.isEmpty() -> null
            accessLogFilters.size == 1 -> accessLogFilters[0]
            else -> {
                val andFilter = AndFilter.newBuilder()
                    .addAllFilters(accessLogFilters)
                    .build()
                AccessLogFilter.newBuilder()
                    .setAndFilter(andFilter)
                    .build()
            }
        }
    }

    private fun createResponseFlagFilter(flags: Iterable<String>): AccessLogFilter {
        return AccessLogFilter.newBuilder()
            .setResponseFlagFilter(
                ResponseFlagFilter.newBuilder()
                    .addAllFlags(flags)
                    .build()
            )
            .build()
    }

    private fun createDurationFilter(
        settings: ComparisonFilterSettings
    ): AccessLogFilter {
        return AccessLogFilter.newBuilder().setDurationFilter(
            DurationFilter.newBuilder()
                .setComparison(
                    createComparison(settings, "access_log_filter_duration")
                )
                .build()
        )
            .build()
    }

    private fun createStatusCodeFilter(
        settings: ComparisonFilterSettings
    ): AccessLogFilter {
        return AccessLogFilter.newBuilder().setStatusCodeFilter(
            StatusCodeFilter.newBuilder()
                .setComparison(
                    createComparison(settings, "access_log_filter_http_code")
                )
                .build()
        ).build()
    }

    private fun createHeaderFilter(settings: HeaderFilterSettings): AccessLogFilter {
        return AccessLogFilter.newBuilder().setHeaderFilter(
            HeaderFilter.newBuilder()
                .setHeader(
                    HeaderMatcher.newBuilder()
                        .setName(settings.headerName)
                        .setSafeRegexMatch(
                            RegexMatcher.newBuilder()
                                .setGoogleRe2(RegexMatcher.GoogleRE2.getDefaultInstance())
                                .setRegex(settings.regex)
                        )
                        .build()
                )
                .build()
        ).build()
    }

    private fun createComparison(settings: ComparisonFilterSettings, runtimeKey: String): ComparisonFilter {
        return ComparisonFilter.newBuilder()
            .setOp(settings.comparisonOperator)
            .setValue(
                RuntimeUInt32.newBuilder()
                    .setDefaultValue(settings.comparisonCode)
                    .setRuntimeKey(runtimeKey)
                    .build()
            )
            .build()
    }

    private fun stringValue(value: String) = Value.newBuilder().setStringValue(value).build()
}
