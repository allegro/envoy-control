package io.envoyproxy.controlplane.cache;

import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Message;

class ResourceMapBuilder<T extends Message> {

  private final ImmutableMap.Builder<String, io.envoyproxy.controlplane.cache.VersionedResource<T>> versionedResources = ImmutableMap.builder();
  private final ImmutableMap.Builder<String, T> resources = ImmutableMap.builder();


  ImmutableMap<String, io.envoyproxy.controlplane.cache.VersionedResource<T>> getVersionedResources() {
    return versionedResources.build();
  }

  ImmutableMap<String, T> getResources() {
    return resources.build();
  }

  void put(Object resource) {
    if (resource instanceof io.envoyproxy.controlplane.cache.VersionedResource) {
      io.envoyproxy.controlplane.cache.VersionedResource<T> eCast = (io.envoyproxy.controlplane.cache.VersionedResource<T>) resource;
      versionedResources.put(io.envoyproxy.controlplane.cache.Resources.getResourceName(eCast.resource()), eCast);
      resources.put(io.envoyproxy.controlplane.cache.Resources.getResourceName(eCast.resource()), eCast.resource());
    } else {
      T eCast = (T) resource;
      versionedResources.put(io.envoyproxy.controlplane.cache.Resources.getResourceName(eCast), VersionedResource.create(eCast));
      resources.put(Resources.getResourceName(eCast), eCast);
    }
  }

  ResourceMapBuilder<T> putAll(ResourceMapBuilder<T> other) {
    versionedResources.putAll(other.getVersionedResources());
    resources.putAll(other.getResources());
    return this;
  }
}
