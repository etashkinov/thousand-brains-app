package com.eta.tbp.web.monty

import kotlinx.browser.window
import kotlinx.serialization.json.Json
import org.w3c.fetch.RequestInit
import kotlin.js.json

/** `:server`'s default port (see `server/src/main/kotlin/com/eta/tbp/server/Main.kt`'s `DEFAULT_PORT`) — run it with `./gradlew :server:run`. */
private const val SERVER_BASE_URL = "http://localhost:8081"

fun fetchMemory(
    onResult: (MemorySnapshotDto) -> Unit,
    onError: (String) -> Unit,
) {
    window
        .fetch("$SERVER_BASE_URL/memory")
        .then { response ->
            response.text().then { text ->
                if (response.ok) onResult(Json.decodeFromString(text)) else onError(httpErrorMessage(response.status.toInt()))
            }
        }.catch { error -> onError(unreachableMessage(error)) }
}

fun postExperiment(
    request: ExperimentRequestDto,
    onResult: (ExperimentResultDto) -> Unit,
    onError: (String) -> Unit,
) {
    val body = Json.encodeToString(request)
    window
        .fetch(
            "$SERVER_BASE_URL/experiment",
            RequestInit(method = "POST", headers = json("Content-Type" to "application/json"), body = body),
        ).then { response ->
            response.text().then { text ->
                if (response.ok) onResult(Json.decodeFromString(text)) else onError(httpErrorMessage(response.status.toInt()))
            }
        }.catch { error -> onError(unreachableMessage(error)) }
}

fun resetMemory(
    onDone: () -> Unit,
    onError: (String) -> Unit,
) {
    window
        .fetch("$SERVER_BASE_URL/memory", RequestInit(method = "DELETE"))
        .then { response -> if (response.ok) onDone() else onError(httpErrorMessage(response.status.toInt())) }
        .catch { error -> onError(unreachableMessage(error)) }
}

private fun httpErrorMessage(status: Int) = "Monty server returned HTTP $status"

private fun unreachableMessage(error: Throwable) =
    "Couldn't reach the Monty server at $SERVER_BASE_URL — is `./gradlew :server:run` running? (${error.message})"
