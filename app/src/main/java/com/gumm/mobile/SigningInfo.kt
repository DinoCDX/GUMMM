package com.gumm.mobile

import android.content.pm.PackageManager
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

object SigningInfo {
    private fun hex(bytes: ByteArray): String = bytes.joinToString(":") { String.format("%02X", it) }

    fun fingerprint(der: ByteArray, algorithm: String): String =
        hex(MessageDigest.getInstance(algorithm).digest(der))

    fun parse(der: ByteArray): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate

    fun pem(der: ByteArray): String {
        val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der)
        return "-----BEGIN CERTIFICATE-----\n$body\n-----END CERTIFICATE-----\n"
    }

    fun describe(der: ByteArray): String {
        val c = parse(der)
        return "Subject: ${c.subjectX500Principal.name}\n" +
            "Serial: ${c.serialNumber.toString(16)}\n" +
            "Valid: ${c.notBefore} to ${c.notAfter}\n" +
            "SHA-256: ${fingerprint(der, "SHA-256")}\n" +
            "SHA-1: ${fingerprint(der, "SHA-1")}\n" +
            "MD5: ${fingerprint(der, "MD5")}"
    }

    fun fromApk(pm: PackageManager, path: String): List<ByteArray> {
        return try {
            val info = pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES)
            info?.signingInfo?.apkContentsSigners?.map { it.toByteArray() } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun fromInstalled(pm: PackageManager, pkg: String): List<ByteArray> {
        if (pkg.isBlank()) return emptyList()
        return try {
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            info.signingInfo?.apkContentsSigners?.map { it.toByteArray() } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
}
