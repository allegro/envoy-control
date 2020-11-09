package pl.allegro.tech.servicemesh.envoycontrol;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.protobuf.Message;
import io.envoyproxy.controlplane.cache.CacheStatusInfo;
import io.envoyproxy.controlplane.cache.NodeGroup;
import io.envoyproxy.controlplane.cache.Resources;
import io.envoyproxy.controlplane.cache.Response;
import io.envoyproxy.controlplane.cache.Snapshot;
import io.envoyproxy.controlplane.cache.SnapshotCache;
import io.envoyproxy.controlplane.cache.StatusInfo;
import io.envoyproxy.controlplane.cache.Watch;
import io.envoyproxy.controlplane.cache.WatchCancelledException;
import io.envoyproxy.controlplane.cache.XdsRequest;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.concurrent.GuardedBy;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static io.envoyproxy.controlplane.cache.Resources.RESOURCE_TYPES_IN_ORDER;

/**
 * This class is copy of {@link io.envoyproxy.controlplane.cache.SimpleCache}
 */
public class SimpleCache<T, U extends Snapshot> implements SnapshotCache<T, U> {

    private static final Logger LOGGER = LoggerFactory.getLogger(SimpleCache.class);

    private final NodeGroup<T> groups;
    private final boolean shouldSendMissingEndpoints;

    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final Lock readLock = lock.readLock();
    private final Lock writeLock = lock.writeLock();

    @GuardedBy("lock")
    private final Map<T, U> snapshots = new HashMap<>();
    private final ConcurrentMap<T, ConcurrentMap<Resources.ResourceType, CacheStatusInfo<T>>> statuses = new ConcurrentHashMap<>();

    private AtomicLong watchCount = new AtomicLong();

