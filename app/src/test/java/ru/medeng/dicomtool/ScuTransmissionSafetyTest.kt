package ru.medeng.dicomtool

import org.dcm4che3.data.*
import org.dcm4che3.io.DicomInputStream
import org.dcm4che3.io.DicomOutputStream
import org.dcm4che3.net.*
import org.dcm4che3.net.pdu.*
import org.dcm4che3.net.service.AbstractDicomService
import org.dcm4che3.net.service.DicomServiceRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.medeng.dicomtool.data.logging.*
import ru.medeng.dicomtool.dicom.scu.*
import ru.medeng.dicomtool.domain.model.*
import java.io.*
import java.net.ServerSocket
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

class ScuTransmissionSafetyTest {
    @get:Rule val temp = TemporaryFolder()
    private data class Fixture(val candidate: ScuCandidateFile, val original: ByteArray, val encoded: ByteArray)
    private fun fixture(ts: String = UID.ExplicitVRLittleEndian, sopClass: String = UID.SecondaryCaptureImageStorage,
        instance: String = "1.2.840.999.1", raw: Boolean = false, large: Boolean = false): Fixture {
        val dataset = Attributes().apply {
            setString(Tag.SOPClassUID, VR.UI, sopClass); setString(Tag.SOPInstanceUID, VR.UI, instance)
            setString(Tag.StudyInstanceUID, VR.UI, "1.2.840.999"); setString(Tag.SeriesInstanceUID, VR.UI, "1.2.840.999.2")
            setString(Tag.PatientName, VR.PN, "SYNTHETIC^SCU")
            setInt(Tag.Rows, VR.US, if (large) 4096 else 16); setInt(Tag.Columns, VR.US, if (large) 4096 else 16); setInt(Tag.SamplesPerPixel, VR.US, 1)
            setInt(Tag.BitsAllocated, VR.US, 8); setInt(Tag.BitsStored, VR.US, 8); setInt(Tag.HighBit, VR.US, 7)
            setInt(Tag.PixelRepresentation, VR.US, 0); setString(Tag.PhotometricInterpretation, VR.CS, "MONOCHROME2")
            if (ts == UID.JPEGBaseline8Bit || ts == UID.RLELossless) {
                val frame = if (ts == UID.JPEGBaseline8Bit) {
                    syntheticJpeg()
                } else {
                    val header = ByteArray(64).apply { this[0] = 1; this[4] = 64 }
                    header + ByteArray(16 * 17) { offset -> if (offset % 17 == 0) 15 else (offset % 17 - 1).toByte() }
                }
                newFragments(Tag.PixelData, VR.OB, 2).apply {
                    add(ByteArray(0)); add(frame.copyOf(frame.size + frame.size % 2))
                }
            } else setBytes(Tag.PixelData, VR.OB, ByteArray(if (large) 4096 * 4096 else 256) { (it % 16).toByte() })
        }
        val out = ByteArrayOutputStream()
        DicomOutputStream(out, if (raw) ts else UID.ExplicitVRLittleEndian).use { it.writeDataset(if (raw) null else dataset.createFileMetaInformation(ts), dataset) }
        val original = out.toByteArray()
        // Independent oracle: dcm4che generated FMI length, not production reader.
        val offset = if (raw) 0 else 144 + (0..3).fold(0) { value, i -> value or ((original[140 + i].toInt() and 255) shl (8 * i)) }
        val candidate = DicomFileScanner.inspectInputStream(ByteArrayInputStream(original), fileName = "synthetic.dcm", fileSize = original.size.toLong())
        assertTrue(candidate.validationError, candidate.isValidDicom)
        return Fixture(candidate, original, original.copyOfRange(offset, original.size))
    }
    private fun logger() = PersistentDicomTechnicalLogger(File(temp.newFolder(), "events.tsv"))
    // JVM-only fixture generation; Android's compilation bootclasspath excludes java.desktop APIs.
    private fun syntheticJpeg(): ByteArray {
        val intType = Int::class.javaPrimitiveType!!
        val imageType = Class.forName("java.awt.image.BufferedImage")
        val image = imageType.getConstructor(intType, intType, intType).newInstance(16, 16, 10) // TYPE_BYTE_GRAY
        val raster = imageType.getMethod("getRaster").invoke(image)
        val setSample = Class.forName("java.awt.image.WritableRaster").getMethod("setSample", intType, intType, intType, intType)
        for (y in 0..15) for (x in 0..15) setSample.invoke(raster, x, y, 0, x)
        val output = ByteArrayOutputStream()
        val written = Class.forName("javax.imageio.ImageIO").getMethod("write", Class.forName("java.awt.image.RenderedImage"),
            String::class.java, OutputStream::class.java).invoke(null, image, "jpeg", output)
        check(written == true)
        return output.toByteArray()
    }
    private fun run(files: List<Fixture>, peer: Peer, logger: PersistentDicomTechnicalLogger,
        token: ScuCancellation = ScuCancellation(), progress: (Int, ScuCandidateFile, FileSendStatus, String?) -> Unit = { _, _, _, _ -> }): ScuBatchOperationResult {
        val originals = files.associate { it.candidate.id to it.original }
        val result = ScuFileBatchSender(logger).send("127.0.0.1", peer.port, "TEST_SCU", "TEST_PACS",
            files.map { it.candidate }, { ByteArrayInputStream(originals.getValue(it.id)) }, progress, token, { token.isRequested },
            ScuTimeouts(connect = 1000, response = 400, send = 1000, release = 400))
        assertEquals(files.size, result.fileResults.size)
        assertEquals(files.size, result.successCount + result.warningCount + result.failedCount + result.skippedCount + result.cancelledCount + result.unconfirmedCount)
        assertTrue(result.fileResults.all { it.status != FileSendStatus.PENDING && it.status != FileSendStatus.SENDING })
        logger.flushPendingWrites()
        return result
    }

