package com.example.hinditv

import android.content.Context
import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * One shared HTTP client for playlist, logos and video streams.
 * Old Android TVs (5.x) miss newer root certificates, so we either bundle them
 * or (default) accept all certificates - see "Relaxed SSL" in Settings.
 */
object Net {
    private var cached: OkHttpClient? = null
    private var cachedRelaxed = true
    private var bundled: X509TrustManager? = null

    @Synchronized
    fun client(ctx: Context): OkHttpClient {
        val relaxed = Settings.relaxedSsl
        val existing = cached
        if (existing != null && cachedRelaxed == relaxed) return existing

        val b = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)

        try {
            if (relaxed) {
                val trustAll = object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                }
                val sc = SSLContext.getInstance("TLS")
                sc.init(null, arrayOf(trustAll), SecureRandom())
                b.sslSocketFactory(sc.socketFactory, trustAll)
                b.hostnameVerifier(HostnameVerifier { _, _ -> true })
            } else {
                val tm = mergedTrust(ctx.applicationContext)
                val sc = SSLContext.getInstance("TLS")
                sc.init(null, arrayOf(tm), SecureRandom())
                b.sslSocketFactory(sc.socketFactory, tm)
            }
        } catch (e: Exception) {
            // fall back to platform defaults
        }

        val c = b.build()
        cached = c
        cachedRelaxed = relaxed
        return c
    }

    private fun platformTrust(ks: KeyStore?): X509TrustManager {
        val f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        f.init(ks)
        return f.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private fun bundledTrust(ctx: Context): X509TrustManager {
        bundled?.let { return it }
        val ks = KeyStore.getInstance(KeyStore.getDefaultType())
        ks.load(null, null)
        val text = ctx.resources.openRawResource(R.raw.cacerts).bufferedReader().use { it.readText() }
        val cf = CertificateFactory.getInstance("X.509")
        var i = 0
        for (part in text.split("-----END CERTIFICATE-----")) {
            val idx = part.indexOf("-----BEGIN CERTIFICATE-----")
            if (idx < 0) continue
            val pem = part.substring(idx) + "-----END CERTIFICATE-----\n"
            try {
                val cert = cf.generateCertificate(ByteArrayInputStream(pem.toByteArray()))
                ks.setCertificateEntry("bundled" + i, cert)
                i++
            } catch (e: Exception) {
                // skip certificates this Android version cannot parse
            }
        }
        val tm = platformTrust(ks)
        bundled = tm
        return tm
    }

    private fun mergedTrust(ctx: Context): X509TrustManager {
        val system = platformTrust(null)
        val extra = bundledTrust(ctx)
        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                system.checkClientTrusted(chain, authType)
            }

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                try {
                    system.checkServerTrusted(chain, authType)
                } catch (e: CertificateException) {
                    extra.checkServerTrusted(chain, authType)
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> =
                system.acceptedIssuers + extra.acceptedIssuers
        }
    }
}
