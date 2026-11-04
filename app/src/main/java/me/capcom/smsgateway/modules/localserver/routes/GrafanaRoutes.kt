package me.capcom.smsgateway.modules.localserver.routes

import com.google.gson.Gson
import com.google.gson.JsonParseException
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.contentLength
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import me.capcom.smsgateway.modules.grafana.GrafanaAlertsService
import me.capcom.smsgateway.modules.grafana.GrafanaAlertsSettings
import me.capcom.smsgateway.modules.grafana.domain.GrafanaWebhookPayload
import java.security.MessageDigest
import android.util.Base64

/**
 * Receiver for Grafana "webhook" contact points.
 *
 * Deliberately registered OUTSIDE the gateway's basic/JWT `authenticate` block: it has its own
 * dedicated bearer token so Grafana never needs credentials that can send SMS.
 *
 * Accepted credentials (Grafana supports both in the webhook contact point):
 *  - `Authorization: Bearer <token>`
 *  - `Authorization: Basic base64(<any user>:<token>)`
 */
class GrafanaRoutes(
    private val service: GrafanaAlertsService,
    private val settings: GrafanaAlertsSettings,
) {
    private val gson = Gson()

    fun register(routing: Route) {
        routing.route(GrafanaAlertsSettings.ALERTS_PATH) {
            // Health/credential probe used by the discovery relay.
            get {
                if (!authorize(call)) return@get
                call.respond(mapOf("status" to "ok", "service" to settings.serviceName))
            }
            post {
                if (!authorize(call)) return@post

                val length = call.request.contentLength() ?: 0L
                if (length > MAX_BODY_BYTES) {
                    return@post call.respond(
                        HttpStatusCode.PayloadTooLarge,
                        mapOf("message" to "Payload too large")
                    )
                }

                val body = call.receiveText()
                if (body.length > MAX_BODY_BYTES) {
                    return@post call.respond(
                        HttpStatusCode.PayloadTooLarge,
                        mapOf("message" to "Payload too large")
                    )
                }

                val payload = try {
                    gson.fromJson(body, GrafanaWebhookPayload::class.java)
                } catch (e: JsonParseException) {
                    null
                } ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("message" to "Body is not a valid Grafana webhook payload")
                )

                val shown = service.handle(payload)
                call.respond(mapOf("status" to "ok", "accepted" to shown))
            }
        }
    }

    private suspend fun authorize(call: ApplicationCall): Boolean {
        if (!settings.enabled) {
            call.respond(HttpStatusCode.NotFound, mapOf("message" to "Grafana alerts are disabled"))
            return false
        }

        val presented = extractCredential(call.request.header("Authorization"))
        val expected = settings.token.toByteArray(Charsets.UTF_8)
        val ok = presented != null &&
                MessageDigest.isEqual(presented.toByteArray(Charsets.UTF_8), expected)

        if (!ok) {
            call.response.header("WWW-Authenticate", "Bearer realm=\"SMSGate Grafana alerts\"")
            call.respond(HttpStatusCode.Unauthorized, mapOf("message" to "Unauthorized"))
        }
        return ok
    }

    private fun extractCredential(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val parts = header.trim().split(' ', limit = 2)
        if (parts.size != 2) return null

        return when (parts[0].lowercase()) {
            "bearer" -> parts[1].trim()
            "basic" -> try {
                String(Base64.decode(parts[1].trim(), Base64.DEFAULT), Charsets.UTF_8)
                    .substringAfter(':', missingDelimiterValue = "")
            } catch (e: IllegalArgumentException) {
                null
            }

            else -> null
        }
    }

    companion object {
        private const val MAX_BODY_BYTES = 512 * 1024
    }
}
