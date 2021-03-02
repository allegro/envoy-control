package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.config

import com.google.protobuf.Struct
import com.google.protobuf.UInt32Value
import com.google.protobuf.util.JsonFormat
import io.envoyproxy.envoy.config.accesslog.v3.AccessLogFilter
import io.envoyproxy.envoy.config.accesslog.v3.ComparisonFilter
import io.envoyproxy.envoy.config.accesslog.v3.HeaderFilter
import io.envoyproxy.envoy.config.accesslog.v3.ResponseFlagFilter
import io.envoyproxy.envoy.config.accesslog.v3.StatusCodeFilter
import io.envoyproxy.envoy.config.core.v3.DataSource
import io.envoyproxy.envoy.config.core.v3.RuntimeUInt32
import io.envoyproxy.envoy.config.core.v3.SubstitutionFormatString
import io.envoyproxy.envoy.config.route.v3.HeaderMatcher
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.LocalReplyConfig
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.ResponseMapper
import io.envoyproxy.envoy.type.matcher.v3.RegexMatcher
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.HeaderMatcher as HeaderMatcherProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.LocalReplyMapperProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.MatcherAndMapper
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.ResponseFormat
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.util.StatusCodeFilterParser

class LocalReplyConfigFactory(
    localReplyMapperProperties: LocalReplyMapperProperties,
    private val jsonParser: JsonFormat.Parser = JsonFormat.parser()
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

    private fun createFilteredResponseMapper(matcherAndMapper: MatcherAndMapper): ResponseMapper.Builder {
        val responseMapperBuilder = ResponseMapper.newBuilder()
        createResponseFormat(matcherAndMapper.responseFormat)?.let {
            responseMapperBuilder.setBodyFormatOverride(it)
        }
        setResponseBody(matcherAndMapper, responseMapperBuilder)
        setStatusCode(matcherAndMapper, responseMapperBuilder)

        return when {
            matcherAndMapper.headerMatcher.name.isNotEmpty() -> {
                responseMapperBuilder.setFilter(
                    AccessLogFilter.newBuilder().setHeaderFilter(createHeaderFilter(matcherAndMapper.headerMatcher))
                )
                responseMapperBuilder
            }
            matcherAndMapper.responseFlagMatcher.isNotEmpty() -> {
                responseMapperBuilder.setFilter(
                    AccessLogFilter.newBuilder().setResponseFlagFilter(
                        createResponseFlagFilter(matcherAndMapper.responseFlagMatcher)
                    )
                )
                responseMapperBuilder
            }
            matcherAndMapper.statusCodeMatcher.isNotEmpty() -> {
                responseMapperBuilder.setFilter(
                    AccessLogFilter.newBuilder().setStatusCodeFilter(
                        createStatusCodeFilter(matcherAndMapper.statusCodeMatcher)
                    )
                )
                responseMapperBuilder
            }
            else -> {
                responseMapperBuilder
            }
        }
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

    private fun createHeaderFilter(headerMatcher: HeaderMatcherProperties): HeaderFilter.Builder {
        val headerFilterBuilder = HeaderFilter.newBuilder()
        val headerMatcherBuilder = HeaderMatcher.newBuilder()
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

    private fun createResponseFormat(responseFormat: ResponseFormat): SubstitutionFormatString? {
        val responseFormatBuilder = SubstitutionFormatString.newBuilder()
        if (responseFormat.contentType.isNotEmpty()) {
            responseFormatBuilder.contentType = responseFormat.contentType
        }

        return when {
            responseFormat.textFormat.isNotEmpty() -> {
                responseFormatBuilder.setTextFormat(responseFormat.textFormat).build()
            }
            responseFormat.jsonFormat.isNotEmpty() -> {
                val responseBody = Struct.newBuilder()
                jsonParser.merge(responseFormat.jsonFormat, responseBody)
                responseFormatBuilder.setJsonFormat(responseBody.build()).build()
            }
            else -> {
                null
            }
        }
    }

    private fun validateMatchersDefinition(matcherAndMapper: MatcherAndMapper) {
        var definitions = 0

        if (matcherAndMapper.statusCodeMatcher.isNotEmpty()) {
            definitions += 1
        }

        if (matcherAndMapper.responseFlagMatcher.isNotEmpty()) {
            definitions += 1
        }
        if (matcherAndMapper.headerMatcher.name.isNotBlank()) {
            definitions += 1
            validateHeaderMatcher(matcherAndMapper.headerMatcher)
        }

        if (definitions != 1) {
            throw IllegalArgumentException(
                "One and only one of: headerMatcher, responseFlagMatcher, statusCodeMatcher has to be defined.")
        }
    }

    private fun validateHeaderMatcher(headerMatcher: HeaderMatcherProperties) {
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
