import "server-only";

import { createHighlighterCore, type HighlighterCore, type ThemeRegistration } from "shiki/core";
import { createJavaScriptRegexEngine } from "shiki/engine/javascript";
import { format } from "sql-formatter";

import { CopyButton } from "@/components/copy-button";
import { cn } from "@/lib/utils";

/**
 * SQL, formatted (PostgreSQL dialect) and highlighted on the server: the browser gets plain HTML
 * and no highlighting JavaScript. Shiki escapes the text it highlights, so query text taken from
 * the monitored database can't inject markup.
 */
let highlighter: Promise<HighlighterCore> | undefined;

/**
 * The dashboard's SQL colors as CSS variables (`--sql-*` in globals.css), so the same server-rendered
 * HTML follows the light and dark themes: violet keywords, teal functions, warm literals and `$n`
 * parameters, ink identifiers. Every color is ≥ 4.5:1 on the code background in both themes.
 */
const THEME: ThemeRegistration = {
  name: "pglens",
  type: "light",
  colors: {
    "editor.background": "var(--code-background)",
    "editor.foreground": "var(--sql-plain)",
  },
  tokenColors: [
    { settings: { foreground: "var(--sql-plain)" } },
    {
      scope: [
        "keyword",
        "storage",
        "keyword.other",
        "keyword.operator.logical",
        "keyword.operator.word",
      ],
      settings: { foreground: "var(--sql-keyword)" },
    },
    {
      scope: ["keyword.operator", "punctuation"],
      settings: { foreground: "var(--sql-punctuation)" },
    },
    {
      scope: ["support.function", "entity.name.function"],
      settings: { foreground: "var(--sql-function)" },
    },
    {
      scope: [
        "string",
        "constant.numeric",
        "constant.language",
        "variable.parameter",
        "variable.other.positional",
      ],
      settings: { foreground: "var(--sql-literal)" },
    },
    { scope: ["comment"], settings: { foreground: "var(--sql-comment)", fontStyle: "italic" } },
  ],
};

function sqlHighlighter() {
  highlighter ??= createHighlighterCore({
    langs: [import("shiki/langs/sql.mjs")],
    themes: [THEME],
    engine: createJavaScriptRegexEngine(),
  });
  return highlighter;
}

/** Pretty-printed SQL, or the text as captured when the formatter can't parse it. */
export function formatSql(sql: string): string {
  try {
    return format(sql, { language: "postgresql", keywordCase: "upper", tabWidth: 2 });
  } catch {
    return sql;
  }
}

export async function SqlBlock({
  sql,
  pretty = true,
  copy = false,
  label,
}: {
  sql: string;
  pretty?: boolean;
  copy?: boolean;
  label: string;
}) {
  const text = pretty ? formatSql(sql) : sql;
  const html = (await sqlHighlighter()).codeToHtml(text, { lang: "sql", theme: "pglens" });
  return (
    <figure className="bg-code relative rounded-md border">
      <figcaption className="sr-only">{label}</figcaption>
      {copy && (
        // Phones: above the code, so it never covers it; wider screens: in the corner.
        <div className="flex justify-end px-2 pt-2 sm:absolute sm:top-0 sm:right-0 sm:p-2">
          <CopyButton text={text} label={`Copy ${label}`} />
        </div>
      )}
      <div
        // Focusable, so keyboard users can scroll long lines.
        tabIndex={0}
        className={cn(
          "sql overflow-x-auto font-mono text-[13px] leading-relaxed [&_pre]:px-4 [&_pre]:py-3 [&_pre]:whitespace-pre",
          copy && "sm:[&_pre]:pr-24",
        )}
        // Shiki's output: escaped text in spans, generated here on the server.
        dangerouslySetInnerHTML={{ __html: html }}
      />
    </figure>
  );
}

/**
 * A query on one line, colored like the SQL blocks, for lists and headings: whitespace collapsed,
 * clamped by the caller. Rendered from Shiki's tokens as React text, so nothing is injected.
 */
export async function SqlInline({ sql, className }: { sql: string; className?: string }) {
  const flat = sql.replace(/\s+/g, " ").trim();
  const { tokens } = (await sqlHighlighter()).codeToTokens(flat, { lang: "sql", theme: "pglens" });
  return (
    <code className={cn("font-mono wrap-anywhere", className)}>
      {tokens.flat().map((t, i) => (
        <span key={i} style={t.color ? { color: t.color } : undefined}>
          {t.content}
        </span>
      ))}
    </code>
  );
}
