package ru.medeng.dicomtool.data.storage

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.withLock
import kotlin.concurrent.write

/** Provider metadata (notably SIZE) is never evidence that bytes were saved. */
internal data class StorageNode(val id: String, val name: String, val directory: Boolean)

internal interface StorageAccess {
    val scope: String
    val root: StorageNode
    fun children(parent: StorageNode): List<StorageNode>
    fun directory(parent: StorageNode, name: String): StorageNode
    fun create(parent: StorageNode, name: String): StorageNode
    fun read(node: StorageNode): InputStream
    fun write(node: StorageNode): OutputStream
    fun deleteFile(node: StorageNode): Boolean
    // A backend may publish by a no-replace move. SAF must not assume rename support.
    fun publish(stage: StorageNode, parent: StorageNode, name: String): StorageNode? = null
}

internal data class ContentDigest(val bytes: Long, val sha256: String)
internal data class OwnedDocument(
    val scope: String, val id: String, val name: String,
    val study: String, val series: String, val sop: String, val digest: ContentDigest
)

/** Short directory locks + per-instance locks; maintenance excludes concurrent publication. */
internal class StorageCoordinator private constructor() {
    private val gate = ReentrantReadWriteLock(true)
    private data class Entry(val lock: ReentrantLock = ReentrantLock(), var users: Int = 0)
    private val entries = mutableMapOf<String, Entry>()
    fun <T> receiving(block: () -> T): T = gate.read(block)
    fun <T> maintenance(block: () -> T): T = gate.write(block)
    fun <T> keyed(key: String, block: () -> T): T {
        val entry = synchronized(entries) { entries.getOrPut(key) { Entry() }.also { it.users++ } }
        try { return entry.lock.withLock(block) }
        finally { synchronized(entries) { if (--entry.users == 0) entries.remove(key) } }
    }
    companion object {
        private val roots = mutableMapOf<String, StorageCoordinator>()
        fun forRoot(root: File): StorageCoordinator = synchronized(roots) {
            roots.getOrPut(root.canonicalPath) { StorageCoordinator() }
        }
    }
}

