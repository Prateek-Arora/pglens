package com.pglens.server.grpc;

import io.grpc.InsecureServerCredentials;
import io.grpc.ServerCredentials;
import io.grpc.TlsServerCredentials;
import java.io.File;
import java.io.IOException;

/**
 * How the gRPC listener is secured (ADR-0044): TLS by default, plaintext only when explicitly asked
 * for. The agent's bearer token rides every call, so a plaintext channel exposes it to anyone on
 * the network path — that is why plaintext is an opt-out, never a fallback.
 */
public final class GrpcServerSecurity {

  private GrpcServerSecurity() {}

  /**
   * @param plaintext {@code pglens.grpc.plaintext} — true only for a trusted private network
   * @param certChain PEM certificate chain ({@code pglens.grpc.tls.cert})
   * @param privateKey PEM, unencrypted PKCS#8 private key ({@code pglens.grpc.tls.key})
   * @throws IllegalStateException TLS is on but the certificate or key is missing or unreadable
   */
  public static ServerCredentials credentials(
      boolean plaintext, String certChain, String privateKey) {
    if (plaintext) {
      return InsecureServerCredentials.create();
    }
    File cert = readable("pglens.grpc.tls.cert (PGLENS_GRPC_TLS_CERT)", certChain);
    File key = readable("pglens.grpc.tls.key (PGLENS_GRPC_TLS_KEY)", privateKey);
    try {
      return TlsServerCredentials.create(cert, key);
    } catch (IOException | IllegalArgumentException e) {
      throw new IllegalStateException(
          "gRPC TLS: could not load the certificate/key (" + e.getMessage() + ")", e);
    }
  }

  private static File readable(String setting, String path) {
    if (path == null || path.isBlank()) {
      throw new IllegalStateException(
          "gRPC TLS is on (the default) but "
              + setting
              + " is not set. Point it at a PEM file, or set PGLENS_GRPC_PLAINTEXT=true to run"
              + " without TLS on a trusted private network (the agent token then travels"
              + " unencrypted).");
    }
    File f = new File(path);
    if (!f.canRead()) {
      throw new IllegalStateException("gRPC TLS: " + setting + " is not readable: " + path);
    }
    return f;
  }
}
