"use client";

import { useActionState } from "react";

import { AgentSnippet } from "@/components/agent-snippet";
import { Button } from "@/components/ui/button";
import { Field, FieldDescription, FieldError, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { registerDatabase, type CredentialsState } from "./database-actions";

export function RegisterForm() {
  const [state, action, pending] = useActionState<CredentialsState, FormData>(registerDatabase, {});
  if (state.token && state.name) {
    return (
      <div role="status">
        <h3 className="mb-2 font-medium">Registered {state.name}</h3>
        <AgentSnippet name={state.name} token={state.token} />
      </div>
    );
  }
  return (
    <form action={action} noValidate>
      <Field data-invalid={state.error ? true : undefined}>
        <FieldLabel htmlFor="db-name">Name</FieldLabel>
        {/* Input and button share a row (same height); the help text sits under both. */}
        <div className="flex flex-wrap gap-3">
          <Input
            id="db-name"
            name="name"
            defaultValue={state.name}
            placeholder="e.g. shop-prod"
            aria-invalid={state.error ? true : undefined}
            aria-describedby="db-name-help"
            className="w-full sm:w-72"
            required
          />
          <Button type="submit" disabled={pending}>
            {pending ? "Adding…" : "Add database"}
          </Button>
        </div>
        <FieldDescription id="db-name-help">
          What PgLens calls it; the agent uses it too.
        </FieldDescription>
        {state.error && <FieldError role="alert">{state.error}</FieldError>}
      </Field>
    </form>
  );
}
