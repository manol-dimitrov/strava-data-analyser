package com.endurocoach.strava

import com.endurocoach.domain.Activity
import com.endurocoach.domain.ActivityRepository
import java.time.Duration
import java.time.Instant

class CachedActivityRepository(
    private val delegate: ActivityRepository,
    private val ttl: Duration = Duration.ofHours(1)
) : ActivityRepository {
    private var cache: CacheEntry? = null

    override suspend fun getActivitiesLastDays(days: Int): List<Activity> {
        val now = Instant.now()
        val current = cache

        if (current != null && current.days == days && Duration.between(current.cachedAt, now) < ttl) {
            return current.activities
        }

        val fetched = delegate.getActivitiesLastDays(days)
        cache = CacheEntry(days = days, activities = fetched, cachedAt = now)
        return fetched
    }

    private data class CacheEntry(
        val days: Int,
        val activities: List<Activity>,
        val cachedAt: Instant
    )
}
