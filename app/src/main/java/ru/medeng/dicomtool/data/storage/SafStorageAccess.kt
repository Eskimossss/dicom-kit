package ru.medeng.dicomtool.data.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** No filesystem paths, SIZE shortcuts or assumptions about provider rename/atomicity. */
internal class SafStorageAccess(private val context: Context, private val treeUri: Uri) : StorageAccess {
    override val scope = treeUri.toString()
    override val root = node(DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri)))
    private val resolver get() = context.contentResolver
    private fun node(uri: Uri): StorageNode {
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use {
            if (it.moveToFirst()) return StorageNode(uri.toString(), it.getString(0) ?: "",
                it.getString(1) == DocumentsContract.Document.MIME_TYPE_DIR)
        }
        throw IOException("SAF document unavailable")
    }
    override fun children(parent: StorageNode): List<StorageNode> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getDocumentId(Uri.parse(parent.id)))
        val result = mutableListOf<StorageNode>()
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(0))
                result.add(StorageNode(childUri.toString(), cursor.getString(1) ?: "", cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR))
            }
        } ?: throw IOException("SAF child query failed")
        return result
    }
    override fun directory(parent: StorageNode, name: String): StorageNode {
        StoragePaths.component(name)
        val uri = DocumentsContract.createDocument(resolver, Uri.parse(parent.id), DocumentsContract.Document.MIME_TYPE_DIR, name)
            ?: throw IOException("SAF createDirectory failed")
        val created = node(uri)
        if (created.name != name || !created.directory) throw IOException("SAF provider changed directory name/type")
        return created
    }
    override fun create(parent: StorageNode, name: String): StorageNode {
        StoragePaths.component(name)
        val mime = if (name.endsWith(".dcm")) "application/dicom" else "application/octet-stream"
        val previousIds = children(parent).map { it.id }.toSet()
        val uri = DocumentsContract.createDocument(resolver, Uri.parse(parent.id), mime, name)
            ?: throw IOException("SAF createDocument failed")
        val created = node(uri)
        if (created.id in previousIds || created.directory) throw IOException("SAF provider did not create a new file")
        return created
    }
    override fun read(node: StorageNode): InputStream = context.contentResolver.openInputStream(Uri.parse(node.id))
        ?: throw IOException("SAF openInputStream returned null")
    override fun write(node: StorageNode): OutputStream = context.contentResolver.openOutputStream(Uri.parse(node.id), "wt")
        ?: throw IOException("SAF openOutputStream returned null")
    override fun deleteFile(node: StorageNode): Boolean {
        val uri = Uri.parse(node.id)
        return !node(uri).directory && DocumentsContract.deleteDocument(resolver, uri)
    }
}
