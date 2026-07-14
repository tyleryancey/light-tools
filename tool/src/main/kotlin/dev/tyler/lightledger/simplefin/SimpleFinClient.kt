package dev.tyler.lightledger.simplefin

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import java.net.URI
import java.util.Base64
import kotlinx.coroutines.CancellationException

/**
 * The Access URL split into its credential-free base and a pre-built Basic
 * Authorization header value (or `null` if the URL carries no userinfo).
 */
internal data class AccessUrlParts(
    val baseUrl: String,
    val basicAuthHeader: String?,
)

/**
 * Pure helper: extracts Basic-auth credentials embedded in a SimpleFIN Access URL
 * (`https://user:pass@host/path`) per CLAUDE-light-ledger.md §10 — "OkHttp drops
 * URL-embedded credentials — set the Basic Authorization header yourself."
 */
internal fun parseAccessUrl(accessUrl: String): AccessUrlParts {
    val uri = URI(accessUrl.trim())
    val portPart = if (uri.port != -1) ":${uri.port}" else ""
    val baseUrl = "${uri.scheme}://${uri.host}$portPart${uri.rawPath.orEmpty()}"
    val basicAuthHeader = uri.userInfo?.let {
        "Basic " + Base64.getEncoder().encodeToString(it.toByteArray())
    }
    return AccessUrlParts(baseUrl = baseUrl, basicAuthHeader = basicAuthHeader)
}

// Responses are read via bodyAsText() + SimpleFinDecoder.decode() (manual decode keeps the
// tolerant ignoreUnknownKeys Json config in one place), so no ContentNegotiation plugin is needed.
private fun defaultClient(): HttpClient = HttpClient(OkHttp)

/**
 * Thrown for non-2xx SimpleFIN responses so callers (Task 7's `JOB_SYNC`) can map
 * status codes per CLAUDE-light-ledger.md §6.2.3 (403 ⇒ token revoked, other 4xx ⇒
 * surfaced error).
 */
internal class SimpleFinHttpException(val statusCode: Int, message: String) : Exception(message)

/**
 * The subset of [SimpleFinClient] Task 7's sync orchestration and Task 10's connect flow
 * depend on — lets `SimpleFinSyncRunner`/`SimpleFinConnectViewModel` be unit-tested against
 * a fake instead of real Ktor/HTTP.
 */
interface SimpleFinApi {
    suspend fun claim(setupTokenBase64: String): Result<String>
    suspend fun fetch(accessUrl: String, startEpochS: Long): Result<AccountSet>
}

/**
 * SimpleFIN claim + fetch client (CLAUDE-light-ledger.md §6.1). Both the setup token
 * and the claimed Access URL are bearer-equivalent credentials — never logged here.
 * A fresh [HttpClient] is created per operation (via [clientFactory]) and always
 * closed, success or failure.
 */
class SimpleFinClient(private val clientFactory: () -> HttpClient = { defaultClient() }) : SimpleFinApi {

    /** Base64-decodes [setupTokenBase64] into the claim URL, POSTs an empty body, and
     * returns the response body (trimmed) as the Access URL. */
    override suspend fun claim(setupTokenBase64: String): Result<String> = try {
        val claimUrl = String(Base64.getDecoder().decode(setupTokenBase64.trim()))
        val client = clientFactory()
        try {
            val response = client.post(claimUrl)
            if (!response.status.isSuccess()) {
                throw SimpleFinHttpException(
                    statusCode = response.status.value,
                    message = "SimpleFIN claim HTTP ${response.status.value}",
                )
            }
            Result.success(response.bodyAsText().trim())
        } finally {
            client.close()
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (e: Throwable) {
        Result.failure(e)
    }

    /** GETs `<accessUrl>/accounts?start-date=<startEpochS>&pending=1` with an explicit
     * Basic Authorization header, decoding the response into an [AccountSet]. */
    override suspend fun fetch(accessUrl: String, startEpochS: Long): Result<AccountSet> = try {
        val parts = parseAccessUrl(accessUrl)
        val client = clientFactory()
        try {
            val baseUrl = parts.baseUrl.trimEnd('/')
            val response = client.get("$baseUrl/accounts?start-date=$startEpochS&pending=1") {
                parts.basicAuthHeader?.let { header("Authorization", it) }
            }
            if (!response.status.isSuccess()) {
                throw SimpleFinHttpException(
                    statusCode = response.status.value,
                    message = "SimpleFIN fetch HTTP ${response.status.value}",
                )
            }
            Result.success(SimpleFinDecoder.decode(response.bodyAsText()))
        } finally {
            client.close()
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (e: Throwable) {
        Result.failure(e)
    }
}