/** Private, durable ledger. Unknown/legacy SAF documents are never adopted or deleted. */
internal class DocumentOwnership(private val file: File) {
    private var documents: Map<String, OwnedDocument> = load()
    @Synchronized fun owned(scope: String): List<OwnedDocument> = documents.values.filter { it.scope == scope }
    @Synchronized fun record(doc: OwnedDocument) {
        val updated = documents + (key(doc.scope, doc.id) to doc)
        persist(updated)
        documents = updated
    }
    @Synchronized fun forget(scope: String, id: String) {
        val updated = documents - key(scope, id)
        persist(updated)
        documents = updated
    }
    private fun key(scope: String, id: String) = "$scope\u0000$id"
    private fun load(): Map<String, OwnedDocument> = try {
        if (!file.exists()) emptyMap() else file.readLines(Charsets.UTF_8).associate { line ->
            val f = line.split('\t')
            require(f.size == 8)
            val doc = OwnedDocument(decode(f[0]), decode(f[1]), decode(f[2]), decode(f[3]),
                decode(f[4]), decode(f[5]), ContentDigest(f[6].toLong(), f[7]))
            key(doc.scope, doc.id) to doc
        }
    } catch (_: Exception) { emptyMap() } // Fail closed: losing the ledger cannot authorize deletion.
    private fun persist(updated: Map<String, OwnedDocument>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(tmp).use { out ->
                val text = updated.values.joinToString("\n", postfix = if (updated.isEmpty()) "" else "\n") {
                    listOf(it.scope, it.id, it.name, it.study, it.series, it.sop).joinToString("\t", transform = ::encode) +
                        "\t${it.digest.bytes}\t${it.digest.sha256}"
                }
                out.write(text.toByteArray(Charsets.UTF_8)); out.flush(); out.fd.sync()
            }
            try { Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { tmp.delete() }
    }
    companion object {
        private val instances = mutableMapOf<String, DocumentOwnership>()
        fun forRoot(root: File): DocumentOwnership = synchronized(instances) {
            val file = File(root, ".storage_ownership.tsv").canonicalFile
            instances.getOrPut(file.path) { DocumentOwnership(file) }
        }
        private fun encode(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray(Charsets.UTF_8))
        private fun decode(s: String) = String(Base64.getUrlDecoder().decode(s), Charsets.UTF_8)
    }
}

internal object StoragePaths {
    fun uid(value: String): String {
        require(value.length in 1..64 && value.matches(Regex("[0-9]+(\\.[0-9]+)*")) &&
            value.split('.').none { it.length > 1 && it.startsWith('0') }) { "Invalid DICOM UID: $value" }
        return value
    }
    fun contained(root: File, file: File): File {
        val base = root.canonicalFile.toPath()
        val candidate = file.canonicalFile
        require(candidate.toPath().startsWith(base) && candidate.toPath() != base) { "Storage path escapes root" }
        return candidate
    }
    fun component(name: String): String {
        require(name.isNotEmpty() && name != "." && name != ".." && name.length <= 200 &&
            name.matches(Regex("[a-zA-Z0-9._-]+"))) { "Unsafe storage component" }
        return name
    }
    fun dicomFiles(root: File): Sequence<File> = root.walkTopDown().onEnter { dir ->
        !Files.isSymbolicLink(dir.toPath()) &&
            (dir.absoluteFile == root.absoluteFile || runCatching { contained(root, dir) }.isSuccess)
    }.filter { it.isFile && !Files.isSymbolicLink(it.toPath()) && it.extension.equals("dcm", true) &&
        runCatching { contained(root, it) }.isSuccess }
}

internal data class StorageSource(val open: () -> InputStream, val remove: () -> Boolean)
internal fun File.storageSource() = StorageSource({ FileInputStream(this) }, { delete() })

/** The same production algorithm is used by file storage, SAF and both migration directions. */
internal class VerifiedDicomStore(
    private val access: StorageAccess,
    private val ownership: DocumentOwnership,
    private val coordinator: StorageCoordinator
) {
    fun save(source: StorageSource, study: String, series: String, sop: String): DicomSaveResult = try {
        StoragePaths.uid(study); StoragePaths.uid(series); StoragePaths.uid(sop)
        coordinator.receiving {
            val expected = fingerprint(source.open)
            val studyDir = ensureDirectory(access.root, study)
            val seriesDir = ensureDirectory(studyDir, series)
            coordinator.keyed("${access.scope}:${seriesDir.id}:$sop") {
                val candidates = access.children(seriesDir).filter { !it.directory &&
                    (it.name == "$sop.dcm" || (it.name.startsWith("${sop}_dup_") && it.name.endsWith(".dcm"))) }
                val identical = candidates.firstOrNull { fingerprint { access.read(it) } == expected }
                if (identical != null) {
                    // Do not claim ownership of an existing, possibly user-created, identical document.
                    finish(source, identical, expected, "Identical re-transmission (verified SHA-256)")
                } else {
                    var stage: StorageNode? = null
                    var target: StorageNode? = null
                    var committed = false
                    try {
                        val staged = access.create(seriesDir, ".stage-${UUID.randomUUID()}.part")
                        stage = staged
                        copyAndVerify(source.open, staged, expected)
                        val name = if (candidates.none { it.name == "$sop.dcm" }) "$sop.dcm"
                            else "${sop}_dup_${UUID.randomUUID()}.dcm"
                        val published = access.publish(staged, seriesDir, name) ?: access.create(seriesDir, name).also {
                            target = it
                            copyAndVerify({ access.read(staged) }, it, expected)
                        }
                        target = published
                        verify(published, expected)
                        ownership.record(OwnedDocument(access.scope, published.id, published.name, study, series, sop, expected))
                        committed = true
                        // The source survives crashes until content verification AND durable ownership commit.
                        finish(source, published, expected, if (candidates.isEmpty()) "Verified new instance" else "Different content preserved separately")
                    } catch (e: Exception) {
                        if (!committed) target?.let { runCatching { access.deleteFile(it) } }
                        throw e
                    } finally { stage?.let { runCatching { access.deleteFile(it) } } }
                }
            }
        }
    } catch (e: Exception) {
        DicomSaveResult.Failure("Storage verification failed: ${e.message ?: e.javaClass.simpleName}",
            e.message?.contains("ENOSPC", true) == true, e)
    }
    private fun ensureDirectory(parent: StorageNode, name: String): StorageNode =
        coordinator.keyed("${access.scope}:${parent.id}:directory:$name") {
            val existing = access.children(parent).filter { it.name == name }
            require(existing.size <= 1 && existing.none { !it.directory }) { "Ambiguous destination directory" }
            existing.firstOrNull() ?: access.directory(parent, name)
        }
    private fun finish(source: StorageSource, target: StorageNode, expected: ContentDigest, note: String): DicomSaveResult.Success {
        require(fingerprint(source.open) == expected) { "Source changed during transfer; retained" }
        val removed = source.remove()
        return DicomSaveResult.Success(target.id, expected.bytes,
            note + if (removed) "" else "; $RETAINED_SOURCE_WARNING", sourceRetained = !removed)
    }
    private fun copyAndVerify(open: () -> InputStream, target: StorageNode, expected: ContentDigest) {
        val copied = open().use { input -> access.write(target).use { output -> digest(input, output).also { output.flush() } } }
        require(copied == expected) { "Source changed during copy" }
        verify(target, expected)
    }
    private fun verify(target: StorageNode, expected: ContentDigest) {
        // Providers may defer visibility after close. Retry actual reads, never trust SIZE=0/old SIZE.
        var failure: Exception? = null
        repeat(3) { attempt ->
            try {
                if (fingerprint { access.read(target) } == expected) return
                failure = IOException("Incomplete or altered destination content")
            } catch (e: Exception) { failure = e }
            if (attempt < 2) try { Thread.sleep(50) } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("Verification interrupted; source retained")
            }
        }
        throw failure ?: IOException("Destination verification failed")
    }
    fun clearOwned(study: String? = null): Int = coordinator.maintenance {
        var removed = 0
        for (doc in ownership.owned(access.scope).filter { study == null || it.study == study }) {
            val node = StorageNode(doc.id, doc.name, false)
            try {
                if (fingerprint { access.read(node) } == doc.digest && access.deleteFile(node)) {
                    ownership.forget(doc.scope, doc.id); removed++
                }
            } catch (_: Exception) { /* Unreadable/changed/unknown files must remain untouched. */ }
        }
        // No SAF directory deletion: a directory can contain unknown documents or a concurrent external writer.
        removed
    }
    companion object {
        fun fingerprint(open: () -> InputStream): ContentDigest = open().use { digest(it) }
        private fun digest(input: InputStream, output: OutputStream? = null): ContentDigest {
            val hash = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(65536)
            var count = 0L
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Copy interrupted; source retained")
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                output?.write(buffer, 0, read); hash.update(buffer, 0, read); count += read
            }
            return ContentDigest(count, hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
        }
    }
}

