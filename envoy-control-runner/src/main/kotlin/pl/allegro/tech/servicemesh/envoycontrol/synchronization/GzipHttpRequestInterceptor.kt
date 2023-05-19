package pl.allegro.tech.servicemesh.envoycontrol.synchronization

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpRequest
import org.springframework.http.HttpStatus
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.ClientHttpResponse
import java.io.InputStream
import java.util.zip.GZIPInputStream

class GzipHttpRequestInterceptor : ClientHttpRequestInterceptor {
    companion object {
        const val HEADER_GZIP = "gzip"
    }

    override fun intercept(
        request: HttpRequest,
        body: ByteArray,
        execution: ClientHttpRequestExecution
    ): ClientHttpResponse {
        request.headers.add(HttpHeaders.CONTENT_ENCODING, HEADER_GZIP)
        val gzipResponse = execution.execute(request, body)
        return object : ClientHttpResponse {
            override fun getHeaders(): HttpHeaders = gzipResponse.headers

            override fun getBody(): InputStream = GZIPInputStream(gzipResponse.body)

            override fun close() = gzipResponse.close()

            override fun getStatusCode(): HttpStatus = gzipResponse.statusCode

            override fun getRawStatusCode(): Int = gzipResponse.rawStatusCode

            override fun getStatusText(): String = gzipResponse.statusText
        }
    }
}
