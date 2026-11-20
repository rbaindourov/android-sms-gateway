#!/usr/bin/env node
import readline from 'node:readline';
import { createSmsGatewayClient } from './lib.mjs';

const TOOLS = [
  {
    name: 'sms_gateway_status',
    description:
      'Discover the Android SMSGateway phone on the LAN via mDNS and verify health and read-only authentication.',
    inputSchema: {
      type: 'object',
      properties: {},
      additionalProperties: false,
    },
  },
  {
    name: 'sms_list_inbox',
    description:
      'Read received SMS/MMS messages from the Android SMSGateway local /inbox endpoint (strictly read-only).',
    inputSchema: {
      type: 'object',
      properties: {
        type: {
          type: 'string',
          enum: ['SMS', 'DATA_SMS', 'MMS', 'MMS_DOWNLOADED'],
          description: 'Optional incoming message type filter.',
        },
        from: { type: 'string', description: 'Optional ISO-8601 start timestamp.' },
        to: { type: 'string', description: 'Optional ISO-8601 end timestamp.' },
        limit: { type: 'integer', minimum: 1, maximum: 200, default: 25 },
        offset: { type: 'integer', minimum: 0, default: 0 },
      },
      additionalProperties: false,
    },
  },
  {
    name: 'sms_list_sent_messages',
    description:
      'Read outbound messages dispatched through the Android SMSGateway local /messages endpoint (strictly read-only).',
    inputSchema: {
      type: 'object',
      properties: {
        state: {
          type: 'string',
          enum: ['Pending', 'Processed', 'Sent', 'Delivered', 'Failed'],
        },
        from: { type: 'string', description: 'Optional ISO-8601 start timestamp.' },
        to: { type: 'string', description: 'Optional ISO-8601 end timestamp.' },
        limit: { type: 'integer', minimum: 1, maximum: 200, default: 25 },
        offset: { type: 'integer', minimum: 0, default: 0 },
      },
      additionalProperties: false,
    },
  },
];

const client = createSmsGatewayClient();

export async function handleJsonRpcRequest(message) {
  const response = {
    jsonrpc: '2.0',
    id: message?.id !== undefined ? message.id : null,
  };

  if (!message || message.jsonrpc !== '2.0' || !message.method) {
    response.error = { code: -32600, message: 'Invalid Request' };
    return response;
  }

  switch (message.method) {
    case 'initialize':
      response.result = {
        protocolVersion: '2024-11-05',
        capabilities: {
          tools: { listChanged: false },
        },
        serverInfo: {
          name: 'smsgateway-mcp-readonly',
          version: '1.0.0',
        },
        instructions:
          'Strictly read-only MCP bridge to Android SMSGateway over local mDNS/LAN. Exposes status, received /inbox messages, and gateway-sent /messages without write/send permissions.',
      };
      return response;

    case 'notifications/initialized':
    case 'ping':
      response.result = {};
      return response;

    case 'tools/list':
      response.result = { tools: TOOLS };
      return response;

    case 'tools/call': {
      const name = message.params?.name;
      const args = message.params?.arguments || {};
      try {
        let output;
        if (name === 'sms_gateway_status') {
          output = await client.getStatus();
        } else if (name === 'sms_list_inbox') {
          output = await client.listInbox(args);
        } else if (name === 'sms_list_sent_messages') {
          output = await client.listSentMessages(args);
        } else {
          response.error = { code: -32601, message: `Tool not found: ${name}` };
          return response;
        }
        response.result = {
          content: [{ type: 'text', text: JSON.stringify(output, null, 2) }],
        };
      } catch (err) {
        response.result = {
          content: [{ type: 'text', text: `ERROR: ${err?.message || err}` }],
          isError: true,
        };
      }
      return response;
    }

    default:
      response.error = { code: -32601, message: `Method not found: ${message.method}` };
      return response;
  }
}

const isCliEntry = process.argv[1] && import.meta.url.endsWith(process.argv[1].replace(/^\//, ''));
if (isCliEntry || process.argv[1]?.endsWith('server.mjs')) {
  const rl = readline.createInterface({ input: process.stdin, terminal: false });
  rl.on('line', async (line) => {
    const trimmed = line.trim();
    if (!trimmed) return;
    let req;
    try {
      req = JSON.parse(trimmed);
    } catch {
      process.stdout.write(`${JSON.stringify({ jsonrpc: '2.0', id: null, error: { code: -32700, message: 'Parse error' } })}\n`);
      return;
    }
    const isNotification = req.id === undefined && String(req.method || '').startsWith('notifications/');
    const res = await handleJsonRpcRequest(req);
    if (!isNotification) {
      process.stdout.write(`${JSON.stringify(res)}\n`);
    }
  });
}
