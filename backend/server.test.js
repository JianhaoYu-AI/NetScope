'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawn } = require('node:child_process');
const { createPolicy } = require('./access-policy');

function protectedConfig(directory) {
  return {
    NETSCOPE_DEPLOYMENT_MODE: 'protected', NETSCOPE_BACKEND_HOST: '127.0.0.1',
    NETSCOPE_TLS_TERMINATED: '1', NETSCOPE_CLIENT_TOKEN: 'synthetic-review-access-code-123456789',
    NETSCOPE_API_KEY: 'synthetic-provider-key', NETSCOPE_ALLOWED_MODELS: 'mock-model',
    NETSCOPE_UPSTREAM_CHAT_URL: 'https://example.com/v1/chat/completions',
    NETSCOPE_QUOTA_FILE: path.join(directory, 'quota.json'), NETSCOPE_MAX_TOTAL_REQUESTS: '10',
    NETSCOPE_TRACE_LOG: path.join(directory, 'requests.jsonl'),
  };
}
function tempDirectory() { return fs.mkdtempSync(path.join(os.tmpdir(), 'netscope-backend-')); }
function removeTemp(directory) {
  const prefix = path.resolve(os.tmpdir(), 'netscope-backend-').toLowerCase();
  if (!path.resolve(directory).toLowerCase().startsWith(prefix)) throw new Error('Unsafe test cleanup target');
  fs.rmSync(directory, { recursive: true, force: true });
}

async function freePort() {
  const server = http.createServer();
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const port = server.address().port;
  await new Promise((resolve) => server.close(resolve));
  return port;
}

async function startGateway(env) {
  const port = await freePort();
  const process = spawn(global.process.execPath, [path.join(__dirname, 'server.js')], {
    env: { ...global.process.env, ...env, NETSCOPE_BACKEND_PORT: String(port) },
    stdio: 'ignore',
  });
  const base = `http://127.0.0.1:${port}`;
  for (let attempt = 0; attempt < 40; attempt++) {
    try { await fetch(`${base}/health`); return { process, base }; }
    catch { await new Promise((resolve) => setTimeout(resolve, 100)); }
  }
  process.kill();
  throw new Error('gateway did not start');
}

async function stopGateway(child) {
  if (child.exitCode !== null) return;
  child.kill();
  await new Promise((resolve) => child.once('exit', resolve));
}

test('unconfigured gateway degrades explicitly and never calls upstream', async () => {
  const gateway = await startGateway({ NETSCOPE_API_KEY: '', NETSCOPE_UPSTREAM_CHAT_URL: '' });
  try {
    const health = await (await fetch(`${gateway.base}/health`)).json();
    assert.deepEqual(health, { status: 'ok', modelConfigured: false });
    const response = await fetch(`${gateway.base}/v1/chat/completions`, { method: 'POST' });
    assert.equal(response.status, 503);
    assert.equal((await response.json()).error, 'model_unavailable');
  } finally { await stopGateway(gateway.process); }
});

test('public bind fails closed until protected settings are complete', () => {
  assert.throws(() => createPolicy({ NETSCOPE_BACKEND_HOST: '0.0.0.0' }));
  const directory = tempDirectory();
  try {
    const env = protectedConfig(directory);
    for (const missing of ['NETSCOPE_CLIENT_TOKEN', 'NETSCOPE_ALLOWED_MODELS', 'NETSCOPE_QUOTA_FILE', 'NETSCOPE_MAX_TOTAL_REQUESTS', 'NETSCOPE_TLS_TERMINATED']) {
      assert.throws(() => createPolicy({ ...env, [missing]: '' }));
    }
    assert.throws(() => createPolicy({ ...env, NETSCOPE_UPSTREAM_CHAT_URL: 'http://example.com/' }));
    assert.throws(() => createPolicy({ ...env, NETSCOPE_API_KEY: env.NETSCOPE_CLIENT_TOKEN }));
  } finally { removeTemp(directory); }
});

