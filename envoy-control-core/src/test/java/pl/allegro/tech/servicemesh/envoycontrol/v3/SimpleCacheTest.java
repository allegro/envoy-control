package pl.allegro.tech.servicemesh.envoycontrol.v3;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import com.google.protobuf.Message;
import io.envoyproxy.controlplane.cache.DeltaResponse;
import io.envoyproxy.controlplane.cache.DeltaWatch;
import io.envoyproxy.controlplane.cache.DeltaXdsRequest;
import io.envoyproxy.controlplane.cache.NodeGroup;
import io.envoyproxy.controlplane.cache.Resources;
import static io.envoyproxy.controlplane.cache.Resources.ResourceType.CLUSTER;
import static io.envoyproxy.controlplane.cache.Resources.ResourceType.ENDPOINT;
import static io.envoyproxy.controlplane.cache.Resources.ResourceType.LISTENER;
import static io.envoyproxy.controlplane.cache.Resources.ResourceType.ROUTE;
import static io.envoyproxy.controlplane.cache.Resources.ResourceType.SECRET;
import static io.envoyproxy.controlplane.cache.Resources.V3.CLUSTER_TYPE_URL;
import static io.envoyproxy.controlplane.cache.Resources.V3.ENDPOINT_TYPE_URL;
import static io.envoyproxy.controlplane.cache.Resources.V3.LISTENER_TYPE_URL;
import static io.envoyproxy.controlplane.cache.Resources.V3.ROUTE_TYPE_URL;
import static io.envoyproxy.controlplane.cache.Resources.V3.SECRET_TYPE_URL;
import io.envoyproxy.controlplane.cache.Response;
import io.envoyproxy.controlplane.cache.StatusInfo;
import io.envoyproxy.controlplane.cache.VersionedResource;
import io.envoyproxy.controlplane.cache.Watch;
import io.envoyproxy.controlplane.cache.XdsRequest;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.Node;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.Secret;
import io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * This class is copy of {@link io.envoyproxy.controlplane.cache.v3.SimpleCacheTest}
 */
public class SimpleCacheTest {

    private static final boolean ADS = ThreadLocalRandom.current().nextBoolean();
    protected static final String CLUSTER_NAME = "cluster0";
    private static final String LISTENER_NAME = "listener0";
    private static final String ROUTE_NAME = "route0";
    private static final String SECRET_NAME = "secret0";

    private static final String VERSION1 = UUID.randomUUID().toString();
    protected static final String VERSION2 = UUID.randomUUID().toString();

    private static final Snapshot SNAPSHOT1 = Snapshot.create(
            ImmutableList.of(Cluster.newBuilder().setName(CLUSTER_NAME).build()),
            ImmutableList.of(ClusterLoadAssignment.getDefaultInstance()),
            ImmutableList.of(Listener.newBuilder().setName(LISTENER_NAME).build()),
            ImmutableList.of(RouteConfiguration.newBuilder().setName(ROUTE_NAME).build()),
            ImmutableList.of(Secret.newBuilder().setName(SECRET_NAME).build()),
            VERSION1);

    private static final Snapshot SNAPSHOT2 = Snapshot.create(
            ImmutableList.of(Cluster.newBuilder().setName(CLUSTER_NAME).build()),
            ImmutableList.of(ClusterLoadAssignment.getDefaultInstance()),
            ImmutableList.of(Listener.newBuilder().setName(LISTENER_NAME).build()),
            ImmutableList.of(RouteConfiguration.newBuilder().setName(ROUTE_NAME).build()),
            ImmutableList.of(Secret.newBuilder().setName(SECRET_NAME).build()),
            VERSION2);

    protected static final Snapshot MULTIPLE_RESOURCES_SNAPSHOT2 = Snapshot.create(
            ImmutableList.of(Cluster.newBuilder().setName(CLUSTER_NAME).build()),
            ImmutableList.of(ClusterLoadAssignment.newBuilder().setClusterName(CLUSTER_NAME).build()),
            ImmutableList.of(Listener.newBuilder().setName(LISTENER_NAME).build()),
            ImmutableList.of(RouteConfiguration.newBuilder().setName(ROUTE_NAME).build()),
            ImmutableList.of(Secret.newBuilder().setName(SECRET_NAME).build()),
            VERSION2);

