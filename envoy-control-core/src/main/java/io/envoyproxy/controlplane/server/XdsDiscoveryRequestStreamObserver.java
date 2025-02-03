package io.envoyproxy.controlplane.server;

import io.envoyproxy.controlplane.cache.Watch;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * {@code XdsDiscoveryRequestStreamObserver} is a lightweight implementation of {@link DiscoveryRequestStreamObserver}
 * tailored for non-ADS streams which handle a single watch.
 */
public class XdsDiscoveryRequestStreamObserver<T, U> extends DiscoveryRequestStreamObserver<T, U> {
  private volatile Watch watch;
  private volatile io.envoyproxy.controlplane.server.LatestDiscoveryResponse latestDiscoveryResponse;
  // ackedResources is only used in the same thread so it need not be volatile
  private Set<String> ackedResources;

  XdsDiscoveryRequestStreamObserver(String defaultTypeUrl,
                                    StreamObserver<U> responseObserver,
                                    long streamId,
                                    Executor executor,
                                    DiscoveryServer<T, U, ?, ?, ?> discoveryServer,
                                    MeterRegistry meterRegistry) {
    super(defaultTypeUrl, responseObserver, streamId, executor, discoveryServer, meterRegistry);
    this.ackedResources = Collections.emptySet();
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
  io.envoyproxy.controlplane.server.LatestDiscoveryResponse latestResponse(String typeUrl) {
    return latestDiscoveryResponse;
  }

  @Override
  void setLatestResponse(String typeUrl, LatestDiscoveryResponse response) {
    latestDiscoveryResponse = response;
  }

  @Override
  Set<String> ackedResources(String typeUrl) {
    return ackedResources;
  }

  @Override
  void setAckedResources(String typeUrl, Set<String> resources) {
    ackedResources = resources;
  }

  @Override
  void computeWatch(String typeUrl, Supplier<Watch> watchCreator) {
    cancel();
    watch = watchCreator.get();
  }
}