test('total request reservation persists across policy restart and quota corruption fails closed', () => {
  const directory = tempDirectory();
  try {
    const env = { ...protectedConfig(directory), NETSCOPE_MAX_TOTAL_REQUESTS: '1' };
    const first = createPolicy(env);
    assert.equal(first.reserve(), null);
    first.release();
    assert.equal(createPolicy(env).reserve(), 'total_request_budget_exhausted');
    fs.writeFileSync(env.NETSCOPE_QUOTA_FILE, '{"reservedRequests":-1}');
    assert.throws(() => createPolicy(env));
  } finally { removeTemp(directory); }
});

test('concurrency and rate limits stop reservations before upstream', () => {
  const directory = tempDirectory();
  try {
    const policy = createPolicy({ ...protectedConfig(directory), NETSCOPE_MAX_TOTAL_REQUESTS: '100' });
    assert.equal(policy.reserve(), null);
    assert.equal(policy.reserve(), null);
    assert.equal(policy.reserve(), 'gateway_busy');
    policy.release(); policy.release();
    for (let i = 2; i < 20; i++) { assert.equal(policy.reserve(), null); policy.release(); }
    assert.equal(policy.reserve(), 'gateway_busy');
    assert.equal(JSON.parse(fs.readFileSync(path.join(directory, 'quota.json'))).reservedRequests, 20);
  } finally { removeTemp(directory); }
});

test('quota write failure refuses reservation instead of silently losing the cap', () => {
  const directory = tempDirectory();
  try {
    const env = protectedConfig(directory);
    const policy = createPolicy(env);
    fs.mkdirSync(env.NETSCOPE_QUOTA_FILE + '.pending');
    assert.throws(() => policy.reserve());
    assert.equal(JSON.parse(fs.readFileSync(env.NETSCOPE_QUOTA_FILE)).reservedRequests, 0);
  } finally { removeTemp(directory); }
});

test('protected HTTP gateway authenticates client separately, limits model/output/body, and retains quota after restart', async () => {
  let upstreamCalls = 0;
  const mock = http.createServer(async (request, response) => {
    upstreamCalls++;
    assert.equal(request.headers.authorization, 'Bearer synthetic-provider-key');
    const chunks = [];
    for await (const chunk of request) chunks.push(chunk);
    const body = JSON.parse(Buffer.concat(chunks));
    assert.equal(body.max_tokens, 1024);
    response.end(JSON.stringify({ choices: [], text: 'synthetic-review-access-code-123456789' }));
  });
  await new Promise(resolve => mock.listen(0, '127.0.0.1', resolve));
  const directory = tempDirectory();
  const env = { ...protectedConfig(directory), NETSCOPE_MAX_TOTAL_REQUESTS: '1',
    NETSCOPE_ALLOW_INSECURE_LOOPBACK: '1', NETSCOPE_UPSTREAM_CHAT_URL: `http://127.0.0.1:${mock.address().port}/v1/chat/completions` };
  let gateway;
  try {
    gateway = await startGateway(env);
    const send = (body, authenticated = true) => fetch(`${gateway.base}/v1/chat/completions`, {
      method: 'POST', headers: { 'content-type': 'application/json', ...(authenticated ? { authorization: `Bearer ${env.NETSCOPE_CLIENT_TOKEN}` } : {}) },
      body: JSON.stringify(body),
    });
    const body = { model: 'mock-model', messages: [{ role: 'user', content: 'synthetic engineering request' }] };
    assert.equal((await send(body, false)).status, 401);
    assert.equal((await send({ ...body, model: 'unauthorized-model' })).status, 400);
    assert.equal((await send({ ...body, n: 2 })).status, 400);
    assert.equal((await send({ ...body, max_tokens: 1025 })).status, 400);
    assert.equal((await send({ ...body, messages: [{ role: 'user', content: 'x'.repeat(70_000) }] })).status, 413);
    assert.equal(upstreamCalls, 0);
    const response = await send(body);
    assert.equal(response.status, 200);
    await response.text();
    assert.equal(upstreamCalls, 1);
    for (let i = 0; i < 20 && !fs.existsSync(env.NETSCOPE_TRACE_LOG); i++) {
      await new Promise(resolve => setTimeout(resolve, 50));
    }
    assert.ok(fs.existsSync(env.NETSCOPE_TRACE_LOG), 'Completed upstream call must be recorded before process termination');
    await stopGateway(gateway.process);
    gateway = await startGateway(env);
    assert.equal((await send(body)).status, 429);
    assert.equal(upstreamCalls, 1);
    const trace = fs.readFileSync(env.NETSCOPE_TRACE_LOG, 'utf8');
    assert.ok(!trace.includes(env.NETSCOPE_API_KEY));
    assert.ok(!trace.includes(env.NETSCOPE_CLIENT_TOKEN));
    assert.match(trace, /REDACTED_SECRET/);
  } finally {
    if (gateway) await stopGateway(gateway.process);
    await new Promise(resolve => mock.close(resolve));
    removeTemp(directory);
  }
});

