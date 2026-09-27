/** The PgLens mark: an instrument reticle with the action color at its center, plus the name. */
export function Wordmark() {
  return (
    <span className="inline-flex items-center gap-2 font-semibold tracking-tight">
      <svg aria-hidden viewBox="0 0 20 20" className="size-5" fill="none">
        <circle cx="10" cy="10" r="6.25" stroke="currentColor" strokeWidth="1.5" />
        <path
          d="M10 1.5v3M10 15.5v3M1.5 10h3M15.5 10h3"
          stroke="currentColor"
          strokeWidth="1.5"
          strokeLinecap="round"
        />
        <circle cx="10" cy="10" r="2" className="fill-primary" />
      </svg>
      <span>
        <span className="font-mono text-[0.95em] font-semibold">Pg</span>Lens
      </span>
    </span>
  );
}
