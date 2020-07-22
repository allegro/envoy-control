package pl.allegro.tech.servicemesh.envoycontrol.assertions

import okhttp3.Response
import org.assertj.core.api.ObjectAssert

fun ObjectAssert<Response>.isOk(): ObjectAssert<Response> {
    matches { it.isSuccessful }
    return this
}
