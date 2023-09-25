package pl.allegro.tech.servicemesh.envoycontrol.config

import okhttp3.OkHttpClient
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier
import org.apache.hc.client5.http.ssl.TrustAllStrategy
import org.apache.hc.core5.ssl.SSLContextBuilder
import java.security.KeyStore
import java.time.Duration
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

object ClientsFactory {

    // envoys default timeout is 15 seconds while OkHttp is 10
    private const val TIMEOUT_SECONDS: Long = 20
    private var insecureOkHttpClient: OkHttpClient? = null
    private var okHttpClient: OkHttpClient? = null

    fun createClient(): OkHttpClient = if (okHttpClient == null) {
        OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .readTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .build().also { okHttpClient = it }
    } else {
        okHttpClient!!
    }

    fun createInsecureClient(): OkHttpClient = if (insecureOkHttpClient == null) {
        OkHttpClient.Builder()
            .hostnameVerifier(NoopHostnameVerifier())
            .sslSocketFactory(getInsecureSSLSocketFactory(), getInsecureTrustManager())
            .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .readTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .build().also { insecureOkHttpClient = it }
    } else {
        insecureOkHttpClient!!
    }

    private fun getInsecureSSLSocketFactory(): SSLSocketFactory {
        val builder = SSLContextBuilder()
        builder.loadTrustMaterial(null, TrustAllStrategy())
        return builder.build().socketFactory
    }

    private fun getInsecureTrustManager(): X509TrustManager {
        val trustManagerFactory: TrustManagerFactory = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm())
        trustManagerFactory.init(null as KeyStore?)
        val trustManagers = trustManagerFactory.trustManagers
        return trustManagers[0] as X509TrustManager
    }
}
