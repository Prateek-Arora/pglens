import type { components } from "@/lib/api/schema";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { CopyButton } from "@/components/copy-button";

/** "Planner-validated ≠ safe", with the pre-filled `pglens confirm` command to measure on a copy. */
export function ConfirmBox({ confirm }: { confirm: components["schemas"]["Confirm"] }) {
  return (
    <Alert>
      <AlertTitle>Measure before you build it</AlertTitle>
      <AlertDescription className="min-w-0">
        <p>{confirm.caveat}</p>
        <div className="mt-2 flex w-full min-w-0 items-start gap-2">
          <pre
            // Keyboard users can scroll it when the command is wider than the screen.
            tabIndex={0}
            className="bg-muted text-foreground min-w-0 flex-1 overflow-x-auto rounded-md p-2 text-xs"
          >
            {confirm.command}
          </pre>
          <CopyButton text={confirm.command} label="Copy the confirm command" />
        </div>
      </AlertDescription>
    </Alert>
  );
}