    protected static Map<Resources.ResourceType, ResourceTypeUrlAndName> resourceNamesMap = Map.of(
            CLUSTER, new ResourceTypeUrlAndName(CLUSTER_TYPE_URL, CLUSTER_NAME),
            LISTENER, new ResourceTypeUrlAndName(LISTENER_TYPE_URL, LISTENER_NAME),
            ROUTE, new ResourceTypeUrlAndName(ROUTE_TYPE_URL, ROUTE_NAME),
            SECRET, new ResourceTypeUrlAndName(SECRET_TYPE_URL, SECRET_NAME),
            ENDPOINT, new ResourceTypeUrlAndName(ENDPOINT_TYPE_URL, CLUSTER_NAME)
    );

    protected boolean shouldSendMissingEndpoints() {
        return false;
    }

    @Test
    public void invalidNamesListShouldReturnWatcherWithNoResponseInAdsMode() {
        SimpleCache<String> cache = new SimpleCache<>(new SingleNodeGroup(), shouldSendMissingEndpoints());

        cache.setSnapshot(SingleNodeGroup.GROUP, SNAPSHOT1);

        ResponseTracker responseTracker = new ResponseTracker();

        Watch watch = cache.createWatch(
                true,
                XdsRequest.create(DiscoveryRequest.newBuilder()
                        .setNode(Node.getDefaultInstance())
                        .setTypeUrl(ENDPOINT_TYPE_URL)
                        .addResourceNames("none")
                        .build()),
                Collections.emptySet(),
                responseTracker,
                false,
                false
        );
        assertThatWatchIsOpenWithNoResponses(new WatchAndTracker(watch, responseTracker));
    }

    @Test
    public void invalidNamesListShouldReturnWatcherWithResponseInXdsMode() {
        SimpleCache<String> cache = new SimpleCache<>(new SingleNodeGroup(), shouldSendMissingEndpoints());

        cache.setSnapshot(SingleNodeGroup.GROUP, SNAPSHOT1);

        ResponseTracker responseTracker = new ResponseTracker();

        Watch watch = cache.createWatch(
                false,
                XdsRequest.create(DiscoveryRequest.newBuilder()
                        .setNode(Node.getDefaultInstance())
                        .setTypeUrl(ENDPOINT_TYPE_URL)
                        .addResourceNames("none")
                        .build()),
                Collections.emptySet(),
                responseTracker,
                false,
                false
        );

        assertThat(watch.isCancelled()).isFalse();
        assertThat(responseTracker.responses).isNotEmpty();
    }

    @Test
    public void successfullyWatchAllResourceTypesWithSetBeforeWatch() {
        SimpleCache<String> cache = new SimpleCache<>(new SingleNodeGroup(), shouldSendMissingEndpoints());

        cache.setSnapshot(SingleNodeGroup.GROUP, SNAPSHOT1);

        for (String typeUrl : Resources.V3.TYPE_URLS) {
            ResponseTracker responseTracker = new ResponseTracker();

            Watch watch = cache.createWatch(
                    ADS,
                    XdsRequest.create(DiscoveryRequest.newBuilder()
                            .setNode(Node.getDefaultInstance())
                            .setTypeUrl(typeUrl)
                            .addAllResourceNames(SNAPSHOT1.resources(typeUrl).keySet())
                            .build()),
                    Collections.emptySet(),
                    responseTracker,
                    false,
                    false
            );

            assertThat(watch.request().getTypeUrl()).isEqualTo(typeUrl);
            assertThat(watch.request().getResourceNamesList()).containsExactlyElementsOf(
                    SNAPSHOT1.resources(typeUrl).keySet());

            assertThatWatchReceivesSnapshot(new WatchAndTracker(watch, responseTracker), SNAPSHOT1);
        }
    }

