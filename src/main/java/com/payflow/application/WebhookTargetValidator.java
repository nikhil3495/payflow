package com.payflow.application;

import com.payflow.config.PayflowProperties;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Guards outbound webhook targets against SSRF: https only (unless explicitly relaxed), no
 * credentials in the URL, and no destination that resolves to a loopback, private, link-local or
 * otherwise internal address (unless explicitly relaxed for local development and tests).
 */
@Component
public class WebhookTargetValidator {

  private final boolean allowPrivate;
  private final boolean allowInsecure;

  public WebhookTargetValidator(PayflowProperties properties) {
    this.allowPrivate = properties.webhook().allowPrivateTargets();
    this.allowInsecure = properties.webhook().allowInsecureTargets();
  }

  /**
   * @throws IllegalArgumentException describing why the URL is not an acceptable target
   */
  public URI validate(String url) {
    URI uri;
    try {
      uri = new URI(url);
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException("url is not a valid URI");
    }
    String scheme =
        uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
    boolean schemeOk = scheme.equals("https") || (allowInsecure && scheme.equals("http"));
    if (!schemeOk) {
      throw new IllegalArgumentException("url must use https");
    }
    if (uri.getHost() == null || uri.getUserInfo() != null) {
      throw new IllegalArgumentException("url must have a host and no credentials");
    }
    if (!allowPrivate) {
      for (InetAddress address : resolve(uri.getHost())) {
        if (isInternal(address)) {
          throw new IllegalArgumentException("url resolves to a non-public address");
        }
      }
    }
    return uri;
  }

  private static List<InetAddress> resolve(String host) {
    try {
      return List.of(InetAddress.getAllByName(host));
    } catch (UnknownHostException e) {
      throw new IllegalArgumentException("url host cannot be resolved");
    }
  }

  static boolean isInternal(InetAddress address) {
    if (address.isAnyLocalAddress()
        || address.isLoopbackAddress()
        || address.isLinkLocalAddress()
        || address.isSiteLocalAddress()
        || address.isMulticastAddress()) {
      return true;
    }
    byte[] bytes = address.getAddress();
    if (address instanceof Inet6Address) {
      return (bytes[0] & 0xFE) == 0xFC; // unique local fc00::/7
    }
    // Carrier-grade NAT 100.64.0.0/10
    return (bytes[0] & 0xFF) == 100 && (bytes[1] & 0xC0) == 64;
  }
}
