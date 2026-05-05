package com.kliniq.infra.security.breach

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest

/**
 * Breach check via HIBP's k-anonymity API: we hash the password with
 * SHA-1, send only the first five hex chars over the network, and look
 * for our remaining 35-char suffix in the response. The plaintext
 * password never leaves the JVM and HIBP never learns which entry we
 * matched.
 *
 * Returns false (i.e. "looks fine") on any HIBP-side failure — see
 * [BreachedPasswordChecker] for the rationale on fail-open.
 */
@Component
class HibpBreachedPasswordChecker(
    private val properties: BreachedPasswordProperties,
) : BreachedPasswordChecker {
    private val log = LoggerFactory.getLogger(javaClass)
    private val client: HttpClient =
        HttpClient.newBuilder().connectTimeout(properties.timeout).build()

    @Suppress("ReturnCount") // each branch is one informative early exit
    override fun isBreached(password: String): Boolean {
        if (!properties.enabled) return false
        val hash = sha1HexUpper(password)
        val prefix = hash.substring(0, PREFIX_LEN)
        val suffix = hash.substring(PREFIX_LEN)

        val request =
            HttpRequest
                .newBuilder()
                .uri(URI.create("${properties.apiBase}/range/$prefix"))
                .timeout(properties.timeout)
                // Add-Padding asks HIBP to inject decoy lines so a network
                // observer can't infer popularity from response size.
                .header("Add-Padding", "true")
                .header("User-Agent", properties.userAgent)
                .GET()
                .build()

        return try {
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != HTTP_OK) {
                log.warn(
                    "breach-check: HIBP returned status {}; failing open",
                    response.statusCode(),
                )
                return false
            }
            response.body().lineSequence().any { line -> matchesSuffix(line, suffix) }
        } catch (e: IOException) {
            log.warn("breach-check: HIBP unreachable, failing open: {}", e.message)
            false
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            log.warn("breach-check: HIBP request interrupted, failing open")
            false
        }
    }

    /** Each line is `SUFFIX:COUNT`. Padding lines have count 0. */
    @Suppress("ReturnCount") // each branch is a parse guard
    private fun matchesSuffix(
        line: String,
        suffix: String,
    ): Boolean {
        val parts = line.split(':', limit = 2)
        if (parts.size != 2) return false
        val (lineSuffix, countText) = parts
        val count = countText.trim().toIntOrNull() ?: return false
        return count > 0 && lineSuffix.equals(suffix, ignoreCase = true)
    }

    private fun sha1HexUpper(s: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and BYTE_MASK
            out.append(HEX[v ushr HEX_NIBBLE_SHIFT])
            out.append(HEX[v and HEX_NIBBLE_MASK])
        }
        return out.toString()
    }

    companion object {
        private const val PREFIX_LEN = 5
        private const val HTTP_OK = 200
        private const val HEX_NIBBLE_SHIFT = 4
        private const val HEX_NIBBLE_MASK = 0x0F
        private const val BYTE_MASK = 0xFF
        private val HEX = "0123456789ABCDEF".toCharArray()
    }
}
