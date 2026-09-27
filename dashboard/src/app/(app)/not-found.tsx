import Link from "next/link";

export default function NotFound() {
  return (
    <div className="space-y-2">
      <h1 className="text-2xl font-semibold">Not found</h1>
      <p className="text-muted-foreground">
        PgLens has no such database or query. It may have been deleted, or the query hasn&apos;t
        been seen yet.
      </p>
      <Link href="/" className="underline underline-offset-4">
        Back to the databases
      </Link>
    </div>
  );
}