    @Test
    public void shouldSendEdsWhenClusterChangedButEdsVersionDidnt() {
        SimpleCache<String> cache = new SimpleCache<>(new SingleNodeGroup(), shouldSendMissingEndpoints());

        cache.setSnapshot(SingleNodeGroup.GROUP, SNAPSHOT1);

        ResponseTracker responseTracker = new ResponseTracker();

        Watch watch = cache.createWatch(
                ADS,
                XdsRequest.create(DiscoveryRequest.newBuilder()
                        .setNode(Node.getDefaultInstance())
                        .setVersionInfo(VERSION1)
                        .setTypeUrl(ENDPOINT_TYPE_URL)
                        .addAllResourceNames(SNAPSHOT1.resources(ENDPOINT_TYPE_URL).keySet())
                        .build()),
                Sets.newHashSet(""),
                responseTracker,
                true,
                false
        );

        assertThat(watch.request().getTypeUrl()).isEqualTo(ENDPOINT_TYPE_URL);
        assertThat(watch.request().getResourceNamesList()).containsExactlyElementsOf(
                SNAPSHOT1.resources(ENDPOINT_TYPE_URL).keySet());

        assertThatWatchReceivesSnapshot(new WatchAndTracker(watch, responseTracker), SNAPSHOT1);
    }

    @Test
    public void successfullyWatchAllResourceTypesWithSetAfterWatch() {
        SimpleCache<String> cache = new SimpleCache<>(new SingleNodeGroup(), shouldSendMissingEndpoints());

        Map<String, WatchAndTracker> watches = Resources.V3.TYPE_URLS.stream()
                .collect(Collectors.toMap(
                        typeUrl -> typeUrl,
                        typeUrl -> {
                            ResponseTracker responseTracker = new ResponseTracker();

                            Watch watch = cache.createWatch(
                                    ADS,
                                    XdsRequest.create(DiscoveryRequest.newBuilder()
                                            .setNode(Node.getDefaultInstance())
                                            .setTypeUrl(typeUrl)
                                            .addAllResourceNames(SNAPSHOT1.resources(typeUrl).keySet())
                                            .build()),
                                    Collections.emptySet(),
                                    responseTracker,
                                    false,
                                    false
                            );

                            return new WatchAndTracker(watch, responseTracker);
                        }));

        cache.setSnapshot(SingleNodeGroup.GROUP, SNAPSHOT1);

        for (String typeUrl : Resources.V3.TYPE_URLS) {
            assertThatWatchReceivesSnapshot(watches.get(typeUrl), SNAPSHOT1);
        }
    }

    @Test
    public void successfullyWatchAllResourceTypesWithSetBeforeWatchWithRequestVersion() {
        SimpleCache<String> cache = new SimpleCache<>(new SingleNodeGroup(), shouldSendMissingEndpoints());

        cache.setSnapshot(SingleNodeGroup.GROUP, SNAPSHOT1);

        ResponseOrderTracker responseOrderTracker = new ResponseOrderTracker();

        HashMap<String, WatchAndTracker> watches = new HashMap<>();

        for (int i = 0; i < 2; ++i) {
            watches.putAll(Resources.V3.TYPE_URLS.stream()
                    .collect(Collectors.toMap(
                            typeUrl -> typeUrl,
                            typeUrl -> {
                                ResponseTracker responseTracker = new ResponseTracker();

                                Watch watch = cache.createWatch(
                                        ADS,
                                        XdsRequest.create(DiscoveryRequest.newBuilder()
                                                .setNode(Node.getDefaultInstance())
                                                .setTypeUrl(typeUrl)
                                                .setVersionInfo(SNAPSHOT1.version(typeUrl))
                                                .addAllResourceNames(SNAPSHOT1.resources(typeUrl).keySet())
                                                .build()),
                                        SNAPSHOT2.resources(typeUrl).keySet(),
                                        r -> {
                                            responseTracker.accept(r);
                                            responseOrderTracker.accept(r);
                                        },
                                        false,
                                        false
                                );

                                return new WatchAndTracker(watch, responseTracker);
                            }))
            );
        }

        // The request version matches the current snapshot version, so the watches shouldn't receive any responses.
        for (String typeUrl : Resources.V3.TYPE_URLS) {
            assertThatWatchIsOpenWithNoResponses(watches.get(typeUrl));
        }

        cache.setSnapshot(SingleNodeGroup.GROUP, SNAPSHOT2);

        for (String typeUrl : Resources.V3.TYPE_URLS) {
            assertThatWatchReceivesSnapshot(watches.get(typeUrl), SNAPSHOT2);
        }

        // Verify that CDS and LDS always get triggered before EDS and RDS respectively.
        assertThat(responseOrderTracker.responseTypes).containsExactly(CLUSTER_TYPE_URL,
                CLUSTER_TYPE_URL, ENDPOINT_TYPE_URL, ENDPOINT_TYPE_URL,
                LISTENER_TYPE_URL, LISTENER_TYPE_URL, ROUTE_TYPE_URL,
                ROUTE_TYPE_URL, SECRET_TYPE_URL, SECRET_TYPE_URL);
    }

