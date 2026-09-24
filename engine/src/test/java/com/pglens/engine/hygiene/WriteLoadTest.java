package com.pglens.engine.hygiene;

import static org.assertj.core.api.Assertions.assertThat;

import com.pglens.engine.model.TableActivity;
import com.pglens.engine.model.TableWriteLoad;
import org.junit.jupiter.api.Test;

/** Pure tests for the tuple-level write-load note (ADR-0038). */
class WriteLoadTest {

  @Test
  void writeDominantWhenMoreTuplesAreWrittenThanRead() {
    TableWriteLoad w = WriteLoad.assess("events", new TableActivity(900, 300, 0, 1000), "over 6 h");
    assertThat(w.level()).isEqualTo(TableWriteLoad.Level.WRITE_DOMINANT);
    assertThat(w.label())
        .contains("Write-dominant")
        .contains("1,200 rows written")
        .contains("over 6 h");
  }

  @Test
  void readDominantOtherwiseIncludingATie() {
    assertThat(WriteLoad.assess("t", new TableActivity(10, 0, 0, 10_000), "w").level())
        .isEqualTo(TableWriteLoad.Level.READ_DOMINANT);
    assertThat(WriteLoad.assess("t", new TableActivity(5, 0, 5, 10), "w").level())
        .isEqualTo(TableWriteLoad.Level.READ_DOMINANT);
  }

  @Test
  void noActivityWhenCountersAreZeroOrMissing() {
    assertThat(WriteLoad.assess("t", new TableActivity(0, 0, 0, 0), "w").level())
        .isEqualTo(TableWriteLoad.Level.NO_ACTIVITY);
    assertThat(WriteLoad.assess("t", null, "w").level())
        .isEqualTo(TableWriteLoad.Level.NO_ACTIVITY);
  }
}
