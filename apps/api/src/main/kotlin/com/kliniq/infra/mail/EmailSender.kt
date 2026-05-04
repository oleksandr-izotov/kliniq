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
}