    @Test
    @Disabled("was broken in previous versions")
    public void successfullyWatchAllResourceTypesWithSetBeforeWatchWithSameRequestVersionNewResourceHints() {
        SimpleCache<String> cache = new SimpleCache<>(new SingleNodeGroup(), shouldSendMissingEndpoints());

        cache.setSnapshot(SingleNodeGroup.GROUP, MULTIPLE_RESOURCES_SNAPSHOT2);

        // Set a watch for the current snapshot with the same version but with resource hints present
        // in the snapshot that the watch creator does not currently know about.
        //
        // Note how we're requesting the resources from MULTIPLE_RESOURCE_SNAPSHOT2 while claiming we
        // only know about the ones from SNAPSHOT2
        Map<String, WatchAndTracker> watches = Resources.V3.TYPE_URLS.stream()
                .collect(Collectors.toMap(
                        typeUrl -> typeUrl,
                        typeUrl -> {
                            ResponseTracker responseTracker = new ResponseTracker();

                            Watch watch = cache.createWatch(
                                    ADS,
                                    XdsRequest.create(DiscoveryRequest.newBuilder()
                                            .setNode(Node.getDefaultInstance())
                                            .setTypeUrl(typeUrl)
                                            .setVersionInfo(MULTIPLE_RESOURCES_SNAPSHOT2.version(typeUrl))
                                            .addAllResourceNames(MULTIPLE_RESOURCES_SNAPSHOT2.resources(typeUrl).keySet())
                                            .build()),
                                    SNAPSHOT2.resources(typeUrl).keySet(),
                                    responseTracker,
                                    false,
                                    false
                            );

                            return new WatchAndTracker(watch, responseTracker);
                        }));

        // The snapshot version matches for all resources, but for eds and cds there are new resources present
        // for the same version, so we expect the watches to trigger.
        assertThatWatchReceivesSnapshot(watches.remove(CLUSTER_TYPE_URL), MULTIPLE_RESOURCES_SNAPSHOT2);
        assertThatWatchReceivesSnapshot(watches.remove(ENDPOINT_TYPE_URL), MULTIPLE_RESOURCES_SNAPSHOT2);

        // Remaining watches should not trigger
        for (WatchAndTracker watchAndTracker : watches.values()) {
            assertThatWatchIsOpenWithNoResponses(watchAndTracker);
        }
    }

    @Test
    public void successfullyWatchAllResourceTypesWithSetBeforeWatchWithSameRequestVersionNewResourceHintsNoChange() {
        SimpleCache<String> cache = new SimpleCache<>(new SingleNodeGroup(), shouldSendMissingEndpoints());

        cache.setSnapshot(SingleNodeGroup.GROUP, SNAPSHOT2);

        // Set a watch for the current snapshot for the same version but with new resource hints not
        // present in the snapshot that the watch creator does not know about.
        //
        // Note that we're requesting the additional resources found in MULTIPLE_RESOURCE_SNAPSHOT2
        // while we only know about the resources found in SNAPSHOT2. Since SNAPSHOT2 is the current
        // snapshot, we have nothing to respond with for the new resources so we should not trigger
        // the watch.
        Map<String, WatchAndTracker> watches = Resources.V3.TYPE_URLS.stream()
                .collect(Collectors.toMap(
                        typeUrl -> typeUrl,
                        typeUrl -> {
                            ResponseTracker responseTracker = new ResponseTracker();

                            Watch watch = cache.createWatch(
                                    ADS,
                                    XdsRequest.create(DiscoveryRequest.newBuilder()
                                            .setNode(Node.getDefaultInstance())
                                            .setTypeUrl(typeUrl)
                                            .setVersionInfo(SNAPSHOT2.version(typeUrl))
                                            .addAllResourceNames(MULTIPLE_RESOURCES_SNAPSHOT2.resources(typeUrl).keySet())
                                            .build()),
                                    SNAPSHOT2.resources(typeUrl).keySet(),
                                    responseTracker,
                                    false,
                                    false
                            );

                            return new WatchAndTracker(watch, responseTracker);
                        }));

        // No watches should trigger since no new information will be returned
        for (WatchAndTracker watchAndTracker : watches.values()) {
            assertThatWatchIsOpenWithNoResponses(watchAndTracker);
        }
    }

