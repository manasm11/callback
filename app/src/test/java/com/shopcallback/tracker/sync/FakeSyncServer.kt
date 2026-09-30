package com.shopcallback.tracker.sync

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder

/**
 * In-process stand-in for server/callback_sync_server.py speaking the same JSON protocol.
 *
 * Built on a bare [ServerSocket] (rather than com.sun.net.httpserver) so it compiles against
 * android.jar, which is all the Android Gradle plugin puts on the unit-test Kotlin compile
 * classpath. Requests are handled one at a time on a single daemon thread, which is fine for
 * the sequential HTTP calls these tests make.
 */
class FakeSyncServer : AutoCloseable {
    @Volatile var serverId = "server-1"
    @Volatile var rejectUploads = false

    /** Stored events, each with "seq" and "deviceId" added. Synchronize on this list to read it. */
    val events = mutableListOf<JSONObject>()

    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val url: String get() = "http://127.0.0.1:${server.localPort}"

    private val acceptThread = Thread {
        while (true) {
            val socket = try {
                server.accept()
            } catch (e: SocketException) {
                break // server.close() was called
            }
            try {
                handle(socket)
            } catch (e: Exception) {
                // A malformed or aborted request; nothing a test helper needs to report.
            } finally {
                socket.close()
            }
        }
    }.apply {
        isDaemon = true
        start()
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

    private fun handle(socket: Socket) {
        val input = socket.getInputStream()
        val requestLine = readLine(input) ?: return
        val requestParts = requestLine.split(" ")
        if (requestParts.size < 2) return
        val method = requestParts[0]
        val target = requestParts[1]

        var contentLength = 0
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon >= 0 && line.substring(0, colon).trim().equals("Content-Length", ignoreCase = true)) {
                contentLength = line.substring(colon + 1).trim().toIntOrNull() ?: 0
            }
        }
        val body = if (contentLength > 0) readExact(input, contentLength) else ""

        val queryIndex = target.indexOf('?')
        val path = if (queryIndex >= 0) target.substring(0, queryIndex) else target
        val query = if (queryIndex >= 0) target.substring(queryIndex + 1) else ""

        when {
            path == "/health" -> respond(socket, 200, JSONObject().put("serverId", serverId))
            path == "/events" && method == "POST" -> handleUpload(socket, body)
            path == "/events" -> handlePull(socket, query)
            else -> respond(socket, 404, JSONObject().put("error", "not found"))
        }
    }

    private fun handleUpload(socket: Socket, body: String) {
        if (rejectUploads) return respond(socket, 400, JSONObject().put("error", "rejected"))
        val json = JSONObject(body)
        val deviceId = json.getString("deviceId")
        val incoming = json.getJSONArray("events")
        val accepted = (0 until incoming.length()).count { store(deviceId, incoming.getJSONObject(it)) }
        respond(socket, 200, JSONObject().put("serverId", serverId).put("accepted", accepted))
    }

    private fun handlePull(socket: Socket, query: String) {
        val params = query.split("&").filter { it.isNotEmpty() }.associate {
            it.substringBefore("=") to URLDecoder.decode(it.substringAfter("="), "UTF-8")
        }
        val after = params.getValue("after").toLong()
        val device = params.getValue("device")
        val (latest, page) = synchronized(events) {
            events.size.toLong() to events
                .filter { it.getLong("seq") > after && it.getString("deviceId") != device }
                .take(PAGE_SIZE)
        }
        respond(socket, 200, JSONObject().put("serverId", serverId).put("latestSeq", latest).put("events", JSONArray(page)))
    }

    /** Returns false if an event with the same eventId is already stored. */
    private fun store(deviceId: String, event: JSONObject): Boolean = synchronized(events) {
        if (events.any { it.getString("eventId") == event.getString("eventId") }) return false
        events.add(JSONObject(event.toString()).put("deviceId", deviceId).put("seq", events.size + 1L))
        true
    }

    private fun respond(socket: Socket, status: Int, json: JSONObject) {
        val bodyBytes = json.toString().toByteArray(Charsets.UTF_8)
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            else -> "Not Found"
        }
        val header = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${bodyBytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        val output = BufferedOutputStream(socket.getOutputStream())
        output.write(header.toByteArray(Charsets.UTF_8))
        output.write(bodyBytes)
        output.flush()
    }

    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (line.isEmpty()) null else line.toString()
            if (b == '\n'.code) {
                if (line.isNotEmpty() && line.last() == '\r') line.deleteCharAt(line.length - 1)
                return line.toString()
            }
            line.append(b.toChar())
        }
    }

    private fun readExact(input: InputStream, length: Int): String {
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(bytes, offset, length - offset)
            if (read == -1) break
            offset += read
        }
        return String(bytes, 0, offset, Charsets.UTF_8)
    }

    override fun close() {
        server.close()
        acceptThread.join(1000)
    }

    companion object {
        const val PAGE_SIZE = 500
    }
}
