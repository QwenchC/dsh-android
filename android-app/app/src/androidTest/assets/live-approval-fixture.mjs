/** Test-only workspace bootstrap and a secret-free projection of durable Session events. */
import { appendFileSync } from 'node:fs';
import { join } from 'node:path';
export const name = 'live-approval-fixture';
export const inject = ['workspaceRegistry'];
export async function apply(ctx) {
  await ctx.workspaceRegistry.create(process.cwd(), 'Live approval regression');
  ctx.on('session/event', (session, event) => {
    const { type, data, seq } = event;
    const row = { session: session.id, type, seq };
    if (type === 'approval/asked') Object.assign(row, { id: data.id, toolName: data.toolName, callId: data.callId });
    if (type === 'approval/decided') Object.assign(row, { id: data.id, outcome: data.outcome });
    if (type === 'request/context') Object.assign(row, { model: data.model, provider: data.provider });
    if (type === 'request/header') Object.assign(row, { model: data.header.config.model, provider: data.header.config.provider });
    // No prompts, model text, command arguments, URLs, or credentials enter this projection.
    appendFileSync(join(process.cwd(), 'live-audit.jsonl'), JSON.stringify(row) + '\n', { mode: 0o600 });
  });
}
