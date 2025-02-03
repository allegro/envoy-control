

package io.envoyproxy.controlplane.server;

import javax.annotation.Generated;
import java.util.Set;

@Generated("com.google.auto.value.processor.AutoValueProcessor")
final class AutoValue_LatestDiscoveryResponse extends LatestDiscoveryResponse {

  private final String nonce;

  private final Set<String> resourceNames;

  AutoValue_LatestDiscoveryResponse(
      String nonce,
      Set<String> resourceNames) {
    if (nonce == null) {
      throw new NullPointerException("Null nonce");
    }
    this.nonce = nonce;
    if (resourceNames == null) {
      throw new NullPointerException("Null resourceNames");
    }
    this.resourceNames = resourceNames;
  }

  @Override
  String nonce() {
    return nonce;
  }

  @Override
  Set<String> resourceNames() {
    return resourceNames;
  }

  @Override
  public String toString() {
    return "LatestDiscoveryResponse{"
         + "nonce=" + nonce + ", "
         + "resourceNames=" + resourceNames
        + "}";
  }

  @Override
  public boolean equals(Object o) {
    if (o == this) {
      return true;
    }
    if (o instanceof LatestDiscoveryResponse) {
      LatestDiscoveryResponse that = (LatestDiscoveryResponse) o;
      return this.nonce.equals(that.nonce())
          && this.resourceNames.equals(that.resourceNames());
    }
    return false;
  }

  @Override
  public int hashCode() {
    int h$ = 1;
    h$ *= 1000003;
    h$ ^= nonce.hashCode();
    h$ *= 1000003;
    h$ ^= resourceNames.hashCode();
    return h$;
  }

}
