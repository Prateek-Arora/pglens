package com.pglens.agent.grpc;

import com.pglens.proto.v1.IngestGrpc;
import com.pglens.proto.v1.IngestSummary;
import com.pglens.proto.v1.SampleBatch;
import io.grpc.Channel;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Thin gRPC client for the {@code Ingest.StreamSnapshot} RPC. Opens a short-lived client-streaming
 * call per interval, sends this batch, half-closes, and blocks for the one {@link IngestSummary}
 * watermark. The per-agent bearer token (ADR-0027) is attached as the {@code x-pglens-token} header
 * the server's {@code AuthInterceptor} reads.
 */
public class IngestClient {

  /** Must match the server-side {@code AuthInterceptor.TOKEN_HEADER}. */
  static final Metadata.Key<String> TOKEN_HEADER =
      Metadata.Key.of("x-pglens-token", Metadata.ASCII_STRING_MARSHALLER);

  private final Channel channel;
  private final String token;

  public IngestClient(Channel channel, String token) {
    this.channel = channel;
    this.token = token == null ? "" : token;
  }

  /**
   * Streams one batch and returns the server's watermark. Blocks up to {@code timeoutSeconds} for
   * the response.
   *
   * @throws IngestException if the server rejects the batch, the stream fails, or the deadline
   *     elapses — the caller logs and retries next interval (server-side deltas are ack-anchored,
   *     so a failed send loses no window; ADR-0024).
   */
  public IngestSummary send(SampleBatch batch, long timeoutSeconds) {
    Metadata headers = new Metadata();
    headers.put(TOKEN_HEADER, token);
    IngestGrpc.IngestStub stub =
        IngestGrpc.newStub(channel)
            .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));

    CompletableFuture<IngestSummary> result = new CompletableFuture<>();
    StreamObserver<SampleBatch> request =
        stub.streamSnapshot(
            new StreamObserver<>() {
              private IngestSummary summary;

              @Override
              public void onNext(IngestSummary value) {
                this.summary = value;
              }

              @Override
              public void onError(Throwable t) {
                result.completeExceptionally(t);
              }

              @Override
              public void onCompleted() {
                result.complete(summary);
              }
            });

    try {
      request.onNext(batch);
      request.onCompleted();
      return result.get(timeoutSeconds, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      request.onError(e);
      Thread.currentThread().interrupt();
      throw new IngestException("interrupted while streaming a sample batch", e);
    } catch (ExecutionException e) {
      throw new IngestException("server rejected the sample batch", e.getCause());
    } catch (TimeoutException e) {
      request.onError(e);
      throw new IngestException("timed out waiting for the ingest watermark", e);
    }
  }

  /** Thrown when a single ingest attempt fails; the collector logs it and retries next interval. */
  public static class IngestException extends RuntimeException {
    public IngestException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
