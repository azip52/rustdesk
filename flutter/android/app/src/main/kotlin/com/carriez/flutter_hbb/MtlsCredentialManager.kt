package com.carriez.flutter_hbb

import android.app.Activity
import android.content.Context
import android.security.KeyChain
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import javax.security.auth.x500.X500Principal

/** Stores only a KeyChain alias. Private keys remain owned by Android KeyChain. */
class MtlsCredentialManager(private val context: Context) {
    companion object {
        private const val PREFS = "rustdesk-mtls"
        private const val ALIAS = "client-keychain-alias"
        private const val CLIENT_AUTH_EKU = "1.3.6.1.5.5.7.3.2"
        private const val REQUIRED_OU = "RustDeskClient"
        private val executor = Executors.newSingleThreadExecutor()

        data class Credential(
            val alias: String,
            val privateKey: PrivateKey,
            val chain: Array<X509Certificate>,
        )

        private fun preferences(context: Context) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        private fun clear(context: Context) {
            preferences(context).edit().remove(ALIAS).apply()
        }

        private fun splitRdns(subject: String): List<String> {
            val values = mutableListOf<String>()
            val current = StringBuilder()
            var escaped = false
            for (character in subject) {
                if (escaped) {
                    current.append(character)
                    escaped = false
                } else if (character == '\\') {
                    current.append(character)
                    escaped = true
                } else if (character == ',' || character == '+') {
                    values.add(current.toString())
                    current.clear()
                } else {
                    current.append(character)
                }
            }
            values.add(current.toString())
            return values
        }

        private fun isValidCertificate(certificate: X509Certificate): Boolean {
            certificate.checkValidity()
            val subject = certificate.subjectX500Principal.getName(X500Principal.RFC2253)
            val hasRequiredOu = splitRdns(subject).any { rdn ->
                val separator = rdn.indexOf('=')
                separator > 0 && rdn.substring(0, separator).equals("OU", true) &&
                    rdn.substring(separator + 1) == REQUIRED_OU
            }
            if (!hasRequiredOu) return false
            return certificate.extendedKeyUsage?.contains(CLIENT_AUTH_EKU) ?: true
        }

        fun selectedCredential(context: Context): Credential {
            val alias = preferences(context).getString(ALIAS, null)
                ?: throw IllegalStateException("No mTLS client certificate is selected")
            return try {
                val privateKey = KeyChain.getPrivateKey(context, alias)
                    ?: throw IllegalStateException("Selected mTLS private key is unavailable")
                val chain = KeyChain.getCertificateChain(context, alias)
                    ?: throw IllegalStateException("Selected mTLS certificate chain is unavailable")
                if (chain.isEmpty() || !isValidCertificate(chain[0])) {
                    throw IllegalStateException("Selected mTLS certificate is not valid")
                }
                Credential(alias, privateKey, chain)
            } catch (error: Exception) {
                clear(context)
                throw error
            }
        }
    }

    fun choose(activity: Activity, callback: (Result<Unit>) -> Unit) {
        KeyChain.choosePrivateKeyAlias(activity, { alias ->
            executor.execute {
                try {
                    if (alias.isNullOrBlank()) throw IllegalStateException("No mTLS certificate selected")
                    if (KeyChain.getPrivateKey(context, alias) == null) {
                        throw IllegalStateException("Selected mTLS private key is unavailable")
                    }
                    val chain = KeyChain.getCertificateChain(context, alias)
                        ?: throw IllegalStateException("Selected mTLS certificate chain is unavailable")
                    if (chain.isEmpty() || !isValidCertificate(chain[0])) {
                        throw IllegalStateException("Selected mTLS certificate is not valid")
                    }
                    preferences(context).edit().putString(ALIAS, alias).apply()
                    callback(Result.success(Unit))
                } catch (error: Exception) {
                    clear(context)
                    callback(Result.failure(error))
                }
            }
        }, null, null, null, -1, null)
    }
}
