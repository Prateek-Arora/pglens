package com.pglens.explain;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The reference cards an explanation is grounded in — short texts we wrote, one per rule and one
 * per validation status, each linking the PostgreSQL docs it paraphrases. Chosen by id, never by
 * search, so the same facts always get the same cards (ADR-0043). Card files: {@code
 * knowledge/cards/<id>.md} — a title line, the body, then {@code Docs: <url>} lines.
 */
public final class KnowledgeCards {

  /** One card: its id, title, body text and docs links. */
  public record Card(String id, String title, String body, List<String> docs) {
    public Card {
      docs = List.copyOf(docs);
    }
  }

  private KnowledgeCards() {}

  /** The cards for these facts: one per addressed rule (in order), then the status card. */
  public static List<Card> forFacts(ExplanationFacts facts) {
    Set<String> ids = new LinkedHashSet<>();
    facts.findings().forEach(f -> ids.add(f.rule()));
    ids.add(facts.plannerValidated() ? "estimate" : "not-validated");
    List<Card> cards = new ArrayList<>();
    for (String id : ids) {
      load(id).ifPresent(cards::add);
    }
    return cards;
  }

  /** A card by id, or empty when there is none (a rule added to the engine before its card). */
  public static Optional<Card> load(String id) {
    String path = "/knowledge/cards/" + id + ".md";
    try (InputStream in = KnowledgeCards.class.getResourceAsStream(path)) {
      if (in == null) {
        return Optional.empty();
      }
      return Optional.of(parse(id, new String(in.readAllBytes(), StandardCharsets.UTF_8)));
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read " + path, e);
    }
  }

  static Card parse(String id, String text) {
    List<String> lines = text.strip().lines().toList();
    List<String> body = new ArrayList<>();
    List<String> docs = new ArrayList<>();
    for (String line : lines.subList(1, lines.size())) {
      if (line.startsWith("Docs:")) {
        docs.add(line.substring("Docs:".length()).strip());
      } else {
        body.add(line.strip());
      }
    }
    return new Card(id, lines.get(0).strip(), String.join(" ", body), docs);
  }
}