    @Test
    public void setSnapshotWithVersionMatchingRequestShouldLeaveWatchOpenWithoutAdditionalResponse() {
        SimpleCache<String> cache = new SimpleCache<>(new SingleNodeGroup(), shouldSendMissingEndpoints());

        cache.setSnapshot(SingleNodeGroup.GROUP, SNAPSHOT1);

        Map<String, WatchAndTracker> watches = Resources.V3.TYPE_URLS.stream()
                .collect(Collectors.toMap(
                        typeUrl -> typeUrl,
                        typeUrl -> {
                            ResponseTracker responseTracker = new ResponseTracker();

                            Watch watch = cache.createWatch(
                                    ADS,
                                    XdsRequest.create(DiscoveryRequest.newBuilder()
                                            .setNode(Node.getDefaultInstance())
                                            .setTypeUrl(typeUrl)
                                            .setVersionInfo(SNAPSHOT1.version(typeUrl))
                                            .addAllResourceNames(SNAPSHOT1.resources(typeUrl).keySet())
                                            .build()),
                                    SNAPSHOT1.resources(typeUrl).keySet(),
                                    responseTracker,
                                    false,
                                    false
                            );

                            return new WatchAndTracker(watch, responseTracker);
                        }));

        // The request version matches the current snapshot version, so the watches shouldn't receive any responses.
        for (String typeUrl : Resources.V3.TYPE_URLS) {
            assertThatWatchIsOpenWithNoResponses(watches.get(typeUrl));
        }

        cache.setSnapshot(SingleNodeGroup.GROUP, SNAPSHOT1);

        // The request version still matches the current snapshot version, so the watches shouldn't receive any responses.
        for (String typeUrl : Resources.V3.TYPE_URLS) {
            assertThatWatchIsOpenWithNoResponses(watches.get(typeUrl));
        }
    }

    @Test
    public void watchesAreReleasedAfterCancel() {
        SimpleCache<String> cache = new SimpleCache<>(new SingleNodeGroup(), shouldSendMissingEndpoints());

        Map<String, WatchAndTracker> watches = Resources.V3.TYPE_URLS.stream()
                .collect(Collectors.toMap(
                        typeUrl -> typeUrl,
                        typeUrl -> {
                            ResponseTracker responseTracker = new ResponseTracker();

                            Watch watch = cache.createWatch(
                                    ADS,
                                    XdsRequest.create(DiscoveryRequest.newBuilder()
                                            .setNode(Node.getDefaultInstance())
                                            .setTypeUrl(typeUrl)
                                            .addAllResourceNames(SNAPSHOT1.resources(typeUrl).keySet())
                                            .build()),
                                    Collections.emptySet(),
                                    responseTracker,
                                    false,
                                    false
                            );

                            return new WatchAndTracker(watch, responseTracker);
                        }));

        StatusInfo statusInfo = cache.statusInfo(SingleNodeGroup.GROUP);

        assertThat(statusInfo.numWatches()).isEqualTo(watches.size());

        watches.values().forEach(w -> w.watch.cancel());

        assertThat(statusInfo.numWatches()).isZero();

        watches.values().forEach(w -> assertThat(w.watch.isCancelled()).isTrue());
    }

    @Test
    public void watchIsLeftOpenIfNotRespondedImmediately() {
        SimpleCache<String> cache = new SimpleCache<>(new SingleNodeGroup(), shouldSendMissingEndpoints());
        cache.setSnapshot(SingleNodeGroup.GROUP, Snapshot.create(
                ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), VERSION1));

        ResponseTracker responseTracker = new ResponseTracker();
        Watch watch = cache.createWatch(
                true,
                XdsRequest.create(DiscoveryRequest.newBuilder()
                        .setNode(Node.getDefaultInstance())
                        .setTypeUrl(ROUTE_TYPE_URL)
                        .addAllResourceNames(Collections.singleton(ROUTE_NAME))
                        .build()),
                Collections.singleton(ROUTE_NAME),
                responseTracker,
                false,
                false
        );

