package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.HeaderMatcher
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.LocalReplyMapperProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.MatcherAndMapper
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.ResponseFormat

class LocalReplyConfigFactoryTest {

    companion object {
        @JvmStatic
        fun invalidMatcherCombinations() = listOf(
            Arguments.of(MatcherAndMapper().apply {
                responseFlagMatcher = listOf("UH", "UF")
                statusCodeMatcher = "EQ:400"
                headerMatcher = HeaderMatcher().apply {
                    name = "test"
                }
            }, "Invalid configuration with all machers defined"),
            Arguments.of(MatcherAndMapper().apply {
                responseFlagMatcher = listOf("UH", "UF")
                headerMatcher = HeaderMatcher().apply {
                    name = "test"
                }
            }, "Invalid configuration with responseFlagMatcher, headerMatcher defined"),
            Arguments.of(MatcherAndMapper().apply {
                statusCodeMatcher = "EQ:400"
                headerMatcher = HeaderMatcher().apply {
                    name = "test"
                }
            }, "Invalid configuration with statusCodeMatcher, headerMatcher defined"),
            Arguments.of(MatcherAndMapper().apply {
                responseFlagMatcher = listOf("UH", "UF")
                headerMatcher = HeaderMatcher().apply {
                    name = "test"
                }
            }, "Invalid configuration with responseFlagMatcher, headerMatcher defined"),
            Arguments.of(MatcherAndMapper().apply {
                statusCodeToReturn = 500
            }, "Invalid configuration with no matcher defined")
        )
    }

    @Test
    fun `should set text format for response format`() {
        // given
        val properties = LocalReplyMapperProperties().apply {
            enabled = true
            responseFormat = ResponseFormat().apply {
                textFormat = "%LOCAL_REPLY%"
            }
        }
        // when
        val factory = LocalReplyConfigFactory(properties)

        // then
        assertThat(factory.configuration.bodyFormat.textFormat).isEqualTo(properties.responseFormat.textFormat)
    }

    @Test
    fun `should set json format for response format`() {
        // given
        val expected = expectedConfigWhenBodyFormatIsCustomJson
        val properties = LocalReplyMapperProperties().apply {
            enabled = true
            responseFormat = ResponseFormat().apply {
                jsonFormat = """{
                    "body":"%BODY%",
                    "statusCode":"%STATUS_CODE%"
                }""".trimIndent()
            }
        }
        // when
        val factory = LocalReplyConfigFactory(properties)

        // then
        assertThat(factory.configuration.toString().trimIndent()).isEqualTo(expected)
    }

    @Test
    fun `should set status code matcher and overridden response format`() {
        // given
        val expected = expectedConfigWithStatusCodeMatcherAndOverriddenResponseFormatAndOverriddenStatusCode
        val properties = LocalReplyMapperProperties().apply {
            enabled = true
            matchers = listOf(
                MatcherAndMapper().apply {
                    statusCodeToReturn = 500
                    bodyToReturn = "Something went wrong and matched with 400"
                    statusCodeMatcher = "EQ:400"
                    responseFormat = ResponseFormat().apply {
                        jsonFormat = """{
                            "body":"status code body"
                        }"""
                    }
                }
            )
            responseFormat = ResponseFormat().apply {
                jsonFormat = """{ 
                    "body":"%BODY%",
                    "statusCode":"%STATUS_CODE%"
                }"""
            }
        }
        // when
        val factory = LocalReplyConfigFactory(properties)

        // then
        assertThat(factory.configuration.toString().trimIndent()).isEqualTo(expected)
    }

    @Test
    fun `should create configuration for exact header matcher`() {
        // given
        val expected = expectedConfigForHeaderExactMatch
        val properties = LocalReplyMapperProperties().apply {
            enabled = true
            matchers = listOf(
                MatcherAndMapper().apply {
                    headerMatcher = HeaderMatcher().apply {
                        name = ":path"
                        exactMatch = "match_this"
                    }
                }
            )
        }
        // when
        val factory = LocalReplyConfigFactory(properties)

        // then
        assertThat(factory.configuration.toString().trimIndent()).isEqualTo(expected)
    }

