package com.endurocoach.routes

import com.endurocoach.domain.Activity
import com.endurocoach.domain.LoadSnapshot
import com.endurocoach.session.SessionRegistry
import com.endurocoach.strava.CachedActivityRepository
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.json.*

private const val MCP_PROTOCOL_VERSION = "2024-11-05"
private const val SERVER_NAME = "maestro-enduro-coach"
private const val SERVER_VERSION = "1.0"

/**
 * Dependencies required by the MCP server routes.
 *
 * @param sessionRegistry Used to resolve athlete sessions by ID.
 * @param loadProvider Computes training-load metrics for a given repository / HR profile / window.
 *   Parameters: activityRepository, days, maxHr, restingHr.
 */
data class McpDependencies(
    val sessionRegistry: SessionRegistry,
    val loadProvider: suspend (CachedActivityRepository?, Int, Int, Int) -> Triple<String, LoadSnapshot, List<Activity>>
)

/**
 * Registers the MCP (Model Context Protocol) endpoint at POST /mcp.
 *
 * Implements the MCP Streamable-HTTP transport (protocol version 2024-11-05).
 * Notifications (requests without an `id` field) receive 202 Accepted with no body.
 * All other requests receive a JSON-RPC 2.0 response.
 *
 * Exposed tools:
 *   - get_training_load  — CTL, ATL, TSB, ACWR, monotony, ramp rate + time-series
 *   - get_athlete_profile — sport focus, target event, HR zones, onboarding status
 *   - get_workout_plan   — latest AI-generated workout prescription + coach reasoning
 *
 * Each tool accepts an optional `sessionId` argument (value of the `enduro_session` cookie).
 * When omitted the server creates a temporary session backed by demo activity data.
 */
fun Route.installMcpRoutes(deps: McpDependencies) {
    post("/mcp") {
        val body = call.receiveText()
        val request = try {
            Json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            call.respondText(jsonRpcError(null, -32700, "Parse error"), ContentType.Application.Json)
            return@post
        }

        // JSON-RPC notifications have no "id" — acknowledge without responding
        if (!request.containsKey("id")) {
            call.respond(HttpStatusCode.Accepted)
            return@post
        }

        val id = request["id"]
        val method = request["method"]?.jsonPrimitive?.contentOrNull
        if (method == null) {
            call.respondText(jsonRpcError(id, -32600, "Invalid Request"), ContentType.Application.Json)
            return@post
        }

        val params = request["params"]?.jsonObject ?: JsonObject(emptyMap())
        val response = when (method) {
            "initialize" -> handleInitialize(id)
            "tools/list" -> handleToolsList(id)
            "tools/call" -> handleToolsCall(id, params, deps)
            else -> jsonRpcError(id, -32601, "Method not found: $method")
        }
        call.respondText(response, ContentType.Application.Json)
    }
}

// ---------------------------------------------------------------------------
// Method handlers
// ---------------------------------------------------------------------------

private fun handleInitialize(id: JsonElement?): String =
    jsonRpcSuccess(
        id,
        buildJsonObject {
            put("protocolVersion", MCP_PROTOCOL_VERSION)
            put("capabilities", buildJsonObject { put("tools", JsonObject(emptyMap())) })
            put("serverInfo", buildJsonObject {
                put("name", SERVER_NAME)
                put("version", SERVER_VERSION)
            })
        }
    )

private fun handleToolsList(id: JsonElement?): String {
    val tools = buildJsonArray {
        add(
            toolDef(
                name = "get_training_load",
                description = "Retrieve training-load metrics computed from the athlete's activity history " +
                    "using the Banister impulse-response model: CTL (fitness), ATL (fatigue), TSB (form), " +
                    "ACWR, monotony, CTL ramp rate, recent volume, and a daily time-series.",
                properties = buildJsonObject {
                    put("sessionId", prop("string", "Athlete session ID (value of the enduro_session cookie). Omit for demo data."))
                    put("days", prop("integer", "Analysis window in days (7–120). Defaults to 45."))
                    put("maxHr", prop("integer", "Athlete max heart rate bpm (120–230). Defaults to 190."))
                    put("restingHr", prop("integer", "Athlete resting heart rate bpm (30–90). Defaults to 50."))
                }
            )
        )
        add(
            toolDef(
                name = "get_athlete_profile",
                description = "Retrieve the athlete's onboarding profile: sport focus, target event name " +
                    "and date, heart-rate profile (maxHr, restingHr), and whether onboarding is complete.",
                properties = buildJsonObject {
                    put("sessionId", prop("string", "Athlete session ID (value of the enduro_session cookie). Omit for demo/default profile."))
                }
            )
        )
        add(
            toolDef(
                name = "get_workout_plan",
                description = "Retrieve the most recently AI-generated workout plan for the athlete: " +
                    "session prescription, coach reasoning, generation timestamp, and data source. " +
                    "Returns null when no plan has been generated yet.",
                properties = buildJsonObject {
                    put("sessionId", prop("string", "Athlete session ID (value of the enduro_session cookie). Omit for demo session."))
                }
            )
        )
    }
    return jsonRpcSuccess(id, buildJsonObject { put("tools", tools) })
}

