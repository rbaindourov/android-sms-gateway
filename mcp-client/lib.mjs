import { execFile } from 'node:child_process';

export const READ_ONLY_SCOPES = ['inbox:list', 'inbox:read', 'messages:read'];

export function unescapeAvahiField(raw) {
  const str = String(raw || '');
  const bytes = [];
  for (let i = 0; i < str.length; i++) {
    if (str[i] === '\\' && i + 3 < str.length && /^\d{3}$/.test(str.slice(i + 1, i + 4))) {
      bytes.push(Number(str.slice(i + 1, i + 4)));
      i += 3;
    } else if (str[i] === '\\' && i + 1 < str.length) {
      bytes.push(...Buffer.from(str[i + 1], 'utf8'));
      i += 1;
    } else {
      bytes.push(...Buffer.from(str[i], 'utf8'));
    }
  }
  return Buffer.from(bytes).toString('utf8');
}

export function pickRoutableAddress(addresses) {
  const list = Array.isArray(addresses) ? addresses : [];
  const ipv4 = list.find((a) => /^\d{1,3}(\.\d{1,3}){3}$/.test(a) && !a.startsWith('169.254.'));
  if (ipv4) return ipv4;
  return list.find((a) => a.includes(':') && !a.toLowerCase().startsWith('fe80')) || null;
}

export function parseAvahiBrowseOutput(stdout) {
  const byName = new Map();
  for (const line of String(stdout || '').split(/\r?\n/)) {
    if (!line.startsWith('=')) continue;
    const parts = line.split(';');
    if (parts.length < 9) continue;

    const name = unescapeAvahiField(parts[3]);
    const host = unescapeAvahiField(parts[6]);
    const address = unescapeAvahiField(parts[7]);
    const port = Number(parts[8]);
    if (!name || !address || !Number.isFinite(port) || port <= 0) continue;

    const existing = byName.get(name);
    if (existing) {
      if (!existing.addresses.includes(address)) existing.addresses.push(address);
      if (!existing.host && host) existing.host = host;
    } else {
      byName.set(name, { name, host, port, addresses: [address] });
    }
  }
  return [...byName.values()];
}

