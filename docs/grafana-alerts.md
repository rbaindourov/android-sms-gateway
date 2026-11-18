# Grafana alerts on the phone (LAN push + mDNS discovery)

Adds a Grafana alert receiver to the SMSGate Android app. While the phone is on Wi‑Fi it
advertises itself over mDNS/DNS‑SD, Grafana alerts are pushed to it, and each alert becomes
a loud, high‑priority notification (🔥 firing / ✅ resolved) plus an entry in an in‑app
"Recent alerts" list.

```
Grafana ──POST──> phone-alert-relay (127.0.0.1:9099, next to each Grafana)
                      │  finds phones via mDNS: _grafana-alerts._tcp
                      ▼
                 phone :8080  POST /grafana/alerts   (Bearer <alert token>)
```

No IP address is hard‑coded anywhere: Grafana always posts to the loopback relay, and the
relay discovers the phone(s). Move Grafana to another server, or run several Grafana
instances, and nothing in the app needs to change. The relay side lives in the telemetry
repo (`native/phone-alert-relay`, see `docs/phone_alert_relay.md` there).

## App side

| Piece | Where |
| --- | --- |
| Receiver route `POST /grafana/alerts` (+ `GET` credential probe) | `modules/localserver/routes/GrafanaRoutes.kt` |
| Payload parsing (unified + legacy alerting) | `modules/grafana/GrafanaAlertParser.kt` |
| Persistent, bounded history (100, newest first, retry de‑dup 60 s) | `modules/grafana/GrafanaAlertStore.kt` |
| High‑priority notifications (`Follow link`, `Open in Grafana`, `Back office`), firing→resolved replaces same alert | `modules/grafana/GrafanaAlertNotifier.kt` |
| Alert detail screen (status, tappable web links in summary/description/labels, Follow Destination URL & Redirect Inspector buttons, Grafana & Back Office links) | `ui/GrafanaAlertDetailActivity.kt` |
| mDNS advertisement, Wi‑Fi only | `modules/grafana/GrafanaAlertsDiscovery.kt` |
| Settings screen **Settings → Grafana alerts…** | `ui/settings/GrafanaAlertsSettingsFragment.kt` |

* **Auth:** a dedicated 32‑character bearer token (Settings → Grafana alerts… → *Alert token*,
  copyable, regenerable by editing it). It is separate from the gateway username/password, so Grafana
  never holds credentials that can send SMS. `Authorization: Bearer <token>` or HTTP Basic with the
  token as the password are both accepted. The route is outside the gateway's basic/JWT auth block.
* **Discovery:** service type `_grafana-alerts._tcp`, port = local server port, TXT
  `v=1 path=/grafana/alerts auth=bearer model=<device>`. Registered when a Wi‑Fi network becomes
  available and withdrawn when the last one is lost (`ConnectivityManager` network callback).
* **Requirements on the phone:** the **Local server** must be running (Home tab → toggle) because
  the receiver is part of it; notifications must be allowed (Android 13+ prompts on first launch);
  exclude the app from battery optimisation (Settings → *Disable battery optimisation*) so Samsung's
  power management doesn't kill the foreground service.
* **Test without Grafana:** Settings → Grafana alerts… → *Send test alert*.

### Manual test with curl

```bash
curl -i -X POST http://<phone-ip>:8080/grafana/alerts \
  -H "Authorization: Bearer <alert token>" -H "Content-Type: application/json" \
  -d '{"status":"firing","externalURL":"http://192.168.11.32:3010/",
       "alerts":[{"status":"firing","fingerprint":"t1",
                  "labels":{"alertname":"Manual test","severity":"critical"},
                  "annotations":{"summary":"hello from curl"}}]}'
```

Browse the advertisement from a Linux box: `avahi-browse -rt _grafana-alerts._tcp`.

## Build & install

```bash
./gradlew testDebugUnitTest assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
~/Android/Sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The phone needs **USB debugging** enabled (Settings → About phone → tap *Build number* 7×,
then Developer options → USB debugging) and the RSA prompt accepted. Set USB mode to
*File transfer* if the device is not listed.

## Tests

`GrafanaAlertParserTest` (10) and `GrafanaAlertStoreTest` (6) run on the JVM:
`./gradlew testDebugUnitTest --tests 'me.capcom.smsgateway.modules.grafana.*'`.
