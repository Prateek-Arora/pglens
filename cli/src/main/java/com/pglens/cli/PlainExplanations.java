package com.pglens.cli;

import com.pglens.explain.Explainer;
import com.pglens.explain.Explanation;
import com.pglens.explain.ExplanationTarget;
import com.pglens.explain.ReferenceSource;
import com.pglens.explain.llm.EndpointPolicy;
import com.pglens.explain.llm.LlmSettings;
import com.pglens.explain.llm.OpenAiCompatibleClient;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Runs {@code --plain}: explains each target with the LLM (or the template), printing progress to
 * stderr — a small local model takes 10–60 s per answer on a CPU, so silence would look like a
 * hang. Never fails the command: every problem becomes a template explanation with its reason.
 */
final class PlainExplanations {

  private PlainExplanations() {}

  static List<Explanation> run(
      List<ExplanationTarget> targets, LlmOptions options, PrintStream progress) {
    if (targets.isEmpty()) {
      return List.of();
    }
    Explainer explainer;
    if (options.templateOnly()) {
      explainer = Explainer.templateOnly();
    } else {
      LlmSettings settings = options.settings();
      OpenAiCompatibleClient client = new OpenAiCompatibleClient(settings);
      EndpointPolicy.Decision decision = client.endpointDecision();
      if (decision.allowed() && decision.remote()) {
        progress.println("pglens: " + decision.reason() + ".");
      }
      progress.printf(
          "pglens: explaining %d index%s with %s at %s (the first answer also loads the model)…%n",
          targets.size(), targets.size() == 1 ? "" : "es", settings.model(), settings.baseUrl());
      explainer = new Explainer(client, ReferenceSource.NONE);
    }
    List<Explanation> out = new ArrayList<>();
    int i = 1;
    for (ExplanationTarget target : targets) {
      long start = System.nanoTime();
      Explanation e = explainer.explain(target);
      out.add(e);
      if (!options.templateOnly()) {
        progress.printf(
            Locale.US,
            "  [%d/%d] %s — %s, %.1f s%n",
            i,
            targets.size(),
            target.recommendation().candidate().suggestedName(),
            e.source() == Explanation.Source.LLM ? "written by the model" : "template",
            (System.nanoTime() - start) / 1e9);
      }
      i++;
    }
    return out;
  }
}
