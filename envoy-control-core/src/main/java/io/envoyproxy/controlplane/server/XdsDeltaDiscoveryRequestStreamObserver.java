package io.envoyproxy.controlplane.server;

import io.envoyproxy.controlplane.cache.DeltaWatch;
import io.envoyproxy.controlplane.cache.Resources;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * {@code XdsDeltaDiscoveryRequestStreamObserver} is a lightweight implementation of
 * {@link io.envoyproxy.controlplane.server.DeltaDiscoveryRequestStreamObserver} tailored for non-ADS streams which handle a single watch.
 */
public class XdsDeltaDiscoveryRequestStreamObserver<V, X, Y> extends DeltaDiscoveryRequestStreamObserver<V, X, Y> {
  // tracked is only used in the same thread so it need not be volatile
  private final Map<String, String> trackedResources;
  private final Set<String> pendingResources;
  private final boolean isWildcard;
  private final ConcurrentMap<String, io.envoyproxy.controlplane.server.LatestDeltaDiscoveryResponse> responses;
  private volatile DeltaWatch watch;
  private volatile String latestVersion;

  XdsDeltaDiscoveryRequestStreamObserver(String defaultTypeUrl,
                                         StreamObserver<X> responseObserver,
                                         long streamId,
                                         Executor executor,
                                         DiscoveryServer<?, ?, V, X, Y> discoveryServer, MeterRegistry meterRegistry) {
    super(defaultTypeUrl, responseObserver, streamId, executor, discoveryServer, meterRegistry);
    this.trackedResources = new HashMap<>();
    this.pendingResources = new HashSet<>();
    Resources.ResourceType resourceType = Resources.TYPE_URLS_TO_RESOURCE_TYPE.get(defaultTypeUrl);
    this.isWildcard = Resources.ResourceType.CLUSTER.equals(resourceType)
        || Resources.ResourceType.LISTENER.equals(resourceType);
    this.responses = new ConcurrentHashMap<>();
  }

  @Override
  void cancel() {
    if (watch != null) {
      watch.cancel();
    }
  }

  @Override
  boolean ads() {
    return false;
  }

  @Override
  void setLatestVersion(String typeUrl, String version) {
    latestVersion = version;
  }

  @Override
  String latestVersion(String typeUrl) {
    return latestVersion;
  }

  @Override
  void setResponse(String typeUrl, String nonce, io.envoyproxy.controlplane.server.LatestDeltaDiscoveryResponse response) {
    responses.put(nonce, response);
  }

  @Override
  LatestDeltaDiscoveryResponse clearResponse(String typeUrl, String nonce) {
    return responses.remove(nonce);
  }

  @Override
  int responseCount(String typeUrl) {
    return responses.size();
  }

  @Override
  Map<String, String> resourceVersions(String typeUrl) {
    return trackedResources;
  }

  @Override
  Set<String> pendingResources(String typeUrl) {
    return pendingResources;
  }

  @Override
  boolean isWildcard(String typeUrl) {
    return isWildcard;
  }

  @Override
  void updateTrackedResources(String typeUrl,
                              Map<String, String> resourcesVersions,
                              List<String> removedResources) {

    resourcesVersions.forEach((k, v) -> {
      trackedResources.put(k, v);
      pendingResources.remove(k);
    });
    removedResources.forEach(trackedResources::remove);
  }

  @Override
  void updateSubscriptions(String typeUrl,
                           List<String> resourceNamesSubscribe,
                           List<String> resourceNamesUnsubscribe) {

    // unsubscribe first
    resourceNamesUnsubscribe.forEach(s -> {
      trackedResources.remove(s);
      pendingResources.remove(s);
    });
    pendingResources.addAll(resourceNamesSubscribe);
  }

  @Override
  void computeWatch(String typeUrl, Supplier<DeltaWatch> watchCreator) {
    cancel();
    watch = watchCreator.get();
  }
}
