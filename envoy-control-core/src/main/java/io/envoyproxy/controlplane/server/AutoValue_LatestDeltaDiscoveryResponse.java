

package io.envoyproxy.controlplane.server;

import javax.annotation.Generated;
import java.util.List;
import java.util.Map;

@Generated("com.google.auto.value.processor.AutoValueProcessor")
final class AutoValue_LatestDeltaDiscoveryResponse extends LatestDeltaDiscoveryResponse {

  private final String nonce;

  private final String version;

  private final Map<String, String> resourceVersions;

  private final List<String> removedResources;

  AutoValue_LatestDeltaDiscoveryResponse(
      String nonce,
      String version,
      Map<String, String> resourceVersions,
      List<String> removedResources) {
    if (nonce == null) {
      throw new NullPointerException("Null nonce");
    }
    this.nonce = nonce;
    if (version == null) {
      throw new NullPointerException("Null version");
    }
    this.version = version;
    if (resourceVersions == null) {
      throw new NullPointerException("Null resourceVersions");
    }
    this.resourceVersions = resourceVersions;
    if (removedResources == null) {
      throw new NullPointerException("Null removedResources");
    }
    this.removedResources = removedResources;
  }

  @Override
  String nonce() {
    return nonce;
  }

  @Override
  String version() {
    return version;
  }

  @Override
  Map<String, String> resourceVersions() {
    return resourceVersions;
  }

  @Override
  List<String> removedResources() {
    return removedResources;
  }

  @Override
  public String toString() {
    return "LatestDeltaDiscoveryResponse{"
         + "nonce=" + nonce + ", "
         + "version=" + version + ", "
         + "resourceVersions=" + resourceVersions + ", "
         + "removedResources=" + removedResources
        + "}";
  }

  @Override
  public boolean equals(Object o) {
    if (o == this) {
      return true;
    }
    if (o instanceof LatestDeltaDiscoveryResponse) {
      LatestDeltaDiscoveryResponse that = (LatestDeltaDiscoveryResponse) o;
      return this.nonce.equals(that.nonce())
          && this.version.equals(that.version())
          && this.resourceVersions.equals(that.resourceVersions())
          && this.removedResources.equals(that.removedResources());
    }
    return false;
  }

  @Override
  public int hashCode() {
    int h$ = 1;
    h$ *= 1000003;
    h$ ^= nonce.hashCode();
    h$ *= 1000003;
    h$ ^= version.hashCode();
    h$ *= 1000003;
    h$ ^= resourceVersions.hashCode();
    h$ *= 1000003;
    h$ ^= removedResources.hashCode();
    return h$;
  }

}
