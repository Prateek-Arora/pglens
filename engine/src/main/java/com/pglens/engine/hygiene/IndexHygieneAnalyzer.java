package com.pglens.engine.hygiene;

import com.pglens.engine.hygiene.IndexHygieneFinding.Kind;
import com.pglens.engine.model.IndexInfo;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The index-hygiene rule (Phase 2, B5 / ADR-0029): from the existing-index catalog plus each
 * index's scan history, flag indexes worth reviewing for removal — <b>duplicate</b> (an exact
 * structural twin), <b>redundant</b> (a leading prefix of a wider index), or <b>unused</b> (never
 * scanned over the observed window). Pure: no I/O, no DB — the scan history arrives pre-computed as
 * {@link IndexScanWindow}s, so the whole rule is fixture-testable.
 *
 * <p><b>Safety invariant (never violated):</b> a {@link IndexInfo#guarded() guarded} index — one
 * that backs a UNIQUE / PRIMARY KEY / EXCLUSION constraint or covers a FOREIGN KEY — is never
 * flagged. Dropping it could break correctness or FK enforcement, so it is skipped outright; it may
 * still be the <em>survivor</em> a non-guarded duplicate points at (the safe direction to keep).
 *
 * <p>At most one finding per index, in priority order {@code DUPLICATE > REDUNDANT > UNUSED}: a
 * structural twin is a stronger, always-safe removal than "unused over a window", which depends on
 * how long we have watched.
 */
public final class IndexHygieneAnalyzer {

  /**
   * @param indexes every existing index across the monitored db's tables
   * @param scanWindows scan-growth window per index name (lowercased); an index absent from the map
   *     has too little history (or a reset) to judge, so it is never called unused
   */
  public List<IndexHygieneFinding> analyze(
      Collection<IndexInfo> indexes, Map<String, IndexScanWindow> scanWindows) {
    Map<String, List<IndexInfo>> byTable = new LinkedHashMap<>();
    for (IndexInfo ix : indexes) {
      byTable.computeIfAbsent(lower(ix.table()), k -> new ArrayList<>()).add(ix);
    }

    List<IndexHygieneFinding> findings = new ArrayList<>();
    for (List<IndexInfo> siblings : byTable.values()) {
      for (IndexInfo ix : siblings) {
        // The B5 invariant: a constraint/FK-backing index is never a drop candidate.
        if (ix.guarded()) {
          continue;
        }
        IndexHygieneFinding finding = classify(ix, siblings, scanWindows);
        if (finding != null) {
          findings.add(finding);
        }
      }
    }
    findings.sort(
        Comparator.comparing(IndexHygieneFinding::table)
            .thenComparing(IndexHygieneFinding::indexName));
    return findings;
  }

  private IndexHygieneFinding classify(
      IndexInfo ix, List<IndexInfo> siblings, Map<String, IndexScanWindow> scanWindows) {
    IndexInfo duplicateOf = duplicateSurvivorFor(ix, siblings);
    if (duplicateOf != null) {
      return new IndexHygieneFinding(
          ix.table(),
          ix.name(),
          ix.definition(),
          Kind.DUPLICATE,
          duplicateOf.name(),
          "Duplicate of "
              + duplicateOf.name()
              + " — identical columns "
              + ix.columns()
              + " and access method ("
              + methodOf(ix)
              + "); one of the two can be dropped after review.");
    }

    IndexInfo widerCover = redundantCoverFor(ix, siblings);
    if (widerCover != null) {
      return new IndexHygieneFinding(
          ix.table(),
          ix.name(),
          ix.definition(),
          Kind.REDUNDANT,
          widerCover.name(),
          "Redundant — its key columns "
              + ix.columns()
              + " are a leading prefix of "
              + widerCover.name()
              + " "
              + widerCover.columns()
              + ", which already serves those lookups.");
    }

    IndexScanWindow window = scanWindows.get(lower(ix.name()));
    if (window != null && window.unused()) {
      return new IndexHygieneFinding(
          ix.table(),
          ix.name(),
          ix.definition(),
          Kind.UNUSED,
          null,
          "No index scans recorded across "
              + window.snapshots()
              + " snapshots ("
              + window.from()
              + " .. "
              + window.to()
              + ") — likely unused.");
    }
    return null;
  }

  /**
   * If {@code ix} has one or more exact structural twins (same method, ordered columns, and partial
   * predicate), returns the deterministic survivor to keep — preferring a guarded twin (so a plain
   * index yields to the constraint index), else the lowest index name. Returns null when {@code ix}
   * itself is the survivor, or has no twin.
   */
  private static IndexInfo duplicateSurvivorFor(IndexInfo ix, List<IndexInfo> siblings) {
    List<IndexInfo> group = new ArrayList<>();
    for (IndexInfo other : siblings) {
      if (sameSignature(ix, other)) {
        group.add(other);
      }
    }
    if (group.size() < 2) {
      return null;
    }
    IndexInfo survivor =
        group.stream()
            .min(
                Comparator.comparing(IndexInfo::guarded)
                    .reversed() // guarded (true) sorts first — keep the constraint index
                    .thenComparing(IndexInfo::name))
            .orElseThrow();
    return survivor.name().equals(ix.name()) ? null : survivor;
  }

  /**
   * Returns a wider index whose key columns {@code ix} is a strict leading prefix of (same method +
   * predicate), or null. That wider index already serves {@code ix}'s lookups, so {@code ix} is
   * redundant.
   */
  private static IndexInfo redundantCoverFor(IndexInfo ix, List<IndexInfo> siblings) {
    return siblings.stream()
        .filter(other -> !other.name().equals(ix.name()))
        .filter(other -> sameMethod(ix, other) && samePredicate(ix, other))
        .filter(other -> isStrictPrefix(ix.columns(), other.columns()))
        .min(Comparator.comparing(IndexInfo::name))
        .orElse(null);
  }

  private static boolean sameSignature(IndexInfo a, IndexInfo b) {
    return sameMethod(a, b) && samePredicate(a, b) && a.columns().equals(b.columns());
  }

  private static boolean sameMethod(IndexInfo a, IndexInfo b) {
    return methodOf(a).equals(methodOf(b));
  }

  private static boolean samePredicate(IndexInfo a, IndexInfo b) {
    return predicateKey(a).equals(predicateKey(b));
  }

  private static boolean isStrictPrefix(List<String> shorter, List<String> longer) {
    return shorter.size() < longer.size() && longer.subList(0, shorter.size()).equals(shorter);
  }

  private static String methodOf(IndexInfo ix) {
    return ix.method() == null ? "btree" : ix.method().toLowerCase(Locale.ROOT);
  }

  private static String predicateKey(IndexInfo ix) {
    return ix.predicate() == null ? "" : ix.predicate().trim();
  }

  private static String lower(String s) {
    return s == null ? null : s.toLowerCase(Locale.ROOT);
  }
}
