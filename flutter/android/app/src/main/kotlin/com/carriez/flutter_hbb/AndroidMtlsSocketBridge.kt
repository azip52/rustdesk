package com.carriez.flutter_hbb

import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager

/** JNI bridge for the narrow RustDesk ID/relay mTLS transport. */
object AndroidMtlsSocketBridge {
    private const val TAG = "RustDeskMtls"
    private val executor = Executors.newCachedThreadPool()

    @JvmStatic
    fun open(context: Context, host: String, port: Int, timeoutMillis: Int): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || host.isBlank() || port !in 1..65535) {
            return -1
        }
        return try {
            val credential = MtlsCredentialManager.selectedCredential(context)
            Log.i(TAG, "Opening mTLS connection to $host:$port")
            val keyManager = object : X509ExtendedKeyManager() {
                override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: java.net.Socket?) = credential.alias
                override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = credential.alias
                override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(credential.alias)
                override fun getPrivateKey(alias: String?) : PrivateKey? = if (alias == credential.alias) credential.privateKey else null
                override fun getCertificateChain(alias: String?) : Array<X509Certificate>? = if (alias == credential.alias) credential.chain else null
                override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: java.net.Socket?) : String? = null
                override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) : String? = null
                override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) : Array<String>? = null
            }
            val sslContext = SSLContext.getInstance("TLS")
            // null TrustManagers intentionally means Android's system trust store.
            sslContext.init(arrayOf(keyManager), null, null)
            val socket = (sslContext.socketFactory.createSocket() as SSLSocket).apply {
                connect(InetSocketAddress(host, port), timeoutMillis)
                soTimeout = timeoutMillis
                sslParameters = sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                startHandshake()
                soTimeout = 0
            }
            Log.i(TAG, "mTLS handshake completed")
            val pair = ParcelFileDescriptor.createSocketPair()
            val rustFd = pair[0].detachFd()
            pump(pair[1], socket)
            rustFd
        } catch (error: Exception) {
            Log.w(TAG, "mTLS connection failed: ${error.javaClass.simpleName}")
            -1
        }
    }

    private fun pump(local: ParcelFileDescriptor, socket: SSLSocket) {
        executor.execute {
            ParcelFileDescriptor.AutoCloseInputStream(local.dup()).use { input ->
                copy(input, socket.outputStream, "local-to-network")
            }
            runCatching { socket.close() }
        }
        executor.execute {
            ParcelFileDescriptor.AutoCloseOutputStream(local).use { output ->
                copy(socket.inputStream, output, "network-to-local")
            }
            runCatching { socket.close() }
        }
    }

    private fun copy(input: InputStream, output: OutputStream, direction: String) {
        var total = 0L
        val buffer = ByteArray(16 * 1024)
        try {
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                output.flush()
                total += read
            }
        } catch (_: Exception) {
            // Both sides are closed as part of normal teardown as well as errors.
        } finally {
            Log.i(TAG, "mTLS $direction stream ended after $total bytes")
        }
    }
}
