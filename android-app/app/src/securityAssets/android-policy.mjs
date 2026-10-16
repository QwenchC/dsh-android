/** Android tool approval at the registry gate, including nested PTC calls. */
export const name = 'android-tool-approval';
export const inject = ['tools', 'approval'];

// An allowlist keeps new tools behind approval until their effects are reviewed.
// run_code is deliberately gated: its program can use Node APIs before calling tools.
const READ_TOOLS = new Set(['read', 'glob', 'grep', 'web_search', 'web_fetch',
  'ask_user_question', 'request_user_input', 'todo_write', 'update_plan']);

/** Installs an effect-owned gate. Autonomous mode is captured at process startup. */
export function apply(ctx) {
  const autonomous = process.env.DSH_ANDROID_AUTONOMOUS === '1';
  ctx.on('tools/pre-execute', async (exec, next) => {
    if (exec.signal?.aborted) return { kind: 'cancel' };
    // Delegate even after deciding to ask, so another gate can still deny/cancel.
    const decision = await next();
    if (decision.kind !== 'allow' || autonomous || READ_TOOLS.has(exec.name)) return decision;
    return { kind: 'ask', reason: 'Android: approve this tool once. Commands and code run with DSH’s app permissions; shared-storage access can extend beyond this project.' };
  }, { prepend: true });
  console.log('[android-policy] active');
}
