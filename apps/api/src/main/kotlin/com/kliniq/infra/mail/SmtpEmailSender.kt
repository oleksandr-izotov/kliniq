package com.kliniq.infra.mail

import jakarta.mail.internet.MimeMessage
import org.springframework.beans.factory.annotation.Value
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets

/**
 * Sends mail through Spring's [JavaMailSender]. In dev that's pointed at
 * Mailpit (localhost:1025) so messages stay in the UI at :8025 instead of
 * leaving the developer's machine. Templates are inline strings — Sprint 1
 * keeps it light; richer templating (Thymeleaf, MJML) lands in V2.
 */
@Component
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
                    setSubject("Verify your Kliniq account")
                    setText(plainTextVerify(displayName, verifyUrl), htmlVerify(displayName, verifyUrl))
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
                    setSubject("Reset your Kliniq password")
                    setText(plainTextReset(displayName, resetUrl), htmlReset(displayName, resetUrl))
                }
            }
        mail.send(msg)
    }

    private fun plainTextVerify(
        name: String,
        url: String,
    ) = """
        Hi $name,

        Welcome to Kliniq. Please verify your email by visiting:
        $url

        This link expires in 24 hours. If you didn't sign up, ignore this email.

        — Kliniq
        """.trimIndent()

    private fun htmlVerify(
        name: String,
        url: String,
    ) = """
        <p>Hi $name,</p>
        <p>Welcome to Kliniq. Please verify your email by clicking the button below:</p>
        <p><a href="$url" style="background:#10b981;color:#fff;padding:12px 24px;text-decoration:none;border-radius:6px;display:inline-block;">Verify email</a></p>
        <p>Or paste this link into your browser: <br><a href="$url">$url</a></p>
        <p style="color:#64748b;font-size:13px;">This link expires in 24 hours. If you didn't sign up, ignore this email.</p>
        <p style="color:#64748b;font-size:13px;">— Kliniq</p>
        """.trimIndent()

    private fun plainTextReset(
        name: String,
        url: String,
    ) = """
        Hi $name,

        Someone (hopefully you) requested a password reset for your Kliniq account.
        To set a new password, visit:
        $url

        This link expires in 15 minutes. If you didn't ask for this, ignore the email
        — your password stays unchanged.

        — Kliniq
        """.trimIndent()

    private fun htmlReset(
        name: String,
        url: String,
    ) = """
        <p>Hi $name,</p>
        <p>Someone (hopefully you) requested a password reset for your Kliniq account.</p>
        <p><a href="$url" style="background:#10b981;color:#fff;padding:12px 24px;text-decoration:none;border-radius:6px;display:inline-block;">Reset password</a></p>
        <p>Or paste this link into your browser: <br><a href="$url">$url</a></p>
        <p style="color:#64748b;font-size:13px;">This link expires in 15 minutes. If you didn't ask for this, ignore the email — your password stays unchanged.</p>
        <p style="color:#64748b;font-size:13px;">— Kliniq</p>
        """.trimIndent()
}
