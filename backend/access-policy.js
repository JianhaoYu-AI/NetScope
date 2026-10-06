'use strict';
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

function createPolicy(env) {
  const protectedMode = env.NETSCOPE_DEPLOYMENT_MODE === 'protected';
  const host = env.NETSCOPE_BACKEND_HOST || '127.0.0.1';
  if (env.NETSCOPE_DEPLOYMENT_MODE && !['local', 'protected'].includes(env.NETSCOPE_DEPLOYMENT_MODE)) {
    throw new Error('Invalid deployment mode');
  }
  if (!protectedMode && !['127.0.0.1', 'localhost', '::1'].includes(host)) {
    throw new Error('Non-loopback bind requires protected deployment mode');
  }
  if (!protectedMode) return { protectedMode, authorize: () => true, validate: () => null,
    reserve: () => null, release: () => {}, maxRequestBytes: 256 * 1024 };
  const token = env.NETSCOPE_CLIENT_TOKEN || '';
  const models = (env.NETSCOPE_ALLOWED_MODELS || '').split(',').map(x => x.trim()).filter(Boolean);
  const stateFile = env.NETSCOPE_QUOTA_FILE;
  const maxTotal = Number(env.NETSCOPE_MAX_TOTAL_REQUESTS);
  if (!/^[A-Za-z0-9_-]{32,128}$/.test(token) || !models.length || !stateFile || !path.isAbsolute(stateFile) ||
      !Number.isSafeInteger(maxTotal) || maxTotal < 1 || !env.NETSCOPE_API_KEY ||
      env.NETSCOPE_TLS_TERMINATED !== '1' || token === env.NETSCOPE_API_KEY) {
    throw new Error('Protected deployment requires token, models, absolute quota file, positive total cap, provider key and HTTPS ingress');
  }
  const endpoint = new URL(env.NETSCOPE_UPSTREAM_CHAT_URL);
  if (endpoint.username || endpoint.password || endpoint.hash || endpoint.search) {
    throw new Error('Upstream URL must not contain credentials, query or fragment');
  }
  if (endpoint.protocol !== 'https:' && !(env.NETSCOPE_ALLOW_INSECURE_LOOPBACK === '1' &&
      endpoint.protocol === 'http:' && ['127.0.0.1', 'localhost'].includes(endpoint.hostname))) {
    throw new Error('Protected upstream must use HTTPS');
  }
  fs.mkdirSync(path.dirname(stateFile), { recursive: true });
  let total = 0;
  if (fs.existsSync(stateFile)) {
    total = JSON.parse(fs.readFileSync(stateFile, 'utf8')).reservedRequests;
    if (!Number.isSafeInteger(total) || total < 0) throw new Error('Invalid persisted quota state');
  }
  // One process/replica and a persistent private volume are required for this counter.
  const persist = value => {
    const temporary = stateFile + '.pending';
    fs.writeFileSync(temporary, JSON.stringify({ reservedRequests: value }), { mode: 0o600 });
    fs.renameSync(temporary, stateFile);
  };
  if (!fs.existsSync(stateFile)) persist(total);
  const expected = crypto.createHash('sha256').update(`Bearer ${token}`).digest();
  let inFlight = 0;
  let recent = [];
  return {
    protectedMode, maxRequestBytes: 64 * 1024,
    authorize: header => crypto.timingSafeEqual(expected,
      crypto.createHash('sha256').update(typeof header === 'string' ? header : '').digest()),
    validate(body) {
      if (!models.includes(body.model)) return 'model_not_allowed';
      if (body.n !== undefined && body.n !== 1) return 'multiple_outputs_not_allowed';
      if (body.max_tokens !== undefined && (!Number.isSafeInteger(body.max_tokens) || body.max_tokens < 1 || body.max_tokens > 1024)) {
        return 'output_budget_exceeded';
      }
      if (body.max_completion_tokens !== undefined) return 'unsupported_output_budget';
      body.max_tokens = body.max_tokens === undefined ? 1024 : body.max_tokens;
      return null;
    },
    reserve() {
      const now = Date.now();
      recent = recent.filter(time => now - time < 60_000);
      if (total >= maxTotal) return 'total_request_budget_exhausted';
      if (inFlight >= 2 || recent.length >= 20) return 'gateway_busy';
      persist(total + 1); // Reserve before any upstream call; failures are not refunded.
      total++;
      inFlight++;
      recent.push(now);
      return null;
    },
    release() { inFlight--; },
  };
}

module.exports = { createPolicy };
