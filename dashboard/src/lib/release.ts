/**
 * This release's version and the agent image it pairs with. Kept equal to package.json, the Gradle
 * build and the compose defaults by scripts/check_versions.sh (ADR-0050).
 */
export const PGLENS_VERSION = "0.1.0-rc";

export const AGENT_IMAGE = `ghcr.io/prateek-arora/pglens-agent:${PGLENS_VERSION}`;
