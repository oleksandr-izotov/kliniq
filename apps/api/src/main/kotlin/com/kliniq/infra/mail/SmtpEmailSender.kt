package com.kliniq.infra.mail

import jakarta.mail.internet.MimeMessage
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets

/**
 * Sends mail through Spring's [JavaMailSender]. Used in dev where it's
 * pointed at Mailpit (localhost:1025) so messages stay in the UI at
 * :8025 instead of leaving the developer's machine. In prod we use
 * [ResendApiEmailSender] over HTTPS instead — Hetzner blocks outbound
 * SMTP ports by default for new cloud accounts.
 *
 * The `matchIfMissing = true` keeps Mailpit-on-SMTP as the default when
 * `app.mail.provider` is unset, which matches local dev / CI behaviour.
 */
@Component
@ConditionalOnProperty(name = ["app.mail.provider"], havingValue = "smtp", matchIfMissing = true)
class SmtpEmailSender(
    private val mail: JavaMailSender,
    @Value("\${app.mail.from}") private val from: String,
) : EmailSender {
    override fun sendEmailVerification(
        to: String,
        displayName: String,
        verifyUrl: String,
    ) {
        val msg =
            mail.createMimeMessage().also { mime ->
                MimeMessageHelper(mime, true, StandardCharsets.UTF_8.name()).apply {
                    setFrom(from)
                    setTo(to)
                    setSubject(MailTemplates.VERIFY_SUBJECT)
                    setText(
                        MailTemplates.verifyText(displayName, verifyUrl),
                        MailTemplates.verifyHtml(displayName, verifyUrl),
                    )
                }
            }
        mail.send(msg)
    }

    override fun sendPasswordReset(
        to: String,
        displayName: String,
        resetUrl: String,
    ) {
        val msg: MimeMessage =
            mail.createMimeMessage().also { mime ->
                MimeMessageHelper(mime, true, StandardCharsets.UTF_8.name()).apply {
                    setFrom(from)
                    setTo(to)
                    setSubject(MailTemplates.RESET_SUBJECT)
                    setText(
                        MailTemplates.resetText(displayName, resetUrl),
                        MailTemplates.resetHtml(displayName, resetUrl),
                    )
                }
            }
        mail.send(msg)
    }

    override fun sendInvitation(
        to: String,
        roleLabel: String,
        acceptUrl: String,
    ) {
        val msg: MimeMessage =
            mail.createMimeMessage().also { mime ->
                MimeMessageHelper(mime, true, StandardCharsets.UTF_8.name()).apply {
                    setFrom(from)
                    setTo(to)
                    setSubject(MailTemplates.INVITATION_SUBJECT)
                    setText(
                        MailTemplates.invitationText(roleLabel, acceptUrl),
                        MailTemplates.invitationHtml(roleLabel, acceptUrl),
                    )
                }
            }
        mail.send(msg)
    }
}
