import type { Metadata } from "next";
import type { ReactNode } from "react";

import { ActionForm } from "@/components/action-form";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { NativeSelect } from "@/components/ui/native-select";
import { api, currentUser, read } from "@/lib/api/server";
import { formatUtc } from "@/lib/format";
import {
  changePassword,
  createApiToken,
  createUser,
  deleteUser,
  resetPassword,
  revokeApiToken,
} from "./actions";
import { DeleteDatabase, RotateToken } from "./database-admin";

export const metadata: Metadata = { title: "Settings" };

export default async function SettingsPage() {
  const me = await currentUser();
  const client = await api();
  const signedIn = me.via === "SESSION";
  const admin = me.role === "ADMIN";
  const [tokens, users, databases] = await Promise.all([
    signedIn ? read(client.GET("/api/v1/api-tokens")) : Promise.resolve([]),
    admin && signedIn ? read(client.GET("/api/v1/users")) : Promise.resolve([]),
    admin ? read(client.GET("/api/v1/databases")) : Promise.resolve([]),
  ]);

  return (
    <div className="max-w-5xl space-y-5">
      <h1 className="text-2xl font-semibold">Settings</h1>

      {!signedIn && (
        <p className="text-muted-foreground text-sm">
          Logins are turned off, so there are no accounts, passwords or API tokens to manage.
        </p>
      )}

      {signedIn && (
        <Section id="password" title="Your password">
          <ActionForm action={changePassword} submit="Change password">
            <Field id="current" label="Current password">
              <Input
                id="current"
                name="currentPassword"
                type="password"
                autoComplete="current-password"
              />
            </Field>
            <Field id="new" label="New password">
              <Input id="new" name="newPassword" type="password" autoComplete="new-password" />
            </Field>
          </ActionForm>
        </Section>
      )}

      {signedIn && (
        <Section
          id="tokens"
          title="API tokens"
          intro="Read-only tokens for scripts and CI: send one as “Authorization: Bearer …”. They can read everything you can, and change nothing."
        >
          {tokens.length > 0 && (
            <ul className="divide-y rounded-md border text-sm">
              {tokens.map((t) => (
                <li key={t.id} className="flex flex-wrap items-center gap-3 p-3">
                  <span className="font-medium">{t.name}</span>
                  <span className="text-muted-foreground">
                    created {formatUtc(t.createdAt)} ·{" "}
                    {t.lastUsedAt ? `last used ${formatUtc(t.lastUsedAt)}` : "never used"}
                  </span>
                  <ActionForm
                    action={revokeApiToken}
                    submit="Revoke"
                    variant="outline"
                    className="ml-auto"
                  >
                    <input type="hidden" name="id" value={t.id} />
                  </ActionForm>
                </li>
              ))}
            </ul>
          )}
          <ActionForm action={createApiToken} submit="Create token">
            <Field id="token-name" label="Name">
              <Input id="token-name" name="name" placeholder="e.g. ci-report" />
            </Field>
          </ActionForm>
        </Section>
      )}

      {admin && signedIn && (
        <Section
          id="users"
          title="Users"
          intro="Admins manage users and databases; viewers can read everything."
        >
          <ul className="divide-y rounded-md border text-sm">
            {users.map((u) => (
              <li key={u.username} className="space-y-2 p-3">
                <div className="flex flex-wrap items-center gap-3">
                  <span className="font-medium">{u.username}</span>
                  <span className="text-muted-foreground">
                    {u.role.toLowerCase()} · added {formatUtc(u.createdAt)}
                  </span>
                </div>
                {u.username !== me.username && (
                  <details>
                    <summary className="text-muted-foreground cursor-pointer">
                      Reset password or delete
                    </summary>
                    <div className="mt-2 space-y-3">
                      <ActionForm action={resetPassword} submit="Reset password" variant="outline">
                        <input type="hidden" name="username" value={u.username} />
                        <Field id={`pw-${u.username}`} label={`New password for ${u.username}`}>
                          <Input
                            id={`pw-${u.username}`}
                            name="password"
                            type="password"
                            autoComplete="new-password"
                          />
                        </Field>
                      </ActionForm>
                      <ActionForm
                        action={deleteUser}
                        submit={`Delete ${u.username}`}
                        variant="destructive"
                      >
                        <input type="hidden" name="username" value={u.username} />
                      </ActionForm>
                    </div>
                  </details>
                )}
              </li>
            ))}
          </ul>
          <ActionForm action={createUser} submit="Add user">
            <Field id="new-username" label="Username">
              <Input id="new-username" name="username" autoComplete="off" />
            </Field>
            <Field id="new-user-password" label="Password">
              <Input
                id="new-user-password"
                name="password"
                type="password"
                autoComplete="new-password"
              />
            </Field>
            <Field id="new-role" label="Role" className="sm:w-32">
              <NativeSelect id="new-role" name="role" defaultValue="VIEWER">
                <option value="VIEWER">Viewer</option>
                <option value="ADMIN">Admin</option>
              </NativeSelect>
            </Field>
          </ActionForm>
        </Section>
      )}

      {admin && (
        <Section id="databases" title="Databases">
          {databases.length === 0 ? (
            <p className="text-muted-foreground text-sm">None registered.</p>
          ) : (
            <ul className="divide-y rounded-md border">
              {databases.map((db) => (
                <li key={db.name} className="space-y-3 p-3">
                  <p className="font-medium">{db.name}</p>
                  <RotateToken name={db.name} />
                  <details>
                    <summary className="text-muted-foreground cursor-pointer text-sm">
                      Delete {db.name}
                    </summary>
                    <div className="mt-2">
                      <DeleteDatabase name={db.name} />
                    </div>
                  </details>
                </li>
              ))}
            </ul>
          )}
        </Section>
      )}
    </div>
  );
}

function Section({
  id,
  title,
  intro,
  children,
}: {
  id: string;
  title: string;
  intro?: string;
  children: ReactNode;
}) {
  return (
    <section
      aria-labelledby={id}
      className="sheet grid gap-4 p-5 md:grid-cols-[14rem_1fr] md:gap-8 md:p-6"
    >
      <div className="space-y-1">
        <h2 id={id} className="text-base font-semibold">
          {title}
        </h2>
        {intro && <p className="text-muted-foreground text-sm">{intro}</p>}
      </div>
      <div className="min-w-0 space-y-4">{children}</div>
    </section>
  );
}

function Field({
  id,
  label,
  className = "sm:w-52",
  children,
}: {
  id: string;
  label: string;
  className?: string;
  children: ReactNode;
}) {
  return (
    <div className={`w-full space-y-1.5 ${className}`}>
      <Label htmlFor={id}>{label}</Label>
      {children}
    </div>
  );
}
