import { CopyButton } from "@/components/copy-button";
import { AGENT_IMAGE } from "@/lib/release";

const README =
  "https://github.com/Prateek-Arora/pglens/blob/main/docs/operations.md#2-prepare-each-database";

/**
 * How to start an agent for a newly registered database. The token appears here once; PgLens only
 * keeps its hash.
 */
export function AgentSnippet({ name, token }: { name: string; token: string }) {
  const env = [
    `PGLENS_DB_NAME=${name}`,
    `PGLENS_AGENT_TOKEN=${token}`,
    "PGLENS_MONITORED_DB_URL=postgresql://<read-only role>:<password>@<db host>:5432/<database>",
    "PGLENS_SERVER_HOST=<this PgLens server's host>",
    "PGLENS_SERVER_PORT=9090",
    "PGLENS_SERVER_CA_CERT=/certs/ca.pem",
  ].join("\n");
  const ca =
    "docker compose -f deploy/compose/docker-compose.yml run --rm --no-deps --entrypoint cat certs /certs/ca/ca.pem > ca.pem";
  const run = `docker run -d --name pglens-agent-${name} --restart unless-stopped --add-host=host.docker.internal:host-gateway --env-file agent.env -v "$PWD/ca.pem:/certs/ca.pem:ro" ${AGENT_IMAGE}`;
  return (
    <div className="space-y-3 text-sm">
      <p>
        <strong>Copy the token now</strong> — PgLens keeps only its hash, so it can&apos;t be shown
        again. (If you lose it, rotate it in Settings.)
      </p>
      <p>
        Save this as <code>agent.env</code> on a machine that can reach the database, and fill in
        the <code>&lt;…&gt;</code> parts. The database needs <code>pg_stat_statements</code> (and{" "}
        <code>hypopg</code> to check indexes), and the role needs <code>pg_read_all_stats</code>,{" "}
        <code>USAGE</code> on each schema and <code>SELECT</code> on its tables —{" "}
        <a href={README} className="underline underline-offset-4" target="_blank" rel="noreferrer">
          the grants are in the operations guide
        </a>
        . On the same machine as PgLens, use <code>host.docker.internal</code> for a host.
      </p>
      <div className="flex items-start gap-2">
        <pre className="bg-muted flex-1 overflow-x-auto rounded-md p-2 text-xs">{env}</pre>
        <CopyButton text={env} label="Copy the agent settings" />
      </div>
      <p>
        Get the server&apos;s CA certificate from the PgLens host (the server&apos;s certificate
        must name the host you put in <code>PGLENS_SERVER_HOST</code>: <code>server</code>,{" "}
        <code>localhost</code> and <code>host.docker.internal</code> are included — add others with{" "}
        <code>PGLENS_TLS_EXTRA_SANS</code>):
      </p>
      <div className="flex items-start gap-2">
        <pre className="bg-muted flex-1 overflow-x-auto rounded-md p-2 text-xs">{ca}</pre>
        <CopyButton text={ca} label="Copy the CA command" />
      </div>
      <p>
        Then start the agent. For the bundled demo, put the token in{" "}
        <code>deploy/compose/.env</code> as <code>PGLENS_AGENT_TOKEN</code> and run{" "}
        <code>make up</code> instead.
      </p>
      <div className="flex items-start gap-2">
        <pre className="bg-muted flex-1 overflow-x-auto rounded-md p-2 text-xs">{run}</pre>
        <CopyButton text={run} label="Copy the docker run command" />
      </div>
    </div>
  );
}
