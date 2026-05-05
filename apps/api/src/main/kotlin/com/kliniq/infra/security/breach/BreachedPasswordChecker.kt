package com.kliniq.infra.security.breach

/**
 * Strategy for deciding whether a candidate password has appeared in a
 * known credential leak. Implementations MUST tolerate transient outages
 * — a public breach service is not in our trust boundary, and losing
 * registrations because Have I Been Pwned is down is worse UX than
 * letting a few breached passwords through (per OWASP ASVS V2.1.7
 * guidance, fail-open with monitoring is the conventional posture).
 */
interface BreachedPasswordChecker {
    fun isBreached(password: String): Boolean
}
