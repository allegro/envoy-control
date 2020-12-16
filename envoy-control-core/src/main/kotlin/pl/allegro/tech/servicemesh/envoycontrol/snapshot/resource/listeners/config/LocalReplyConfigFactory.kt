package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.config

import com.google.protobuf.ListValue
import com.google.protobuf.Struct
import com.google.protobuf.UInt32Value
import com.google.protobuf.Value
import io.envoyproxy.envoy.config.accesslog.v3.AccessLogFilter
import io.envoyproxy.envoy.config.accesslog.v3.ComparisonFilter
import io.envoyproxy.envoy.config.accesslog.v3.HeaderFilter
import io.envoyproxy.envoy.config.accesslog.v3.ResponseFlagFilter
import io.envoyproxy.envoy.config.accesslog.v3.StatusCodeFilter
import io.envoyproxy.envoy.config.core.v3.DataSource
import io.envoyproxy.envoy.config.core.v3.RuntimeUInt32
import io.envoyproxy.envoy.config.core.v3.SubstitutionFormatString
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.LocalReplyConfig
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.ResponseMapper
import io.envoyproxy.envoy.type.matcher.v3.RegexMatcher
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.HeaderMatcher
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.LocalReplyMapperProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.MatcherAndMapper
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.ResponseFormat
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.util.StatusCodeFilterParser

