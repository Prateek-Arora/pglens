package com.pglens.engine.confirm;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Splits SQL text into statements at top-level semicolons, respecting string literals ({@code '…'},
 * {@code E'…'} with backslash escapes), quoted identifiers, dollar-quoted bodies ({@code
 * $tag$…$tag$}), and line / block comments (block comments nest, as in PostgreSQL). Also reports a
 * statement's leading keyword, so callers can keep read-only statements. Pure — no I/O.
 */
public final class SqlSplitter {

  /** Leading keywords of statements that only read (a writing CTE is caught by READ ONLY). */
  private static final Set<String> READ_KEYWORDS = Set.of("SELECT", "WITH", "VALUES", "TABLE");

  private SqlSplitter() {}

  /** The statements in {@code sql}, trimmed, without the separating semicolons; blanks dropped. */
  public static List<String> split(String sql) {
    List<String> out = new ArrayList<>();
    int start = 0;
    int i = 0;
    int n = sql.length();
    while (i < n) {
      char c = sql.charAt(i);
      if (c == ';') {
        add(out, sql.substring(start, i));
        start = ++i;
      } else {
        i = skipToken(sql, i);
      }
    }
    add(out, sql.substring(start));
    return out;
  }

  /**
   * True if the statement starts (after comments, whitespace and opening parentheses) with a
   * keyword that only reads: {@code SELECT}, {@code WITH}, {@code VALUES} or {@code TABLE}.
   */
  public static boolean isRead(String statement) {
    String keyword = leadingKeyword(statement);
    return keyword != null && READ_KEYWORDS.contains(keyword);
  }

  /** The first keyword of the statement, upper-cased, or null if there is none. */
  static String leadingKeyword(String statement) {
    int i = 0;
    int n = statement.length();
    while (i < n) {
      char c = statement.charAt(i);
      if (Character.isWhitespace(c) || c == '(') {
        i++;
      } else if (startsWith(statement, i, "--") || startsWith(statement, i, "/*")) {
        i = skipToken(statement, i);
      } else {
        break;
      }
    }
    int end = i;
    while (end < n && Character.isLetter(statement.charAt(end))) {
      end++;
    }
    return end == i ? null : statement.substring(i, end).toUpperCase(Locale.ROOT);
  }

  // Returns the index just past the token at i (a literal, identifier, comment, dollar-quoted body,
  // or a single character).
  private static int skipToken(String s, int i) {
    char c = s.charAt(i);
    if (c == '\'') {
      boolean escapes =
          i > 0
              && (s.charAt(i - 1) == 'E' || s.charAt(i - 1) == 'e')
              && (i < 2 || !isIdentChar(s.charAt(i - 2)));
      return skipQuoted(s, i, '\'', escapes);
    }
    if (c == '"') {
      return skipQuoted(s, i, '"', false);
    }
    if (startsWith(s, i, "--")) {
      int nl = s.indexOf('\n', i);
      return nl < 0 ? s.length() : nl + 1;
    }
    if (startsWith(s, i, "/*")) {
      int depth = 0;
      int j = i;
      while (j < s.length()) {
        if (startsWith(s, j, "/*")) {
          depth++;
          j += 2;
        } else if (startsWith(s, j, "*/")) {
          depth--;
          j += 2;
          if (depth == 0) {
            return j;
          }
        } else {
          j++;
        }
      }
      return s.length();
    }
    if (c == '$' && (i == 0 || !isIdentChar(s.charAt(i - 1)))) {
      String tag = dollarTag(s, i);
      if (tag != null) {
        int close = s.indexOf(tag, i + tag.length());
        return close < 0 ? s.length() : close + tag.length();
      }
    }
    return i + 1;
  }

  private static int skipQuoted(String s, int i, char quote, boolean backslashEscapes) {
    int j = i + 1;
    while (j < s.length()) {
      char c = s.charAt(j);
      if (backslashEscapes && c == '\\') {
        j += 2;
      } else if (c == quote) {
        if (j + 1 < s.length() && s.charAt(j + 1) == quote) {
          j += 2; // doubled quote
        } else {
          return j + 1;
        }
      } else {
        j++;
      }
    }
    return s.length();
  }

  // "$tag$" or "$$" starting at i, or null ($1 is a parameter, not a tag).
  private static String dollarTag(String s, int i) {
    int j = i + 1;
    while (j < s.length() && isIdentChar(s.charAt(j)) && s.charAt(j) != '$') {
      j++;
    }
    if (j < s.length() && s.charAt(j) == '$') {
      String tag = s.substring(i, j + 1);
      return tag.length() > 2 && Character.isDigit(tag.charAt(1)) ? null : tag;
    }
    return null;
  }

  private static boolean isIdentChar(char c) {
    return Character.isLetterOrDigit(c) || c == '_' || c == '$';
  }

  private static boolean startsWith(String s, int i, String prefix) {
    return s.startsWith(prefix, i);
  }

  private static void add(List<String> out, String statement) {
    String trimmed = statement.strip();
    if (!trimmed.isEmpty() && !onlyComments(trimmed)) {
      out.add(trimmed);
    }
  }

  private static boolean onlyComments(String s) {
    int i = 0;
    while (i < s.length()) {
      if (Character.isWhitespace(s.charAt(i))) {
        i++;
      } else if (startsWith(s, i, "--") || startsWith(s, i, "/*")) {
        i = skipToken(s, i);
      } else {
        return false;
      }
    }
    return true;
  }
}