    @Test
    fun `should create configuration for regex header matcher`() {
        // given
        val expected = expectedConfigForHeaderRegexMatch
        val properties = LocalReplyMapperProperties().apply {
            enabled = true
            matchers = listOf(
                MatcherAndMapper().apply {
                    headerMatcher = HeaderMatcher().apply {
                        name = ":path"
                        regexMatch = "regex_*"
                    }
                }
            )
        }
        // when
        val factory = LocalReplyConfigFactory(properties)

        // then
        assertThat(factory.configuration.toString().trimIndent()).isEqualTo(expected)
    }

    @Test
    fun `should create configuration for presence header matcher`() {
        // given
        val expected = expectedConfigForHeaderPresenceMatch
        val properties = LocalReplyMapperProperties().apply {
            enabled = true
            matchers = listOf(
                MatcherAndMapper().apply {
                    headerMatcher = HeaderMatcher().apply {
                        name = ":path"
                    }
                }
            )
        }
        // when
        val factory = LocalReplyConfigFactory(properties)

        // then
        assertThat(factory.configuration.toString().trimIndent()).isEqualTo(expected)
    }

    @Test
    fun `should create configuration for response flags matcher`() {
        // given
        val expected = expectedConfigForResponseFlagsMatcher
        val properties = LocalReplyMapperProperties().apply {
            enabled = true
            matchers = listOf(
                MatcherAndMapper().apply {
                    responseFlagMatcher = listOf("UH", "UF")
                }
            )
        }
        // when
        val factory = LocalReplyConfigFactory(properties)

        // then
        assertThat(factory.configuration.toString().trimIndent()).isEqualTo(expected)
    }

    @Test
    fun `should create nested json format with custom content type`() {
        // given
        val expected = expectedNestedResponseFormat
        val properties = LocalReplyMapperProperties().apply {
            enabled = true
            responseFormat.apply {
                contentType = "application/envoy+json"
                jsonFormat = """{
                    "destination":{
                        "service-tag":"test",
                        "responseFlags":["UH","UF"],
                        "listOfIntegers":[1, 2, 3]
                    },
                    "reason":1,
                    "listOfMap":[
                        {
                            "test":"test"
                        },
                        {
                            "test2":"test2"
                        }
                    ]
                }""".trimIndent()
            }
        }
        // when
        val factory = LocalReplyConfigFactory(properties)

        // then
        assertThat(factory.configuration.toString().trimIndent()).isEqualTo(expected)
    }

    @ParameterizedTest
    @MethodSource("invalidMatcherCombinations")
    fun `should throw exception when more then one matcher is defined in MatcherAndMapper`(
        matcherAndMapper: MatcherAndMapper
    ) {
        // given
        val properties = LocalReplyMapperProperties().apply {
            enabled = true
            matchers = listOf(matcherAndMapper)
        }

        // expects
        assertThatExceptionOfType(IllegalArgumentException::class.java)
            .isThrownBy { LocalReplyConfigFactory(properties) }
            .satisfies {
                assertThat(it.message).isEqualTo(
                    "One and only one of: headerMatcher, responseFlagMatcher, statusCodeMatcher has to be defined."
                )
            }
    }

    @Test
    fun `should throw exception when more then one matcher is defined for HeaderMatcher`() {
        // given
        val properties = LocalReplyMapperProperties().apply {
            enabled = true
            matchers = listOf(
                MatcherAndMapper().apply {
                    headerMatcher = HeaderMatcher().apply {
                        name = ":path"
                        regexMatch = "regex_*"
                        exactMatch = "exact"
                    }
                }
            )
        }

        // expects
        assertThatExceptionOfType(IllegalArgumentException::class.java)
            .isThrownBy { LocalReplyConfigFactory(properties) }
            .satisfies {
                assertThat(it.message).isEqualTo(
                    "Only one of: exactMatch, regexMatch can be defined."
                )
            }
    }

    @Test
    fun `should throw exception when more then one property is defined in response format`() {
        // given
        val properties = LocalReplyMapperProperties().apply {
            enabled = true
            responseFormat = ResponseFormat().apply {
                jsonFormat = """{
                    "body":"custom response body"
                }"""
                textFormat = "custom response body"
            }
        }

        // expects
        assertThatExceptionOfType(IllegalArgumentException::class.java)
            .isThrownBy { LocalReplyConfigFactory(properties) }
            .satisfies {
                assertThat(it.message).isEqualTo(
                    "Only one of: jsonFormat, textFormat can be defined."
                )
            }
    }

