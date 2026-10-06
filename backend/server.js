'use strict';

const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { createPolicy } = require('./access-policy');

const port = Number(process.env.NETSCOPE_BACKEND_PORT || 8787);
const host = process.env.NETSCOPE_BACKEND_HOST || '127.0.0.1';
const upstreamUrl = process.env.NETSCOPE_UPSTREAM_CHAT_URL;
const apiKey = process.env.NETSCOPE_API_KEY;
const logPath = process.env.NETSCOPE_TRACE_LOG || path.join(__dirname, 'logs', 'requests.jsonl');
const policy = createPolicy(process.env);
const maxRequestBytes = policy.maxRequestBytes;
const maxLoggedResponseBytes = 1024 * 1024;

function respond(response, status, body, traceId) {
  response.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'x-netscope-trace-id': traceId,
  });
  response.end(JSON.stringify(body));
}

function appendTrace(record) {
  fs.mkdirSync(path.dirname(logPath), { recursive: true });
  let serialized = JSON.stringify(record);
  for (const secret of [apiKey, process.env.NETSCOPE_CLIENT_TOKEN]) {
    if (secret) serialized = serialized.split(secret).join('[REDACTED_SECRET]');
  }
  fs.appendFileSync(logPath, serialized + '\n', { encoding: 'utf8', mode: 0o600 });
}

function readBody(request) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    request.on('data', chunk => {
      size += chunk.length;
      if (size > maxRequestBytes) { reject(new Error('request_too_large')); return; }
      chunks.push(chunk);
    });
    request.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    request.on('error', reject);
    request.on('aborted', () => reject(new Error('request_aborted')));
  });
}

const server = http.createServer(async (request, response) => {
  const traceId = crypto.randomUUID();
  if (request.method === 'GET' && request.url === '/health') {
    respond(response, 200, { status: 'ok', modelConfigured: Boolean(upstreamUrl && apiKey) }, traceId);
    return;
  }
  if (request.method !== 'POST' || request.url !== '/v1/chat/completions') {
    respond(response, 404, { error: 'not_found' }, traceId);
    return;
  }
  if (!policy.authorize(request.headers.authorization)) {
    request.resume();
    respond(response, 401, { error: 'unauthorized' }, traceId);
    return;
  }
  if (!upstreamUrl || !apiKey) {
    respond(response, 503, { error: 'model_unavailable', traceId }, traceId);
    return;
  }
  let endpoint;
  try {
    endpoint = new URL(upstreamUrl);
    const loopbackTest = process.env.NETSCOPE_ALLOW_INSECURE_LOOPBACK === '1' &&
      endpoint.protocol === 'http:' && ['127.0.0.1', 'localhost'].includes(endpoint.hostname);
    if (endpoint.protocol !== 'https:' && !loopbackTest) throw new Error('upstream must use HTTPS');
  } catch {
    respond(response, 503, { error: 'invalid_upstream', traceId }, traceId);
    return;
  }

  let rawBody;
  try { rawBody = await readBody(request); }
  catch (error) {
    if (!response.destroyed) respond(response, error.message === 'request_too_large' ? 413 : 400,
      { error: error.message === 'request_too_large' ? 'request_too_large' : 'invalid_request' }, traceId);
    return;
  }
  let parsed;
  try {
    parsed = JSON.parse(rawBody);
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed) ||
        typeof parsed.model !== 'string' || !Array.isArray(parsed.messages)) {
      throw new Error('invalid completion request');
    }
  } catch {
    respond(response, 400, { error: 'invalid_json', traceId }, traceId);
    return;
  }
  const validationError = policy.validate(parsed);
  if (validationError) { respond(response, 400, { error: validationError }, traceId); return; }
  let budgetError;
  try { budgetError = policy.reserve(); }
  catch { respond(response, 503, { error: 'quota_state_unavailable' }, traceId); return; }
  if (budgetError) { respond(response, 429, { error: budgetError }, traceId); return; }

  const startedAt = Date.now();
  const abort = new AbortController();
  response.once('close', () => { if (!response.writableFinished) abort.abort(); });
  const timeout = setTimeout(() => abort.abort(), 90_000);
  const trace = {
    traceId, startedAt: new Date(startedAt).toISOString(),
    model: parsed.model, stream: parsed.stream === true,
    request: parsed, upstreamStatus: null, response: null,
  };
  try {
    const upstream = await fetch(endpoint, {
      method: 'POST',
      headers: {
        authorization: `Bearer ${apiKey}`,
        'content-type': 'application/json',
        'x-netscope-trace-id': traceId,
      },
      body: JSON.stringify(parsed),
      signal: abort.signal,
    });
    trace.upstreamStatus = upstream.status;
    response.writeHead(upstream.status, {
      'content-type': upstream.headers.get('content-type') || 'application/json',
      'x-netscope-trace-id': traceId,
      'cache-control': 'no-store',
    });
    const captured = [];
    let capturedBytes = 0;
    for await (const chunk of upstream.body) {
      response.write(chunk);
      if (capturedBytes < maxLoggedResponseBytes) {
        const remainder = maxLoggedResponseBytes - capturedBytes;
        captured.push(Buffer.from(chunk).subarray(0, remainder));
        capturedBytes += Math.min(chunk.length, remainder);
      }
    }
    response.end();
    trace.response = Buffer.concat(captured).toString('utf8');
    trace.responseTruncated = capturedBytes >= maxLoggedResponseBytes;
  } catch (error) {
    trace.error = error?.name || 'upstream_error';
    if (!response.headersSent) respond(response, 502, { error: 'upstream_unavailable', traceId }, traceId);
    else response.end();
  } finally {
    policy.release();
    clearTimeout(timeout);
    trace.durationMs = Date.now() - startedAt;
    try { appendTrace(trace); }
    catch (error) { process.stderr.write(`NetScope trace log failed: ${error.message}\n`); }
  }
});

server.requestTimeout = 15_000;
server.headersTimeout = 10_000;

server.listen(port, host, () => {
  process.stdout.write(`NetScope model gateway listening on ${host}:${port}; configured=${Boolean(upstreamUrl && apiKey)}\n`);
});

module.exports = server;
