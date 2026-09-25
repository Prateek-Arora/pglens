package com.pglens.engine.candidate;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Whether a recommended B-tree might fail to build because a key value is too wide (backlog B17,
 * ADR-0041). A B-tree entry holds at most about 2.7 kB ({@code BTMaxItemSize} on 8 kB pages), and
 * HypoPG never writes an entry, so a hypothetical index validates fine where the real {@code CREATE
 * INDEX} fails — on JOB/IMDB PgLens's #2 recommendation, {@code movie_info (info)}, did exactly
 * that (ADR-0040).
 *
 * <p>The check is catalog-only (no user data is read). A key column can only hold a value that wide
 * if its type is variable-length without a small declared limit. And a value that is still over
 * about 2 kB after compression is moved out of line into the table's TOAST relation, so a table
 * whose TOAST relation is empty can't hold one — unless the column's storage keeps values in line
 * ({@code STORAGE MAIN}). So: warn only for an unbounded key column on a table that has TOAST data
 * (or one stored {@code MAIN}), and say how to check. Pure — no I/O.
 */
public final class BtreeEntryWidth {

  /** The largest B-tree entry on 8 kB pages ({@code BTMaxItemSize}), in bytes. */
  public static final int MAX_ENTRY_BYTES = 2704;

  // Variable-length types with no declared bound (varchar/bpchar are handled by their typmod).
  private static final Set<String> UNBOUNDED_TYPES =
      Set.of("text", "bytea", "jsonb", "citext", "tsvector");

  private BtreeEntryWidth() {}

  /**
   * A key column's type as the catalog declares it: the base type name (through domains), its
   * {@code typcategory} ({@code 'A'} = array), the type modifier ({@code -1} = none) and the
   * column's {@code attstorage} ({@code 'm'} = MAIN keeps values in line).
   */
  public record KeyColumn(String name, String baseType, char category, int typmod, char storage) {}

  /**
   * The caution for a B-tree on {@code table (columns)}, or empty when every key column is bounded
   * or the table has no out-of-line (TOAST) data to hold a too-wide value.
   */
  public static Optional<String> caution(String table, List<KeyColumn> columns, long toastBytes) {
    List<KeyColumn> risky =
        columns.stream()
            .filter(BtreeEntryWidth::unbounded)
            .filter(c -> toastBytes > 0 || c.storage() == 'm')
            .toList();
    if (risky.isEmpty()) {
      return Optional.empty();
    }
    String names = String.join(", ", risky.stream().map(KeyColumn::name).toList());
    String why =
        toastBytes > 0
            ? "%s stores %s of values too long to keep in line (TOAST), so some values are over"
                    .formatted(table, bytes(toastBytes))
                + " ~2 kB"
            : "%s keeps long values in line (STORAGE MAIN)".formatted(table);
    String check =
        String.join(
            ", ", risky.stream().map(c -> "max(pg_column_size(%s))".formatted(c.name())).toList());
    return Optional.of(
        ("Build caution: %s. A B-tree entry holds at most ~2.7 kB, so CREATE INDEX fails if any"
                + " value of %s is longer — HypoPG can't check this. Check first: SELECT %s FROM"
                + " %s;")
            .formatted(why, names, check, table));
  }

  /** True if the column's declared type allows a value wider than a B-tree entry. */
  static boolean unbounded(KeyColumn c) {
    if (c.category() == 'A') {
      return true; // arrays have no declared bound
    }
    String type = c.baseType() == null ? "" : c.baseType().toLowerCase(Locale.ROOT);
    if (UNBOUNDED_TYPES.contains(type)) {
      return true;
    }
    if (type.equals("varchar") || type.equals("bpchar")) {
      // typmod = declared length + 4 (VARHDRSZ); a character takes at most 4 bytes in UTF-8.
      return c.typmod() < 0 || (long) (c.typmod() - 4) * 4 > MAX_ENTRY_BYTES;
    }
    return false;
  }

  private static String bytes(long b) {
    if (b >= 1L << 30) {
      return String.format(Locale.US, "%.1f GB", b / (double) (1L << 30));
    }
    if (b >= 1L << 20) {
      return String.format(Locale.US, "%.1f MB", b / (double) (1L << 20));
    }
    return String.format(Locale.US, "%d kB", Math.max(1, b / 1024));
  }
}
