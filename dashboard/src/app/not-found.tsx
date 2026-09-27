import Link from "next/link";

export default function NotFound() {
  return (
    <main className="mx-auto max-w-xl space-y-2 p-6">
      <h1 className="text-2xl font-semibold">Page not found</h1>
      <Link href="/" className="underline underline-offset-4">
        Go to PgLens
      </Link>
    </main>
  );
}
