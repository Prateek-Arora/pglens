"use client";

import { useActionState } from "react";

import { AgentSnippet } from "@/components/agent-snippet";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  deleteDatabase,
  rotateToken,
  type CredentialsState,
  type DeleteState,
} from "../database-actions";

export function RotateToken({ name }: { name: string }) {
  const [state, action, pending] = useActionState<CredentialsState, FormData>(rotateToken, {});
  return (
    <form action={action}>
      <input type="hidden" name="name" value={name} />
      <Button type="submit" variant="outline" size="sm" disabled={pending}>
        {pending ? "Rotating…" : "Rotate agent token"}
      </Button>
      {state.error && (
        <p role="alert" className="text-destructive mt-2 text-sm">
          {state.error}
        </p>
      )}
      {state.token && (
        <div role="status" className="mt-3">
          <p className="mb-2 text-sm">The old token stopped working. Restart the agent with:</p>
          <AgentSnippet name={name} token={state.token} />
        </div>
      )}
    </form>
  );
}

export function DeleteDatabase({ name }: { name: string }) {
  const [state, action, pending] = useActionState<DeleteState, FormData>(deleteDatabase, {});
  const id = `confirm-${name}`;
  return (
    <form action={action} className="space-y-2" noValidate>
      <input type="hidden" name="name" value={name} />
      <p className="text-sm">
        Deletes {name}&apos;s history from PgLens&apos;s own store. The database itself is never
        touched. This can&apos;t be undone.
      </p>
      <div className="flex flex-wrap items-end gap-3">
        <div className="w-full space-y-1.5 sm:w-auto">
          <Label htmlFor={id}>
            Type <code>{name}</code> to confirm
          </Label>
          <Input id={id} name="confirm" autoComplete="off" className="w-full sm:w-56" />
        </div>
        <Button type="submit" variant="destructive" disabled={pending}>
          {pending ? "Deleting…" : `Delete ${name}`}
        </Button>
      </div>
      {state.error && (
        <p role="alert" className="text-destructive text-sm">
          {state.error}
        </p>
      )}
    </form>
  );
}
