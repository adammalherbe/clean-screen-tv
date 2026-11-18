package dk.adam.tv

import android.annotation.SuppressLint
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/** What the Chromecast is doing: the running app, and media details when it reports them. */
class CastStatus(
    val app: String,
    val title: String?,
    val subtitle: String?,
    /** PLAYING, PAUSED, BUFFERING, or null when the app reports no media. */
    val state: String?,
    val imageUrl: String?,
) {
    val playing get() = state == "PLAYING" || state == "BUFFERING"
}

/**
 * A read-only Cast v2 client: TLS to port 8009, length-prefixed CastMessage protobufs
 * with JSON payloads. Asks the receiver what's running, then that app for media status.
 * No Google Play Services.
 */
object CastClient {
    private const val NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
    private const val NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
    private const val NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
    private const val NS_MEDIA = "urn:x-cast:com.google.cast.media"
    private const val SENDER = "sender-adamtv"
    private const val RECEIVER = "receiver-0"

    /** Null when the Chromecast is idle. Throws IOException when it can't be reached. */
    fun status(ip: String): CastStatus? {
        tlsSocket(ip).use { socket ->
            val input = DataInputStream(socket.inputStream)
            val out = socket.outputStream
            fun send(dest: String, ns: String, json: String) = out.write(frame(dest, ns, json)).also { out.flush() }

            fun await(type: String, requestId: Int): JSONObject {
                while (true) {
                    val (ns, payload) = read(input)
                    val json = JSONObject(payload)
                    if (ns == NS_HEARTBEAT && json.optString("type") == "PING") {
                        send(RECEIVER, NS_HEARTBEAT, """{"type":"PONG"}""")
                        continue
                    }
                    if (json.optString("type") == type && json.optInt("requestId") == requestId) return json
                }
            }

            send(RECEIVER, NS_CONNECTION, """{"type":"CONNECT"}""")
            send(RECEIVER, NS_RECEIVER, """{"type":"GET_STATUS","requestId":1}""")
            val apps = await("RECEIVER_STATUS", 1).getJSONObject("status").optJSONArray("applications")
                ?: return null
            val app = (0 until apps.length()).map { apps.getJSONObject(it) }
                .firstOrNull { !it.optBoolean("isIdleScreen", false) } ?: return null
            val name = app.optString("displayName", "Chromecast")
            val namespaces = app.optJSONArray("namespaces")
            val hasMedia = namespaces != null &&
                (0 until namespaces.length()).any { namespaces.getJSONObject(it).optString("name") == NS_MEDIA }
            val transport = app.optString("transportId")
            if (!hasMedia || transport.isEmpty()) return CastStatus(name, null, null, null, null)

            send(transport, NS_CONNECTION, """{"type":"CONNECT"}""")
            send(transport, NS_MEDIA, """{"type":"GET_STATUS","requestId":2}""")
            val media = await("MEDIA_STATUS", 2).optJSONArray("status")?.optJSONObject(0)
                ?: return CastStatus(name, null, null, null, null)
            val meta = media.optJSONObject("media")?.optJSONObject("metadata")
            fun meta(key: String) = meta?.optString(key)?.takeIf { it.isNotBlank() }
            val image = meta?.optJSONArray("images")?.optJSONObject(0)?.optString("url")?.takeIf { it.isNotBlank() }
            return CastStatus(
                app = name,
                title = meta("title"),
                subtitle = meta("subtitle") ?: meta("seriesTitle") ?: meta("artist") ?: meta("albumName"),
                state = media.optString("playerState").takeIf { it.isNotBlank() && it != "IDLE" },
                imageUrl = image,
            )
        }
    }

    // The Chromecast presents a self-signed device certificate. We only read status on the LAN.
    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private object TrustAll : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private fun tlsSocket(ip: String): SSLSocket {
        val raw = Socket()
        raw.connect(InetSocketAddress(ip, 8009), 3000)
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(TrustAll), SecureRandom()) }
        return (ctx.socketFactory.createSocket(raw, ip, 8009, true) as SSLSocket).apply {
            soTimeout = 4000
            startHandshake()
        }
    }

    /** CastMessage: version, source, destination, namespace, payload type STRING, payload. */
    private fun frame(dest: String, ns: String, json: String): ByteArray {
        val body = ByteArrayOutputStream()
        fun varint(v: Int) {
            var n = v
            while (n and 0x7f.inv() != 0) {
                body.write((n and 0x7f) or 0x80)
                n = n ushr 7
            }
            body.write(n)
        }
        fun string(tag: Int, s: String) {
            val b = s.toByteArray()
            varint(tag shl 3 or 2)
            varint(b.size)
            body.write(b)
        }
        varint(1 shl 3); varint(0)
        string(2, SENDER)
        string(3, dest)
        string(4, ns)
        varint(5 shl 3); varint(0)
        string(6, json)
        val bytes = body.toByteArray()
        val n = bytes.size
        return byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()) + bytes
    }

    /** Returns (namespace, payload) from the next message. */
    private fun read(input: DataInputStream): Pair<String, String> {
        val length = input.readInt()
        if (length <= 0 || length > 1 shl 20) throw IOException("bad cast frame length $length")
        val b = ByteArray(length)
        input.readFully(b)
        var i = 0
        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val c = b[i++].toInt() and 0xff
                result = result or ((c and 0x7f).toLong() shl shift)
                if (c and 0x80 == 0) return result
                shift += 7
            }
        }
        var ns = ""
        var payload = "{}"
        while (i < b.size) {
            val key = varint().toInt()
            when (key and 7) {
                0 -> varint()
                2 -> {
                    val len = varint().toInt()
                    val s = String(b, i, len)
                    i += len
                    when (key ushr 3) {
                        4 -> ns = s
                        6 -> payload = s
                    }
                }
                else -> throw IOException("unexpected wire type")
            }
        }
        return ns to payload
    }
}

/** Finds a Chromecast on the LAN over mDNS (_googlecast._tcp) with the platform's NsdManager. */
class CastFinder(context: Context, private val onFound: (String) -> Unit) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())
    private var listener: NsdManager.DiscoveryListener? = null
    // NsdManager resolves one service at a time on Android 7, so found services queue up.
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    fun find() {
        if (listener != null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                main.post {
                    queue.addLast(info)
                    resolveNext()
                }
            }

            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, error: Int) {
                listener = null
            }

            override fun onStopDiscoveryFailed(type: String, error: Int) {}
        }
        listener = l
        nsd.discoverServices("_googlecast._tcp", NsdManager.PROTOCOL_DNS_SD, l)
        main.postDelayed({ stop() }, 30_000)
    }

    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        queue.clear()
    }

    private fun resolveNext() {
        if (resolving || listener == null) return
        val next = queue.removeFirstOrNull() ?: return
        resolving = true
        nsd.resolveService(next, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, error: Int) {
                main.post {
                    resolving = false
                    resolveNext()
                }
            }

            override fun onServiceResolved(info: NsdServiceInfo) {
                // The TV itself also advertises _googlecast._tcp ("BRAVIA 2015"): skip it.
                val model = info.attributes["md"]?.let { String(it) }.orEmpty()
                val ip = info.host?.hostAddress
                Log.i("CleanScreenTV", "cast: found ${info.serviceName} ($model) at $ip")
                main.post {
                    resolving = false
                    if (ip != null && model.contains("Chromecast", true)) {
                        stop()
                        onFound(ip)
                    } else {
                        resolveNext()
                    }
                }
            }
        })
    }
}
