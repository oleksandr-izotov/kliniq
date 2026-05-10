package com.kliniq.infra.mail

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Sends mail through Resend's REST API at https://api.resend.com/emails.
 * Used in prod where the host blocks outbound SMTP (Hetzner default for
 * fresh cloud accounts blocks 25/465/587 as anti-spam) so the JavaMail
 * SmtpEmailSender hangs on TCP connect for ~130 s before failing the
 * register call. Switching to HTTPS sidesteps the block — port 443 is
 * always open.
 *
 * Wired in only when `app.mail.provider=resend`. The default
 * (`app.mail.provider` unset / =smtp) keeps SmtpEmailSender for local
 * Mailpit dev so this class never runs without the API key.
 *
 * Failure mode parity with SmtpEmailSender: any non-2xx response throws
 * [MailDeliveryException], which the GlobalExceptionHandler maps to a
 * 500 the same way Spring's MailSendException would.
 */
@Component
@ConditionalOnProperty(name = ["app.mail.provider"], havingValue = "resend")
class ResendApiEmailSender(
    private val mapper: ObjectMapper,
    @Value("\${app.mail.from}") private val from: String,
    @Value("\${app.mail.resend.api-key}") private val apiKey: String,
) : EmailSender {
    private val log = LoggerFactory.getLogger(javaClass)

    // 10 s connect / 15 s response. Fast-fail keeps the user-facing
    // register/forgot-password call from blocking on a stuck Resend.
    private val httpClient: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
            .build()

    override fun sendEmailVerification(
        to: String,
        displayName: String,
        verifyUrl: String,
    ) {
        send(
            to = to,
            subject = MailTemplates.VERIFY_SUBJECT,
            text = MailTemplates.verifyText(displayName, verifyUrl),
            html = MailTemplates.verifyHtml(displayName, verifyUrl),
        )
    }

    override fun sendPasswordReset(
        to: String,
        displayName: String,
        resetUrl: String,
    ) {
        send(
            to = to,
            subject = MailTemplates.RESET_SUBJECT,
            text = MailTemplates.resetText(displayName, resetUrl),
            html = MailTemplates.resetHtml(displayName, resetUrl),
        )
    }

    override fun sendInvitation(
        to: String,
        roleLabel: String,
        acceptUrl: String,
    ) {
        send(
            to = to,
            subject = MailTemplates.INVITATION_SUBJECT,
            text = MailTemplates.invitationText(roleLabel, acceptUrl),
            html = MailTemplates.invitationHtml(roleLabel, acceptUrl),
        )
    }

    private fun send(
        to: String,
        subject: String,
        text: String,
        html: String,
    ) {
        val payload =
            mapOf(
                "from" to from,
                "to" to listOf(to),
                "subject" to subject,
                "text" to text,
                "html" to html,
            )
        val body = mapper.writeValueAsString(payload)

        val request =
            HttpRequest
                .newBuilder()
                .uri(URI.create(RESEND_EMAILS_ENDPOINT))
                .timeout(Duration.ofSeconds(RESPONSE_TIMEOUT_SECONDS))
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()

        val response = executeRequest(request)

        if (response.statusCode() !in HTTP_OK_RANGE) {
            // Logged at WARN — the stack trace from the thrown exception
            // gets us the user-facing 500 detail in GlobalExceptionHandler.
            log.warn(
                "resend.send failed: status={} body={}",
                response.statusCode(),
                response.body().take(BODY_PREVIEW_CHARS),
            )
            throw MailDeliveryException(
                "Resend API returned ${response.statusCode()}",
            )
        }
    }

    /**
     * Wraps the HttpClient send so [send] only owns the non-2xx throw
     * (detekt's ThrowsCount caps a function at two). Both transient
     * failure modes — IO and thread interruption — collapse into the
     * same [MailDeliveryException] so callers don't have to branch.
     */
    private fun executeRequest(request: HttpRequest): HttpResponse<String> {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: java.io.IOException) {
            throw MailDeliveryException("Resend API unreachable", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw MailDeliveryException("Resend API call interrupted", e)
        }
    }

    companion object {
        private const val RESEND_EMAILS_ENDPOINT = "https://api.resend.com/emails"
        private const val CONNECT_TIMEOUT_SECONDS = 10L
        private const val RESPONSE_TIMEOUT_SECONDS = 15L
        private val HTTP_OK_RANGE = 200..299
        private const val BODY_PREVIEW_CHARS = 500
    }
}

/** Thrown when Resend rejects a delivery or is unreachable. */
class MailDeliveryException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
