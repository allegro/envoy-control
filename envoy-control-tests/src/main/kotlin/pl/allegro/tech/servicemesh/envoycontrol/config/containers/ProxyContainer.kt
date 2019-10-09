package pl.allegro.tech.servicemesh.envoycontrol.config.containers

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.testcontainers.images.builder.ImageFromDockerfile
import pl.allegro.tech.servicemesh.envoycontrol.testcontainers.GenericContainer
import java.time.Duration

class ProxyContainer(private val proxyUrl: String) : GenericContainer<ProxyContainer>(
    ImageFromDockerfile()
        .withFileFromString("/index.js", """
           const http = require('http');
           const url = require('url');
           const port = parseInt(process.argv[2], 10) || 5678;

           const requestHandler = (request, response) => {
             const url_parts = url.parse(request.url, true);
             
             const call = url_parts.query.call;             
  
             if (call === undefined) {
               console.log('health check');
               response.end('ok');
               return;
             }

             console.log('Calling', call);
             try {
               http.get(url_parts.query.call, (res) => {
                 res.on('data', () => {});
                 res.on('end', () => {
                   response.end('Got response');
                 });
               }).on('error', (e) => {
                 console.error('error', e.message);
                 response.statusCode = 500;
                 response.end('There was a problem');
               });
             } catch (e) {
               console.error('error', e.message);
               response.statusCode = 500;
               response.end('There was a problem');
             }
           }

           const server = http.createServer(requestHandler);

           server.listen(port, '0.0.0.0', (err) => {
             if (err) {
               return console.log('something bad happened', err);
             }

             console.log('server is listening on', port);
           }) 
        """.trimIndent())
        .withDockerfileFromBuilder {
        it.from("node:10.16.3-jessie")
            .copy("index.js", "/index.js")
            .build()
    }
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(Duration.ofSeconds(5))
        .build()

    override fun configure() {
        super.configure()
        withStartupTimeout(Duration.ofHours(2))
        withExposedPorts(5678)
        withCommand("node", "/index.js", "5678")
    }

    fun call(destination: String): Response {
        val url = "$proxyUrl/?call=http://$destination"
        return client.newCall(
            Request.Builder()
                .get()
                .url(url)
                .header("Host", "proxy1")
                .build()
        ).execute()
    }
}
