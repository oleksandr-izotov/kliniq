package com.kliniq.infra.mail

/**
 * Outbound email port. The runtime impl uses Spring's [JavaMailSender]
 * pointed at Mailpit in dev / Resend in prod (Sprint 0 deferred). Tests
 * substitute a recording fake.
 */
interface EmailSender {
    fun sendEmailVerification(
        to: String,
        displayName: String,
        verifyUrl: String,
    )

    fun sendPasswordReset(
        to: String,
        displayName: String,
        resetUrl: String,
    )

    /**
     * Notifies the recipient of an admin-issued invitation. The link
     * lands them on the public accept page where they pick a password
     * and display name; role / surgeon flag are pre-set from the
     * invitation row.
     */
    fun sendInvitation(
        to: String,
        roleLabel: String,
        acceptUrl: String,
    )
}
