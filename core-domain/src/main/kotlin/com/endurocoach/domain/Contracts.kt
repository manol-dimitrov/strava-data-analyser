package com.endurocoach.domain

interface ActivityRepository {
    suspend fun getActivitiesLastDays(days: Int): List<Activity>
}

interface LlmStructuredClient {
    suspend fun generateWorkoutPlan(request: WorkoutRequest): WorkoutPlan
}

interface TokenStore {
    fun saveToken(key: String, tokenJson: String)
    fun loadToken(key: String): String?
}
