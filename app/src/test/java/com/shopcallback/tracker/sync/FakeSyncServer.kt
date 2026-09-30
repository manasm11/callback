package com.shopcallback.tracker.sync

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.URLDecoder

/** In-process stand-in for server/callback_sync_server.py speaking the same JSON protocol. */
class FakeSyncServer : AutoCloseable {
    @Volatile var serverId = "server-1"
    @Volatile var rejectUploads = false

    /** Stored events, each with "seq" and "deviceId" added. Synchronize on this list to read it. */
    val events = mutableListOf<JSONObject>()

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val url: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/health") { exchange -> respond(exchange, 200, JSONObject().put("serverId", serverId)) }
        server.createContext("/events") { exchange ->
            if (exchange.requestMethod == "POST") handleUpload(exchange) else handlePull(exchange)
        }
        server.start()
    }

    fun addFromOtherPhone(
        eventId: String,
        type: String,
        number: String,
        timestamp: Long,
        durationSeconds: Int? = null,
        direction: String? = null
    ) {
        store(
            "other-phone",
            JSONObject()
                .put("eventId", eventId)
                .put("type", type)
                .put("number", number)
                .put("timestamp", timestamp)
                .put("durationSeconds", durationSeconds ?: JSONObject.NULL)
                .put("direction", direction ?: JSONObject.NULL)
        )
    }

    private fun handleUpload(exchange: HttpExchange) {
        if (rejectUploads) return respond(exchange, 400, JSONObject().put("error", "rejected"))
        val body = JSONObject(exchange.requestBody.bufferedReader().readText())
        val deviceId = body.getString("deviceId")
        val incoming = body.getJSONArray("events")
        val accepted = (0 until incoming.length()).count { store(deviceId, incoming.getJSONObject(it)) }
        respond(exchange, 200, JSONObject().put("serverId", serverId).put("accepted", accepted))
    }

    private fun handlePull(exchange: HttpExchange) {
        val params = exchange.requestURI.rawQuery.split("&").associate {
            it.substringBefore("=") to URLDecoder.decode(it.substringAfter("="), "UTF-8")
        }
        val after = params.getValue("after").toLong()
        val device = params.getValue("device")
        val (latest, page) = synchronized(events) {
            events.size.toLong() to events
                .filter { it.getLong("seq") > after && it.getString("deviceId") != device }
                .take(PAGE_SIZE)
        }
        respond(exchange, 200, JSONObject().put("serverId", serverId).put("latestSeq", latest).put("events", JSONArray(page)))
    }

    /** Returns false if an event with the same eventId is already stored. */
    private fun store(deviceId: String, event: JSONObject): Boolean = synchronized(events) {
        if (events.any { it.getString("eventId") == event.getString("eventId") }) return false
        events.add(JSONObject(event.toString()).put("deviceId", deviceId).put("seq", events.size + 1L))
        true
    }

    private fun respond(exchange: HttpExchange, status: Int, json: JSONObject) {
        val bytes = json.toString().toByteArray()
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    override fun close() = server.stop(0)

    companion object {
        const val PAGE_SIZE = 500
    }
}
