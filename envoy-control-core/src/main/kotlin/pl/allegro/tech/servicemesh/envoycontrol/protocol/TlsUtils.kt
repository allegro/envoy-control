package pl.allegro.tech.servicemesh.envoycontrol.protocol

class TlsUtils private constructor() {
    companion object {
        fun resolveSanUri(serviceName: String, format: String): String {
            return format.replace("{service-name}", serviceName)
        }
    }
}
