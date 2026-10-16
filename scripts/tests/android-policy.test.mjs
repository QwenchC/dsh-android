import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { mkdtemp, writeFile, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

// Exercise the actual shipped ToolRuntime and approval service, with a Loader
// reading cordis.yml. Only tool effects and the human answer are fixtures.
const require = createRequire(new URL('../../dist/payload/package.json', import.meta.url));
const load = name => import(pathToFileURL(require.resolve(name)).href);
const { Context } = await load('@deepseek-ai/cordis');
const { default: Loader } = await load('@deepseek-ai/cordis-plugin-loader');
const { default: Include } = await load('@deepseek-ai/cordis-plugin-include');
const { Session, SessionId, SESSION_FORMAT_VERSION } = await load('@deepseek-ai/dsh-session');
const { ToolCallId } = await load('@deepseek-ai/dsh-llm');
const { defineTool } = await load('@deepseek-ai/dsh-tools');
const { PtcRuntime } = await load('@deepseek-ai/dsh-ptc-runtime');
const { setApprovalPolicy } = await load('@deepseek-ai/dsh-user-approval');
const policyUrl = new URL('../../android-app/app/src/securityAssets/android-policy.mjs', import.meta.url).href;
const policyTemplate = await readFile(new URL('../../android-app/app/src/securityAssets/android-policy.patch.yml', import.meta.url), 'utf8');
const { parse: parseYaml } = require('yaml');
const renderedPatch = parseYaml(policyTemplate.replace('__ANDROID_POLICY_MODULE_URI__', policyUrl));
const moduleName = renderedPatch.find(row => row.insert)?.insert[0].name;
assert.equal(moduleName, policyUrl, 'the production patch must insert a literal, resolvable module name');

async function fixture({ autonomous = false, answer = 'allowed-once', gate, ptc = false } = {}) {
  const folder = await mkdtemp(join(tmpdir(), 'dsh-android-policy-'));
  const configPath = join(folder, 'cordis.yml');
  const prior = process.env.DSH_ANDROID_AUTONOMOUS;
  process.env.DSH_ANDROID_AUTONOMOUS = autonomous ? '1' : '0';
  const ctx = new Context();
  try {
    await writeFile(configPath, [
      "- name: '@deepseek-ai/dsh-agent'",
      "- name: '@deepseek-ai/dsh-system-prompt'",
      "- name: '@deepseek-ai/dsh-tools'",
      '  config:', `    mode: ${ptc ? 'both' : 'native'}`,
      "- name: '@deepseek-ai/dsh-user-approval'",
      '  config:', '    policy: ask',
      `- name: ${JSON.stringify(moduleName)}`, '',
    ].join('\n'));
    ctx.baseUrl = pathToFileURL(folder).href + '/';
    await ctx.plugin(Loader);
    ctx.loader.builtins.include = Include;
    ctx.loader.internal = {
      version: 'v2',
      async import(specifier) {
        if (specifier === policyUrl) return import(policyUrl);
        return (await load(specifier)).default;
      },
    };
    await ctx.loader.create({ name: 'cordis:include', config: { path: pathToFileURL(configPath).href } });
    await ctx.loader.await();
    assert.ok(ctx.get('tools'), 'Loader must mount the real tools service');
    const scope = ctx.plugin(() => {});
    const id = SessionId('android-policy-fixture');
    const session = Session.create(id, [], { version: SESSION_FORMAT_VERSION, id, createdAt: 0, cwd: folder, isSeeded: false });
    session.append('turn/start', { turn: 1 });
    const owner = {
      id, options: {}, session, ctx: scope.ctx, status: 'idle',
      inbox: {}, send() {}, followup() {}, steer() {}, inject() {}, cancel() {},
      runMaintenance: task => task(new AbortController().signal), whenIdle: () => Promise.resolve(),
    };
    await ctx.agents.register(owner);
    const calls = [], requests = [];
    if (ptc) {
      class FixtureRuntime extends PtcRuntime {
        language = 'typescript'; isolation = 'fixture';
        resolve(request) { return { ...request, cwd: folder, timeoutMs: 1000 }; }
        async run(spec) {
          calls.push('run_code');
          await spec.bindings.find(item => item.global === 'tools').functions.bash({});
          return { logs: [] };
        }
      }
      await ctx.plugin(FixtureRuntime);
    }
    if (answer !== null) ctx.on('approval/request', async req => {
      requests.push(req.toolName);
      if (typeof answer === 'function') return answer(req);
      return answer;
    });
    if (gate) ctx.on('tools/pre-execute', async () => gate);
    const names = ['bash', 'terminal', 'run_code_fixture', 'write', 'plugin_custom', 'read', 'grep', 'glob'];
    for (const name of names) ctx.tools.register(defineTool({
      name, description: 'fixture effect', parameters: {},
      output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
      async execute() { calls.push(name); return 'executed'; },
    }));
    const execute = (name, signal = new AbortController().signal) => ctx.tools.execute({
      callId: ToolCallId('call-' + name), name, arguments: name === 'run_code' ? { code: 'await tools.bash({})', description: 'Test nested admission' } : {}, agent: owner, signal,
    });
    return { ctx, owner, calls, requests, execute, async close() {
      await ctx.fiber.dispose(); await rm(folder, { recursive: true, force: true });
    } };
  } catch (error) {
    await ctx.fiber.dispose(); await rm(folder, { recursive: true, force: true }); throw error;
  } finally {
    if (prior === undefined) delete process.env.DSH_ANDROID_AUTONOMOUS;
    else process.env.DSH_ANDROID_AUTONOMOUS = prior;
  }
}

test('Loader composition asks once per effectful call and records approval events', async () => {
  const f = await fixture();
  try {
    for (const name of ['bash', 'terminal', 'write', 'plugin_custom']) assert.equal((await f.execute(name)).isError, false);
    assert.deepEqual(f.calls, ['bash', 'terminal', 'write', 'plugin_custom']);
    assert.deepEqual(f.requests, f.calls);
    const events = f.owner.session.snapshotEvents().filter(event => event.type.startsWith('approval/'));
    assert.deepEqual(events.map(event => event.type), Array(4).fill(['approval/asked', 'approval/decided']).flat());
  } finally { await f.close(); }
});

test('read and search continue without confirmation', async () => {
  const f = await fixture({ answer: null });
  try { for (const name of ['read', 'grep', 'glob']) assert.equal((await f.execute(name)).isError, false); }
  finally { await f.close(); }
});

for (const answer of [null, 'rejected', 'unavailable']) test(`command fails closed when answer is ${answer}`, async () => {
  const f = await fixture({ answer });
  try { assert.equal((await f.execute('bash')).isError, true); assert.deepEqual(f.calls, []); }
  finally { await f.close(); }
});

test('autonomous opt-in skips added prompts but respects other gates', async () => {
  const f = await fixture({ autonomous: true, answer: null });
  try { assert.equal((await f.execute('bash')).isError, false); assert.deepEqual(f.requests, []); }
  finally { await f.close(); }
  const denied = await fixture({ autonomous: true, gate: { kind: 'deny', reason: 'another policy denied' } });
  try { assert.equal((await denied.execute('bash')).isError, true); assert.deepEqual(denied.calls, []); }
  finally { await denied.close(); }
});

test('stored never policy cannot be bypassed by the Android gate', async () => {
  const f = await fixture();
  try {
    setApprovalPolicy(f.owner.session, 'never');
    assert.equal((await f.execute('bash')).isError, true);
    assert.deepEqual(f.requests, []); assert.deepEqual(f.calls, []);
  } finally { await f.close(); }
});

test('cancelling a pending approval prevents tool effects after a late allow', async () => {
  let answer;
  const waiting = new Promise(resolve => { answer = resolve; });
  let asked;
  const entered = new Promise(resolve => { asked = resolve; });
  const f = await fixture({ answer: () => { asked(); return waiting; } });
  try {
    const cancellation = new AbortController(); const result = f.execute('bash', cancellation.signal);
    await entered; cancellation.abort(); answer('allowed-once');
    assert.equal((await result).isError, true); assert.deepEqual(f.calls, []);
  } finally { await f.close(); }
});

test('PTC entry cannot run a program after rejected approval', async () => {
  const f = await fixture({ answer: 'rejected', ptc: true });
  try {
    assert.equal((await f.execute('run_code')).isError, true);
    assert.deepEqual(f.requests, ['run_code']); assert.deepEqual(f.calls, []);
  } finally { await f.close(); }
});

test('PTC nested shell call needs its own approval', async () => {
  const f = await fixture({ answer: req => req.toolName === 'run_code' ? 'allowed-once' : 'rejected', ptc: true });
  try {
    await f.execute('run_code');
    assert.deepEqual(f.requests, ['run_code', 'bash']); assert.deepEqual(f.calls, ['run_code']);
  } finally { await f.close(); }
});
