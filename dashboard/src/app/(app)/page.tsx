import type { Metadata } from "next";
import Link from "next/link";

import { AgentStatus } from "@/components/agent-status";
import { api, currentUser, read } from "@/lib/api/server";
import { formatUtc } from "@/lib/format";
import { routes } from "@/lib/routes";
import { RegisterForm } from "./register-form";

export const metadata: Metadata = { title: "Databases" };

export default async function DatabasesPage() {
  const [databases, me] = await Promise.all([
    read((await api()).GET("/api/v1/databases")),
    currentUser(),
  ]);
  const now = new Date();
  const admin = me.role === "ADMIN";
  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-semibold">Databases</h1>
      {databases.length === 0 && (
        <p className="text-muted-foreground">
          No databases yet.{" "}
          {admin
            ? "Add one below, then start its agent: PgLens reads its query statistics from there."
            : "Ask an admin to add one."}
        </p>
      )}
      {databases.length > 0 && (
        <ul className="sheet divide-y overflow-hidden">
          {databases.map((db) => (
            <li
              key={db.name}
              className="hover:bg-accent/60 relative flex flex-wrap items-center gap-x-6 gap-y-2 px-4 py-3 transition-colors"
            >
              <div className="min-w-48 flex-1">
                {/* The whole row is the link's target (the ::after covers it). */}
                <Link
                  href={routes.database(db.name)}
                  className="font-semibold after:absolute after:inset-0 hover:underline"
                >
                  {db.name}
                </Link>
                <p className="text-muted-foreground text-sm">
                  {db.host ? `${db.host} · ` : ""}added {formatUtc(db.createdAt)}
                </p>
              </div>
              <AgentStatus db={db} now={now} />
            </li>
          ))}
        </ul>
      )}
      {admin && (
        <section aria-labelledby="add-db" className="sheet space-y-3 p-5">
          <h2 id="add-db" className="text-base font-semibold">
            Add a database
          </h2>
          <RegisterForm />
        </section>
      )}
    </div>
  );
}