export function createSmsGatewayClient({
  explicitUrl = process.env.SMSGATE_URL || '',
  token = process.env.SMSGATE_TOKEN || '',
  username = process.env.SMSGATE_USER || '',
  password = process.env.SMSGATE_PASS || '',
  serviceType = process.env.SMSGATE_MDNS_SERVICE || '_grafana-alerts._tcp',
  fetchImpl = fetch,
  execFileImpl = execFile,
  timeoutMs = 5000,
} = {}) {
  let cachedJwt = token ? { token, expiresAtMs: Number.MAX_SAFE_INTEGER } : null;

  async function discoverBaseUrl() {
    const cleanExplicit = String(explicitUrl || '').trim().replace(/\/+$/, '');
    if (cleanExplicit) return cleanExplicit;

    const stdout = await new Promise((resolve, reject) => {
      execFileImpl('avahi-browse', ['-rpt', serviceType], { timeout: 3500 }, (err, out) => {
        if (err && !out) return reject(err);
        resolve(out || '');
      });
    });

    const entries = parseAvahiBrowseOutput(stdout);
    for (const entry of entries) {
      const addr = pickRoutableAddress(entry.addresses) || entry.host;
      if (addr) {
        const formatted = addr.includes(':') ? `[${addr}]` : addr;
        return `http://${formatted}:${entry.port}`;
      }
    }

    throw new Error(
      `No SMSGateway phone found on LAN via mDNS (${serviceType}). Make sure Local Server is running on Wi-Fi or set SMSGATE_URL.`
    );
  }

  async function getReadOnlyBearerToken(baseUrl) {
    if (cachedJwt && cachedJwt.expiresAtMs - 30_000 > Date.now()) {
      return cachedJwt.token;
    }
    if (!username || !password) {
      throw new Error(
        'Missing SMSGateway credentials. Set SMSGATE_TOKEN (recommended read-only JWT) or SMSGATE_USER and SMSGATE_PASS.'
      );
    }

    const basicAuth = 'Basic ' + Buffer.from(`${username}:${password}`, 'utf8').toString('base64');
    const res = await fetchImpl(`${baseUrl}/auth/token`, {
      method: 'POST',
      headers: {
        Authorization: basicAuth,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        scopes: READ_ONLY_SCOPES,
        ttl: 3600,
      }),
      signal: AbortSignal.timeout(timeoutMs),
    });

    if (!res.ok) {
      const msg = await res.text().catch(() => '');
      throw new Error(`Failed to mint read-only JWT token via POST /auth/token (HTTP ${res.status}): ${msg}`);
    }

    const data = await res.json();
    if (!data?.access_token) {
      throw new Error('POST /auth/token response did not contain access_token.');
    }

    const expiresAtMs = data.expires_at ? Date.parse(data.expires_at) : Date.now() + 3600_000;
    cachedJwt = {
      token: data.access_token,
      expiresAtMs: Number.isFinite(expiresAtMs) ? expiresAtMs : Date.now() + 3600_000,
    };
    return cachedJwt.token;
  }

  async function authorizedGet(reqPath) {
    const baseUrl = await discoverBaseUrl();
    const bearer = await getReadOnlyBearerToken(baseUrl);
    const res = await fetchImpl(`${baseUrl}${reqPath}`, {
      method: 'GET',
      headers: {
        Authorization: `Bearer ${bearer}`,
        Accept: 'application/json',
      },
      signal: AbortSignal.timeout(timeoutMs),
    });

    if (res.status === 401 || res.status === 403) {
      cachedJwt = token ? cachedJwt : null;
    }
    if (!res.ok) {
      const errBody = await res.text().catch(() => '');
      throw new Error(`GET ${reqPath} failed (HTTP ${res.status}): ${errBody}`);
    }

    const totalHeader = res.headers.get('x-total-count');
    const data = await res.json();
    return {
      baseUrl,
      totalCount: totalHeader !== null ? Number(totalHeader) : undefined,
      data,
    };
  }

  async function getStatus() {
    const baseUrl = await discoverBaseUrl();
    const res = await fetchImpl(`${baseUrl}/health`, {
      method: 'GET',
      signal: AbortSignal.timeout(timeoutMs),
    });
    const health = res.ok ? await res.json().catch(() => null) : { httpStatus: res.status };
    let rootInfo = null;
    try {
      const root = await authorizedGet('/');
      rootInfo = root.data;
    } catch (err) {
      rootInfo = { authError: err.message };
    }
    return { baseUrl, health, gateway: rootInfo, scopesRequested: READ_ONLY_SCOPES };
  }

  async function listInbox({ type, from, to, limit = 25, offset = 0 } = {}) {
    const safeLimit = Math.max(1, Math.min(200, Number(limit) || 25));
    const safeOffset = Math.max(0, Number(offset) || 0);
    const params = new URLSearchParams({
      limit: String(safeLimit),
      offset: String(safeOffset),
    });
    if (type) params.set('type', String(type));
    if (from) params.set('from', String(from));
    if (to) params.set('to', String(to));

    const result = await authorizedGet(`/inbox?${params.toString()}`);
    return {
      baseUrl: result.baseUrl,
      totalCount: result.totalCount,
      messages: result.data,
    };
  }

  async function listSentMessages({ state, from, to, limit = 25, offset = 0 } = {}) {
    const safeLimit = Math.max(1, Math.min(200, Number(limit) || 25));
    const safeOffset = Math.max(0, Number(offset) || 0);
    const params = new URLSearchParams({
      includeContent: 'true',
      limit: String(safeLimit),
      offset: String(safeOffset),
    });
    if (state) params.set('state', String(state));
    if (from) params.set('from', String(from));
    if (to) params.set('to', String(to));

    const result = await authorizedGet(`/messages?${params.toString()}`);
    return {
      baseUrl: result.baseUrl,
      totalCount: result.totalCount,
      messages: result.data,
    };
  }

  return {
    discoverBaseUrl,
    getStatus,
    listInbox,
    listSentMessages,
  };
}