    @Test
    fun `should throw exception when more then one property is defined in specific matcher response format`() {
        // given
        val properties = LocalReplyMapperProperties().apply {
            enabled = true
            matchers = listOf(
                MatcherAndMapper().apply {
                    headerMatcher = HeaderMatcher().apply {
                        name = ":path"
                        regexMatch = "regex_*"
                    }
                    responseFormat = ResponseFormat().apply {
                        textFormat = "custom response body"
                        jsonFormat = """{
                            "body":"custom response body"
                        }"""
                    }
                }
            )
        }

        // expects
        assertThatExceptionOfType(IllegalArgumentException::class.java)
            .isThrownBy { LocalReplyConfigFactory(properties) }
            .satisfies {
                assertThat(it.message).isEqualTo(
                    "Only one of: jsonFormat, textFormat can be defined."
                )
            }
    }

    private val expectedConfigForResponseFlagsMatcher = """mappers {
  filter {
    response_flag_filter {
      flags: "UH"
      flags: "UF"
    }
  }
}"""

    private val expectedConfigForHeaderRegexMatch = """mappers {
  filter {
    header_filter {
      header {
        name: ":path"
        safe_regex_match {
          google_re2 {
          }
          regex: "regex_*"
        }
      }
    }
  }
}"""

    private val expectedConfigForHeaderExactMatch = """mappers {
  filter {
    header_filter {
      header {
        name: ":path"
        exact_match: "match_this"
      }
    }
  }
}""".trimIndent()

    private val expectedConfigForHeaderPresenceMatch = """mappers {
  filter {
    header_filter {
      header {
        name: ":path"
        present_match: true
      }
    }
  }
}""".trimIndent()

    private val expectedConfigWhenBodyFormatIsCustomJson = """body_format {
  json_format {
    fields {
      key: "body"
      value {
        string_value: "%BODY%"
      }
    }
    fields {
      key: "statusCode"
      value {
        string_value: "%STATUS_CODE%"
      }
    }
  }
}""".trimIndent()

    private val expectedConfigWithStatusCodeMatcherAndOverriddenResponseFormatAndOverriddenStatusCode = """mappers {
  filter {
    status_code_filter {
      comparison {
        value {
          default_value: 400
          runtime_key: "local_reply_mapper_http_code"
        }
      }
    }
  }
  status_code {
    value: 500
  }
  body {
    inline_string: "Something went wrong and matched with 400"
  }
  body_format_override {
    json_format {
      fields {
        key: "body"
        value {
          string_value: "status code body"
        }
      }
    }
  }
}
body_format {
  json_format {
    fields {
      key: "body"
      value {
        string_value: "%BODY%"
      }
    }
    fields {
      key: "statusCode"
      value {
        string_value: "%STATUS_CODE%"
      }
    }
  }
}""".trimIndent()

    private val expectedNestedResponseFormat = """body_format {
  json_format {
    fields {
      key: "destination"
      value {
        struct_value {
          fields {
            key: "service-tag"
            value {
              string_value: "test"
            }
          }
          fields {
            key: "responseFlags"
            value {
              list_value {
                values {
                  string_value: "UH"
                }
                values {
                  string_value: "UF"
                }
              }
            }
          }
          fields {
            key: "listOfIntegers"
            value {
              list_value {
                values {
                  number_value: 1.0
                }
                values {
                  number_value: 2.0
                }
                values {
                  number_value: 3.0
                }
              }
            }
          }
        }
      }
    }
    fields {
      key: "reason"
      value {
        number_value: 1.0
      }
    }
    fields {
      key: "listOfMap"
      value {
        list_value {
          values {
            struct_value {
              fields {
                key: "test"
                value {
                  string_value: "test"
                }
              }
            }
          }
          values {
            struct_value {
              fields {
                key: "test2"
                value {
                  string_value: "test2"
                }
              }
            }
          }
        }
      }
    }
  }
  content_type: "application/envoy+json"
}""".trimIndent()
}
