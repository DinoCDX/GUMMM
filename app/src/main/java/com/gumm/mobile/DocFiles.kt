package com.gumm.mobile

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.OpenableColumns
import java.io.File

object DocFiles {
    fun copyTree(cr: ContentResolver, tree: Uri, dest: File, skipTop: Set<String> = emptySet()) {
        copyDir(cr, tree, DocumentsContract.getTreeDocumentId(tree), dest, skipTop)
    }

    private fun copyDir(cr: ContentResolver, tree: Uri, docId: String, dest: File, skip: Set<String>) {
        dest.mkdirs()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val entries = ArrayList<Array<String>>()
        val projection = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE
        )
        cr.query(childrenUri, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                entries.add(arrayOf(c.getString(0), c.getString(1), c.getString(2)))
            }
        }
        for (e in entries) {
            val name = safeName(e[1])
            if (skip.contains(name)) continue
            if (e[2] == Document.MIME_TYPE_DIR) {
                copyDir(cr, tree, e[0], File(dest, name), emptySet())
            } else {
                val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, e[0])
                cr.openInputStream(docUri)?.use { input ->
                    File(dest, name).outputStream().use { out -> input.copyTo(out, 1 shl 16) }
                }
            }
        }
    }

    fun treeName(cr: ContentResolver, tree: Uri): String {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        cr.query(docUri, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0) ?: "mod"
        }
        return "mod"
    }

    fun fileName(cr: ContentResolver, uri: Uri): String {
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0) ?: "mod"
        }
        return uri.lastPathSegment ?: "mod"
    }

    fun safeName(n: String): String {
        val s = n.replace('/', '_').replace('\\', '_')
        return if (s == "." || s == "..") "_" else s
    }
}