test('same gateway proxies JSON and streaming responses with trace IDs, without logging key', async () => {
  const mock = http.createServer(async (request, response) => {
    assert.equal(request.headers.authorization, 'Bearer local-test-key');
    const chunks = [];
    for await (const chunk of request) chunks.push(chunk);
    const body = JSON.parse(Buffer.concat(chunks).toString('utf8'));
    if (body.stream) {
      response.writeHead(200, { 'content-type': 'text/event-stream' });
      response.end('data: {"choices":[]}\n\ndata: [DONE]\n\n');
    } else {
      response.writeHead(200, { 'content-type': 'application/json' });
      response.end(JSON.stringify({ model: body.model, choices: [] }));
    }
  });
  await new Promise((resolve) => mock.listen(0, '127.0.0.1', resolve));
  const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'netscope-backend-'));
  const tracePath = path.join(temp, 'requests.jsonl');
  const gateway = await startGateway({
    NETSCOPE_API_KEY: 'local-test-key',
    NETSCOPE_UPSTREAM_CHAT_URL: `http://127.0.0.1:${mock.address().port}/v1/chat/completions`,
    NETSCOPE_ALLOW_INSECURE_LOOPBACK: '1',
    NETSCOPE_TRACE_LOG: tracePath,
  });
  try {
    for (const stream of [false, true]) {
      const response = await fetch(`${gateway.base}/v1/chat/completions`, {
        method: 'POST', headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ model: 'mock-model', messages: [{ role: 'user', content: 'test' }], stream }),
      });
      assert.equal(response.status, 200);
      assert.ok(response.headers.get('x-netscope-trace-id'));
      const content = await response.text();
      assert.match(content, stream ? /data: \[DONE\]/ : /"mock-model"/);
    }
    for (let attempt = 0; attempt < 20 && (!fs.existsSync(tracePath) || fs.readFileSync(tracePath, 'utf8').trim().split('\n').length < 2); attempt++) {
      await new Promise((resolve) => setTimeout(resolve, 50));
    }
    const raw = fs.readFileSync(tracePath, 'utf8');
    const traces = raw.trim().split('\n').map(JSON.parse);
    assert.equal(traces.length, 2);
    assert.ok(traces.every((item) => item.upstreamStatus === 200 && item.traceId));
    assert.ok(!raw.includes('local-test-key'));
  } finally {
    await stopGateway(gateway.process);
    await new Promise((resolve) => mock.close(resolve));
    const expectedPrefix = path.resolve(os.tmpdir(), 'netscope-backend-').toLowerCase();
    if (!path.resolve(temp).toLowerCase().startsWith(expectedPrefix)) {
      throw new Error('refusing to remove a directory outside the NetScope test temp root');
    }
    fs.rmSync(temp, { recursive: true, force: true });
  }
});
