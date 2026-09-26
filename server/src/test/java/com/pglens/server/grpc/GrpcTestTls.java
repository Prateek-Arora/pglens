package com.pglens.server.grpc;

import io.grpc.Grpc;
import io.grpc.ManagedChannelBuilder;
import io.grpc.TlsChannelCredentials;
import io.grpc.testing.TlsTesting;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * TLS for server tests, the production default (ADR-0044). Uses grpc-java's bundled test CA and
 * server certificate (from {@code grpc-testing}), copied to temp files at runtime so no private key
 * is ever committed to this repo. The certificate is issued for {@code *.test.google.fr}, so
 * clients verify {@link #AUTHORITY} instead of {@code localhost}.
 */
public final class GrpcTestTls {

  /** A name the test server certificate is valid for. */
  public static final String AUTHORITY = "foo.test.google.fr";

  public static final File CA = copy("ca.pem");
  public static final File SERVER_CERT = copy("server1.pem");
  public static final File SERVER_KEY = copy("server1.key");

  private GrpcTestTls() {}

  /** Points the server's gRPC listener at the test certificate. */
  public static void register(DynamicPropertyRegistry registry) {
    registry.add("pglens.grpc.tls.cert", SERVER_CERT::getAbsolutePath);
    registry.add("pglens.grpc.tls.key", SERVER_KEY::getAbsolutePath);
  }

  /** A client channel to {@code localhost:port} that trusts only the test CA. */
  public static ManagedChannelBuilder<?> channel(int port) {
    try {
      return Grpc.newChannelBuilderForAddress(
              "localhost", port, TlsChannelCredentials.newBuilder().trustManager(CA).build())
          .overrideAuthority(AUTHORITY);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static File copy(String name) {
    try (InputStream in = TlsTesting.loadCert(name)) {
      File f = File.createTempFile("pglens-test-", "-" + name);
      f.deleteOnExit();
      Files.copy(in, f.toPath(), StandardCopyOption.REPLACE_EXISTING);
      return f;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
