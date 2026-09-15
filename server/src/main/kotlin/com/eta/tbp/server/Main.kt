package com.eta.tbp.server

import com.eta.tbp.lib.lm.ExperimentMode
import com.eta.tbp.lib.memory.GraphObjectModel
import com.eta.tbp.lib.sensor.FloatLocation
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress

private const val DEFAULT_PORT = 8081

fun main(args: Array<String>) {
    val port = args.firstOrNull()?.toIntOrNull() ?: DEFAULT_PORT
    val service = MontyService()
    val server = HttpServer.create(InetSocketAddress(port), 0)

    server.createContext("/memory") { exchange -> handleMemory(exchange, service) }
    server.createContext("/experiment") { exchange -> handleExperiment(exchange, service) }
    server.executor = null
    server.start()
    println("Monty server listening on http://localhost:$port")
}

private fun handleMemory(
    exchange: HttpExchange,
    service: MontyService,
) = withCors(exchange) {
    when (exchange.requestMethod) {
        "GET" -> respond(exchange, 200, encodeMemory(service.memorySnapshot()))
        "DELETE" -> {
            service.reset()
            respond(exchange, 204, null)
        }
        else -> respond(exchange, 405, null)
    }
}

private fun handleExperiment(
    exchange: HttpExchange,
    service: MontyService,
) = withCors(exchange) {
    if (exchange.requestMethod != "POST") {
        respond(exchange, 405, null)
        return@withCors
    }
    val body = exchange.requestBody.readBytes().decodeToString()
    val request = decodeExperimentRequest(JSONObject(body))
    val result = service.runExperiment(request)
    respond(exchange, 200, encodeExperimentResult(result))
}

/** Every route is fetched cross-origin from the web app's own dev-server port, so every response (including the OPTIONS preflight) needs these headers — this is a local dev tool, so `*` rather than pinning one allowed origin. */
private fun withCors(
    exchange: HttpExchange,
    handle: () -> Unit,
) {
    exchange.responseHeaders.add("Access-Control-Allow-Origin", "*")
    exchange.responseHeaders.add("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")
    exchange.responseHeaders.add("Access-Control-Allow-Headers", "Content-Type")
    if (exchange.requestMethod == "OPTIONS") {
        exchange.sendResponseHeaders(204, -1)
        exchange.close()
        return
    }
    try {
        handle()
    } finally {
        exchange.close()
    }
}

private fun respond(
    exchange: HttpExchange,
    status: Int,
    jsonBody: String?,
) {
    if (jsonBody == null) {
        exchange.sendResponseHeaders(status, -1)
        return
    }
    val bytes = jsonBody.encodeToByteArray()
    exchange.responseHeaders.add("Content-Type", "application/json")
    exchange.sendResponseHeaders(status, bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
}

private fun decodeExperimentRequest(json: JSONObject): ExperimentRequest {
    val landmarksJson = json.getJSONArray("landmarks")
    val landmarks =
        (0 until landmarksJson.length()).map { i ->
            val entry = landmarksJson.getJSONObject(i)
            LandmarkInput(row = entry.getInt("row"), col = entry.getInt("col"), label = entry.getString("label"))
        }
    return ExperimentRequest(
        mode = if (json.getString("mode") == "TRAIN") ExperimentMode.TRAIN else ExperimentMode.EVALUATE,
        cityName = json.getString("cityName"),
        citySize = json.getInt("citySize"),
        landmarks = landmarks,
        startRow = json.optIntOrNull("startRow"),
        startCol = json.optIntOrNull("startCol"),
    )
}

/** `null` when [key] is absent/JSON `null`, rather than `org.json`'s own `optInt`, which folds a missing key into `0` — indistinguishable here from an explicit start at row/col 0. */
private fun JSONObject.optIntOrNull(key: String): Int? = if (has(key) && !isNull(key)) getInt(key) else null

private fun encodeMemory(snapshot: Map<String, List<GraphObjectModel>>): String {
    val objects = JSONArray()
    snapshot.forEach { (label, exemplars) ->
        val exemplarsJson = JSONArray()
        exemplars.forEach { exemplar ->
            val nodesJson = JSONArray()
            exemplar.nodes.forEach { node ->
                val location = node.location as? FloatLocation
                nodesJson.put(
                    JSONObject()
                        .put("row", location?.location?.getOrNull(0) ?: 0f)
                        .put("col", location?.location?.getOrNull(1) ?: 0f)
                        .put("label", node.feature.label),
                )
            }
            exemplarsJson.put(JSONObject().put("nodes", nodesJson))
        }
        objects.put(JSONObject().put("label", label).put("exemplars", exemplarsJson))
    }
    return JSONObject().put("objects", objects).toString()
}

private fun encodeExperimentResult(result: ExperimentResult): String {
    val visitedCellsJson = JSONArray()
    result.visitedCells.forEach { cell ->
        visitedCellsJson.put(
            JSONObject().put("row", cell.row).put("col", cell.col).put("step", cell.step).put("state", cell.state),
        )
    }
    val decisionsJson = JSONArray()
    result.decisions.forEach { decision ->
        decisionsJson.put(
            JSONObject()
                .put("step", decision.step)
                .putOpt("row", decision.row)
                .putOpt("col", decision.col)
                .put("message", decision.message)
                .put("state", decision.state),
        )
    }
    return JSONObject()
        .put("outcome", result.outcome)
        .putOpt("label", result.label)
        .putOpt("confidence", result.confidence)
        .put("locationsVisited", result.locationsVisited)
        .put("visitedCells", visitedCellsJson)
        .put("decisions", decisionsJson)
        .toString()
}