class LocalReplyConfigFactory(
    localReplyMapperProperties: LocalReplyMapperProperties
) {
    var configuration: LocalReplyConfig = LocalReplyConfig.getDefaultInstance()

    init {
        if (localReplyMapperProperties.enabled) {
            validateResponseFormatProperties(localReplyMapperProperties.responseFormat)
            localReplyMapperProperties.matchers.forEach {
                validateMatchersDefinition(it)
                validateResponseFormatProperties(it.responseFormat)
            }
            configuration = createLocalReplyMapper(localReplyMapperProperties)
        }
    }

    private fun createLocalReplyMapper(localReplyMapperProperties: LocalReplyMapperProperties): LocalReplyConfig {
        val localReplyBuilder = LocalReplyConfig.newBuilder()
        if (localReplyMapperProperties.matchers.isNotEmpty()) {
            localReplyMapperProperties.matchers.forEach {
                localReplyBuilder.addMappers(createFilteredResponseMapper(it))
            }
        }
        createResponseFormat(localReplyMapperProperties.responseFormat)?.let {
            localReplyBuilder.setBodyFormat(it)
        }
        return localReplyBuilder.build()
    }

    @SuppressWarnings("ReturnCount")
    private fun createFilteredResponseMapper(matcherAndMapper: MatcherAndMapper): ResponseMapper.Builder {
        val responseMapperBuilder = ResponseMapper.newBuilder()
        createResponseFormat(matcherAndMapper.responseFormat)?.let {
            responseMapperBuilder.setBodyFormatOverride(it)
        }
        setResponseBody(matcherAndMapper, responseMapperBuilder)
        setStatusCode(matcherAndMapper, responseMapperBuilder)

        if (matcherAndMapper.headerMatcher.name.isNotEmpty()) {
            responseMapperBuilder.setFilter(
                AccessLogFilter.newBuilder().setHeaderFilter(createHeaderFilter(matcherAndMapper.headerMatcher))
            )
            return responseMapperBuilder
        }
        if (matcherAndMapper.responseFlagMatcher.isNotEmpty()) {
            responseMapperBuilder.setFilter(
                AccessLogFilter.newBuilder().setResponseFlagFilter(
                    createResponseFlagFilter(matcherAndMapper.responseFlagMatcher)
                )
            )
            return responseMapperBuilder
        }
        if (matcherAndMapper.statusCodeMatcher.isNotEmpty()) {
            responseMapperBuilder.setFilter(
                AccessLogFilter.newBuilder().setStatusCodeFilter(
                    createStatusCodeFilter(matcherAndMapper.statusCodeMatcher)
                )
            )
            return responseMapperBuilder
        }
        return responseMapperBuilder
    }

    private fun setStatusCode(
        matcherAndMapper: MatcherAndMapper,
        responseMapperBuilder: ResponseMapper.Builder
    ) {
        if (matcherAndMapper.statusCodeToReturn != 0) {
            responseMapperBuilder.setStatusCode(UInt32Value.newBuilder().setValue(matcherAndMapper.statusCodeToReturn))
        }
    }

    private fun setResponseBody(
        matcherAndMapper: MatcherAndMapper,
        responseMapperBuilder: ResponseMapper.Builder
    ) {
        if (matcherAndMapper.bodyToReturn.isNotEmpty()) {
            responseMapperBuilder.setBody(DataSource.newBuilder().setInlineString(matcherAndMapper.bodyToReturn))
        }
    }

    private fun createStatusCodeFilter(statusCode: String): StatusCodeFilter.Builder {
        val parsedStatusCodeMatcher = StatusCodeFilterParser.parseStatusCodeFilter(statusCode)
        return StatusCodeFilter.newBuilder().setComparison(
            ComparisonFilter.newBuilder()
                .setOp(parsedStatusCodeMatcher.comparisonOperator)
                .setValue(
                    RuntimeUInt32.newBuilder()
                        .setDefaultValue(parsedStatusCodeMatcher.comparisonCode)
                        .setRuntimeKey("local_reply_mapper_http_code")
                        .build()
                )
        )
    }

    private fun createResponseFlagFilter(responseFlags: List<String>): ResponseFlagFilter.Builder {
        return ResponseFlagFilter.newBuilder().addAllFlags(responseFlags)
    }

    private fun createHeaderFilter(headerMatcher: HeaderMatcher): HeaderFilter.Builder {
        val headerFilterBuilder = HeaderFilter.newBuilder()
        val headerMatcherBuilder = io.envoyproxy.envoy.config.route.v3.HeaderMatcher.newBuilder()
            .setName(headerMatcher.name)
        return when {
            headerMatcher.regexMatch.isNotEmpty() -> {
                headerFilterBuilder.setHeader(
                    headerMatcherBuilder.setSafeRegexMatch(
                        RegexMatcher.newBuilder()
                            .setGoogleRe2(RegexMatcher.GoogleRE2.getDefaultInstance())
                            .setRegex(headerMatcher.regexMatch)
                    )
                )
            }
            headerMatcher.exactMatch.isNotEmpty() -> {
                headerFilterBuilder.setHeader(headerMatcherBuilder.setExactMatch(headerMatcher.exactMatch))
            }
            else -> {
                headerFilterBuilder.setHeader(headerMatcherBuilder.setPresentMatch(true))
            }
        }
    }

    @SuppressWarnings("ReturnCount")
    private fun createResponseFormat(responseFormat: ResponseFormat): SubstitutionFormatString? {
        if (responseFormat.textFormat.isNotEmpty()) {
            return SubstitutionFormatString.newBuilder()
                .setTextFormat(responseFormat.textFormat)
                .build()
        }
        if (responseFormat.jsonFormat.isNotEmpty()) {
            val newBuilder = SubstitutionFormatString.newBuilder()
            val responseBody = Struct.newBuilder()
            responseFormat.jsonFormat.forEach {
                setJsonResponseField(responseBody, it.key, it.value)
            }
            return newBuilder.setJsonFormat(responseBody.build()).build()
        }
        return null
    }

    private fun setJsonResponseField(
        responseBody: Struct.Builder,
        key: String,
        value: Any
    ) {
        when (value) {
            is Map<*, *> -> {
                val map = value as Map<String, Any>
                val struct = Struct.newBuilder()
                map.forEach {
                    struct.putFields(it.key, getValueField(it.value))
                }
                responseBody.putFields(key, Value.newBuilder().setStructValue(struct).build())
            }
            is List<*> -> {
                responseBody.putFields(key, getValueField(value))
            }
            is String -> {
                responseBody.putFields(key, Value.newBuilder().setStringValue(value).build())
            }
            is Number -> {
                responseBody.putFields(key, Value.newBuilder().setNumberValue(value.toDouble()).build())
            }
            else -> {
                throw IllegalArgumentException("Wrong data has been passed")
            }
        }
    }

    @SuppressWarnings("ReturnCount")
    private fun getValueField(
        value: Any?
    ): Value {
        when (value) {
            is Map<*, *> -> {
                val map = value as Map<String, Any>
                val struct = Struct.newBuilder()
                map.forEach {
                    struct.putFields(it.key, getValueField(it.value))
                }
                return Value.newBuilder().setStructValue(struct).build()
            }
            is List<*> -> {
                val list = ListValue.newBuilder()
                value.forEach {
                    list.addValues(getValueField(it))
                }
                return Value.newBuilder().setListValue(list).build()
            }
            is String -> {
                return Value.newBuilder().setStringValue(value).build()
            }
            is Number -> {
                return Value.newBuilder().setNumberValue(value.toDouble()).build()
            }
            else -> {
                throw IllegalArgumentException("Wrong data has been passed")
            }
        }
    }

    private fun validateMatchersDefinition(matcherAndMapper: MatcherAndMapper) {
        var matcherSet = 0
        if (matcherAndMapper.statusCodeMatcher.isNotEmpty()) {
            matcherSet = 1
        }
        if (matcherAndMapper.responseFlagMatcher.isNotEmpty()) {
            matcherSet = matcherSet xor (1 shl 1)
        }
        if (matcherAndMapper.headerMatcher.name.isNotBlank()) {
            matcherSet = matcherSet xor (1 shl 2)
            validateHeaderMatcher(matcherAndMapper.headerMatcher)
        }
        // Check if one and only one value is defined.
        // (matcherSet and (matcherSet - 1) check if matcherSet has only one bit set.
        if (matcherSet == 0 || (matcherSet and (matcherSet - 1) != 0)) {
            throw IllegalArgumentException(
                "One and only one of: headerMatcher, responseFlagMatcher, statusCodeMatcher has to be defined."
            )
        }
    }

    private fun validateHeaderMatcher(headerMatcher: HeaderMatcher) {
        if (headerMatcher.exactMatch.isNotEmpty() && headerMatcher.regexMatch.isNotEmpty()) {
            throw IllegalArgumentException(
                "Only one of: exactMatch, regexMatch can be defined."
            )
        }
    }

    private fun validateResponseFormatProperties(responseFormat: ResponseFormat) {
        if (responseFormat.jsonFormat.isNotEmpty() && responseFormat.textFormat.isNotEmpty()) {
            throw IllegalArgumentException(
                "Only one of: jsonFormat, textFormat can be defined."
            )
        }
    }
}
