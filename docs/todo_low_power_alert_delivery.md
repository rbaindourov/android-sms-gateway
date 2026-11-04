# TODO: low-power Grafana alert delivery (revisit after we have operational data)

**Status:** parked on purpose. Decision (2026-11-03): run the current LAN design for a while, observe real
battery/reliability numbers, then decide.

## Why this exists

The Grafana receiver rides on the SMSGate local server, which is an always-on foreground service that holds
a CPU `PARTIAL_WAKE_LOCK` and a `WIFI_MODE_FULL_HIGH_PERF` Wi‑Fi lock (`WebService.kt`). That keeps the phone
reachable but costs battery. Android's Doze / Samsung battery management can also kill or throttle it unless the
app is exempted from battery optimisation.

## Options (not yet chosen)

| Option | Idea | Pros | Cons |
| --- | --- | --- | --- |
| **A. Outbound connection, no wake locks** | Drop the wake/Wi‑Fi locks. Phone opens ONE outbound connection (SSE/WebSocket) to the relay and holds it with cheap keepalives; relay pushes alerts down it. | LAN-only, no Google, much lower drain, survives Doze better than an inbound listener | App + relay work; needs reliability testing; mDNS then only used so the phone finds the relay |
| **B. ntfy (self-hosted, native Go binary)** | Grafana webhook → ntfy → ntfy Android app | Proven, low power, no app code | Replaces the feature we built; another service to run |
| **C. FCM** | Relay → Google FCM → phone | Best battery, works off-LAN | Alert text transits Google; needs our own Firebase project (the bundled `google-services.json` is the upstream author's) |
| **D. Stay as is** | Current design | Instant, simple | Noticeable battery cost; needs battery-optimisation exemption |

Also possible as a cheap interim: an **"alerts only" mode** that drops the wake/Wi‑Fi locks (alerts may be delayed
while the phone is in Doze).

## What to measure first (operational data)

Run the current build for a few days on the S23+ (SM-S916U1, Android 16) and record:

1. Battery drain attributable to the app: `adb shell dumpsys batterystats --charged me.capcom.smsgateway`
   (and Settings → Battery → app usage).
2. Wake lock and Wi‑Fi lock time over the same window.
3. Alert delivery reliability and latency: compare Grafana's sent notifications with the app's *Recent alerts* list
   (Settings → Grafana alerts…), especially after the phone has been idle/screen-off for hours.
4. Whether Samsung ever kills the service despite the battery exemption (gaps in the mDNS advertisement:
   `avahi-browse -rt _grafana-alerts._tcp`).

## Decision rule

* Drain acceptable and zero missed alerts → keep **D**.
* Drain too high but alerts reliable → try the **alerts-only** mode, then **A**.
* Missed alerts while idle → **A** or **C**.
* Want alerts off-LAN → **C** (or **B** behind our own reverse proxy).

Context: `docs/grafana-alerts.md` (app) and `docs/phone_alert_relay.md` in the telemetry repo (relay).
