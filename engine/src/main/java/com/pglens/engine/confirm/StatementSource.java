package com.pglens.engine.confirm;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the user's real statements for {@code pglens confirm} (Phase 2.6, ADR-0042) from either a
 * {@code .sql} file or a PostgreSQL <b>stderr</b> log, and keeps the ones that only read.
 *
 * <p>A log is recognised by its message lines. Statements come from {@code statement:} lines
 * (simple protocol, values inline — a line may hold several statements) and {@code execute <name>:}
 * lines (extended protocol, {@code $N} placeholders) with their {@code DETAIL: parameters:} line,
 * whether logged by {@code log_statement} or with a {@code duration:} by {@code
 * log_min_duration_statement}. {@code parse}/{@code bind} lines and {@code execute fetch from}
 * continuations are skipped so a statement counts once. A message's continuation lines start with a
 * tab. The {@code log_line_prefix} is ignored. Verified on real PG16 output (the test fixtures).
 * Pure — no I/O.
 */
public final class StatementSource {

  /** What was read: the read-only statements to replay, and how many others were skipped. */
  public record Parsed(
      List<WorkloadStatement> statements, int skippedNotRead, int skippedNoValues) {}

  // "<prefix>LOG:  <message>" — PostgreSQL separates the severity from the message by two spaces.
  private static final Pattern MESSAGE =
      Pattern.compile(
          "^.*?\\b(LOG|DETAIL|ERROR|STATEMENT|HINT|CONTEXT|WARNING|NOTICE|FATAL|PANIC|INFO|DEBUG\\d?):  (.*)$");
  private static final Pattern DURATION = Pattern.compile("^duration: [0-9.]+ ms  ");
  private static final Pattern SIMPLE = Pattern.compile("^statement: ", Pattern.DOTALL);
  private static final Pattern EXECUTE = Pattern.compile("^execute ([^:]*): ", Pattern.DOTALL);
  private static final Pattern PARAMETER = Pattern.compile("\\$(\\d+) = ");
  // A log line: no quote before "LOG:" (so a SQL literal quoting a log line isn't one).
  private static final Pattern LOG_LINE =
      Pattern.compile("(?m)^[^'\\n]*\\bLOG:  (duration: [0-9.]+ ms  )?(statement|execute [^:]*): ");

  private StatementSource() {}

  /**
   * Parses {@code content} as a PostgreSQL log if it looks like one, else as a {@code .sql} file.
   */
  public static Parsed parse(String name, String content) {
    return looksLikeLog(content) ? fromLog(name, content) : fromSql(name, content);
  }

  static boolean looksLikeLog(String content) {
    return LOG_LINE.matcher(content).find();
  }

  /** Statements separated by {@code ;}; each is replayed as written (values inline). */
  public static Parsed fromSql(String name, String content) {
    List<WorkloadStatement> out = new ArrayList<>();
    int skipped = 0;
    int n = 0;
    for (String sql : SqlSplitter.split(content)) {
      n++;
      if (SqlSplitter.isRead(sql)) {
        out.add(new WorkloadStatement(sql, List.of(), name + " #" + n));
      } else {
        skipped++;
      }
    }
    return new Parsed(out, skipped, 0);
  }

  /** Statements from a PostgreSQL stderr log. */
  public static Parsed fromLog(String name, String content) {
    List<Message> messages = messages(content);
    List<WorkloadStatement> out = new ArrayList<>();
    int skipped = 0;
    int noValues = 0;
    for (int m = 0; m < messages.size(); m++) {
      Message msg = messages.get(m);
      if (!msg.severity().equals("LOG")) {
        continue;
      }
      String text = DURATION.matcher(msg.text()).replaceFirst("");
      String origin = name + ":" + msg.line();
      Matcher simple = SIMPLE.matcher(text);
      Matcher execute = EXECUTE.matcher(text);
      if (simple.find()) {
        for (String sql : SqlSplitter.split(text.substring(simple.end()))) {
          if (SqlSplitter.isRead(sql)) {
            out.add(new WorkloadStatement(sql, List.of(), origin));
          } else {
            skipped++;
          }
        }
      } else if (execute.find()) {
        if (execute.group(1).startsWith("fetch from ")) {
          continue; // a portal fetching more rows of a statement already counted
        }
        String sql = stripTrailingSemicolon(text.substring(execute.end()));
        if (!SqlSplitter.isRead(sql)) {
          skipped++;
          continue;
        }
        List<String> params = List.of();
        if (m + 1 < messages.size()
            && messages.get(m + 1).severity().equals("DETAIL")
            && messages.get(m + 1).text().startsWith("parameters: ")) {
          params = parameters(messages.get(m + 1).text().substring("parameters: ".length()));
        }
        if (PARAMETER_REF.matcher(sql).find() && params.isEmpty()) {
          noValues++; // placeholders but no logged values (e.g. log_parameter_max_length = 0)
          continue;
        }
        out.add(new WorkloadStatement(sql, params, origin));
      }
    }
    return new Parsed(out, skipped, noValues);
  }

  private static final Pattern PARAMETER_REF = Pattern.compile("\\$\\d+");

  /**
   * Parses {@code $1 = '2', $2 = NULL, $3 = 'it''s'} into SQL literals by position. PostgreSQL logs
   * each value already quoted as a literal ({@code ''} doubles a quote), or {@code NULL}.
   */
  static List<String> parameters(String detail) {
    List<String> values = new ArrayList<>();
    int i = 0;
    int n = detail.length();
    while (i < n) {
      Matcher p = PARAMETER.matcher(detail);
      if (!p.find(i) || p.start() != i) {
        throw new IllegalArgumentException("Unrecognised parameters line: " + detail);
      }
      int index = Integer.parseInt(p.group(1));
      int v = p.end();
      int end;
      if (detail.startsWith("NULL", v)) {
        end = v + 4;
      } else if (v < n && detail.charAt(v) == '\'') {
        end = v + 1;
        while (end < n) {
          if (detail.charAt(end) == '\'') {
            if (end + 1 < n && detail.charAt(end + 1) == '\'') {
              end += 2;
              continue;
            }
            end++;
            break;
          }
          end++;
        }
      } else {
        throw new IllegalArgumentException("Unrecognised parameter value: " + detail);
      }
      if (index != values.size() + 1) {
        throw new IllegalArgumentException("Parameters out of order: " + detail);
      }
      values.add(detail.substring(v, end));
      i = end;
      if (detail.startsWith(", ", i)) {
        i += 2;
      }
    }
    return values;
  }

  private record Message(String severity, String text, int line) {}

  // Groups the log into messages: a header line plus its tab-indented continuation lines.
  private static List<Message> messages(String content) {
    List<Message> out = new ArrayList<>();
    String[] lines = content.split("\r?\n", -1);
    String severity = null;
    StringBuilder text = null;
    int start = 0;
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i];
      if (line.startsWith("\t") && text != null) {
        text.append('\n').append(line.substring(1));
        continue;
      }
      if (text != null) {
        out.add(new Message(severity, text.toString(), start));
        text = null;
      }
      Matcher m = MESSAGE.matcher(line);
      if (m.matches()) {
        severity = m.group(1);
        text = new StringBuilder(m.group(2));
        start = i + 1;
      }
    }
    if (text != null) {
      out.add(new Message(severity, text.toString(), start));
    }
    return out;
  }

  private static String stripTrailingSemicolon(String sql) {
    String s = sql.strip();
    return s.endsWith(";") ? s.substring(0, s.length() - 1).strip() : s;
  }
}