    /**
     * Constructs a simple cache.
     *
     * @param groups                     maps an envoy host to a node group
     * @param shouldSendMissingEndpoints if set to true it will respond with empty endpoints if there is no in snapshot
     */
    public SimpleCache(NodeGroup<T> groups, boolean shouldSendMissingEndpoints) {
        this.groups = groups;
        this.shouldSendMissingEndpoints = shouldSendMissingEndpoints;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean clearSnapshot(T group) {
        // we take a writeLock to prevent watches from being created
        writeLock.lock();
        try {
            Map<Resources.ResourceType, CacheStatusInfo<T>>  status = statuses.get(group);

            // If we don't know about this group, do nothing.
            if (status != null && status.values().stream().mapToLong(CacheStatusInfo::numWatches).sum() > 0) {
                LOGGER.warn("tried to clear snapshot for group with existing watches, group={}", group);

                return false;
            }

            statuses.remove(group);
            snapshots.remove(group);

            return true;
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Watch createWatch(
            boolean ads,
            XdsRequest request,
            Set<String> knownResourceNames,
            Consumer<Response> responseConsumer,
            boolean hasClusterChanged) {
        Resources.ResourceType requestResourceType = request.getResourceType();
        Preconditions.checkNotNull(requestResourceType, "unsupported type URL %s",
                request.getTypeUrl());
        T group;
        if (request.v3Request() != null) {
            group = groups.hash(request.v3Request().getNode());
        } else {
            group = groups.hash(request.v2Request().getNode());
        }

        // even though we're modifying, we take a readLock to allow multiple watches to be created in parallel since it
        // doesn't conflict
        readLock.lock();
        try {
            CacheStatusInfo<T> status = statuses.computeIfAbsent(group, g -> new ConcurrentHashMap<>())
                    .computeIfAbsent(requestResourceType, s -> new CacheStatusInfo<>(group));
            status.setLastWatchRequestTime(System.currentTimeMillis());

            U snapshot = snapshots.get(group);
            String version = snapshot == null ? "" : snapshot.version(requestResourceType, request.getResourceNamesList());

            Watch watch = new Watch(ads, request, responseConsumer);

            if (snapshot != null) {
                Set<String> requestedResources = ImmutableSet.copyOf(request.getResourceNamesList());

                // If the request is asking for resources we haven't sent to the proxy yet, see if we have additional resources.
                if (!knownResourceNames.equals(requestedResources)) {
                    Sets.SetView<String> newResourceHints = Sets.difference(requestedResources, knownResourceNames);

                    // If any of the newly requested resources are in the snapshot respond immediately. If not we'll fall back to
                    // version comparisons.
                    if (snapshot.resources(requestResourceType)
                            .keySet()
                            .stream()
                            .anyMatch(newResourceHints::contains)) {
                        respond(watch, snapshot, group);

                        return watch;
                    }
                } else if (hasClusterChanged && requestResourceType.equals(Resources.ResourceType.ENDPOINT)) {
                    respond(watch, snapshot, group);

                    return watch;
                }
            }

            // If the requested version is up-to-date or missing a response, leave an open watch.
            if (snapshot == null || request.getVersionInfo().equals(version)) {
                long watchId = watchCount.incrementAndGet();

                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("open watch {} for {}[{}] from node {} for version {}",
                            watchId,
                            request.getTypeUrl(),
                            String.join(", ", request.getResourceNamesList()),
                            group,
                            request.getVersionInfo());
                }

                status.setWatch(watchId, watch);

                watch.setStop(() -> status.removeWatch(watchId));

                return watch;
            }

            // Otherwise, the watch may be responded immediately
            boolean responded = respond(watch, snapshot, group);

            if (!responded) {
                long watchId = watchCount.incrementAndGet();

                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("did not respond immediately, leaving open watch {} for {}[{}] from node {} for version {}",
                            watchId,
                            request.getTypeUrl(),
                            String.join(", ", request.getResourceNamesList()),
                            group,
                            request.getVersionInfo());
                }

                status.setWatch(watchId, watch);

                watch.setStop(() -> status.removeWatch(watchId));
            }

            return watch;
        } finally {
            readLock.unlock();
        }
    }

    /**
     * {@inheritDoc}
     * @return
     */
    @Override
    public U getSnapshot(T group) {
        readLock.lock();

        try {
            return snapshots.get(group);
        } finally {
            readLock.unlock();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Collection<T> groups() {
        return ImmutableSet.copyOf(statuses.keySet());
    }

    /**
     * {@inheritDoc}
     *
     * <p>This method cannot be called concurrently for the same group.
     * It can be called concurrently for different groups.
     */
    @Override
    public void setSnapshot(T group, U snapshot) {
        // we take a writeLock to prevent watches from being created while we update the snapshot
        ConcurrentMap<Resources.ResourceType, CacheStatusInfo<T>> status;
        writeLock.lock();
        try {
            // Update the existing snapshot entry.
            snapshots.put(group, snapshot);
            status = statuses.get(group);
        } finally {
            writeLock.unlock();
        }

        if (status == null) {
            return;
        }

        // Responses should be in specific order and TYPE_URLS has a list of resources in the right order.
        respondWithSpecificOrder(group, snapshot, status);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public StatusInfo statusInfo(T group) {
        readLock.lock();

        try {
            ConcurrentMap<Resources.ResourceType, CacheStatusInfo<T>> statusMap = statuses.get(group);
            if (statusMap == null || statusMap.isEmpty()) {
                return null;
            }

            return new GroupCacheStatusInfo<>(statusMap.values());
        } finally {
            readLock.unlock();
        }
    }

    @VisibleForTesting
    protected void respondWithSpecificOrder(T group, U snapshot, ConcurrentMap<Resources.ResourceType, CacheStatusInfo<T>> statusMap) {
        for (Resources.ResourceType resourceType : RESOURCE_TYPES_IN_ORDER) {
            CacheStatusInfo<T> status = statusMap.get(resourceType);
            if (status == null) continue; // todo: why this happens?
            status.watchesRemoveIf((id, watch) -> {
                if (!watch.request().getResourceType().equals(resourceType)) {
                    return false;
                }
                String version = snapshot.version(watch.request().getResourceType(), watch.request().getResourceNamesList());

                if (!watch.request().getVersionInfo().equals(version)) {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("responding to open watch {}[{}] with new version {}",
                                id,
                                String.join(", ", watch.request().getResourceNamesList()),
                                version);
                    }

                    respond(watch, snapshot, group);

                    // Discard the watch. A new watch will be created for future snapshots once envoy ACKs the response.
                    return true;
                }

                // Do not discard the watch. The request version is the same as the snapshot version, so we wait to respond.
                return false;
            });
        }
    }

    private Response createResponse(XdsRequest request, Map<String, ? extends Message> resources, String version) {
        Collection<? extends Message> filtered = request.getResourceNamesList().isEmpty()
                ? resources.values()
                : request.getResourceNamesList().stream()
                .map(resources::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        return Response.create(request, filtered, version);
    }

    private boolean respond(Watch watch, U snapshot, T group) {
        Map<String, ? extends Message> snapshotResources = snapshot.resources(watch.request().getResourceType());
        Map<String, ClusterLoadAssignment> snapshotForMissingResources = Collections.emptyMap();

        if (!watch.request().getResourceNamesList().isEmpty() && watch.ads()) {
            Collection<String> missingNames = watch.request().getResourceNamesList().stream()
                    .filter(name -> !snapshotResources.containsKey(name))
                    .collect(Collectors.toList());

            if (!missingNames.isEmpty()) {
                // In some cases Envoy might send EDS request with cluster names we don't have in snapshot.
                // This may happen when for example Envoy disconnects from an instance of control-plane and connects to
                // other instance.
                //
                // If shouldSendMissingEndpoints is set to false we will not respond to such request. It may cause
                // Envoy to stop working correctly, because it will wait indefinitely for a response,
                // not accepting any other updates.
                //
                // If shouldSendMissingEndpoints is set to true, we will respond to such request anyway, to prevent
                // such problems with Envoy.
                if (shouldSendMissingEndpoints
                        && watch.request().getResourceType().equals(Resources.ResourceType.ENDPOINT)) {
                    LOGGER.info("adding missing resources [{}] to response for {} in ADS mode from node {} at version {}",
                            String.join(", ", missingNames),
                            watch.request().getTypeUrl(),
                            group,
                            snapshot.version(watch.request().getResourceType(), watch.request().getResourceNamesList())
                    );
                    snapshotForMissingResources = new HashMap<>(missingNames.size());
                    for (String missingName : missingNames) {
                        snapshotForMissingResources.put(
                                missingName,
                                ClusterLoadAssignment.newBuilder().setClusterName(missingName).build()
                        );
                    }
                } else {
                    LOGGER.info(
                            "not responding in ADS mode for {} from node {} at version {} for request [{}] since [{}] not in snapshot",
                            watch.request().getTypeUrl(),
                            group,
                            snapshot.version(watch.request().getResourceType(), watch.request().getResourceNamesList()),
                            String.join(", ", watch.request().getResourceNamesList()),
                            String.join(", ", missingNames));

                    return false;
                }
            }
        }

        String version = snapshot.version(watch.request().getResourceType(), watch.request().getResourceNamesList());

        LOGGER.debug("responding for {} from node {} at version {} with version {}",
                watch.request().getTypeUrl(),
                group,
                watch.request().getVersionInfo(),
                version);
        Response response;
        if (!snapshotForMissingResources.isEmpty()) {
            snapshotForMissingResources.putAll((Map<? extends String, ? extends ClusterLoadAssignment>) snapshotResources);
            response = createResponse(
                    watch.request(),
                    snapshotForMissingResources,
                    version);
        } else {
            response = createResponse(
                    watch.request(),
                    snapshotResources,
                    version);
        }

        try {
            watch.respond(response);
            return true;
        } catch (WatchCancelledException e) {
            LOGGER.error(
                    "failed to respond for {} from node {} at version {} with version {} because watch was already cancelled",
                    watch.request().getTypeUrl(),
                    group,
                    watch.request().getVersionInfo(),
                    version);
        }

        return false;
    }
}
