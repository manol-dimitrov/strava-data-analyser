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
            val redirectUri = System.getenv("STRAVA_REDIRECT_URI") ?: return null

            return StravaConfig(
                clientId = clientId,
                clientSecret = clientSecret,
                redirectUri = redirectUri
            )
        }
    }
}
