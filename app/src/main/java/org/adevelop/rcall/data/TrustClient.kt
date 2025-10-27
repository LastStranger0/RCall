package org.adevelop.rcall.data

import android.content.Context
import okhttp3.OkHttpClient
import org.adevelop.rcall.R
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager


class TrustClient {
    private val trustedIPs = listOf("192.168.1.122", "165.232.68.160")


    fun getSecureApiService(context: Context): OkHttpClient {
        val sslContext = createCustomSSLContext(context, R.raw.mycert)

        val trustManagerFactory = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm()
        )
        trustManagerFactory.init(null as KeyStore?)

        val okHttpClient = OkHttpClient.Builder()
            .sslSocketFactory(
                sslContext.socketFactory,
                trustManagerFactory.trustManagers[0] as X509TrustManager
            )
            .hostnameVerifier { hostname, session ->
                hostname in trustedIPs
            }
            .build()

        return okHttpClient
    }

    fun createCustomSSLContext(context: Context, certResourceId: Int): SSLContext {
        try {
            // Load custom certificate from resources
            val certificateFactory = CertificateFactory.getInstance("X.509")
            val inputStream = context.resources.openRawResource(certResourceId)
            val certificate = certificateFactory.generateCertificate(inputStream)

            // Create KeyStore containing our certificate
            val keyStoreType = KeyStore.getDefaultType()
            val keyStore = KeyStore.getInstance(keyStoreType)
            keyStore.load(null, null)
            keyStore.setCertificateEntry("custom-cert", certificate)

            // Create TrustManager that trusts our KeyStore
            val trustManagerFactory = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm()
            )
            trustManagerFactory.init(keyStore)

            // Create SSLContext that uses our TrustManager
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustManagerFactory.trustManagers, null)

            return sslContext

        } catch (e: Exception) {
            throw RuntimeException("Failed to create SSL context", e)
        }
    }
}