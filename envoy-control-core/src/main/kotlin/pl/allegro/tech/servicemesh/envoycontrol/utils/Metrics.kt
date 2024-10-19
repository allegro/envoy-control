package pl.allegro.tech.servicemesh.envoycontrol.utils

import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.noop.NoopTimer

val noopTimer = NoopTimer(Meter.Id("", Tags.empty(), null, null, Meter.Type.TIMER))
const val REACTOR_METRIC = "reactor.stats"
const val REACTOR_TOTAL_METRIC = "reactor.stats.total"
const val SERVICES_STATE_METRIC = "services.state"
const val SERVICES_STATE_ERRORS_METRIC = "services.state.errors.total"
const val SNAPSHOT_METRIC = "snapshot"
const val SNAPSHOT_UPDATE_DURATION_METRIC = "snapshot.update.duration.seconds"
const val SNAPSHOT_ERROR_METRIC = "snapshot.errors"
const val SNAPSHOT_GROUP_ERROR_METRIC = "snapshot.group.errors.total"
const val COMMUNICATION_MODE_ERROR_METRIC = "communication.errors.total"
const val CONNECTIONS_METRIC = "connections.stats"
const val REQUESTS_METRIC = "requests.stats"
const val WATCH_ERRORS_METRIC = "service.watch.errors.total"
const val WATCH_METRIC = "service.watch"
const val ENVOY_CONTROL_WARM_UP_METRIC = "envoy.control.warmup.seconds"
const val CROSS_DC_SYNC_METRIC = "cross.dc.synchronization"
const val CROSS_DC_SYNC_CANCELLED_METRIC = "$CROSS_DC_SYNC_METRIC.cancelled.total"
const val CROSS_DC_SYNC_SECONDS_METRIC = "$CROSS_DC_SYNC_METRIC.seconds"
const val CROSS_DC_SYNC_TOTAL_METRIC = "$CROSS_DC_SYNC_METRIC.total"
const val SIMPLE_CACHE_METRIC = "simple.cache.duration.seconds"
const val PROTOBUF_CACHE_METRIC = "protobuf.cache.serialize.time"
const val CACHE_GROUP_COUNT_METRIC = "cache.groups.count"
const val SNAPSHOT_FACTORY_SECONDS_METRIC = "snapshot.factory.seconds"
const val CHANGE_WATCHER_METRIC = "group.change.watcher"

const val CONNECTION_TYPE_TAG = "connection-type"
const val STREAM_TYPE_TAG = "stream-type"
const val CHECKPOINT_TAG = "checkpoint"
const val WATCH_TYPE_TAG = "watch-type"
const val DISCOVERY_REQ_TYPE_TAG = "discovery-request-type"
const val METRIC_TYPE_TAG = "metric-type"
const val METRIC_EMITTER_TAG = "metric-emitter"
const val UPDATE_TRIGGER_TAG = "update-trigger"
const val SERVICE_TAG = "service"
const val OPERATION_TAG = "operation"
const val CLUSTER_TAG = "cluster"
const val STATUS_TAG = "status"
