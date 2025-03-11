package pl.allegro.tech.servicemesh.envoycontrol.config.sharing

import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext

interface BeforeAndAfterAllOnce : BeforeAllCallback, AfterAllCallback {
    fun beforeAllOnce(context: ExtensionContext)
    fun afterAllOnce(context: ExtensionContext)
    override fun beforeAll(context: ExtensionContext) {
        if (ctx.id != null) {
            return
        }
        ctx.id = context.uniqueId
        beforeAllOnce(context)
    }

    override fun afterAll(context: ExtensionContext) {
        require(!ctx.terminated) { "afterAll called after termination. It should not happen, test hierarchy ordering bug" }
        // terminate only on the last test context, which is the first context beforeAll() was called with.
        if (context.uniqueId == ctx.id) {
            ctx.terminated = true
            afterAllOnce(context)
        }
    }

    val ctx: Context

    class Context(var id: String? = null, var terminated: Boolean = false)
}
