import * as React from "react";

import { cn } from "@/lib/utils";

/** A native `<select>` sized and styled like `<Input>`, so a form row lines up. */
function NativeSelect({ className, ...props }: React.ComponentProps<"select">) {
  return (
    <select
      data-slot="native-select"
      className={cn(
        "border-input bg-background focus-visible:border-ring focus-visible:ring-ring/50 h-9 w-full min-w-0 rounded-md border px-3 text-base outline-none focus-visible:ring-3 md:text-sm",
        className,
      )}
      {...props}
    />
  );
}

export { NativeSelect };
