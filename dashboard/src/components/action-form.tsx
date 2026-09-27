"use client";

import { useActionState, type ReactNode } from "react";

import { CopyButton } from "@/components/copy-button";
import { Button } from "@/components/ui/button";

/** What a settings Server Action answers: an error, a confirmation, or a secret shown once. */
export type FormState = {
  error?: string;
  done?: string;
  secret?: { label: string; value: string };
};

/**
 * A form bound to a Server Action, with its outcome announced (errors as alerts, success as a
 * status). A returned secret — a new token — is shown once with a copy button.
 */
export function ActionForm({
  action,
  submit,
  pendingText,
  variant = "default",
  className,
  children,
}: {
  action: (state: FormState, form: FormData) => Promise<FormState>;
  submit: string;
  pendingText?: string;
  variant?: "default" | "outline" | "destructive";
  className?: string;
  children?: ReactNode;
}) {
  const [state, run, pending] = useActionState(action, {});
  return (
    <form action={run} className={className} noValidate>
      <div className="flex flex-wrap items-end gap-3">
        {children}
        <Button type="submit" variant={variant} disabled={pending}>
          {pending ? (pendingText ?? `${submit}…`) : submit}
        </Button>
      </div>
      {state.error && (
        <p role="alert" className="text-destructive mt-2 text-sm">
          {state.error}
        </p>
      )}
      {state.done && (
        <p role="status" className="mt-2 text-sm">
          {state.done}
        </p>
      )}
      {state.secret && (
        <div role="status" className="bg-muted mt-2 space-y-2 rounded-md p-3 text-sm">
          <p>
            <strong>{state.secret.label}</strong> — copy it now; it can&apos;t be shown again.
          </p>
          <div className="flex items-center gap-2">
            <code className="flex-1 break-all">{state.secret.value}</code>
            <CopyButton text={state.secret.value} label={state.secret.label} />
          </div>
        </div>
      )}
    </form>
  );
}
