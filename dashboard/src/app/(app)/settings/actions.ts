"use server";

import { revalidatePath } from "next/cache";

import type { FormState } from "@/components/action-form";
import { api, attempt } from "@/lib/api/server";

const text = (form: FormData, name: string) => String(form.get(name) ?? "");

export async function changePassword(_: FormState, form: FormData): Promise<FormState> {
  const currentPassword = text(form, "currentPassword");
  const newPassword = text(form, "newPassword");
  if (!currentPassword || !newPassword) return { error: "Fill in both passwords." };
  const r = await attempt(
    (await api()).PUT("/api/v1/me/password", { body: { currentPassword, newPassword } }),
  );
  return r.ok
    ? { done: "Password changed. Your other sessions were signed out." }
    : { error: r.message };
}

export async function createApiToken(_: FormState, form: FormData): Promise<FormState> {
  const name = text(form, "name").trim();
  if (!name) return { error: "Name the token after what will use it." };
  const r = await attempt((await api()).POST("/api/v1/api-tokens", { body: { name } }));
  if (!r.ok) return { error: r.message };
  revalidatePath("/settings");
  return { secret: { label: `API token “${r.data.info.name}”`, value: r.data.token } };
}

export async function revokeApiToken(_: FormState, form: FormData): Promise<FormState> {
  const id = Number(text(form, "id"));
  const r = await attempt(
    (await api()).DELETE("/api/v1/api-tokens/{id}", { params: { path: { id } } }),
  );
  if (!r.ok) return { error: r.message };
  revalidatePath("/settings");
  return { done: "Revoked." };
}

export async function createUser(_: FormState, form: FormData): Promise<FormState> {
  const username = text(form, "username").trim();
  const password = text(form, "password");
  const role = text(form, "role") === "ADMIN" ? "ADMIN" : "VIEWER";
  if (!username || !password) return { error: "A new user needs a username and a password." };
  const r = await attempt(
    (await api()).POST("/api/v1/users", { body: { username, password, role } }),
  );
  if (!r.ok) return { error: r.message };
  revalidatePath("/settings");
  return { done: `Added ${username} (${role.toLowerCase()}).` };
}

export async function resetPassword(_: FormState, form: FormData): Promise<FormState> {
  const username = text(form, "username");
  const password = text(form, "password");
  if (!password) return { error: "Enter the new password." };
  const r = await attempt(
    (await api()).PUT("/api/v1/users/{username}/password", {
      params: { path: { username } },
      body: { password },
    }),
  );
  return r.ok
    ? { done: `Password reset; ${username}'s sessions were signed out.` }
    : { error: r.message };
}

export async function deleteUser(_: FormState, form: FormData): Promise<FormState> {
  const username = text(form, "username");
  const r = await attempt(
    (await api()).DELETE("/api/v1/users/{username}", { params: { path: { username } } }),
  );
  if (!r.ok) return { error: r.message };
  revalidatePath("/settings");
  return { done: `Deleted ${username}.` };
}
