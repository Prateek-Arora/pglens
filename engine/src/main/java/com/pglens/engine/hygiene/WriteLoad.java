package com.pglens.engine.hygiene;

import com.pglens.engine.model.TableActivity;
import com.pglens.engine.model.TableWriteLoad;
import java.util.Locale;

/**
 * Classifies a table's write load so a recommendation can say "weigh the maintenance cost"
 * (ADR-0038). <b>Write-dominant</b> = more tuples written ({@code ins+upd+del}) than read ({@code
 * seq_tup_read + idx_tup_fetch}) over the window — the same unit on both sides. It is a note, never
 * a reason to suppress a validated index.
 *
 * <p>Known bias, on purpose: a table that <em>needs</em> an index is seq-scanned a lot, which
 * inflates its tuples read, so the flag is conservative — it fires only when writes outweigh even
 * those inflated reads. Pure — no I/O.
 */
public final class WriteLoad {

  private WriteLoad() {}

  /**
   * Classifies {@code activity} over {@code window} (a phrase such as "since the database's stats
   * reset" or "over the last 6.0 h"). A null activity is {@code NO_ACTIVITY}.
   */
  public static TableWriteLoad assess(String table, TableActivity activity, String window) {
    if (activity == null || (activity.tuplesWritten() == 0 && activity.tuplesRead() == 0)) {
      return new TableWriteLoad(
          table,
          TableWriteLoad.Level.NO_ACTIVITY,
          activity,
          window,
          "No read/write activity recorded " + window + ".");
    }
    TableWriteLoad.Level level =
        activity.tuplesWritten() > activity.tuplesRead()
            ? TableWriteLoad.Level.WRITE_DOMINANT
            : TableWriteLoad.Level.READ_DOMINANT;
    String counts =
        String.format(
            Locale.US,
            "%,d rows written (%,d ins / %,d upd / %,d del) vs %,d rows read %s",
            activity.tuplesWritten(),
            activity.inserted(),
            activity.updated(),
            activity.deleted(),
            activity.tuplesRead(),
            window);
    String label =
        level == TableWriteLoad.Level.WRITE_DOMINANT
            ? "Write-dominant table: "
                + counts
                + ". Every new index adds work to each write — weigh that before creating it."
            : "Read-dominant table: " + counts + ".";
    return new TableWriteLoad(table, level, activity, window, label);
  }
}
