"use server";

import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";

import { api, attempt } from "@/lib/api/server";

/** Result of registering a database or rotating its token: the agent token is shown once. */
export type CredentialsState = { error?: string; name?: string; token?: string };

export async function registerDatabase(_: CredentialsState, form: FormData) {
  const name = String(form.get("name") ?? "").trim();
  if (!name) return { error: "Give the database a name." };
  const result = await attempt((await api()).POST("/api/v1/databases", { body: { name } }));
  if (!result.ok) return { error: result.message, name };
  revalidatePath("/");
  return { name: result.data.database.name, token: result.data.agentToken };
}

export async function rotateToken(_: CredentialsState, form: FormData) {
  const name = String(form.get("name") ?? "");
  const result = await attempt(
    (await api()).POST("/api/v1/databases/{name}/token", { params: { path: { name } } }),
  );
  if (!result.ok) return { error: result.message, name };
  return { name, token: result.data.agentToken };
}

export type DeleteState = { error?: string };

/** Deletes the database's history from PgLens's own store; never touches the database itself. */
export async function deleteDatabase(_: DeleteState, form: FormData): Promise<DeleteState> {
  const name = String(form.get("name") ?? "");
  const confirm = String(form.get("confirm") ?? "");
  if (confirm !== name) return { error: `Type ${name} to confirm.` };
  const result = await attempt(
    (await api()).DELETE("/api/v1/databases/{name}", {
      params: { path: { name }, query: { confirm } },
    }),
  );
  if (!result.ok) return { error: result.message };
  revalidatePath("/");
  redirect("/settings");
}
