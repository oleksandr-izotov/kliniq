package com.kliniq.infra.mail

/**
 * Shared text + HTML templates for the three transactional emails Kliniq
 * sends. Both [SmtpEmailSender] (used in dev with Mailpit) and
 * [ResendApiEmailSender] (used in prod where Hetzner blocks outbound
 * SMTP) render through the same strings — keeps copy edits one-shot.
 */
internal object MailTemplates {
    const val VERIFY_SUBJECT = "Verify your Kliniq account"
    const val RESET_SUBJECT = "Reset your Kliniq password"
    const val INVITATION_SUBJECT = "You're invited to Kliniq"

    fun verifyText(
        name: String,
        url: String,
    ): String =
        """
        Hi $name,

        Welcome to Kliniq. Please verify your email by visiting:
        $url

        This link expires in 24 hours. If you didn't sign up, ignore this email.

        — Kliniq
        """.trimIndent()

    fun verifyHtml(
        name: String,
        url: String,
    ): String =
        """
        <p>Hi $name,</p>
        <p>Welcome to Kliniq. Please verify your email by clicking the button below:</p>
        <p><a href="$url" style="background:#10b981;color:#fff;padding:12px 24px;text-decoration:none;border-radius:6px;display:inline-block;">Verify email</a></p>
        <p>Or paste this link into your browser: <br><a href="$url">$url</a></p>
        <p style="color:#64748b;font-size:13px;">This link expires in 24 hours. If you didn't sign up, ignore this email.</p>
        <p style="color:#64748b;font-size:13px;">— Kliniq</p>
        """.trimIndent()

    fun resetText(
        name: String,
        url: String,
    ): String =
        """
        Hi $name,

        Someone (hopefully you) requested a password reset for your Kliniq account.
        To set a new password, visit:
        $url

        This link expires in 15 minutes. If you didn't ask for this, ignore the email
        — your password stays unchanged.

        — Kliniq
        """.trimIndent()

    fun resetHtml(
        name: String,
        url: String,
    ): String =
        """
        <p>Hi $name,</p>
        <p>Someone (hopefully you) requested a password reset for your Kliniq account.</p>
        <p><a href="$url" style="background:#10b981;color:#fff;padding:12px 24px;text-decoration:none;border-radius:6px;display:inline-block;">Reset password</a></p>
        <p>Or paste this link into your browser: <br><a href="$url">$url</a></p>
        <p style="color:#64748b;font-size:13px;">This link expires in 15 minutes. If you didn't ask for this, ignore the email — your password stays unchanged.</p>
        <p style="color:#64748b;font-size:13px;">— Kliniq</p>
        """.trimIndent()

    fun invitationText(
        roleLabel: String,
        url: String,
    ): String =
        """
        Hi,

        An admin has invited you to join Kliniq as a $roleLabel.
        To accept and pick a password, visit:
        $url

        This link expires in 7 days. If you weren't expecting this invitation,
        ignore this email — no account is created until you click the link.

        — Kliniq
        """.trimIndent()

    fun invitationHtml(
        roleLabel: String,
        url: String,
    ): String =
        """
        <p>Hi,</p>
        <p>An admin has invited you to join Kliniq as a <strong>$roleLabel</strong>.</p>
        <p><a href="$url" style="background:#10b981;color:#fff;padding:12px 24px;text-decoration:none;border-radius:6px;display:inline-block;">Accept invitation</a></p>
        <p>Or paste this link into your browser: <br><a href="$url">$url</a></p>
        <p style="color:#64748b;font-size:13px;">This link expires in 7 days. If you weren't expecting this invitation, ignore this email — no account is created until you click the link.</p>
        <p style="color:#64748b;font-size:13px;">— Kliniq</p>
        """.trimIndent()
}
