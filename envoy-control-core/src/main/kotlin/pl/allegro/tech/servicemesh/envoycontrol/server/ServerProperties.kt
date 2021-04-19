@file:Suppress("MagicNumber")

package pl.allegro.tech.servicemesh.envoycontrol.server

import java.time.Duration

class ServerProperties {
    var port = 50000
    var nioEventLoopThreadCount = 0 // if set to 0, default Netty value will be used: <number of CPUs> * 2
    var serverPoolSize = 16
    var serverPoolKeepAlive: Duration = Duration.ofMinutes(10)
    var executorGroup = ExecutorProperties()
    var netty = NettyProperties()
    /**
     * Minimum size = 2, to work correctly with reactor operators merge and combineLatest
     */
    var globalSnapshotUpdatePoolSize = 5
    var groupSnapshotUpdateScheduler = ExecutorProperties().apply {
        type = ExecutorType.DIRECT
        parallelPoolSize = 1
    }
    var snapshotCleanup = SnapshotCleanupProperties()
    var reportProtobufCacheMetrics = true
    var logFullRequest = false
    var logFullResponse = false
}

enum class ExecutorType {
    DIRECT, PARALLEL
}

class ExecutorProperties {
    var type = ExecutorType.DIRECT
    var parallelPoolSize = 4
}

class NettyProperties {
    /**
     * @see io.grpc.netty.NettyServerBuilder.keepAliveTime
     */
    var keepAliveTime: Duration = Duration.ofSeconds(15)

    /**
     * @see io.grpc.netty.NettyServerBuilder.permitKeepAliveTime
     */
    var permitKeepAliveTime: Duration = Duration.ofSeconds(10)

    /**
     * @see io.grpc.netty.NettyServerBuilder.permitKeepAliveWithoutCalls
     */
    var permitKeepAliveWithoutCalls = true
}

class SnapshotCleanupProperties {
    var collectAfterMillis: Duration = Duration.ofSeconds(10)
    var collectionIntervalMillis: Duration = Duration.ofSeconds(10)
}
