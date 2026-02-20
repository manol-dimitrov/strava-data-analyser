package com.endurocoach.strava

data class StravaConfig(
    val clientId: String,
    val clientSecret: String,
    val redirectUri: String,
    val scope: String = "read,activity:read_all"
) {
    companion object {
        fun fromEnv(): StravaConfig? {
            val clientId = System.getenv("STRAVA_CLIENT_ID") ?: return null
            val clientSecret = System.getenv("STRAVA_CLIENT_SECRET") ?: return null
            val redirectUri = System.getenv("STRAVA_REDIRECT_URI")
                ?: inferCodespaceRedirectUri()
                ?: "http://localhost:8080/api/strava/exchange"

            return StravaConfig(
                clientId = clientId,
                clientSecret = clientSecret,
                redirectUri = redirectUri
            )
        }

        /**
         * Auto-detect the redirect URI when running inside GitHub Codespaces.
         * Uses the CODESPACE_NAME env var to build the public forwarded URL.
         */
        private fun inferCodespaceRedirectUri(): String? {
            val codespaceName = System.getenv("CODESPACE_NAME") ?: return null
            return "https://${codespaceName}-8080.app.github.dev/api/strava/exchange"
        }
    }
}
