package com.pglens.server.explain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pglens.explain.ReferenceSource.Reference;
import com.pglens.explain.llm.LlmException;
import com.pglens.explain.llm.OpenAiCompatibleClient;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The PostgreSQL-docs passages in pgvector (V8 {@code knowledge_chunks}): loads and embeds the
 * bundled corpus once per corpus version + embedding model, and searches it by cosine distance —
 * through the HNSW index, or with index scans disabled for the exact answer the eval compares
 * against (M7).
 *
 * <p>nomic-embed-text needs task prefixes: {@code search_document:} for stored passages and {@code
 * search_query:} for queries (its model card); without them retrieval quality drops.
 */
public class KnowledgeStore {

  /** Bump when {@code knowledge/pg16-docs.jsonl} changes, so servers re-embed it. */
  public static final String CORPUS_VERSION = "pg16-docs-2026-09-26";

  static final String CORPUS = "/knowledge/pg16-docs.jsonl";
  private static final int EMBED_BATCH = 16;

  /** One passage of the bundled corpus. */
  record Chunk(String id, String url, String title, String text) {}

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final OpenAiCompatibleClient client;

  public KnowledgeStore(JdbcTemplate jdbc, TransactionTemplate tx, OpenAiCompatibleClient client) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.client = client;
  }

  private String model() {
    return client.settings().embeddingModel();
  }

  /** True when every bundled passage is embedded for this corpus version and model. */
  public boolean loaded() {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM knowledge_chunks WHERE corpus_version = ? AND embedding_model = ?",
            Integer.class,
            CORPUS_VERSION,
            model());
    return n != null && n == corpus().size();
  }

  /** Embeds and stores the corpus if needed; returns the number of passages embedded. */
  public int ensureLoaded() throws LlmException {
    if (loaded()) {
      return 0;
    }
    List<Chunk> chunks = corpus();
    List<float[]> vectors = new ArrayList<>();
    for (int i = 0; i < chunks.size(); i += EMBED_BATCH) {
      List<String> batch = new ArrayList<>();
      for (Chunk c : chunks.subList(i, Math.min(i + EMBED_BATCH, chunks.size()))) {
        batch.add("search_document: " + c.title() + "\n" + c.text());
      }
      vectors.addAll(client.embed(batch));
    }
    tx.executeWithoutResult(
        status -> {
          jdbc.update(
              "DELETE FROM knowledge_chunks WHERE corpus_version = ? AND embedding_model = ?",
              CORPUS_VERSION,
              model());
          for (int i = 0; i < chunks.size(); i++) {
            Chunk c = chunks.get(i);
            jdbc.update(
                "INSERT INTO knowledge_chunks (corpus_version, embedding_model, chunk_id, url, "
                    + "title, content, embedding) VALUES (?, ?, ?, ?, ?, ?, ?::vector)",
                CORPUS_VERSION,
                model(),
                c.id(),
                c.url(),
                c.title(),
                c.text(),
                literal(vectors.get(i)));
          }
        });
    return chunks.size();
  }

  /** The {@code k} passages nearest to {@code query}; {@code exact} bypasses the HNSW index. */
  public List<Reference> search(String query, int k, boolean exact) throws LlmException {
    String vector = literal(client.embed(List.of("search_query: " + query)).get(0));
    return tx.execute(
        status -> {
          if (exact) {
            jdbc.execute("SET LOCAL enable_indexscan = off");
          }
          return jdbc.query(
              "SELECT title, content, url FROM knowledge_chunks "
                  + "WHERE corpus_version = ? AND embedding_model = ? "
                  + "ORDER BY embedding <=> ?::vector LIMIT ?",
              (rs, n) -> new Reference(rs.getString(1), rs.getString(2), rs.getString(3)),
              CORPUS_VERSION,
              model(),
              vector,
              k);
        });
  }

  /** Like {@link #search}, but the passages' ids — for the retrieval eval (M7). */
  public List<String> searchIds(String query, int k, boolean exact) throws LlmException {
    String vector = literal(client.embed(List.of("search_query: " + query)).get(0));
    return tx.execute(
        status -> {
          if (exact) {
            jdbc.execute("SET LOCAL enable_indexscan = off");
          }
          return jdbc.queryForList(
              "SELECT chunk_id FROM knowledge_chunks "
                  + "WHERE corpus_version = ? AND embedding_model = ? "
                  + "ORDER BY embedding <=> ?::vector LIMIT ?",
              String.class,
              CORPUS_VERSION,
              model(),
              vector,
              k);
        });
  }

  static List<Chunk> corpus() {
    ObjectMapper json = new ObjectMapper();
    List<Chunk> out = new ArrayList<>();
    try (InputStream in = KnowledgeStore.class.getResourceAsStream(CORPUS);
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      String line;
      while ((line = r.readLine()) != null) {
        if (line.isBlank()) {
          continue;
        }
        JsonNode row = json.readTree(line);
        out.add(
            new Chunk(
                row.get("id").asText(),
                row.get("url").asText(),
                row.get("title").asText(),
                row.get("text").asText()));
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read " + CORPUS, e);
    }
    return out;
  }

  /** pgvector's text form: {@code [0.1,0.2,…]}. */
  static String literal(float[] v) {
    StringBuilder sb = new StringBuilder(v.length * 10).append('[');
    for (int i = 0; i < v.length; i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append(Float.toString(v[i])); // round-trips exactly; pgvector parses "1.0E-5"
    }
    return sb.append(']').toString();
  }
}
