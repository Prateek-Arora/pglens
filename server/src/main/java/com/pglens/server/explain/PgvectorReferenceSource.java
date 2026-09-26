package com.pglens.server.explain;

import com.pglens.explain.ExplanationFacts;
import com.pglens.explain.ReferenceSource;
import com.pglens.explain.llm.LlmException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Retrieves PostgreSQL-docs passages for an explanation, searching pgvector with {@link
 * ReferenceSource#signals}. Retrieval is optional: any failure returns no references.
 */
class PgvectorReferenceSource implements ReferenceSource {

  private static final Logger log = LoggerFactory.getLogger(PgvectorReferenceSource.class);
  static final int TOP_K = 3;

  private final KnowledgeStore store;

  PgvectorReferenceSource(KnowledgeStore store) {
    this.store = store;
  }

  @Override
  public List<Reference> find(ExplanationFacts facts) {
    try {
      return store.search(ReferenceSource.signals(facts), TOP_K, false);
    } catch (LlmException | RuntimeException e) {
      log.debug("docs retrieval skipped: {}", e.getMessage());
      return List.of();
    }
  }
}
