package pl.allegro.tech.servicemesh.envoycontrol.utils

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import pl.allegro.tech.servicemesh.envoycontrol.logger
import java.io.ByteArrayOutputStream
import java.lang.reflect.Type
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.text.Charsets.UTF_8

class GzipUtils(val objectMapper: ObjectMapper) {

    private val logger by logger()

    fun gzip(content: Any): ByteArray {
        try {
            val json = objectMapper.writeValueAsString(content)
            val outputStream = ByteArrayOutputStream()
            GZIPOutputStream(outputStream).bufferedWriter(UTF_8).use { it.write(json) }
            return outputStream.toByteArray()
        } catch (e: Exception) {
            logger.error(e.message)
            throw IllegalStateException("Couldn't compress data $content")
        }
    }

    fun <T> unGzip(content: ByteArray, javaClass: Class<T>): T {
        try {
            val jsonString = GZIPInputStream(content.inputStream())
                .bufferedReader(UTF_8)
                .use { it.readText() }

            return objectMapper.readValue(jsonString, object : TypeReference<T>() {
                override fun getType(): Type = javaClass
            })
        } catch (e: Exception) {
            logger.error(e.message)
            throw IllegalStateException("Couldn't uncompress data $content, target type $javaClass")
        }
    }
}