private suspend fun handleToolsCall(id: JsonElement?, params: JsonObject, deps: McpDependencies): String {
    val name = params["name"]?.jsonPrimitive?.contentOrNull
        ?: return jsonRpcError(id, -32602, "Missing required parameter: name")
    val arguments = params["arguments"]?.jsonObject ?: JsonObject(emptyMap())

    val sessionId = arguments["sessionId"]?.jsonPrimitive?.contentOrNull
    // getOrCreate(null) creates a temporary session backed by demo activity data
    val session = deps.sessionRegistry.getOrCreate(sessionId)

    val text = try {
        when (name) {
            "get_training_load" -> {
                val days = arguments["days"]?.jsonPrimitive?.intOrNull?.coerceIn(7, 120) ?: 45
                val maxHr = arguments["maxHr"]?.jsonPrimitive?.intOrNull?.coerceIn(120, 230) ?: 190
                val restingHr = arguments["restingHr"]?.jsonPrimitive?.intOrNull?.coerceIn(30, 90) ?: 50
                val (source, snapshot, _) = deps.loadProvider(session.activityRepository, days, maxHr, restingHr)
                buildJsonObject {
                    put("source", source)
                    put("date", snapshot.date.toString())
                    put("ctl", snapshot.ctl)
                    put("atl", snapshot.atl)
                    put("tsb", snapshot.tsb)
                    put("acwr", snapshot.acwr)
                    put("monotony", snapshot.monotony)
                    put("ctlRampRate", snapshot.ctlRampRate)
                    put("recentVolumeMinutes", snapshot.recentVolumeMinutes)
                    put("spike10", snapshot.spike10)
                    put("strain10", snapshot.strain10)

                    put("daysSinceLastHardSession", snapshot.daysSinceLastHardSession)
                    put("recentSessions", buildJsonArray {
                        snapshot.recentSessions.forEach { session ->
                            add(buildJsonObject {
                                put("date", session.date.toString())
                                put("name", session.name)
                                put("durationMinutes", session.durationMinutes)
                                put("trimp", session.trimp)
                                put("intensity", session.intensity)
                            })
                        }
                    })
                    put("series", buildJsonArray {
                        snapshot.series.forEach { pt ->
                            add(buildJsonObject {
                                put("date", pt.date.toString())
                                put("trimp", pt.trimp)
                                put("ctl", pt.ctl)
                                put("atl", pt.atl)
                                put("tsb", pt.tsb)
                            })
                        }
                    })
                }.toString()
            }

            "get_athlete_profile" -> {
                val state = session.dashboardState.read()
                buildJsonObject {
                    put("sportFocus", state.onboarding.sportFocus)
                    put("targetEventName", state.onboarding.targetEventName)
                    put("targetEventDate", state.onboarding.targetEventDate)
                    put("maxHr", state.maxHr)
                    put("restingHr", state.restingHr)
                    put("onboardingCompleted", state.onboarding.completed)
                }.toString()
            }

            "get_workout_plan" -> {
                val state = session.dashboardState.read()
                val workout = state.latestWorkout
                if (workout != null) {
                    buildJsonObject {
                        put("session", workout.session)
                        put("coachReasoning", workout.coachReasoning)
                        put("generatedAt", state.generatedAt?.toString())
                        put("source", state.source)
                    }.toString()
                } else {
                    buildJsonObject { put("workout", JsonNull) }.toString()
                }
            }

            else -> return jsonRpcError(id, -32602, "Unknown tool: $name")
        }
    } catch (e: Exception) {
        return jsonRpcSuccess(
            id,
            buildJsonObject {
                put("content", buildJsonArray {
                    add(buildJsonObject { put("type", "text"); put("text", "Error calling tool '$name': ${e.message ?: e::class.simpleName}") })
                })
                put("isError", true)
            }
        )
    }

    return jsonRpcSuccess(
        id,
        buildJsonObject {
            put("content", buildJsonArray {
                add(buildJsonObject { put("type", "text"); put("text", text) })
            })
        }
    )
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private fun toolDef(name: String, description: String, properties: JsonObject): JsonObject =
    buildJsonObject {
        put("name", name)
        put("description", description)
        put("inputSchema", buildJsonObject {
            put("type", "object")
            put("properties", properties)
        })
    }

private fun prop(type: String, description: String): JsonObject =
    buildJsonObject {
        put("type", type)
        put("description", description)
    }

private fun jsonRpcSuccess(id: JsonElement?, result: JsonObject): String =
    buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id ?: JsonNull)
        put("result", result)
    }.toString()

private fun jsonRpcError(id: JsonElement?, code: Int, message: String): String =
    buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id ?: JsonNull)
        put("error", buildJsonObject {
            put("code", code)
            put("message", message)
        })
    }.toString()
