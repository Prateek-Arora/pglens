package com.pglens.explain.llm;

/**
 * Why an LLM call produced no usable answer. Every kind degrades to the template explanation with
 * this message as the reason (charter #3); none is fatal to a scan.
 */
public class LlmException extends Exception {

  /** What went wrong, coarse enough to report and test. */
  public enum Kind {
    /** The endpoint is remote and remote endpoints aren't allowed. */
    REFUSED_REMOTE,
    /** Nothing listening, DNS failure, connection reset. */
    UNREACHABLE,
    /** No answer within the configured timeout. */
    TIMEOUT,
    /** A non-2xx HTTP status. */
    HTTP_ERROR,
    /** {@code finish_reason = length}: the answer was cut off at {@code max_tokens}. */
    TRUNCATED,
    /** No content (for a thinking model: it spent its tokens thinking). */
    EMPTY,
    /** The response isn't the OpenAI shape, or the content isn't the JSON asked for. */
    UNPARSEABLE
  }

  private final Kind kind;

  public LlmException(Kind kind, String message) {
    super(message);
    this.kind = kind;
  }

  public LlmException(Kind kind, String message, Throwable cause) {
    super(message, cause);
    this.kind = kind;
  }

  public Kind kind() {
    return kind;
  }
}
