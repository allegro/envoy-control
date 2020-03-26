package pl.allegro.tech.servicemesh.envoycontrol;

import com.google.common.collect.ImmutableList;
import io.envoyproxy.controlplane.cache.Resources;
import io.envoyproxy.controlplane.cache.Snapshot;
import io.envoyproxy.controlplane.cache.Watch;
import io.envoyproxy.envoy.api.v2.ClusterLoadAssignment;
import io.envoyproxy.envoy.api.v2.DiscoveryRequest;
import io.envoyproxy.envoy.api.v2.core.Node;
import org.junit.Ignore;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static java.util.Collections.emptyList;

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
        SimpleCache<String> cache = new SimpleCache<>(new SingleNodeGroup(), shouldSendMissingEndpoints());

        cache.setSnapshot(SingleNodeGroup.GROUP, MULTIPLE_RESOURCES_SNAPSHOT2);

        ResponseTracker responseTracker = new ResponseTracker();

        Watch watch = cache.createWatch(
                true,
                DiscoveryRequest.newBuilder()
                        .setNode(Node.getDefaultInstance())
                        .setTypeUrl(Resources.ENDPOINT_TYPE_URL)
                        .addResourceNames("none")
                        .addResourceNames(CLUSTER_NAME)
                        .build(),
                Collections.emptySet(),
                responseTracker,
                false);

        assertThatWatchReceivesSnapshot(new WatchAndTracker(watch, responseTracker), SNAPSHOT_WITH_MISSING_RESOURCES);
    }
}
