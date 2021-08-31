package pl.allegro.tech.servicemesh.envoycontrol.config

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import org.apache.http.conn.ssl.NoopHostnameVerifier
import org.apache.http.conn.ssl.TrustAllStrategy
import org.apache.http.ssl.SSLContextBuilder
import java.security.KeyStore
import java.time.Duration
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

object ClientsFactory {

    // envoys default timeout is 15 seconds while OkHttp is 10
    private const val TIMEOUT_SECONDS: Long = 20
    private const val MAX_IDLE_CONNECTIONS: Int = 10

    fun createClient(): OkHttpClient = OkHttpClient.Builder()
        .readTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
        .build()

    fun createInsecureClient(): OkHttpClient = OkHttpClient.Builder()
        .hostnameVerifier(NoopHostnameVerifier())
        .sslSocketFactory(getInsecureSSLSocketFactory(), getInsecureTrustManager())
        .readTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
        .connectionPool(ConnectionPool(MAX_IDLE_CONNECTIONS, TIMEOUT_SECONDS, TimeUnit.SECONDS))
        .build()

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
