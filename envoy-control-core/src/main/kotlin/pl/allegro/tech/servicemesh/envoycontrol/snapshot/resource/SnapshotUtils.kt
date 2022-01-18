package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource

import pl.allegro.tech.servicemesh.envoycontrol.groups.IncomingRateLimitEndpoint
import java.math.BigInteger
import java.security.MessageDigest

fun getRuleId(serviceName: String, endpoint: IncomingRateLimitEndpoint): String {
    val methods = endpoint.methods.sorted().joinToString()
    val clients = endpoint.clients.sorted().joinToString(transform = { "${it.name},${it.selector}"})
    val key = "$serviceName,${endpoint.path},${endpoint.pathMatchingType},$methods,$clients"

    return "${serviceName.replace("-", "_")}_${key.md5()}"
}

@Suppress("MagicNumber")
private fun String.md5(): String {
    val md = MessageDigest.getInstance("MD5")
    return BigInteger(1, md.digest(toByteArray())).toString(16).padStart(32, '0')
}