        assertThatWatchIsOpenWithNoResponses(new WatchAndTracker(watch, responseTracker));
    }

    @Test
    public void getSnapshot() {
        SimpleCache<String> cache = new SimpleCache<>(new SingleNodeGroup(), shouldSendMissingEndpoints());

        cache.setSnapshot(SingleNodeGroup.GROUP, SNAPSHOT1);

        assertThat(cache.getSnapshot(SingleNodeGroup.GROUP)).isEqualTo(SNAPSHOT1);
    }

    @Test
    public void clearSnapshot() {
        SimpleCache<String> cache = new SimpleCache<>(new SingleNodeGroup(), shouldSendMissingEndpoints());

        cache.setSnapshot(SingleNodeGroup.GROUP, SNAPSHOT1);

        assertThat(cache.clearSnapshot(SingleNodeGroup.GROUP)).isTrue();

        assertThat(cache.getSnapshot(SingleNodeGroup.GROUP)).isNull();
    }

    @Test
    public void clearSnapshotWithWatches() {
        SimpleCache<String> cache = new SimpleCache<>(new SingleNodeGroup(), shouldSendMissingEndpoints());

        cache.setSnapshot(SingleNodeGroup.GROUP, SNAPSHOT1);

        // Create a watch with an arbitrary type URL and a versionInfo that matches the saved
        // snapshot, so the watch doesn't immediately close.
        final Watch watch = cache.createWatch(ADS, XdsRequest.create(DiscoveryRequest.newBuilder()
                        .setNode(Node.getDefaultInstance())
                        .setTypeUrl(CLUSTER_TYPE_URL)
                        .setVersionInfo(SNAPSHOT1.version(CLUSTER_TYPE_URL))
                        .build()),
                Collections.emptySet(),
                r -> {
                },
                false,
                false
        );

        // clearSnapshot should fail and the snapshot should be left untouched
        assertThat(cache.clearSnapshot(SingleNodeGroup.GROUP)).isFalse();
        assertThat(cache.getSnapshot(SingleNodeGroup.GROUP)).isEqualTo(SNAPSHOT1);
        assertThat(cache.statusInfo(SingleNodeGroup.GROUP)).isNotNull();

        watch.cancel();

        // now that the watch is gone we should be able to clear it
        assertThat(cache.clearSnapshot(SingleNodeGroup.GROUP)).isTrue();
        assertThat(cache.getSnapshot(SingleNodeGroup.GROUP)).isNull();
        assertThat(cache.statusInfo(SingleNodeGroup.GROUP)).isNull();
    }

    @Test
    public void shouldNotReturnResourcesIfSpecifiedInInitialResourceVersionsForCdsAndLds() {
        SimpleCache<String> cache = new SimpleCache<>(new SingleNodeGroup(), shouldSendMissingEndpoints());
        cache.setSnapshot(SingleNodeGroup.GROUP, MULTIPLE_RESOURCES_SNAPSHOT2);
        Set<String> pendingResources = Set.of();
        DeltaWatchAndTracker watchAndTracker = createDeltaWatch(cache, CLUSTER, pendingResources);

        assertThat(watchAndTracker.watch.isCancelled()).isFalse();
        assertThat(watchAndTracker.tracker.responses).isEmpty();

        DeltaWatchAndTracker watchAndTracker2 = createDeltaWatch(cache, LISTENER, pendingResources);

        assertThat(watchAndTracker2.watch.isCancelled()).isFalse();
        assertThat(watchAndTracker2.tracker.responses).isEmpty();
    }

    @Test
    public void shouldReturnResourcesIfDoesntMatchVersionSpecifiedInInitialResourceVersionsForWildcardRequest() {
        SimpleCache<String> cache = new SimpleCache<>(new SingleNodeGroup(), shouldSendMissingEndpoints());
        cache.setSnapshot(SingleNodeGroup.GROUP, MULTIPLE_RESOURCES_SNAPSHOT2);
        Set<String> pendingResources = Set.of();
        //Request with Cluster/Listener resource type is wildcard
        DeltaWatchAndTracker watchAndTracker = createDeltaWatch(cache, CLUSTER, pendingResources, UUID.randomUUID().toString());

        assertThat(watchAndTracker.watch.isCancelled()).isFalse();
        assertThat(watchAndTracker.tracker.responses).isNotEmpty();

        DeltaWatchAndTracker watchAndTracker2 = createDeltaWatch(cache, LISTENER, pendingResources, UUID.randomUUID().toString());

        assertThat(watchAndTracker2.watch.isCancelled()).isFalse();
        assertThat(watchAndTracker2.tracker.responses).isNotEmpty();
    }

    @Test
    public void shouldNotReturnResourcesIfSpecifiedInInitialResourceVersionsForNonWildcardRequest() {
        SimpleCache<String> cache = new SimpleCache<>(new SingleNodeGroup(), shouldSendMissingEndpoints());
        cache.setSnapshot(SingleNodeGroup.GROUP, MULTIPLE_RESOURCES_SNAPSHOT2);
        Set<String> pendingResources = Set.of();
        //Request with Endpoint resource type is not wildcard
        DeltaWatchAndTracker watchAndTracker = createDeltaWatch(cache, ENDPOINT, pendingResources);

        assertThat(watchAndTracker.watch.isCancelled()).isFalse();
        assertThat(watchAndTracker.tracker.responses).isEmpty();
    }

    @Test
    public void shouldReturnResourcesIfSpecifiedInInitialResourceVersionsForNonWildcardRequest() {
        SimpleCache<String> cache = new SimpleCache<>(new SingleNodeGroup(), shouldSendMissingEndpoints());
        cache.setSnapshot(SingleNodeGroup.GROUP, MULTIPLE_RESOURCES_SNAPSHOT2);
        Set<String> pendingResources = Set.of();
        //Request with Endpoint resource type is not wildcard
        DeltaWatchAndTracker watchAndTracker = createDeltaWatch(cache, ENDPOINT, pendingResources, UUID.randomUUID().toString());

        assertThat(watchAndTracker.watch.isCancelled()).isFalse();
        assertThat(watchAndTracker.tracker.responses).isNotEmpty();
    }

    @Test
    public void shouldRemoveResourcesSpecifiedInInitialResourceVersionsThatNotPresentInSnapshot() {
        SimpleCache<String> cache = new SimpleCache<>(new SingleNodeGroup(), shouldSendMissingEndpoints());
        cache.setSnapshot(SingleNodeGroup.GROUP, MULTIPLE_RESOURCES_SNAPSHOT2);
        Set<String> pendingResources = Set.of();
        DeltaResponseTracker responseTracker = new DeltaResponseTracker();
        // requester version will be empty when there are initial resources in request
        String requesterVersion = "";
        String removedClusterName = "cluster1";

        DeltaWatch watch = cache.createDeltaWatch(
                DeltaXdsRequest.create(DeltaDiscoveryRequest.newBuilder()
                        .setNode(Node.getDefaultInstance())
                        .setTypeUrl(CLUSTER_TYPE_URL)
                        .putAllInitialResourceVersions(Map.of(removedClusterName, UUID.randomUUID().toString()))
                        .build()),
                requesterVersion,
                new HashMap<>(),
                pendingResources,
                true,
                responseTracker,
                false
        );

        assertThat(watch.isCancelled()).isFalse();
        assertThat(responseTracker.responses.getFirst().response.removedResources()).contains(removedClusterName);
    }

    @Test
    public void groups() {
        SimpleCache<String> cache = new SimpleCache<>(new SingleNodeGroup(), shouldSendMissingEndpoints());

        assertThat(cache.groups()).isEmpty();

        cache.createWatch(ADS, XdsRequest.create(DiscoveryRequest.newBuilder()
                        .setNode(Node.getDefaultInstance())
                        .setTypeUrl(CLUSTER_TYPE_URL)
                        .build()),
                Collections.emptySet(),
                r -> {
                },
                false,
                false
        );

        assertThat(cache.groups()).containsExactly(SingleNodeGroup.GROUP);
    }

    private DeltaWatchAndTracker createDeltaWatch(SimpleCache<String> cache, Resources.ResourceType resourceType, Set<String> pendingResources) {
        return createDeltaWatch(
                cache,
                resourceType,
                pendingResources,
                MULTIPLE_RESOURCES_SNAPSHOT2
                        .versionedResources(resourceType).get(resourceNamesMap.get(resourceType).resourceName).version()
        );
    }

    private DeltaWatchAndTracker createDeltaWatch(SimpleCache<String> cache, Resources.ResourceType resourceType, Set<String> pendingResources, String version) {
        Map<String, String> resourceVersions = new HashMap<>();
        DeltaResponseTracker responseTracker = new DeltaResponseTracker();
        boolean hasClusterChanged = resourceType == CLUSTER;
        // requester version will be empty when there are initial resources in request
        String requesterVersion = "";

        DeltaWatch watch = cache.createDeltaWatch(
                DeltaXdsRequest.create(DeltaDiscoveryRequest.newBuilder()
                        .setNode(Node.getDefaultInstance())
                        .setTypeUrl(resourceNamesMap.get(resourceType).resourceTypeUrl)
                        .putAllInitialResourceVersions(Map.of(resourceNamesMap.get(resourceType).resourceName, version))
                        .build()),
                requesterVersion,
                resourceVersions,
                pendingResources,
                true,
                responseTracker,
                hasClusterChanged
        );
        return new DeltaWatchAndTracker(watch, responseTracker);
    }

    private static void assertThatWatchIsOpenWithNoResponses(WatchAndTracker watchAndTracker) {
        assertThat(watchAndTracker.watch.isCancelled()).isFalse();
        assertThat(watchAndTracker.tracker.responses).isEmpty();
    }


    protected static void assertThatWatchReceivesSnapshot(WatchAndTracker watchAndTracker, Snapshot snapshot) {
        assertThat(watchAndTracker.tracker.responses).isNotEmpty();

        Response response = watchAndTracker.tracker.responses.getFirst();

        assertThat(response).isNotNull();
        assertThat(response.version()).isEqualTo(snapshot.version(watchAndTracker.watch.request().getTypeUrl()));
        assertThat(response.resources().toArray(new Message[0]))
                .containsExactlyElementsOf(snapshot.resources(watchAndTracker.watch.request().getTypeUrl()).values()
                        .stream().map(VersionedResource::resource).collect(Collectors.toList()));
    }

    protected static class ResponseTracker implements Consumer<Response> {

        private final LinkedList<Response> responses = new LinkedList<>();

        @Override
        public void accept(Response response) {
            responses.add(response);
        }

        public LinkedList<Response> getResponses() {
            return responses;
        }
    }

    private static class ResponseOrderTracker implements Consumer<Response> {

        private final LinkedList<String> responseTypes = new LinkedList<>();

        @Override
        public void accept(Response response) {
            responseTypes.add(response.request().getTypeUrl());
        }
    }

    private static class DeltaResponseTracker implements Consumer<DeltaResponse> {

        private final LinkedList<ResponseResources> responses = new LinkedList<>();

        @Override
        public void accept(DeltaResponse response) {
            responses.add(new ResponseResources(response, response.resources()
                    .entrySet().stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            entry -> entry.getValue().version()
                    ))));
        }
    }

    protected static class ResponseResources {
        final DeltaResponse response;
        final Map<String, String> resourceVersions;

        protected ResponseResources(DeltaResponse response, Map<String, String> resourceVersions) {
            this.response = response;
            this.resourceVersions = resourceVersions;
        }
    }

    protected static class SingleNodeGroup implements NodeGroup<String> {

        protected static final String GROUP = "node";

        @Override
        public String hash(Node node) {
            if (node == null) {
                throw new IllegalArgumentException("node");
            }

            return GROUP;
        }
    }

    protected static class WatchAndTracker {

        final Watch watch;
        final ResponseTracker tracker;

        WatchAndTracker(Watch watch, ResponseTracker tracker) {
            this.watch = watch;
            this.tracker = tracker;
        }
    }

    protected static class DeltaWatchAndTracker {

        final DeltaWatch watch;
        final DeltaResponseTracker tracker;

        DeltaWatchAndTracker(DeltaWatch watch, DeltaResponseTracker tracker) {
            this.watch = watch;
            this.tracker = tracker;
        }
    }

    protected static class ResourceTypeUrlAndName {
        final String resourceTypeUrl;
        final String resourceName;

        protected ResourceTypeUrlAndName(String resourceTypeUrl, String resourceName) {
            this.resourceTypeUrl = resourceTypeUrl;
            this.resourceName = resourceName;
        }
    }
}
