package com.pglens.explain.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EndpointPolicyTest {

  // Deterministic DNS: "gpu.corp.example" is private, "api.example.com" public, others unknown.
  private final EndpointPolicy policy =
      new EndpointPolicy(
          host ->
              switch (host) {
                case "gpu.corp.example" ->
                    new InetAddress[] {InetAddress.getByAddress(host, new byte[] {10, 0, 0, 7})};
                case "api.example.com" ->
                    new InetAddress[] {
                      InetAddress.getByAddress(host, new byte[] {93, (byte) 184, (byte) 215, 14})
                    };
                case "split.example.com" ->
                    new InetAddress[] {
                      InetAddress.getByAddress(host, new byte[] {10, 0, 0, 8}),
                      InetAddress.getByAddress(host, new byte[] {8, 8, 8, 8})
                    };
                default -> throw new UnknownHostException(host);
              });

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://localhost:11434/v1",
        "http://127.0.0.1:11434/v1",
        "http://[::1]:11434/v1",
        "http://host.docker.internal:11434/v1",
        "http://ollama:11434/v1",
        "http://192.168.1.20:8080/v1",
        "http://172.20.0.5:12434/engines/v1",
        "http://100.101.102.103:11434/v1",
        "http://[fd12:3456::1]:11434/v1",
        "http://gpu.corp.example:8000/v1"
      })
  void localAndPrivateEndpointsAreAllowed(String url) {
    EndpointPolicy.Decision d = policy.check(URI.create(url), false);
    assertThat(d.allowed()).isTrue();
    assertThat(d.remote()).isFalse();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://api.example.com/v1",
        "http://8.8.8.8/v1",
        "https://split.example.com/v1",
        "https://unknown.example.org/v1"
      })
  void anythingElseIsRefusedUnlessAllowed(String url) {
    EndpointPolicy.Decision refused = policy.check(URI.create(url), false);
    assertThat(refused.allowed()).isFalse();
    assertThat(refused.remote()).isTrue();
    assertThat(refused.reason()).contains("--allow-remote-llm");

    EndpointPolicy.Decision allowed = policy.check(URI.create(url), true);
    assertThat(allowed.allowed()).isTrue();
    assertThat(allowed.reason()).contains("query text and table names are sent to");
  }
}
