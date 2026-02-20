package com.endurocoach.strava

import com.endurocoach.domain.Activity
import com.endurocoach.domain.ActivityRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.DefaultJson
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class StravaActivityRepository(
    private val authService: StravaOAuthService,
    private val httpClient: HttpClient
) : ActivityRepository {
    private val json: Json = DefaultJson

    override suspend fun getActivitiesLastDays(days: Int): List<Activity> {
        val safeDays = days.coerceAtLeast(1)
        val afterEpoch = LocalDate.now()
            .minusDays((safeDays - 1).toLong())
            .atStartOfDay()
            .toEpochSecond(ZoneOffset.UTC)

        val token = authService.validAccessToken()
        val activities = httpClient.get("https://www.strava.com/api/v3/athlete/activities") {
            url {
                parameters.append("after", afterEpoch.toString())
                parameters.append("per_page", "200")
                parameters.append("page", "1")
            }
            header(HttpHeaders.Authorization, "Bearer $token")
        }.body<List<StravaActivityDto>>()

        return activities
            .filter { CARDIO_ACTIVITY_TYPES.contains(it.type.lowercase()) }
            .map {
                Activity(
                    date = Instant.parse(it.startDate).atZone(ZoneOffset.UTC).toLocalDate(),
                    durationMinutes = it.elapsedTimeSeconds.toDouble() / 60.0,
                    avgHeartRate = it.averageHeartRate?.toInt()
                )
            }
    }

    companion object {
        /** Cardio activity types included in training load calculations.
         *  TRIMP is HR-based so all types normalise correctly via BanisterTrimpCalculator. */
        private val CARDIO_ACTIVITY_TYPES = setOf(
            "run", "trailrun", "virtualrun",
            "ride", "virtualride", "ebikeride",
            "hike", "walk",
            "swim", "openwatersports",
            "nordicski", "rowing"
        )
    }
}
