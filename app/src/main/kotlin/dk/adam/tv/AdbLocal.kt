package dk.adam.tv

import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.DataInputStream
import java.io.File
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * Just enough of the ADB wire protocol to run one shell command on this TV through
 * its own network ADB (127.0.0.1:5555). Shell may inject key events; an app may not.
 * The first connection makes the TV ask "Allow USB debugging?" for Clean Screen TV's key.
 */
class AdbLocal(private val context: Context) {

    companion object {
        private const val TAG = "CleanScreenTV"
        private const val CNXN = 0x4e584e43
        private const val AUTH = 0x48545541
        private const val OPEN = 0x4e45504f
        private const val OKAY = 0x59414b4f
        private const val CLSE = 0x45534c43
        private const val WRTE = 0x45545257
        private const val VERSION = 0x01000000
        private const val MAX_DATA = 4096
        private const val AUTH_TOKEN = 1
        private const val AUTH_SIGNATURE = 2
        private const val AUTH_RSAPUBLICKEY = 3
        // ASN.1 DigestInfo prefix for SHA-1: adbd verifies the 20-byte token as a SHA-1 digest.
        private val SHA1_PREFIX = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
        )
    }

    private class Message(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    /**
     * Runs [command] in a shell. Waits up to [authWaitMs] for the TV's one-time
     * authorization prompt to be accepted. Returns the output, or null on failure.
     */
    fun shell(command: String, authWaitMs: Int = 60_000): String? = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", 5555), 3000)
            socket.soTimeout = 10_000
            val input = DataInputStream(socket.getInputStream())
            val out = socket.getOutputStream()
            val keys = keyPair()

            send(out, CNXN, VERSION, MAX_DATA, "host::\u0000".toByteArray())
            var msg = read(input)
            var signed = false
            var offeredKey = false
            while (msg.command == AUTH && msg.arg0 == AUTH_TOKEN) {
                when {
                    !signed -> {
                        send(out, AUTH, AUTH_SIGNATURE, 0, sign(keys, msg.data))
                        signed = true
                    }
                    !offeredKey -> {
                        // Signature rejected: the key isn't authorized yet. Offering it prompts on the TV.
                        Log.i(TAG, "adb local: asking the TV to authorize Clean Screen TV's key")
                        send(out, AUTH, AUTH_RSAPUBLICKEY, 0, publicKeyBlob(keys))
                        offeredKey = true
                        socket.soTimeout = authWaitMs
                    }
                    else -> return null
                }
                msg = read(input)
            }
            if (msg.command != CNXN) return null
            socket.soTimeout = 10_000

            send(out, OPEN, 1, 0, "shell:$command\u0000".toByteArray())
            val output = StringBuilder()
            while (true) {
                val m = read(input)
                when (m.command) {
                    WRTE -> {
                        output.append(String(m.data))
                        send(out, OKAY, 1, m.arg0, ByteArray(0))
                    }
                    CLSE -> {
                        send(out, CLSE, 1, m.arg0, ByteArray(0))
                        return output.toString()
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }
    } catch (e: Exception) {
        Log.i(TAG, "adb local: $e")
        null
    }

    private fun send(out: OutputStream, command: Int, arg0: Int, arg1: Int, data: ByteArray) {
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(command).putInt(arg0).putInt(arg1).putInt(data.size)
        header.putInt(data.fold(0) { sum, b -> sum + (b.toInt() and 0xff) })
        header.putInt(command.inv())
        out.write(header.array())
        out.write(data)
        out.flush()
    }

    private fun read(input: DataInputStream): Message {
        val header = ByteArray(24)
        input.readFully(header)
        val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = b.int
        val arg0 = b.int
        val arg1 = b.int
        val length = b.int
        val data = ByteArray(length)
        input.readFully(data)
        return Message(command, arg0, arg1, data)
    }

    private fun sign(keys: KeyPair, token: ByteArray): ByteArray =
        Signature.getInstance("NONEwithRSA").run {
            initSign(keys.private)
            update(SHA1_PREFIX + token)
            sign()
        }

    /** The public key in adb's format: mincrypt's RSAPublicKey struct, base64, then a name. */
    private fun publicKeyBlob(keys: KeyPair): ByteArray {
        val pub = keys.public as RSAPublicKey
        val n = pub.modulus
        val words = 2048 / 32
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = r32.subtract(n.mod(r32).modInverse(r32))
        val rr = BigInteger.ONE.shiftLeft(4096).mod(n)
        val buf = ByteBuffer.allocate(4 + 4 + words * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(words)
        buf.putInt(n0inv.toInt())
        for (i in 0 until words) buf.putInt(n.shiftRight(32 * i).toInt())
        for (i in 0 until words) buf.putInt(rr.shiftRight(32 * i).toInt())
        buf.putInt(pub.publicExponent.toInt())
        val encoded = Base64.encodeToString(buf.array(), Base64.NO_WRAP)
        return "$encoded cleanscreentv@sleep-timer\u0000".toByteArray()
    }

    private fun keyPair(): KeyPair {
        val priv = File(context.filesDir, "adbkey")
        val pub = File(context.filesDir, "adbkey.pub")
        val factory = KeyFactory.getInstance("RSA")
        if (priv.exists() && pub.exists()) {
            return KeyPair(
                factory.generatePublic(X509EncodedKeySpec(pub.readBytes())),
                factory.generatePrivate(PKCS8EncodedKeySpec(priv.readBytes())),
            )
        }
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        priv.writeBytes(pair.private.encoded)
        pub.writeBytes(pair.public.encoded)
        return pair
    }
}
