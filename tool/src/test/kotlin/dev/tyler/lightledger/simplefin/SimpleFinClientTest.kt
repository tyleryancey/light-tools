package dev.tyler.lightledger.simplefin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for the pure [parseAccessUrl] helper only (CLAUDE-light-ledger.md §10:
 * "OkHttp drops URL-embedded credentials — set the Basic Authorization header yourself").
 * No network I/O here — the [SimpleFinClient.claim] and [SimpleFinClient.fetch] HTTP
 * paths are live-tested on-device against the demo token in Task 13.
 */
class SimpleFinClientTest {

    @Test
    fun parsesStandardAccessUrlIntoBaseUrlAndBasicAuthHeader() {
        val parts = parseAccessUrl("https://user:pass@bridge.simplefin.org/simplefin")

        assertEquals("https://bridge.simplefin.org/simplefin", parts.baseUrl)
        assertEquals("Basic dXNlcjpwYXNz", parts.basicAuthHeader)
    }

    @Test
    fun preservesPortAndPathWhileStrippingCredentials() {
        val parts = parseAccessUrl("https://alice:s3cr3t@bridge.simplefin.org:8443/simplefin/v2")

        assertEquals("https://bridge.simplefin.org:8443/simplefin/v2", parts.baseUrl)
        assertEquals("Basic YWxpY2U6czNjcjN0", parts.basicAuthHeader)
    }

    @Test
    fun urlWithoutCredentialsHasNullBasicAuthHeader() {
        val parts = parseAccessUrl("https://bridge.simplefin.org/simplefin")

        assertEquals("https://bridge.simplefin.org/simplefin", parts.baseUrl)
        assertNull(parts.basicAuthHeader)
    }
}
