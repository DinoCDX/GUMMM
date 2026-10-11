package com.gumm.mobile

import android.content.Context
import android.content.pm.PackageManager
import com.android.apksig.ApkSigner
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class KeyManager(store: Store) {
    private val file = File(store.root, "signing.p12")
    private val alias = "gumm"
    private val password = "gumm-mobile".toCharArray()

    @Synchronized
    private fun keyStore(): KeyStore {
        val ks = KeyStore.getInstance("PKCS12")
        if (file.exists()) {
            file.inputStream().use { ks.load(it, password) }
            if (ks.containsAlias(alias)) return ks
        }
        create(ks)
        return ks
    }

    private fun create(ks: KeyStore) {
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048, SecureRandom())
        val pair = gen.generateKeyPair()
        val cert = selfSigned(pair.private, pair.public.encoded)
        ks.load(null, null)
        ks.setKeyEntry(alias, pair.private, password, arrayOf(cert))
        file.outputStream().use { ks.store(it, password) }
    }

    private fun selfSigned(priv: PrivateKey, publicEncoded: ByteArray): X509Certificate {
        val fmt = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        val notBefore = fmt.format(Date(System.currentTimeMillis() - 86_400_000L))
        val notAfter = "491231235959Z"
        val algId = Der.seq(Der.oid("1.2.840.113549.1.1.11"), Der.nul())
        val name = Der.seq(Der.set(Der.seq(Der.oid("2.5.4.3"), Der.utf8("GUMM Mobile"))))
        val serial = BigInteger(63, SecureRandom()).add(BigInteger.ONE)
        val tbs = Der.seq(
            Der.tlv(0xA0, Der.integer(BigInteger.valueOf(2))),
            Der.integer(serial),
            algId,
            name,
            Der.seq(Der.utcTime(notBefore), Der.utcTime(notAfter)),
            name,
            publicEncoded
        )
        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(priv)
        sig.update(tbs)
        val der = Der.seq(tbs, algId, Der.bitString(sig.sign()))
        return CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    }

    fun certificate(): X509Certificate = keyStore().getCertificate(alias) as X509Certificate

    fun signerConfig(): ApkSigner.SignerConfig {
        val ks = keyStore()
        val key = ks.getKey(alias, password) as PrivateKey
        val cert = ks.getCertificate(alias) as X509Certificate
        return ApkSigner.SignerConfig.Builder("gumm", key, listOf(cert)).build()
    }

    fun matches(ctx: Context, pkg: String): Boolean {
        return try {
            val mine = certificate().encoded
            val info = ctx.packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            val signers = info.signingInfo?.apkContentsSigners ?: return false
            signers.any { it.toByteArray().contentEquals(mine) }
        } catch (e: Exception) {
            false
        }
    }

    fun exportTo(out: OutputStream) {
        keyStore()
        file.inputStream().use { it.copyTo(out) }
    }

    @Synchronized
    fun importFrom(input: InputStream) {
        val bytes = input.readBytes()
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(ByteArrayInputStream(bytes), password)
        require(ks.containsAlias(alias)) { "Key file does not contain the GUMM key" }
        ks.getKey(alias, password) as PrivateKey
        file.writeBytes(bytes)
    }
}
