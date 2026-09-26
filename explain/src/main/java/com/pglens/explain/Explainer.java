package com.pglens.explain;

import com.pglens.explain.Explanation.Source;
import com.pglens.explain.ReferenceSource.Reference;
import com.pglens.explain.llm.LlmException;
import com.pglens.explain.llm.LlmException.Kind;
import com.pglens.explain.llm.OpenAiCompatibleClient;
import com.pglens.explain.llm.OpenAiCompatibleClient.ChatResponse;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Explains one target: facts → prompt → LLM → {@link OutputGuard}; a failing answer is retried once
 * with the rules it broke, then replaced by the {@link TemplateExplainer}. Any LLM failure also
 * falls back to the template, with the reason — the LLM is optional (charter #3).
 *
 * <p>After a failure that would repeat for every target (endpoint refused, unreachable, timed out,
 * HTTP error), later targets go straight to the template with the same reason, so a scan with the
 * model down costs one connection attempt, not one timeout per index. Not thread-safe.
 */
public final class Explainer {

  /** One LLM attempt, kept for the eval (M1/M5/M8) and debugging. */
  public record Attempt(
      String content, List<String> violations, long elapsedMs, Integer promptTokens) {}

  /**
   * An explanation plus the attempts that led to it. {@code failure} is set when the LLM call
   * itself failed (not when its answer failed the guard): the server retries those later, since an
   * outage is transient but a rejected answer at temperature 0 repeats.
   */
  public record Trace(Explanation explanation, List<Attempt> attempts, Kind failure) {
    Trace(Explanation explanation, List<Attempt> attempts) {
      this(explanation, attempts, null);
    }
  }

  private static final Set<Kind> SAME_FOR_EVERY_TARGET =
      EnumSet.of(Kind.REFUSED_REMOTE, Kind.UNREACHABLE, Kind.TIMEOUT, Kind.HTTP_ERROR);

  private final OpenAiCompatibleClient client;
  private final ReferenceSource references;
  private final boolean referencesInPrompt;
  private String brokenReason;
  private Kind brokenKind;

  /** An explainer that uses {@code client}, or only the template when {@code client} is null. */
  public Explainer(OpenAiCompatibleClient client, ReferenceSource references) {
    this(client, references, true);
  }

  /**
   * @param referencesInPrompt put retrieved passages in the prompt; when false they only add
   *     "further reading" links (the default until the eval's A/B says otherwise, §1.6)
   */
  public Explainer(
      OpenAiCompatibleClient client, ReferenceSource references, boolean referencesInPrompt) {
    this.client = client;
    this.references = references == null ? ReferenceSource.NONE : references;
    this.referencesInPrompt = referencesInPrompt;
  }

  /** Only the deterministic template ({@code --plain=template}, or no LLM configured). */
  public static Explainer templateOnly() {
    return new Explainer(null, ReferenceSource.NONE);
  }

  public Explanation explain(ExplanationTarget target) {
    return trace(target).explanation();
  }

  public Trace trace(ExplanationTarget target) {
    ExplanationFacts facts = FactsBuilder.build(target);
    String ddl = target.recommendation().candidate().ddl();
    Explanation template = TemplateExplainer.explain(facts, ddl);
    if (client == null) {
      return new Trace(template, List.of());
    }
    if (brokenReason != null) {
      return new Trace(template.withFallback(brokenReason, List.of()), List.of(), brokenKind);
    }

    List<Reference> refs = references.find(facts);
    List<String> docs = new ArrayList<>(template.docs());
    refs.stream().map(Reference::url).filter(u -> !docs.contains(u)).forEach(docs::add);
    template = template.withDocs(docs);
    List<Reference> promptRefs = referencesInPrompt ? refs : List.of();
    List<Attempt> attempts = new ArrayList<>();
    List<String> previous = List.of();
    for (int attempt = 0; attempt < 2; attempt++) {
      ChatResponse response;
      try {
        response = client.chat(PromptBuilder.build(facts, promptRefs, previous));
      } catch (LlmException e) {
        if (SAME_FOR_EVERY_TARGET.contains(e.kind())) {
          brokenReason = e.getMessage();
          brokenKind = e.kind();
        }
        return new Trace(template.withFallback(e.getMessage(), previous), attempts, e.kind());
      }
      OutputGuard.Result checked = OutputGuard.check(response.content(), facts);
      attempts.add(
          new Attempt(
              response.content(),
              checked.violations(),
              response.elapsed().toMillis(),
              response.promptTokens()));
      if (checked.passed()) {
        OutputGuard.Prose p = checked.prose();
        return new Trace(
            new Explanation(
                ddl,
                Source.LLM,
                p.summary(),
                p.whyItIsSlow(),
                p.whatTheIndexChanges(),
                client.settings().model(),
                null,
                List.of(),
                docs),
            attempts);
      }
      previous = checked.violations();
    }
    return new Trace(
        template.withFallback(
            client.settings().model() + "'s answer failed PgLens's checks twice", previous),
        attempts);
  }
}
