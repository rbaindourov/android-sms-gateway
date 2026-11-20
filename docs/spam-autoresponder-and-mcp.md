# On-Device Spam Autoresponder & Read-Only Workstation MCP Bridge

## 1. On-Device Smart Spam Autoresponder (`app`)

Evaluates incoming SMS text messages (`ReceiverService` -> `AutoResponderService`) with strict fail-closed carrier & privacy safeguards before pitching `https://webdesigner.la` (configurable in **Settings → Spam autoresponder…**):

### Safety & Contact Filtering Chain (`AutoResponderPolicy`)
1. **Contacts Exclusion (Fail-Closed)**: Requires runtime `android.permission.READ_CONTACTS`. If permission is missing or the sender matches `ContactsContract.PhoneLookup`, the message is skipped (`contacts_permission_missing` / `saved_contact`).
2. **Shortcode & Alphanumeric Blocking**: `normalizePhoneNumber()` accepts only standard `10–15` digit phone numbers; `<= 6` digit shortcodes and alphabetic sender IDs (`USPS`, `CHASE`, etc.) are ignored.
3. **Self-Number & Echo Guard**: Skips the phone's own active SIM number(s) and any message containing the configured pitch URL/host.
4. **Opt-Out & OTP/Bank Alert Shield**: Immediately skips `STOP`/`UNSUBSCRIBE`/`CANCEL` keywords and verification/OTP/security alerts (`verification code`, `security code`, `2fa`, `otp`, `fraud alert`, or 4–8 digit code contexts).
5. **Backfill Suppression**: Only live `ReceiverService.process(..., triggerWebhooks = true)` arrivals trigger the autoresponder — bulk `/inbox/refresh` exports never trigger replies.
6. **Per-Number Cooldown Ledger**: `AutoResponderStore` records every drafted, approved, sent, or dismissed phone number so the same number receives at most one response per `COOLDOWN_DAYS` (default `30` days).

### Modes
- **Require 1-Tap Notification Approval (default `true`)**: Posts an interactive notification (**Approve & Send** / **Dismiss**) and opens [`AutoResponderDraftActivity`](../app/src/main/java/me/capcom/smsgateway/ui/AutoResponderDraftActivity.kt) when tapped so you can inspect/edit before sending.
- **Automatic Counter-Reply (`requireApproval = false`)**: Toggle off inside **Settings → Spam autoresponder…** to send counter-pitches automatically whenever all guards pass.
- **Inbound Filter Mode**: Choose between `all_non_contacts` or `keywords_only` (matching configurable `SPAM_KEYWORDS`).

---

## 2. Read-Only Workstation MCP Client (`mcp-client/`)

Provides Antigravity / MCP clients strictly read-only visibility into `/inbox`, `/messages`, and `/health` without granting `messages:send`:

- `mcp-client/lib.mjs`: Discovers the phone dynamically on LAN via `avahi-browse -rpt _grafana-alerts._tcp` (no hardcoded IPs) and mints an in-memory **read-only JWT** (`inbox:list`, `inbox:read`, `messages:read`) via `POST /auth/token` (or accepts a pre-minted `SMSGATE_TOKEN`).
- `mcp-client/server.mjs`: Stdio MCP JSON-RPC 2.0 server exposing `sms_gateway_status`, `sms_list_inbox`, and `sms_list_sent_messages`.

### Verification
- Android JVM unit tests: `./gradlew testDebugUnitTest` (`AutoResponderPolicyTest`)
- MCP client unit tests: `cd mcp-client && npm test`
