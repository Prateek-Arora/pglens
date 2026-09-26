package com.pglens.explain.llm;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Decides whether PgLens may send a prompt to an endpoint. A prompt carries normalized query text
 * and table/column names, so by default it may only go to this machine or a private network
 * (ADR-0043 §9): loopback, RFC 1918, link-local, 100.64/10 (e.g. a Tailscale GPU box) and IPv6
 * unique-local addresses, {@code host.docker.internal}, and single-label names (Compose/Kubernetes
 * service names such as {@code ollama}). A name is resolved and every address must be local.
 * Anything else needs {@code allowRemote}.
 *
 * <p>This guards against mistakes (a hosted URL pasted into a config), not against someone who
 * controls your DNS.
 */
public final class EndpointPolicy {

  /** The verdict for one base URL. {@code remote} is true when the host isn't local. */
  public record Decision(boolean allowed, boolean remote, String host, String reason) {}

  /** Resolves host names; replaceable in tests. */
  @FunctionalInterface
  public interface Resolver {
    InetAddress[] resolve(String host) throws UnknownHostException;
  }

  private final Resolver resolver;

  public EndpointPolicy() {
    this(InetAddress::getAllByName);
  }

  public EndpointPolicy(Resolver resolver) {
    this.resolver = resolver;
  }

  public Decision check(URI baseUrl, boolean allowRemote) {
    String host = baseUrl.getHost();
    if (host == null) {
      return new Decision(false, false, null, "the LLM URL has no host: " + baseUrl);
    }
    boolean local = isLocal(host);
    if (local) {
      return new Decision(true, false, host, "local endpoint");
    }
    if (allowRemote) {
      return new Decision(
          true,
          true,
          host,
          "remote endpoint allowed: query text and table names are sent to " + host);
    }
    return new Decision(
        false,
        true,
        host,
        host
            + " is not on this machine or a private network, so PgLens won't send it query text."
            + " Use a local model, or pass --allow-remote-llm (server:"
            + " pglens.llm.allow-remote=true) if you accept sending it.");
  }

  private boolean isLocal(String rawHost) {
    String host = rawHost.toLowerCase(Locale.ROOT);
    if (host.startsWith("[") && host.endsWith("]")) {
      host = host.substring(1, host.length() - 1);
    }
    if (host.equals("localhost") || host.equals("host.docker.internal")) {
      return true;
    }
    boolean literal = host.contains(":") || host.matches("[0-9.]+");
    if (!literal && !host.contains(".")) {
      return true; // a single-label service name; public names always have a dot
    }
    try {
      // A literal is parsed, never looked up; a name goes through the resolver.
      InetAddress[] addresses =
          literal ? new InetAddress[] {InetAddress.getByName(host)} : resolver.resolve(host);
      if (addresses.length == 0) {
        return false;
      }
      for (InetAddress a : addresses) {
        if (!isPrivate(a)) {
          return false;
        }
      }
      return true;
    } catch (UnknownHostException e) {
      return false;
    }
  }

  static boolean isPrivate(InetAddress a) {
    if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()) {
      return true;
    }
    byte[] b = a.getAddress();
    if (b.length == 4) {
      return (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64; // 100.64.0.0/10 (CGNAT, Tailscale)
    }
    return (b[0] & 0xfe) == 0xfc; // IPv6 unique local, fc00::/7
  }
}
