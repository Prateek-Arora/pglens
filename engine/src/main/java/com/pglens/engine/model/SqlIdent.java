package com.pglens.engine.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * SQL identifiers the way Postgres writes them (ADR-0049). The engine keeps raw names (as stored in
 * {@code pg_class.relname} / {@code pg_attribute.attname}, case preserved, unquoted) and renders
 * SQL with {@link #quote}, which follows {@code quote_ident}: a name is left bare only when it is
 * all lowercase ASCII letters, digits and underscores, doesn't start with a digit, and isn't a
 * keyword Postgres reserves in some position. A table's identity everywhere in PgLens is {@link
 * #table}: schema-qualified unless the schema is {@code public}.
 *
 * <p>Pure — no I/O.
 */
public final class SqlIdent {

  private SqlIdent() {}

  /**
   * Keywords {@code quote_ident} quotes: every category but unreserved, from PostgreSQL 18's {@code
   * pg_get_keywords()} (a superset of 16 and 17 — quoting one more word than an older server would
   * is still valid SQL).
   */
  private static final Set<String> QUOTED_KEYWORDS =
      Set.of(
          ("all analyse analyze and any array as asc asymmetric authorization between bigint binary"
                  + " bit boolean both case cast char character check coalesce collate collation"
                  + " column concurrently constraint create cross current_catalog current_date"
                  + " current_role current_schema current_time current_timestamp current_user dec"
                  + " decimal default deferrable desc distinct do else end except exists extract"
                  + " false fetch float for foreign freeze from full grant greatest group grouping"
                  + " having ilike in initially inner inout int integer intersect interval into is"
                  + " isnull join json json_array json_arrayagg json_exists json_object"
                  + " json_objectagg json_query json_scalar json_serialize json_table json_value"
                  + " lateral leading least left like limit localtime localtimestamp merge_action"
                  + " national natural nchar none normalize not notnull null nullif numeric offset"
                  + " on only or order out outer overlaps overlay placing position precision"
                  + " primary real references returning right row select session_user setof"
                  + " similar smallint some substring symmetric system_user table tablesample then"
                  + " time timestamp to trailing treat trim true union unique user using values"
                  + " varchar variadic verbose when where window with xmlattributes xmlconcat"
                  + " xmlelement xmlexists xmlforest xmlnamespaces xmlparse xmlpi xmlroot"
                  + " xmlserialize xmltable")
              .split(" "));

  /** Postgres's default schema: tables in it are written without a schema, as users write them. */
  public static final String DEFAULT_SCHEMA = "public";

  /** Postgres truncates identifiers longer than this many bytes (NAMEDATALEN - 1). */
  public static final int MAX_IDENTIFIER_BYTES = 63;

  /** {@code name} as SQL: bare when {@code quote_ident} would leave it bare, else double-quoted. */
  public static String quote(String name) {
    if (name == null) {
      return null;
    }
    return isBare(name) ? name : '"' + name.replace("\"", "\"\"") + '"';
  }

  private static boolean isBare(String name) {
    if (name.isEmpty() || QUOTED_KEYWORDS.contains(name)) {
      return false;
    }
    char first = name.charAt(0);
    if (!(first >= 'a' && first <= 'z') && first != '_') {
      return false;
    }
    for (int i = 1; i < name.length(); i++) {
      char c = name.charAt(i);
      if (!(c >= 'a' && c <= 'z') && !(c >= '0' && c <= '9') && c != '_') {
        return false;
      }
    }
    return true;
  }

  /**
   * A table's identity and its SQL name: {@code schema.table}, each part {@link #quote quoted} as
   * needed, with the schema left out when it is {@code public} or unknown ({@code null}).
   */
  public static String table(String schema, String name) {
    if (name == null) {
      return null;
    }
    return schema == null || schema.isEmpty() || DEFAULT_SCHEMA.equals(schema)
        ? quote(name)
        : quote(schema) + "." + quote(name);
  }

  private static final Pattern IDENTIFIER_TOKEN =
      Pattern.compile("\"(?:[^\"]|\"\")+\"|[A-Za-z_\\u0080-\\uffff][A-Za-z0-9_$\\u0080-\\uffff]*");

  /** True if {@code token} is exactly one identifier, bare or quoted (not an expression). */
  public static boolean isIdentifier(String token) {
    return token != null && IDENTIFIER_TOKEN.matcher(token.strip()).matches();
  }

  /**
   * The raw name of one SQL identifier token: {@code "User""s"} → {@code User"s}; a bare token is
   * folded to lower case, as Postgres folds it.
   */
  public static String unquote(String token) {
    if (token == null) {
      return null;
    }
    String t = token.strip();
    if (t.length() >= 2 && t.charAt(0) == '"' && t.charAt(t.length() - 1) == '"') {
      return t.substring(1, t.length() - 1).replace("\"\"", "\"");
    }
    return t.toLowerCase(Locale.ROOT);
  }

  /**
   * Splits a dotted SQL name ({@code app."User"}, {@code orders}) into its raw parts, honouring
   * quotes (a dot inside quotes is part of the name). Empty for text that isn't a dotted name.
   */
  public static List<String> parts(String sqlName) {
    List<String> out = new ArrayList<>();
    if (sqlName == null) {
      return out;
    }
    String s = sqlName.strip();
    int i = 0;
    while (i < s.length()) {
      int end;
      if (s.charAt(i) == '"') {
        end = i + 1;
        while (true) {
          end = s.indexOf('"', end);
          if (end < 0) {
            return List.of();
          }
          if (end + 1 < s.length() && s.charAt(end + 1) == '"') {
            end += 2;
            continue;
          }
          end++;
          break;
        }
      } else {
        end = s.indexOf('.', i);
        end = end < 0 ? s.length() : end;
      }
      String token = s.substring(i, end);
      if (token.isBlank()) {
        return List.of();
      }
      out.add(unquote(token));
      if (end < s.length() && s.charAt(end) != '.') {
        return List.of();
      }
      i = end + 1;
    }
    return out;
  }

  /** The raw table name (last part) of a {@link #table} identity. */
  public static String relationName(String tableIdentity) {
    List<String> p = parts(tableIdentity);
    return p.isEmpty() ? tableIdentity : p.get(p.size() - 1);
  }

  /** The raw schema of a {@link #table} identity ({@code public} when it has none). */
  public static String schemaName(String tableIdentity) {
    List<String> p = parts(tableIdentity);
    return p.size() >= 2 ? p.get(p.size() - 2) : DEFAULT_SCHEMA;
  }
}