internal class FileStorageAccess(private val dir: File) : StorageAccess {
    override val scope = dir.canonicalPath
    override val root = StorageNode(scope, dir.name, true)
    private fun file(node: StorageNode) = StoragePaths.contained(dir, File(node.id))
    override fun children(parent: StorageNode): List<StorageNode> {
        val base = if (parent == root) dir.canonicalFile else file(parent)
        return base.listFiles().orEmpty().mapNotNull { child ->
            runCatching { val safe = StoragePaths.contained(dir, child); StorageNode(safe.path, safe.name, safe.isDirectory) }.getOrNull()
        }
    }
    private fun child(parent: StorageNode, name: String): File = StoragePaths.contained(dir,
        File(if (parent == root) dir else file(parent), StoragePaths.component(name)))
    override fun directory(parent: StorageNode, name: String): StorageNode {
        val f = child(parent, name)
        check(f.mkdir()) { "Could not create directory" }
        return StorageNode(f.path, name, true)
    }
    override fun create(parent: StorageNode, name: String): StorageNode {
        val f = child(parent, name)
        check(f.createNewFile()) { "Destination already exists" }
        return StorageNode(f.path, name, false)
    }
    override fun read(node: StorageNode): InputStream = FileInputStream(file(node))
    override fun write(node: StorageNode): OutputStream {
        val out = FileOutputStream(file(node))
        return object : FilterOutputStream(out) {
            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
            override fun close() { try { flush(); out.fd.sync() } finally { out.close() } }
        }
    }
    override fun deleteFile(node: StorageNode): Boolean = file(node).let { !it.isDirectory && it.delete() }
    override fun publish(stage: StorageNode, parent: StorageNode, name: String): StorageNode {
        val target = child(parent, name)
        Files.move(file(stage).toPath(), target.toPath()) // No REPLACE_EXISTING; publication serialized per SOP.
        return StorageNode(target.path, name, false)
    }
}
