package com.pglens.explain.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;

/**
 * A scripted OpenAI-compatible endpoint on the JDK's built-in HTTP server — no model and no extra
 * test dependency. Queue responses with {@link #reply}; inspect what PgLens sent in {@link
 * #requests}.
 */
public final class StubLlmServer implements AutoCloseable {

  /** One scripted reply: status, body, and an optional delay before answering. */
  public record Reply(int status, String body, long delayMs) {}

  private final HttpServer server;
  private final Deque<Reply> replies = new ArrayDeque<>();
  private final List<String> requests = new ArrayList<>();
  private final List<String> paths = new ArrayList<>();
  private volatile int autoEmbeddingDims;

  public StubLlmServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          Reply r;
          String body =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          String path = exchange.getRequestURI().getPath();
          synchronized (this) {
            requests.add(body);
            paths.add(path);
            if (autoEmbeddingDims > 0 && path.endsWith("/embeddings")) {
              r = new Reply(200, embeddings(body, autoEmbeddingDims), 0);
            } else {
              r =
                  replies.isEmpty()
                      ? new Reply(500, "{\"error\":\"no scripted reply\"}", 0)
                      : replies.poll();
            }
          }
          if (r.delayMs() > 0) {
            try {
              Thread.sleep(r.delayMs());
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
          byte[] out = r.body().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(r.status(), out.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(out);
          } catch (IOException ignored) {
            // the client gave up (timeout test)
          }
        });
    server.start();
  }

  public String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
  }

  public synchronized StubLlmServer reply(int status, String body) {
    replies.add(new Reply(status, body, 0));
    return this;
  }

  public synchronized StubLlmServer replySlowly(long delayMs, String body) {
    replies.add(new Reply(200, body, delayMs));
    return this;
  }

  /** A successful chat completion whose message content is {@code content}. */
  public StubLlmServer replyContent(String content) {
    return reply(200, chatBody(content, "stop"));
  }

  public static String chatBody(String content, String finishReason) {
    return "{\"model\":\"stub\",\"choices\":[{\"index\":0,\"finish_reason\":\""
        + finishReason
        + "\",\"message\":{\"role\":\"assistant\",\"content\":"
        + quote(content)
        + "}}],\"usage\":{\"prompt_tokens\":700,\"completion_tokens\":120}}";
  }

  /**
   * Answers every {@code /embeddings} call itself (without using the reply queue) with one
   * deterministic unit vector per input, derived from the input's hash — equal texts, equal
   * vectors.
   */
  public StubLlmServer autoEmbeddings(int dims) {
    this.autoEmbeddingDims = dims;
    return this;
  }

  /** How many requests hit a path ending in {@code suffix}. */
  public synchronized long count(String suffix) {
    return paths.stream().filter(p -> p.endsWith(suffix)).count();
  }

  private static String embeddings(String requestBody, int dims) {
    try {
      JsonNode inputs = new ObjectMapper().readTree(requestBody).get("input");
      StringBuilder sb = new StringBuilder("{\"data\":[");
      for (int i = 0; i < inputs.size(); i++) {
        Random rnd = new Random(inputs.get(i).asText().hashCode());
        double[] v = new double[dims];
        double norm = 0;
        for (int d = 0; d < dims; d++) {
          v[d] = rnd.nextGaussian();
          norm += v[d] * v[d];
        }
        sb.append(i == 0 ? "" : ",").append("{\"index\":").append(i).append(",\"embedding\":[");
        for (int d = 0; d < dims; d++) {
          sb.append(d == 0 ? "" : ",").append((float) (v[d] / Math.sqrt(norm)));
        }
        sb.append("]}");
      }
      return sb.append("]}").toString();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public synchronized List<String> requests() {
    return List.copyOf(requests);
  }

  public synchronized List<String> paths() {
    return List.copyOf(paths);
  }

  static String quote(String s) {
    return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
