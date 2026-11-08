package cn.jlu.schedule.update.security

object UpdateSecurityConfig {
    const val EXPECTED_PACKAGE_NAME = "cn.jlu.schedule"

    /**
     * SHA-256 fingerprint of the official v1.2 release signing certificate.
     */
    const val OFFICIAL_SIGNER_SHA256 = "7ed7c719a6abb18d1eb014a18c173532fd4f11fb4bd350f003245549135dd6dc"

    /**
     * Map of keyId to Base64-encoded RSA SubjectPublicKeyInfo (X.509 DER).
     */
    val TRUSTED_PUBLIC_KEYS = mapOf(
        "main-2026" to "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAs4DRtdfr41/P4Dhiewy2YskgzmtKWYyeWukANtm8oIfQMd8uI3qt2YGnva8oXxwK4abA7ua+/xUx8+kw1+hGNdbOFQXXnZwr7N2KksjzBYKNk8/cXYYv59eSShCezCZyguz/Bw9BeGCeIqvc2t1NqIMMCbGTEuqK0RJLqfAxkJlRMaJwNvPY1PhhWbJodx5V+xQCgw1zdKNeK6o749J3aFjcWf2LfJ8bhbuzV7royKzIUuOkIiM5IA3bGQuDKZxx0q6ehkesgcsf/5xmNwdsBVH0oBW52AaGMGSA9t0wE3kRsul4Bgi2PidWzL9M56xhoxJuGQHUWwhswwR83oYFDQIDAQAB"
    )

    const val DOMESTIC_ENDPOINT = "https://jfyuhong.top/updates/android/stable.json"
    const val OVERSEAS_ENDPOINT = "https://github.com/JFyuhong/JLU_schedule/releases/latest/download/stable.json"

    val DEFAULT_ENDPOINTS = listOf(
        DOMESTIC_ENDPOINT,
        OVERSEAS_ENDPOINT
    )
}
