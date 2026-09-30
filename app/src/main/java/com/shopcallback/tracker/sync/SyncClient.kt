package com.shopcallback.tracker.sync

import com.shopcallback.tracker.data.OutboxEventEntity
import com.shopcallback.tracker.data.RemoteEventEntity
import com.shopcallback.tracker.data.SyncEventType
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Talks to server/callback_sync_server.py. Blocking: call from a background dispatcher.
 * Throws IOException when the server is unreachable or answers with an unexpected status.
 */
class SyncClient(baseUrl: String) {
    private val base = baseUrl.trim().trimEnd('/')

    sealed interface UploadResult {
        data class Accepted(val serverId: String) : UploadResult
        /** The server said the batch is malformed (HTTP 400); retrying won't help. */
        data object Rejected : UploadResult
    }

    data class PulledEvent(val seq: Long, val event: RemoteEventEntity)
    data class Pull(val serverId: String, val latestSeq: Long, val events: List<PulledEvent>)

    fun health(): String = JSONObject(request("GET", "/health").body).getString("serverId")

    fun upload(deviceId: String, events: List<OutboxEventEntity>): UploadResult {
        val body = JSONObject()
            .put("deviceId", deviceId)
            .put("events", JSONArray(events.map { it.toJson() }))
        val response = request("POST", "/events", body.toString())
        if (response.code == HttpURLConnection.HTTP_BAD_REQUEST) return UploadResult.Rejected
        return UploadResult.Accepted(JSONObject(response.body).getString("serverId"))
    }

    fun pull(deviceId: String, after: Long): Pull {
        val json = JSONObject(request("GET", "/events?after=$after&device=${URLEncoder.encode(deviceId, "UTF-8")}").body)
        val events = json.getJSONArray("events")
        return Pull(
            serverId = json.getString("serverId"),
            latestSeq = json.getLong("latestSeq"),
            events = (0 until events.length()).map { events.getJSONObject(it).toPulledEvent() }
        )
    }

    private class Response(val code: Int, val body: String)

    private fun request(method: String, path: String, body: String? = null): Response {
        val connection = URL(base + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299 && code != HttpURLConnection.HTTP_BAD_REQUEST) {
                throw IOException("HTTP $code from $method $path")
            }
            return Response(code, text)
        } finally {
            connection.disconnect()
        }
    }

    private fun OutboxEventEntity.toJson(): JSONObject = JSONObject()
        .put("eventId", eventId)
        .put("type", type.name)
        .put("number", number)
        .put("timestamp", timestamp)
        .apply {
            durationSeconds?.let { put("durationSeconds", it) }
            direction?.let { put("direction", it) }
        }

    private fun JSONObject.toPulledEvent() = PulledEvent(
        seq = getLong("seq"),
        event = RemoteEventEntity(
            eventId = getString("eventId"),
            type = SyncEventType.valueOf(getString("type")),
            number = getString("number"),
            timestamp = getLong("timestamp"),
            durationSeconds = if (isNull("durationSeconds")) null else getInt("durationSeconds"),
            direction = if (isNull("direction")) null else getString("direction")
        )
    )

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 5_000
        const val READ_TIMEOUT_MILLIS = 10_000
    }
}
