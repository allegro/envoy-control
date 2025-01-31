package pl.allegro.tech.servicemesh.envoycontrol.v3;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.Message;
import io.envoyproxy.controlplane.cache.Resources;
import io.envoyproxy.controlplane.cache.Response;
import io.envoyproxy.controlplane.cache.VersionedResource;
import io.envoyproxy.controlplane.cache.Watch;
import io.envoyproxy.controlplane.cache.XdsRequest;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.envoy.config.core.v3.Node;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.Ignore;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;

public class SimpleCacheWithMissingEndpointsTest extends SimpleCacheTest {

    @Override
    protected boolean shouldSendMissingEndpoints() {
        return true;
    }

    protected static final Snapshot SNAPSHOT_WITH_MISSING_RESOURCES = Snapshot.create(
            emptyList(),
            ImmutableList.of(
                    ClusterLoadAssignment.newBuilder().setClusterName("none").build(),
                    ClusterLoadAssignment.newBuilder().setClusterName(CLUSTER_NAME).build()
            ),
            emptyList(),
            emptyList(),
            emptyList(),
            VERSION2
    );

    @Ignore
    @Override
    public void invalidNamesListShouldReturnWatcherWithNoResponseInAdsMode() {
    }

    @Test
    public void missingNamesListShouldReturnWatcherWithResponseInAdsMode() {
        pl.allegro.tech.servicemesh.envoycontrol.v3.SimpleCache<String> cache = new pl.allegro.tech.servicemesh.envoycontrol.v3.SimpleCache<>(new SingleNodeGroup(), shouldSendMissingEndpoints(), new SimpleMeterRegistry());

        cache.setSnapshot(SingleNodeGroup.GROUP, MULTIPLE_RESOURCES_SNAPSHOT2);

        ResponseTracker responseTracker = new ResponseTracker();

        Watch watch = cache.createWatch(
                true,
                XdsRequest.create(DiscoveryRequest.newBuilder()
                        .setNode(Node.getDefaultInstance())
                        .setTypeUrl(Resources.V3.ENDPOINT_TYPE_URL)
                        .addResourceNames("none")
                        .addResourceNames(CLUSTER_NAME)
                        .build()),
                Collections.emptySet(),
                responseTracker,
                false,
                false
            );

        assertThatWatchReceivesSnapshotWithMissingResources(new WatchAndTracker(watch, responseTracker), SNAPSHOT_WITH_MISSING_RESOURCES);
    }

    private static void assertThatWatchReceivesSnapshotWithMissingResources(WatchAndTracker watchAndTracker, Snapshot snapshot) {
        assertThat(watchAndTracker.tracker.getResponses()).isNotEmpty();

        Response response = watchAndTracker.tracker.getResponses().getFirst();

        assertThat(response).isNotNull();
        assertThat(response.version()).isEqualTo(snapshot.version(watchAndTracker.watch.request().getTypeUrl()));
        Message[] responseValues = response.resources().toArray(new Message[0]);
        Message[] snapshotValues = snapshot.resources(watchAndTracker.watch.request().getTypeUrl()).values().stream().map(VersionedResource::resource).toArray(Message[]::new);

        assertThat(responseValues.length).isEqualTo(2);
        assertThat(responseValues.length).isEqualTo(snapshotValues.length);
        assertThat(responseValues[0].toString()).isEqualTo(snapshotValues[0].toString());
        assertThat(responseValues[1].toString()).isEqualTo(snapshotValues[1].toString());
    }
}
