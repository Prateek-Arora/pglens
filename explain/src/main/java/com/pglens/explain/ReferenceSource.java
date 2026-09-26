package com.pglens.explain;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Extra reference text for a prompt beyond the cards — the server's pgvector search over the
 * PostgreSQL docs. Whether its results go into the prompt or only into "further reading" links is
 * decided by the pre-registered A/B ({@code docs/llm-eval.md} §1.6); the CLI uses {@link #NONE}.
 */
@FunctionalInterface
public interface ReferenceSource {

  /** A retrieved passage and where it came from. */
  record Reference(String title, String text, String url) {}

  List<Reference> find(ExplanationFacts facts);

  ReferenceSource NONE = facts -> List.of();

  /**
   * The retrieval query for these facts, built from <em>signals</em> — finding titles, card titles
   * and the index method — never the SQL or table names, so it matches concepts ("sort feeding a
   * Limit", "B-tree") rather than one schema's vocabulary.
   */
  static String signals(ExplanationFacts facts) {
    Set<String> parts = new LinkedHashSet<>();
    facts.findings().forEach(f -> parts.add(f.title()));
    KnowledgeCards.forFacts(facts).forEach(c -> parts.add(c.title()));
    parts.add(facts.index().method() + " index");
    return String.join(". ", parts);
  }
}
