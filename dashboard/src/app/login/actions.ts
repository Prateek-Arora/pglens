"use server";

import { redirect } from "next/navigation";

import { anonymousApi, api, type Problem } from "@/lib/api/server";
import { safeNext } from "@/lib/paths";
import { endSession, startSession } from "@/lib/session";

export type LoginState = { error?: string; username?: string };

export async function login(_: LoginState, form: FormData): Promise<LoginState> {
  const username = String(form.get("username") ?? "").trim();
  const password = String(form.get("password") ?? "");
  if (!username || !password) return { error: "Enter your username and password.", username };

  let result;
  try {
    result = await anonymousApi().POST("/api/v1/auth/login", { body: { username, password } });
  } catch {
    return { error: "Can't reach the PgLens server. Is it running?", username };
  }
  const { data, error, response } = result;
  if (!data) {
    const problem = error as Problem | undefined;
    if (response.status === 401) return { error: "Wrong username or password.", username };
    return { error: problem?.detail ?? `The server answered ${response.status}.`, username };
  }
  await startSession(data.token);
  redirect(safeNext(form.get("next")));
}

export async function logout(): Promise<void> {
  try {
    await (await api()).POST("/api/v1/auth/logout");
  } catch {
    // The session ends here either way; the API expires it on its own.
  }
  await endSession();
  redirect("/login");
}
