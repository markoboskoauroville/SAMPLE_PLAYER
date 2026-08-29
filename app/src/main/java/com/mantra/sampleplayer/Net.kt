package com.mantra.sampleplayer

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * EVERY REQUEST THIS APP MAKES GOES THROUGH HERE, AND THE REASON IS ONE LINE OF IT.
 *
 * `api.hume.ai` sits behind Cloudflare. A request with no User-Agent returns `403, error code:
 * 1010` — measured across 21 account pairs, all 21 failing without one and all 21 succeeding with
 * any string at all. That reads exactly like an entire dead account list and has nothing to do with
 * the credentials. Setting it in one place is the only way it cannot be forgotten in another.
 *
 * A descriptive name, not a browser's. Impersonating Chrome to get past a bot filter is lying to
 * somebody who asked a reasonable question about who was calling.
 */
object Net {

    const val UA = "MantraSamplePlayer/1.0"

    private const val CONNECT_MS = 20_000
    private const val READ_MS = 60_000

    /** Code, body, and headers lowercased. Code -1 means the request never left. */
    data class Reply(val code: Int, val body: String, val headers: Map<String, String>) {
        val status: Status get() = Classify.status(code, body)
        val ok: Boolean get() = code in 200..299
    }

    fun get(url: String, headers: Map<String, String> = emptyMap()): Reply =
        request("GET", url, headers, null)

    fun postJson(url: String, headers: Map<String, String>, json: String): Reply =
        request("POST", url, headers + ("Content-Type" to "application/json"), json.toByteArray())

    fun postForm(url: String, headers: Map<String, String>, form: String): Reply =
        request(
            "POST",
            url,
            headers + ("Content-Type" to "application/x-www-form-urlencoded"),
            form.toByteArray(),
        )

    fun postBytes(url: String, headers: Map<String, String>, body: ByteArray): Reply =
        request("POST", url, headers, body)

    /** Fetch raw bytes, for a preview clip or a generated file. */
    fun bytes(url: String, headers: Map<String, String> = emptyMap()): ByteArray? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_MS
                readTimeout = READ_MS
                setRequestProperty("User-Agent", UA)
                for ((k, v) in headers) setRequestProperty(k, v)
            }
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.use { it.readBytes() }
        } catch (e: Exception) {
            null
        }
    }

    private fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?,
    ): Reply {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_MS
                readTimeout = READ_MS
                setRequestProperty("User-Agent", UA)
                for ((k, v) in headers) setRequestProperty(k, v)
                if (body != null) {
                    doOutput = true
                    setFixedLengthStreamingMode(body.size)
                }
            }
            if (body != null) conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            val hdrs = LinkedHashMap<String, String>()
            for ((k, v) in conn.headerFields) {
                if (k != null && v != null) hdrs[k.lowercase()] = v.joinToString(",")
            }
            Reply(code, text, hdrs)
        } catch (e: Exception) {
            // The message, never the URL with its query string, and never a header.
            Reply(-1, e.javaClass.simpleName, emptyMap())
        }
    }

    /** A very small JSON string reader, so the app carries no JSON library for four fields. */
    fun str(json: String, key: String): String? {
        val i = json.indexOf("\"$key\"")
        if (i < 0) return null
        var j = json.indexOf(':', i)
        if (j < 0) return null
        j++
        while (j < json.length && json[j].isWhitespace()) j++
        if (j >= json.length || json[j] != '"') return null
        val out = ByteArrayOutputStream()
        var k = j + 1
        while (k < json.length) {
            val c = json[k]
            if (c == '\\' && k + 1 < json.length) {
                when (val e = json[k + 1]) {
                    'n' -> out.write('\n'.code)
                    't' -> out.write('\t'.code)
                    'r' -> out.write('\r'.code)
                    'u' -> {
                        if (k + 5 < json.length) {
                            val cp = json.substring(k + 2, k + 6).toIntOrNull(16)
                            if (cp != null) out.write(cp.toChar().toString().toByteArray())
                        }
                        k += 4
                    }
                    else -> out.write(e.code)
                }
                k += 2
                continue
            }
            if (c == '"') break
            out.write(c.toString().toByteArray())
            k++
        }
        return out.toString("UTF-8")
    }
}
