import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { handleJsonRpcRequest } from '../server.mjs';
import {
  createSmsGatewayClient,
  parseAvahiBrowseOutput,
  READ_ONLY_SCOPES,
} from '../lib.mjs';

test('parseAvahiBrowseOutput resolves Galaxy S23+ mDNS IPv4 and port cleanly', () => {
  const raw = [
    '=;wlp59s0;IPv6;SMSGate\\032Alerts\\032\\040SM-S916U1\\041;_grafana-alerts._tcp;local;Android_11SPSS7R.local;fe80::1234;8080;"model=SM-S916U1"',
    '=;wlp59s0;IPv4;SMSGate\\032Alerts\\032\\040SM-S916U1\\041;_grafana-alerts._tcp;local;Android_11SPSS7R.local;192.0.2.44;8080;"model=SM-S916U1"',
  ].join('\n');

  const entries = parseAvahiBrowseOutput(raw);
  assert.equal(entries.length, 1);
  assert.equal(entries[0].port, 8080);
  assert.deepEqual(entries[0].addresses, ['fe80::1234', '192.0.2.44']);
});

test('mints read-only JWT without send scopes and reads /inbox and /messages cleanly', async () => {
  const tokenRequests = [];
  const authHeadersSeen = [];
  const fakePhone = http.createServer((req, res) => {
    let body = '';
    req.on('data', (c) => (body += c));
    req.on('end', () => {
      if (req.method === 'POST' && req.url === '/auth/token') {
        const parsed = JSON.parse(body);
        tokenRequests.push({ auth: req.headers.authorization, body: parsed });
        res.writeHead(201, { 'Content-Type': 'application/json' });
        return res.end(JSON.stringify({ access_token: 'ro-jwt-123', token_type: 'Bearer' }));
      }
      if (req.method === 'GET' && req.url.startsWith('/inbox')) {
        authHeadersSeen.push(req.headers.authorization);
        res.writeHead(200, { 'Content-Type': 'application/json', 'X-Total-Count': '1' });
        return res.end(
          JSON.stringify([
            {
              id: 'text:1',
              type: 'SMS',
              sender: '+18185550199',
              contentPreview: 'Claim free crypto!',
            },
          ])
        );
      }
      if (req.method === 'GET' && req.url.startsWith('/messages')) {
        authHeadersSeen.push(req.headers.authorization);
        res.writeHead(200, { 'Content-Type': 'application/json', 'X-Total-Count': '1' });
        return res.end(
          JSON.stringify([{ id: 'msg1', state: 'Sent', textMessage: { text: 'Visit https://webdesigner.la' } }])
        );
      }
      if (req.method === 'GET' && req.url === '/health') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        return res.end(JSON.stringify({ status: 'pass' }));
      }
      if (req.method === 'GET' && req.url === '/') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        return res.end(JSON.stringify({ status: 'ok', model: 'SM-S916U1' }));
      }
      res.writeHead(404);
      res.end('{}');
    });
  });

  await new Promise((resolve) => fakePhone.listen(0, '127.0.0.1', resolve));
  const port = fakePhone.address().port;

  try {
    const client = createSmsGatewayClient({
      username: 'sms',
      password: 'test-password',
      execFileImpl: (_cmd, _args, _opts, cb) =>
        cb(null, `=;lo;IPv4;SMSGate\\032Test;_grafana-alerts._tcp;local;test.local;127.0.0.1;${port};"v=1"`),
    });

    const inbox = await client.listInbox({ limit: 10 });
    assert.equal(tokenRequests.length, 1);
    assert.deepEqual(tokenRequests[0].body.scopes, READ_ONLY_SCOPES);
    assert.ok(!tokenRequests[0].body.scopes.includes('messages:send'));
    assert.equal(inbox.totalCount, 1);
    assert.equal(inbox.messages[0].contentPreview, 'Claim free crypto!');

    const sent = await client.listSentMessages({ limit: 5 });
    assert.equal(sent.messages[0].textMessage.text, 'Visit https://webdesigner.la');
    assert.deepEqual(authHeadersSeen, ['Bearer ro-jwt-123', 'Bearer ro-jwt-123']);

    const status = await client.getStatus();
    assert.equal(status.health.status, 'pass');
    assert.equal(status.gateway.model, 'SM-S916U1');
  } finally {
    fakePhone.closeAllConnections?.();
    await new Promise((resolve) => fakePhone.close(resolve));
  }
});

test('MCP JSON-RPC server exposes strictly read-only tools via initialize and tools/list', async () => {
  const initRes = await handleJsonRpcRequest({ jsonrpc: '2.0', id: 1, method: 'initialize' });
  assert.equal(initRes.result.serverInfo.name, 'smsgateway-mcp-readonly');

  const listRes = await handleJsonRpcRequest({ jsonrpc: '2.0', id: 2, method: 'tools/list' });
  const names = listRes.result.tools.map((t) => t.name);
  assert.deepEqual(names, ['sms_gateway_status', 'sms_list_inbox', 'sms_list_sent_messages']);
});