    @Test fun generatedCharsetAndRepeatedStorePreserveExactEncodedDatasetEvenWhenJournalReadsPatient() {
        for (mode in SyntheticDicomEncoding.entries) {
            val config = ScuSendConfig(syntheticEncoding = mode,
                patientName = if (mode == SyntheticDicomEncoding.ASCII) "SYNTHETIC^ASCII" else "ТЕСТ^СИНТЕТИКА",
                patientId = if (mode == SyntheticDicomEncoding.ASCII) "SYNTHETIC-ID" else "СИНТЕТИКА-ID",
                studyDescription = "SYNTHETIC STUDY", seriesDescription = "SYNTHETIC SERIES")
            val prepared = DicomSecondaryCaptureGenerator.prepareSecondaryCaptureObject(config)
            fun encoded() = ByteArrayOutputStream().also { out -> DicomOutputStream(out, UID.ExplicitVRLittleEndian).use {
                it.writeDataset(null, prepared.dataset) } }.toByteArray()
            val original = encoded()
            Peer().use { peer -> val log = logger(); try {
                val client = DicomScuClient(log, temp.newFolder())
                repeat(2) {
                    val result = client.performCStore("127.0.0.1", peer.port, "TEST_SCU", "TEST_PACS", prepared)
                    assertTrue("Synthetic encoding ${mode.name}: $result", result is ScuOperationResult.Success)
                    assertArrayEquals("Prepared object changed after C-STORE: ${mode.name}", original, encoded())
                }
                assertEquals(2, peer.received.size)
                peer.received.forEach { assertArrayEquals("Wire dataset ${mode.name}", original, it.second) }
            } finally { log.shutdown() } }
        }
    }
    @Test fun deflatedWireBytesRemainEncodedAndReceiverCanDecode() {
        val file = fixture(UID.DeflatedExplicitVRLittleEndian)
        val before = java.security.MessageDigest.getInstance("SHA-256").digest(file.original)
        Peer().use { peer ->
            val logger = logger()
            try {
                val result = run(listOf(file), peer, logger)
                assertEquals(result.toString(), 1, result.successCount)
                assertArrayEquals(file.encoded, peer.received.single().second)
                DicomInputStream(ByteArrayInputStream(peer.received.single().second), UID.DeflatedExplicitVRLittleEndian).use {
                    assertEquals(file.candidate.sopInstanceUid, it.readDataset().getString(Tag.SOPInstanceUID))
                }
                assertArrayEquals(before, java.security.MessageDigest.getInstance("SHA-256").digest(file.original))
            } finally { logger.shutdown() }
        }
    }
    @Test fun mixedUncompressedSyntaxesPreserveExactWireBytes() {
        val files = listOf(UID.ImplicitVRLittleEndian, UID.ExplicitVRLittleEndian, UID.ExplicitVRBigEndian)
            .mapIndexed { index, ts -> fixture(ts, instance = "1.2.840.999.${index + 10}") }
        Peer().use { peer -> val logger = logger(); try {
            assertEquals(3, run(files, peer, logger).successCount)
            files.zip(peer.received).forEach { (file, received) ->
                assertEquals(file.candidate.transferSyntaxUid, received.first); assertArrayEquals(file.encoded, received.second)
            }
        } finally { logger.shutdown() } }
    }
    @Test fun rawLittleEndianSyntaxesKeepDatasetWithoutPart10Changes() {
        for (ts in listOf(UID.ImplicitVRLittleEndian, UID.ExplicitVRLittleEndian)) {
            val file = fixture(ts, raw = true)
            OriginalDicomDataset.open(ByteArrayInputStream(file.original), ts).use { assertArrayEquals(file.original, it.readBytes()) }
        }
    }
    @Test fun encapsulatedJpegAndRleFragmentsArePreservedWithoutDecoder() {
        val files = listOf(UID.JPEGBaseline8Bit, UID.RLELossless).mapIndexed { index, ts -> fixture(ts, instance = "1.2.840.999.700.$index") }
        Peer().use { peer -> val logger = logger(); try {
            assertEquals(2, run(files, peer, logger).successCount)
            files.zip(peer.received).forEach { (file, received) ->
                assertArrayEquals(file.encoded, received.second)
                DicomInputStream(ByteArrayInputStream(received.second), received.first).use {
                    val dataset = it.readDataset()
                    assertEquals(2, (dataset.getValue(Tag.PixelData) as Fragments).size)
                }
            }
        } finally { logger.shutdown() } }
    }
    @Test fun smallReadsDoNotLoseFmiOrEncodedBytes() {
        val file = fixture(UID.DeflatedExplicitVRLittleEndian)
        val input = object : FilterInputStream(ByteArrayInputStream(file.original)) {
            override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, minOf(len, 3))
            override fun markSupported() = false
        }
        OriginalDicomDataset.open(input, file.candidate.transferSyntaxUid!!).use { assertArrayEquals(file.encoded, it.readBytes()) }
    }
    @Test fun invalidFmiLengthIsRejectedBeforeCStore() {
        val file = fixture(); val invalid = file.original.clone().apply { this[140] = (this[140].toInt() xor 2).toByte() }
        assertThrows(IOException::class.java) { OriginalDicomDataset.open(ByteArrayInputStream(invalid), UID.ExplicitVRLittleEndian) }
    }
    @Test fun mismatchSyntaxRejectedWithoutTranscoding() {
        val file = fixture(UID.DeflatedExplicitVRLittleEndian)
        assertThrows(IOException::class.java) { OriginalDicomDataset.open(ByteArrayInputStream(file.original), UID.ExplicitVRLittleEndian) }
    }
    @Test fun rejectedContextHasNoCStoreResponseOrPayload() {
        Peer(rejectSyntax = UID.DeflatedExplicitVRLittleEndian).use { peer -> val logger = logger(); try {
            val result = run(listOf(fixture(UID.DeflatedExplicitVRLittleEndian)), peer, logger)
            assertEquals(1, result.failedCount); assertNull(result.fileResults.single().responseStatus)
            assertTrue(peer.received.isEmpty()); assertFalse(logger.logsFlow.value.any { it.category == DicomLogCategory.C_STORE_RSP })
            assertEquals(SessionOutcomeStatus.FAILURE, DicomSessionGrouper.groupEvents(logger.logsFlow.value).first().status)
        } finally { logger.shutdown() } }
    }
    @Test fun warningOnlyIsConfirmedAndBriefJournalIsWarning() {
        Peer(statuses = listOf(0xB000, 0xB006)).use { peer -> val logger = logger(); try {
            val result = run(listOf(fixture(), fixture(instance = "1.2.840.999.3")), peer, logger)
            assertEquals(2, result.warningCount)
            assertEquals(listOf(0xB000, 0xB006), result.fileResults.map { it.responseStatus })
            assertEquals(SessionOutcomeStatus.WARNING, DicomSessionGrouper.groupEvents(logger.logsFlow.value).first().status)
        } finally { logger.shutdown() } }
    }
    @Test fun partialSuccessAndRepeatedUidHaveDistinctResponses() {
        Peer(statuses = listOf(0, 0xC000, 0)).use { peer -> val logger = logger(); try {
            val result = run(List(3) { fixture() }, peer, logger)
            assertEquals(2, result.successCount); assertEquals(1, result.failedCount)
            assertEquals(3, result.fileResults.map { it.messageId }.distinct().size)
            val summary = DicomSessionGrouper.groupEvents(logger.logsFlow.value).first()
            assertEquals(SessionOutcomeStatus.WARNING, summary.status)
            assertEquals(listOf(true, false, true), summary.filesTransferred.map { it.isSuccess })
        } finally { logger.shutdown() } }
    }
    @Test fun allFailuresAreNotSuccess() {
        Peer(statuses = listOf(0xC000, 0xA700)).use { peer -> val logger = logger(); try {
            val result = run(List(2) { fixture() }, peer, logger)
            assertEquals(2, result.failedCount); assertEquals(0, result.successCount)
        } finally { logger.shutdown() } }
    }
    @Test fun noResponseIsUnknownAndRemainingFilesAreSkipped() {
        Peer(noResponse = true).use { peer -> val logger = logger(); try {
            val result = run(List(3) { fixture() }, peer, logger)
            assertEquals(result.toString(), 1, result.unconfirmedCount); assertEquals(2, result.skippedCount)
            assertNull(result.fileResults.first().responseStatus)
            assertEquals(SessionOutcomeStatus.UNKNOWN, DicomSessionGrouper.groupEvents(logger.logsFlow.value).first().status)
            assertFalse(logger.logsFlow.value.any { it.category == DicomLogCategory.RELEASE_RP })
        } finally { logger.shutdown() } }
    }
    @Test fun cancelBeforeConnectHasNoPayloadAndCanRetry() {
        Peer().use { peer -> val logger = logger(); try {
            val token = ScuCancellation().apply { request() }
            val first = run(List(2) { fixture() }, peer, logger, token)
            assertTrue(first.isCancelled); assertEquals(2, first.cancelledCount); assertTrue(peer.received.isEmpty())
            assertEquals(1, run(listOf(fixture()), peer, logger).successCount)
        } finally { logger.shutdown() } }
    }
    @Test fun cancelBetweenFilesRetainsConfirmedResultAndStopsRemainder() {
        Peer().use { peer -> val logger = logger(); try {
            val token = ScuCancellation()
            val result = run(List(3) { fixture() }, peer, logger, token) { _, _, status, _ -> if (status == FileSendStatus.SUCCESS) token.request() }
            assertTrue(result.isCancelled); assertEquals(1, result.successCount); assertEquals(2, result.cancelledCount)
            assertEquals(1, peer.received.size)
        } finally { logger.shutdown() } }
    }
    @Test fun cancelWhileWaitingForRspClosesSocketAndAllowsNewSend() {
        Peer(noResponse = true).use { peer -> val logger = logger(); val executor = Executors.newSingleThreadExecutor()
            try {
                val token = ScuCancellation()
                val future = executor.submit<ScuBatchOperationResult> { run(List(2) { fixture() }, peer, logger, token) }
                assertTrue(peer.seen.await(2, TimeUnit.SECONDS)); token.request()
                val result = future.get(3, TimeUnit.SECONDS)
                assertTrue(result.isCancelled); assertEquals(1, result.unconfirmedCount); assertEquals(1, result.cancelledCount)
                Peer().use { next -> assertEquals(1, run(listOf(fixture()), next, logger).successCount) }
            } finally { executor.shutdownNow(); logger.shutdown() }
        }
    }
    @Test fun secondAssociationFailureFinalizesAll121Files() {
        val files = (1..121).map { fixture(sopClass = "1.2.840.999.100.$it", instance = "1.2.840.999.200.$it") }
        Peer(rejectSecondAssociation = true).use { peer -> val logger = logger(); try {
            val result = run(files, peer, logger)
            assertEquals(120, result.successCount); assertEquals(1, result.skippedCount)
            assertFalse(result.summaryMessageRu.startsWith("Все файлы успешно"))
            assertEquals(FileSendStatus.SKIPPED, result.fileResults.last().status)
        } finally { logger.shutdown() } }
    }

    @Test fun isolatedOrthancAcceptsSyntheticDeflatedAndUncompressedObjects() {
        val port = System.getenv("STAGE52_ORTHANC_PORT")?.toIntOrNull()
        org.junit.Assume.assumeNotNull(port)
        val files = listOf(UID.ExplicitVRLittleEndian, UID.ImplicitVRLittleEndian, UID.DeflatedExplicitVRLittleEndian)
            .mapIndexed { index, ts -> fixture(ts, instance = "1.2.840.999.600.${index + 1}") }
        val logger = logger()
        try {
            val originals = files.associate { it.candidate.id to it.original }
            val result = ScuFileBatchSender(logger).send("127.0.0.1", port!!, "TEST_SCU", "TEST_PACS",
                files.map { it.candidate }, { ByteArrayInputStream(originals.getValue(it.id)) },
                { _, _, _, _ -> }, ScuCancellation(), { false })
            assertEquals(result.toString(), 3, result.successCount)
            assertEquals(listOf(0, 0, 0), result.fileResults.map { it.responseStatus })
        } finally { logger.shutdown() }
    }

    @Test fun malformedRspCannotConfirmSuccess() {
        Peer(wrongSopInResponse = true).use { peer -> val logger = logger(); try {
            val result = run(listOf(fixture()), peer, logger)
            assertEquals(1, result.unconfirmedCount); assertNull(result.fileResults.single().responseStatus)
            assertTrue(logger.logsFlow.value.any { it.technicalDetails["Response Valid"] == "false" })
        } finally { logger.shutdown() } }
    }
    @Test fun cancellationDuringBlockedPayloadWriteStopsTransport() {
        Peer(stallPayload = true).use { peer -> val logger = logger(); val executor = Executors.newSingleThreadExecutor()
            try {
                val file = fixture(large = true)
                val token = ScuCancellation()
                val future = executor.submit<ScuBatchOperationResult> { run(listOf(file), peer, logger, token) }
                assertTrue(peer.entered.await(3, TimeUnit.SECONDS)); token.request()
                val result = future.get(3, TimeUnit.SECONDS)
                assertTrue(result.isCancelled); assertEquals(1, result.unconfirmedCount)
                assertEquals(0, result.successCount)
            } finally { executor.shutdownNow(); logger.shutdown() }
        }
    }
    @Test fun cancellationImmediatelyBeforeCommandIsNotRemoteFailure() {
        Peer().use { peer -> val logger = logger(); try {
            val token = ScuCancellation()
            val result = run(listOf(fixture()), peer, logger, token) { _, _, status, _ ->
                if (status == FileSendStatus.SENDING) token.request()
            }
            assertTrue(result.isCancelled); assertEquals(1, result.cancelledCount)
            assertEquals(0, result.unconfirmedCount); assertTrue(peer.received.isEmpty())
        } finally { logger.shutdown() } }
    }
    @Test fun missingReleaseRpDoesNotInventReceiptOrEraseConfirmedStore() {
        Peer().use { peer -> ReleaseProxy(peer.port).use { proxy -> val logger = logger(); try {
            val file = fixture()
            val result = ScuFileBatchSender(logger).send("127.0.0.1", proxy.port, "TEST_SCU", "TEST_PACS",
                listOf(file.candidate), { ByteArrayInputStream(file.original) }, { _, _, _, _ -> },
                ScuCancellation(), { false }, ScuTimeouts(connect = 1000, response = 400, send = 1000, release = 200))
            assertEquals(result.toString(), 1, result.successCount)
            assertTrue(result.closureWarnings.isNotEmpty())
            logger.flushPendingWrites()
            assertFalse(logger.logsFlow.value.any { it.category == DicomLogCategory.RELEASE_RP })
            assertTrue(logger.logsFlow.value.any { it.category == DicomLogCategory.TCP_DISCONNECT })
        } finally { logger.shutdown() } } }
    }
    @Test fun legacyJournalPairsRepeatedUidByMessageId() {
        val events = (1..2).flatMap { id -> listOf(
            DicomLogEntry(id = "rq$id", timestampFormatted = "12:00:00.00$id", category = DicomLogCategory.C_STORE_RQ,
                direction = DicomTrafficDirection.OUTBOUND, associationId = "SCU-1", sessionUid = "legacy-scu",
                title = "C-STORE", summaryLines = emptyList(), technicalDetails = mapOf("Роль" to "SCU", "Message ID" to "$id", "Affected SOP Instance UID" to "1.2.3")),
            DicomLogEntry(id = "rsp$id", timestampFormatted = "12:00:00.01$id", category = DicomLogCategory.C_STORE_RSP,
                direction = DicomTrafficDirection.INBOUND, associationId = "SCU-1", sessionUid = "legacy-scu",
                title = "C-STORE-RSP", summaryLines = emptyList(), technicalDetails = mapOf("Message ID Responded To" to "$id", "Affected SOP Instance UID" to "1.2.3",
                    "Status" to if (id == 1) "0x0000" else "0xC000"))
        ) } + DicomLogEntry(id = "close", timestampFormatted = "12:00:00.100", category = DicomLogCategory.TCP_DISCONNECT,
            direction = DicomTrafficDirection.LOCAL, associationId = "SCU-1", sessionUid = "legacy-scu", title = "closed", summaryLines = emptyList())
        val summary = DicomSessionGrouper.groupEvents(events).single()
        assertEquals(listOf(true, false), summary.filesTransferred.map { it.isSuccess })
        assertEquals(SessionOutcomeStatus.WARNING, summary.status)
    }
    @Test fun legacyRspWithoutStatusCannotInventSuccess() {
        val events = listOf(
            DicomLogEntry("rq", "12:00:00.000", DicomLogCategory.C_STORE_RQ, DicomTrafficDirection.OUTBOUND,
                "SCU-1", "C-STORE", emptyList(), mapOf("SOP Instance UID" to "1.2.3"), sessionUid = "missing-status"),
            DicomLogEntry("rsp", "12:00:00.010", DicomLogCategory.C_STORE_RSP, DicomTrafficDirection.INBOUND,
                "SCU-1", "C-STORE-RSP", emptyList(), mapOf("SOP Instance UID" to "1.2.3"), sessionUid = "missing-status"),
            DicomLogEntry("close", "12:00:00.100", DicomLogCategory.TCP_DISCONNECT, DicomTrafficDirection.LOCAL,
                "SCU-1", "closed", emptyList(), sessionUid = "missing-status")
        )
        val summary = DicomSessionGrouper.groupEvents(events).single()
        assertEquals(SessionOutcomeStatus.UNKNOWN, summary.status)
        assertFalse(summary.filesTransferred.single().isSuccess)
    }
    @Test fun diagnosticWarningsAndMissingResponseDoNotContradictScu() {
        assertTrue(DicomDiagnosticHandbook.lookupDicomStatus(0xB000)!!.technicalName.contains("Coercion"))
        assertTrue(DicomDiagnosticHandbook.lookupDicomStatus(0xB006)!!.technicalName.contains("Discarded"))
        val event = DicomLogEntry("missing", "12:00:00.000", DicomLogCategory.NETWORK_ERROR, DicomTrafficDirection.LOCAL,
            "SCU-1", "C-STORE-RSP не получен", emptyList())
        val recommendation = DicomDiagnosticHandbook.analyzeEntry(event)!!
        assertTrue(recommendation.explanationRu.contains("не подтверждены"))
        assertFalse(recommendation.explanationRu.contains("PACS получил"))
    }
    @Test fun invalidEndpointFailsBeforeOpeningOriginalOrConnecting() {
        val file = fixture(); val logger = logger()
        try {
            val result = ScuFileBatchSender(logger).send("127.0.0.1", 0, "", "INVALID\\AE", listOf(file.candidate),
                { error("Original must not be opened") }, { _, _, _, _ -> }, ScuCancellation(), { false })
            assertEquals(1, result.failedCount); assertNull(result.fileResults.single().responseStatus)
        } finally { logger.shutdown() }
    }

    /** Drops only the peer's real A-RELEASE-RP and leaves client TCP open until its release timeout. */
    private class ReleaseProxy(targetPort: Int) : AutoCloseable {
        private val listener = ServerSocket(0)
        val port = listener.localPort
        private val executor = Executors.newCachedThreadPool()
        private val sockets = CopyOnWriteArrayList<java.net.Socket>()
        init {
            executor.submit {
                val client = listener.accept().also { sockets.add(it) }
                val upstream = java.net.Socket("127.0.0.1", targetPort).also { sockets.add(it) }
                fun forward(from: java.net.Socket, to: java.net.Socket, dropRp: Boolean) {
                    try {
                        val input = from.getInputStream(); val output = to.getOutputStream()
                        while (true) {
                            val header = input.readNBytes(6)
                            if (header.size != 6) break
                            val size = (2..5).fold(0) { n, i -> (n shl 8) or (header[i].toInt() and 255) }
                            require(size in 0..1_048_576)
                            val body = input.readNBytes(size)
                            require(body.size == size)
                            if (dropRp && header[0].toInt() == 6) continue
                            output.write(header); output.write(body); output.flush()
                        }
                    } catch (_: IOException) { }
                }
                executor.submit { forward(client, upstream, false) }
                forward(upstream, client, true)
            }
        }
        override fun close() { listener.close(); sockets.forEach { it.close() }; executor.shutdownNow() }
    }

    private class Peer(val statuses: List<Int> = listOf(0), val noResponse: Boolean = false,
        val rejectSyntax: String? = null, val rejectSecondAssociation: Boolean = false,
        val wrongSopInResponse: Boolean = false, val stallPayload: Boolean = false) : AutoCloseable {
        val port = ServerSocket(0).use { it.localPort }
        val received = CopyOnWriteArrayList<Pair<String, ByteArray>>()
        val seen = CountDownLatch(1)
        val entered = CountDownLatch(1)
        private val readGate = CountDownLatch(1)
        val executor = Executors.newCachedThreadPool()
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val device = Device("synthetic-pacs")
        val connection = Connection().apply { hostname = "127.0.0.1"; this.port = this@Peer.port; socketCloseDelay = 0 }
        init {
            device.executor = executor; device.scheduledExecutor = scheduler; device.addConnection(connection)
            val ae = ApplicationEntity("TEST_PACS").apply {
                isAssociationAcceptor = true; addConnection(connection)
                addTransferCapability(TransferCapability(null, "*", TransferCapability.Role.SCP, "*"))
            }
            device.addApplicationEntity(ae)
            val associations = AtomicInteger()
            device.associationHandler = object : AssociationHandler() {
                override fun negotiate(a: Association, rq: AAssociateRQ): AAssociateAC {
                    if (rejectSecondAssociation && associations.incrementAndGet() == 2) throw AAssociateRJ(1, 1, 1)
                    return super.negotiate(a, rq).also { ac ->
                        rq.presentationContexts.filter { it.transferSyntax == rejectSyntax }.forEach { pc ->
                            // Replace negotiated AC for this context with a rejection.
                            ac.removePresentationContext(ac.getPresentationContext(pc.pcid))
                            ac.addPresentationContext(PresentationContext(pc.pcid, 4, pc.transferSyntax))
                        }
                    }
                }
            }
            device.dimseRQHandler = DicomServiceRegistry().apply {
                addDicomService(object : AbstractDicomService("*") {
                    override fun onDimseRQ(a: Association, pc: PresentationContext, dimse: Dimse, cmd: Attributes, data: Attributes) {
                        error("Streaming handler expected")
                    }
                    override fun onDimseRQ(a: Association, pc: PresentationContext, dimse: Dimse, cmd: Attributes, data: PDVInputStream) {
                        entered.countDown()
                        if (stallPayload) readGate.await(5, TimeUnit.SECONDS)
                        val bytes = data.readBytes(); received.add(pc.transferSyntax to bytes); seen.countDown()
                        if (!noResponse) {
                            val rsp = Commands.mkCStoreRSP(cmd, statuses[(received.size - 1).coerceAtMost(statuses.lastIndex)])
                            if (wrongSopInResponse) rsp.setString(Tag.AffectedSOPInstanceUID, VR.UI, "1.2.840.999.404")
                            a.writeDimseRSP(pc, rsp)
                        }
                    }
                })
            }
            device.bindConnections()
        }
        override fun close() { readGate.countDown(); device.unbindConnections(); executor.shutdownNow(); scheduler.shutdownNow() }
    }
}
