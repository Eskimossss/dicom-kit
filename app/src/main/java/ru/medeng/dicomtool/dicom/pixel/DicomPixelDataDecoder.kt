package ru.medeng.dicomtool.dicom.pixel

import android.graphics.Bitmap
import org.dcm4che3.data.Attributes
import org.dcm4che3.data.Tag
import org.dcm4che3.data.UID
import org.dcm4che3.io.DicomInputStream
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.Locale
import ru.medeng.dicomtool.dicom.tags.BoundedDicomRead
import java.util.concurrent.CancellationException
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Предустановка окна яркости/контрастности (Window Center / Window Width) из DICOM-файла.
 */
data class DicomWindowPreset(
    val name: String,
    val windowCenter: Double,
    val windowWidth: Double,
    val isVoiLutSequence: Boolean = false,
    val voiLutIndex: Int = 0
)

/**
 * Таблица преобразования LUT (Modality LUT (0028,3000) или VOI LUT (0028,3010)).
 */
data class DicomLutTable(
    val numberOfEntries: Int,
    val firstValueMapped: Int,
    val bitsPerEntry: Int,
    val explanation: String,
    val values: IntArray
) {
    fun lookup(inputValue: Int): Int {
        if (values.isEmpty()) return inputValue
        val index = (inputValue - firstValueMapped).coerceIn(0, values.size - 1)
        return values[index]
    }
}

/**
 * Метаданные изображения и параметры медицинского конвейера отображения (PS3.3 C.7.6.3 / C.11).
 */
data class DicomImageDescriptor(
    val rows: Int,
    val columns: Int,
    val samplesPerPixel: Int,
    val photometricInterpretation: String,
    val bitsAllocated: Int,
    val bitsStored: Int,
    val highBit: Int,
    val pixelRepresentation: Int, // 0 = unsigned, 1 = signed (two's complement)
    val numberOfFrames: Int,
    val planarConfiguration: Int, // 0 = chunky (RGBRGB), 1 = planar (RRR...GGG...BBB...)
    val transferSyntaxUid: String,
    val transferSyntaxName: String,
    val sopClassUid: String,
    val presentationIntentType: String,
    val isForProcessing: Boolean,
    val rescaleSlope: Double,
    val rescaleIntercept: Double,
    val hasExplicitRescale: Boolean,
    val modalityLut: DicomLutTable?,
    val voiLutFunction: String, // LINEAR, LINEAR_EXACT, SIGMOID
    val windowPresets: List<DicomWindowPreset>,
    val voiLuts: List<DicomLutTable>,
    val minModalityValue: Double,
    val maxModalityValue: Double,
    val defaultWindowCenter: Double,
    val defaultWindowWidth: Double,
    val pipelineWarnings: List<String>
)

/**
 * Декодированный одиночный кадр DICOM-изображения в компактном буфере памяти.
 * Не хранит все кадры многокадрового исследования одновременно.
 */
class DecodedDicomFrame(
    val filePath: String,
    val frameIndex: Int, // 0-based
    val descriptor: DicomImageDescriptor,
    val grayscale8Buffer: ByteArray? = null,
    val grayscale16Buffer: ShortArray? = null,
    val rgb8Buffer: IntArray? = null
)

/**
 * Результат декодирования DICOM Pixel Data.
 */
sealed class DicomPixelDecodeResult {
    data class Success(val frame: DecodedDicomFrame) : DicomPixelDecodeResult()

    data class UnsupportedFormat(
        val transferSyntaxUid: String,
        val transferSyntaxName: String,
        val photometricInterpretation: String,
        val bitsAllocated: Int,
        val reasonRu: String
    ) : DicomPixelDecodeResult()

    data class DecodeError(
        val reasonRu: String
    ) : DicomPixelDecodeResult()
}

/**
 * Одноэлементный кэш активного декодированного кадра.
 * Позволяет мгновенно пересчитывать WW/WL и инверсию для больших снимков (например, маммографии 3580x2680)
 * без повторного чтения файла с диска и без накопления старых кадров в памяти (защита от OOM / утечек).
 */
class SingleFrameDecodeCache {
    private val lock = Any()
    private var cachedFrame: DecodedDicomFrame? = null
    private var generation = 0L
    fun ticket(): Long = synchronized(lock) { generation }
    fun putIfCurrent(frame: DecodedDicomFrame, ticket: Long) = synchronized(lock) {
        if (ticket == generation) cachedFrame = frame
    }

    fun get(filePath: String, frameIndex: Int): DecodedDicomFrame? = synchronized(lock) {
        val current = cachedFrame
        if (current != null && current.filePath == filePath && current.frameIndex == frameIndex) {
            current
        } else {
            null
        }
    }

    fun put(frame: DecodedDicomFrame) = synchronized(lock) {
        cachedFrame = frame
    }

    fun clear() = synchronized(lock) {
        generation++
        cachedFrame = null
    }
}

/**
 * Настоящий декодер DICOM Pixel Data и медицинский конвейер визуализации (Этап 2.1).
 *
 * Поддерживает:
 * - Несжатые Transfer Syntaxes: Implicit VR Little Endian (1.2.840.10008.1.2) и Explicit VR Little Endian (1.2.840.10008.1.2.1).
 * - Photometric Interpretation: MONOCHROME1, MONOCHROME2, RGB (8-bit, Planar Configuration 0 и 1).
 * - Глубину: 8-bit и 16-bit grayscale, Bits Allocated / Bits Stored / High Bit, Pixel Representation 0 (unsigned) и 1 (signed).
 * - Многокадровые несжатые изображения (потоковый пропуск к нужному кадру без загрузки всех кадров в ОЗУ).
 * - Медицинский конвейер: Raw Pixel -> Modality LUT / Rescale Slope & Intercept -> VOI LUT / Window Center & Width -> MONOCHROME1/2 -> User Invert.
 */
object DicomPixelDataDecoder {
    const val MAX_DECODED_FRAME_BYTES = 48 * 1024 * 1024L
    const val MAX_BITMAP_PIXELS = 4 * 1024 * 1024
    private class UnsupportedTransform(message: String) : IllegalArgumentException(message)

    /** Functional groups are independent macros; simultaneous shared/per-frame definitions are invalid. */
    private fun frameAttributes(attrs: Attributes, frame: Int, frames: Int): Attributes {
        val shared = attrs.getSequence(Tag.SharedFunctionalGroupsSequence)
        val per = attrs.getSequence(Tag.PerFrameFunctionalGroupsSequence)
        if (attrs.getString(Tag.SOPClassUID) in setOf(UID.EnhancedCTImageStorage, UID.EnhancedMRImageStorage, UID.EnhancedPETImageStorage) && shared == null && per == null)
            throw UnsupportedTransform("Enhanced DICOM без Functional Groups не поддерживается")
        if (shared != null && shared.size != 1) throw UnsupportedTransform("Shared Functional Groups должен содержать один Item")
        if (per != null && per.size != frames) throw UnsupportedTransform("Per-frame Functional Groups не соответствует NumberOfFrames")
        val effective = Attributes(attrs, *intArrayOf(Tag.SOPClassUID, Tag.PresentationIntentType, Tag.ModalityLUTSequence,
            Tag.RescaleSlope, Tag.RescaleIntercept, Tag.RescaleType, Tag.WindowCenter, Tag.WindowWidth,
            Tag.WindowCenterWidthExplanation, Tag.VOILUTFunction, Tag.VOILUTSequence).sortedArray())
        for (macro in listOf(Tag.PixelValueTransformationSequence, Tag.FrameVOILUTSequence)) {
            val sharedMacro = shared?.firstOrNull()?.getSequence(macro)
            val frameMacro = per?.get(frame)?.getSequence(macro)
            if (sharedMacro != null && frameMacro != null) throw UnsupportedTransform("Один macro задан одновременно в Shared и Per-frame Functional Groups")
            val tags = if (macro == Tag.PixelValueTransformationSequence)
                listOf(Tag.RescaleSlope, Tag.RescaleIntercept, Tag.RescaleType, Tag.ModalityLUTSequence)
            else listOf(Tag.WindowCenter, Tag.WindowWidth, Tag.WindowCenterWidthExplanation, Tag.VOILUTFunction, Tag.VOILUTSequence)
            if (shared != null || per != null) tags.forEach { effective.remove(it) }
            val selected = frameMacro ?: sharedMacro ?: continue
            if (selected.size != 1) throw UnsupportedTransform("Functional Group macro должен содержать один Item")
            effective.addAll(selected[0])
            if (macro == Tag.PixelValueTransformationSequence &&
                (!effective.containsValue(Tag.RescaleSlope) || !effective.containsValue(Tag.RescaleIntercept)))
                throw UnsupportedTransform("Неполный Pixel Value Transformation: нужны Slope и Intercept")
            if (macro == Tag.FrameVOILUTSequence && !effective.containsValue(Tag.VOILUTSequence) &&
                (!effective.containsValue(Tag.WindowCenter) || !effective.containsValue(Tag.WindowWidth)))
                throw UnsupportedTransform("Неполный Frame VOI: нужны Window Center/Width или LUT")
        }
        if (attrs.contains(Tag.RealWorldValueMappingSequence) || shared?.any { it.contains(Tag.RealWorldValueMappingSequence) } == true ||
            per?.get(frame)?.contains(Tag.RealWorldValueMappingSequence) == true)
            throw UnsupportedTransform("Real World Value Mapping не поддерживается Viewer; исходные теги доступны")
        return effective
    }

    val SUPPORTED_UNCOMPRESSED_TRANSFER_SYNTAXES = setOf(
        UID.ImplicitVRLittleEndian,
        UID.ExplicitVRLittleEndian
    )

    private val SUPPORTED_PHOTOMETRIC = setOf(
        "MONOCHROME1",
        "MONOCHROME2",
        "RGB"
    )

    /**
     * Декодирует выбранный кадр [frameIndex] (0-based) из локального DICOM-файла [file].
     * Если кадр уже находится в [cache], возвращает его без повторного чтения диска.
     */
    fun decodeFrameFromFile(
        file: File,
        frameIndex: Int = 0,
        cache: SingleFrameDecodeCache? = null,
        checkCancelled: () -> Unit = {}
    ): DicomPixelDecodeResult {
        if (!file.exists() || !file.isFile) {
            return DicomPixelDecodeResult.DecodeError("Файл DICOM не найден: ${file.name}")
        }
        return decodeFrameFromSource(
            sourcePathOrUri = file.absolutePath,
            sourceDisplayName = file.name,
            streamOpener = { FileInputStream(file) },
            frameIndex = frameIndex,
            cache = cache, checkCancelled = checkCancelled
        )
    }

    /**
     * Декодирует выбранный кадр [frameIndex] из произвольного источника (File или SAF InputStream).
     */
    fun decodeFrameFromSource(
        sourcePathOrUri: String,
        sourceDisplayName: String = sourcePathOrUri.substringAfterLast('/'),
        streamOpener: () -> InputStream?,
        frameIndex: Int = 0,
        cache: SingleFrameDecodeCache? = null,
        checkCancelled: () -> Unit = {}
    ): DicomPixelDecodeResult {
        checkCancelled()
        val cacheTicket = cache?.ticket()
        val cached = cache?.get(sourcePathOrUri, frameIndex)
        if (cached != null) {
            return DicomPixelDecodeResult.Success(cached)
        }

        return try {
            val rawStream = streamOpener()
                ?: return DicomPixelDecodeResult.DecodeError("Не удалось открыть поток для источника: $sourceDisplayName")

            DicomInputStream(BufferedInputStream(object : java.io.FilterInputStream(rawStream) {
                override fun read(): Int { checkCancelled(); return super.read() }
                override fun read(b: ByteArray, off: Int, len: Int): Int { checkCancelled(); return `in`.read(b, off, len) }
                override fun skip(n: Long): Long { checkCancelled(); return super.skip(n) }
            }, 65536)).use { dis ->
                dis.includeBulkData = DicomInputStream.IncludeBulkData.NO
                BoundedDicomRead.install(dis, checkCancelled)
                val fmi: Attributes? = dis.readFileMetaInformation()
                val headerAttrs: Attributes = dis.readDatasetUntilPixelData()

                val tsuid = (fmi?.getString(Tag.TransferSyntaxUID) ?: dis.transferSyntax ?: UID.ImplicitVRLittleEndian).trim()
                val tsName = runCatching { UID.nameOf(tsuid) }.getOrDefault(tsuid)

                val photometric = headerAttrs.getString(Tag.PhotometricInterpretation, "MONOCHROME2")
                    .trim()
                    .uppercase(Locale.ROOT)
                val bitsAllocated = headerAttrs.getInt(Tag.BitsAllocated, 16)

                // 1. Проверка поддержки Transfer Syntax
                if (tsuid !in SUPPORTED_UNCOMPRESSED_TRANSFER_SYNTAXES) {
                    return DicomPixelDecodeResult.UnsupportedFormat(
                        transferSyntaxUid = tsuid,
                        transferSyntaxName = tsName,
                        photometricInterpretation = photometric,
                        bitsAllocated = bitsAllocated,
                        reasonRu = "Сжатый или неподдерживаемый синтаксис передачи ($tsName, UID: $tsuid). На текущем этапе поддерживаются несжатые Implicit VR Little Endian и Explicit VR Little Endian."
                    )
                }

                // 2. Проверка наличия тега (7FE0,0010) Pixel Data
                if (dis.tag() != Tag.PixelData) {
                    return DicomPixelDecodeResult.DecodeError(
                        "В файле отсутствует тег изображения (7FE0,0010) Pixel Data."
                    )
                }

                val pixelDataLength = dis.unsignedLength()
                if (dis.length() == -1 || pixelDataLength <= 0L) {
                    return DicomPixelDecodeResult.UnsupportedFormat(
                        transferSyntaxUid = tsuid,
                        transferSyntaxName = tsName,
                        photometricInterpretation = photometric,
                        bitsAllocated = bitsAllocated,
                        reasonRu = "Инкапсулированный (фрагментированный) поток Pixel Data неопределённой длины не поддерживается для несжатого декодера."
                    )
                }

                decodeFrameFromStream(
                    filePath = sourcePathOrUri,
                    headerAttrs = headerAttrs,
                    fmi = fmi,
                    transferSyntaxUid = tsuid,
                    transferSyntaxName = tsName,
                    pixelStream = dis,
                    pixelDataLength = pixelDataLength,
                    requestedFrameIndex = frameIndex,
                    cache = cache, checkCancelled = checkCancelled, cacheTicket = cacheTicket
                )
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (e: UnsupportedTransform) {
            DicomPixelDecodeResult.DecodeError("Преобразование DICOM не поддерживается: ${e.message}")
        } catch (oom: OutOfMemoryError) {
            cache?.clear()
            DicomPixelDecodeResult.DecodeError(
                "Недостаточно оперативной памяти Android для декодирования кадра ($sourceDisplayName)."
            )
        } catch (e: Exception) {
            DicomPixelDecodeResult.DecodeError(
                "Ошибка чтения Pixel Data ($sourceDisplayName): ${e.message ?: e.javaClass.simpleName}"
            )
        }
    }

    private fun decodeFrameFromStream(
        filePath: String,
        headerAttrs: Attributes,
        fmi: Attributes?,
        transferSyntaxUid: String,
        transferSyntaxName: String,
        pixelStream: InputStream,
        pixelDataLength: Long,
        requestedFrameIndex: Int,
        cache: SingleFrameDecodeCache?,
        checkCancelled: () -> Unit,
        cacheTicket: Long?
    ): DicomPixelDecodeResult {
        val required = listOf(Tag.Rows, Tag.Columns, Tag.SamplesPerPixel, Tag.PhotometricInterpretation,
            Tag.BitsAllocated, Tag.BitsStored, Tag.HighBit, Tag.PixelRepresentation)
        if (required.any { !headerAttrs.containsValue(it) })
            return DicomPixelDecodeResult.DecodeError("Отсутствуют обязательные параметры Image Pixel; интерпретация пикселей не подтверждена")
        val rows = headerAttrs.getInt(Tag.Rows, 0)
        val columns = headerAttrs.getInt(Tag.Columns, 0)
        val samplesPerPixel = headerAttrs.getInt(Tag.SamplesPerPixel, 1)
        val photometric = headerAttrs.getString(Tag.PhotometricInterpretation, "MONOCHROME2")
            .trim()
            .uppercase(Locale.ROOT)
        val bitsAllocated = headerAttrs.getInt(Tag.BitsAllocated, 16)
        val bitsStored = headerAttrs.getInt(Tag.BitsStored, bitsAllocated)
        val highBit = headerAttrs.getInt(Tag.HighBit, bitsStored - 1)
        val pixelRepresentation = headerAttrs.getInt(Tag.PixelRepresentation, 0)
        val numberOfFrames = headerAttrs.getInt(Tag.NumberOfFrames, 1)
        val planarConfiguration = headerAttrs.getInt(Tag.PlanarConfiguration, 0)
        if (numberOfFrames < 1 || requestedFrameIndex !in 0 until numberOfFrames ||
            bitsAllocated !in listOf(8, 16) || bitsStored !in 1..bitsAllocated || highBit !in (bitsStored - 1) until bitsAllocated ||
            pixelRepresentation !in 0..1) return DicomPixelDecodeResult.DecodeError("Некорректные Frame Index / NumberOfFrames / Bits Stored / High Bit / Pixel Representation")
        val effectiveAttrs = frameAttributes(headerAttrs, requestedFrameIndex, numberOfFrames)
        if (photometric == "RGB" && (bitsStored != 8 || highBit != 7 || pixelRepresentation != 0))
            return DicomPixelDecodeResult.DecodeError("RGB требует unsigned 8-bit samples")

        if (rows <= 0 || columns <= 0) {
            return DicomPixelDecodeResult.DecodeError(
                "Некорректное разрешение снимка: Rows=$rows, Columns=$columns."
            )
        }

        if (photometric !in SUPPORTED_PHOTOMETRIC) {
            return DicomPixelDecodeResult.UnsupportedFormat(
                transferSyntaxUid = transferSyntaxUid,
                transferSyntaxName = transferSyntaxName,
                photometricInterpretation = photometric,
                bitsAllocated = bitsAllocated,
                reasonRu = "Неподдерживаемая фотометрическая интерпретация: $photometric (поддерживаются MONOCHROME1, MONOCHROME2, RGB)."
            )
        }

        if (photometric == "RGB") {
            if (!headerAttrs.containsValue(Tag.PlanarConfiguration))
                return DicomPixelDecodeResult.DecodeError("RGB без Planar Configuration не поддерживается")
            if (samplesPerPixel != 3 || bitsAllocated != 8) {
                return DicomPixelDecodeResult.UnsupportedFormat(
                    transferSyntaxUid = transferSyntaxUid,
                    transferSyntaxName = transferSyntaxName,
                    photometricInterpretation = photometric,
                    bitsAllocated = bitsAllocated,
                    reasonRu = "Для RGB поддерживаются только 8-битные 3-канальные изображения (получено: SamplesPerPixel=$samplesPerPixel, BitsAllocated=$bitsAllocated)."
                )
            }
            if (planarConfiguration != 0 && planarConfiguration != 1) {
                return DicomPixelDecodeResult.UnsupportedFormat(
                    transferSyntaxUid = transferSyntaxUid,
                    transferSyntaxName = transferSyntaxName,
                    photometricInterpretation = photometric,
                    bitsAllocated = bitsAllocated,
                    reasonRu = "Неподдерживаемая Planar Configuration=$planarConfiguration для RGB (поддерживаются 0 и 1)."
                )
            }
        } else {
            // MONOCHROME1 / MONOCHROME2
            if (samplesPerPixel != 1 || (bitsAllocated != 8 && bitsAllocated != 16)) {
                return DicomPixelDecodeResult.UnsupportedFormat(
                    transferSyntaxUid = transferSyntaxUid,
                    transferSyntaxName = transferSyntaxName,
                    photometricInterpretation = photometric,
                    bitsAllocated = bitsAllocated,
                    reasonRu = "Для $photometric поддерживаются 8-bit и 16-bit изображения (получено: SamplesPerPixel=$samplesPerPixel, BitsAllocated=$bitsAllocated)."
                )
            }
        }

        val numPixels = rows.toLong() * columns.toLong()
        val decodedBytes = numPixels * if (photometric == "RGB") 4 else bitsAllocated / 8
        val frameBudget = min(MAX_DECODED_FRAME_BYTES, Runtime.getRuntime().maxMemory() / 4)
        if (numPixels <= 0L || decodedBytes > frameBudget) {
            return DicomPixelDecodeResult.DecodeError(
                "Размер кадра ($columns × $rows) превышает допустимый предел памяти."
            )
        }
        val pixelCount = numPixels.toInt()
        val bytesPerSample = bitsAllocated / 8
        val frameBytes = numPixels * samplesPerPixel.toLong() * bytesPerSample.toLong()
        if (frameBytes * numberOfFrames > pixelDataLength) return DicomPixelDecodeResult.DecodeError("Pixel Data короче объявленного NumberOfFrames")

        val safeFrameIndex = requestedFrameIndex
        val skipBytes = safeFrameIndex.toLong() * frameBytes
        if (skipBytes + frameBytes > pixelDataLength) {
            return DicomPixelDecodeResult.DecodeError(
                "Недостаточно данных в Pixel Data для чтения кадра #${safeFrameIndex + 1} (требуется ${skipBytes + frameBytes} байт, доступно $pixelDataLength байт)."
            )
        }

        // Пропускаем предыдущие кадры без загрузки их в память
        checkCancelled()
        skipFully(pixelStream, skipBytes)

        var gray8Buf: ByteArray? = null
        var gray16Buf: ShortArray? = null
        var rgb8Buf: IntArray? = null

        var rawMin = Int.MAX_VALUE
        var rawMax = Int.MIN_VALUE

        val lowBit = (highBit + 1 - bitsStored).coerceAtLeast(0)
        val mask = if (bitsStored >= 16) 0xFFFF else ((1 shl bitsStored) - 1)
        val signBit = if (pixelRepresentation == 1 && bitsStored >= 1) (1 shl (bitsStored - 1)) else 0
        val signExtendSub = if (pixelRepresentation == 1 && bitsStored >= 1) (1 shl bitsStored) else 0

        if (photometric == "RGB") {
            val rgbPixels = IntArray(pixelCount)
            if (planarConfiguration == 0) {
                // Chunky: R1 G1 B1 R2 G2 B2 ...
                val totalFrameBytes = pixelCount * 3
                val chunk = ByteArray(65535) // кратно 3!
                var pixelIdx = 0
                var remaining = totalFrameBytes
                var carryCount = 0
                val carry = ByteArray(3)

                while (remaining > 0 && pixelIdx < pixelCount) {
                    val toRead = min(chunk.size - carryCount, remaining)
                    val read = pixelStream.read(chunk, carryCount, toRead)
                    if (read <= 0) throw EOFException("Неожиданный конец потока RGB Pixel Data")
                    remaining -= read
                    val available = carryCount + read
                    var i = 0
                    while (i + 2 < available && pixelIdx < pixelCount) {
                        val r = chunk[i].toInt() and 0xFF
                        val g = chunk[i + 1].toInt() and 0xFF
                        val b = chunk[i + 2].toInt() and 0xFF
                        rgbPixels[pixelIdx++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                        i += 3
                    }
                    carryCount = available - i
                    for (c in 0 until carryCount) {
                        carry[c] = chunk[i + c]
                    }
                    for (c in 0 until carryCount) {
                        chunk[c] = carry[c]
                    }
                }
            } else {
                // Planar: R1..Rn, G1..Gn, B1..Bn
                val chunk = ByteArray(65536)
                java.util.Arrays.fill(rgbPixels, 0xFF000000.toInt())
                for (channel in 0..2) {
                    var index = 0
                    while (index < pixelCount) {
                        val count = min(chunk.size, pixelCount - index)
                        readFully(pixelStream, chunk, 0, count)
                        for (i in 0 until count) rgbPixels[index + i] = rgbPixels[index + i] or ((chunk[i].toInt() and 255) shl (16 - channel * 8))
                        index += count
                    }
                }
            }
            rgb8Buf = rgbPixels
            rawMin = 0
            rawMax = 255
        } else if (bitsAllocated == 8) {
            val buf = ByteArray(pixelCount)
            readFully(pixelStream, buf, 0, pixelCount)
            for (i in 0 until pixelCount) {
                val rawByte = buf[i].toInt() and 0xFF
                val unsignedVal = (rawByte ushr lowBit) and mask
                val storedVal = if (signBit != 0 && (unsignedVal and signBit) != 0) {
                    unsignedVal - signExtendSub
                } else {
                    unsignedVal
                }
                if (storedVal < rawMin) rawMin = storedVal
                if (storedVal > rawMax) rawMax = storedVal
            }
            gray8Buf = buf
        } else {
            // 16-bit Little Endian потоковое чтение кусками по 64 КБ прямо в единственный ShortArray(pixelCount)
            val shorts = ShortArray(pixelCount)
            val chunk = ByteArray(65536)
            var sampleIdx = 0
            var remainingBytes = pixelCount * 2
            var hasCarryByte = false
            var carryByte = 0

            while (remainingBytes > 0 && sampleIdx < pixelCount) {
                val toRead = min(chunk.size, remainingBytes)
                val read = pixelStream.read(chunk, 0, toRead)
                if (read <= 0) throw EOFException("Неожиданный конец потока 16-bit Pixel Data")
                remainingBytes -= read

                var offset = 0
                if (hasCarryByte && read > 0) {
                    val b1 = chunk[0].toInt() and 0xFF
                    val word = (b1 shl 8) or carryByte
                    shorts[sampleIdx++] = word.toShort()
                    val unsignedVal = (word ushr lowBit) and mask
                    val storedVal = if (signBit != 0 && (unsignedVal and signBit) != 0) {
                        unsignedVal - signExtendSub
                    } else {
                        unsignedVal
                    }
                    if (storedVal < rawMin) rawMin = storedVal
                    if (storedVal > rawMax) rawMax = storedVal
                    hasCarryByte = false
                    offset = 1
                }

                val limit = read - 1
                while (offset < limit && sampleIdx < pixelCount) {
                    val b0 = chunk[offset].toInt() and 0xFF
                    val b1 = chunk[offset + 1].toInt() and 0xFF
                    val word = (b1 shl 8) or b0
                    shorts[sampleIdx++] = word.toShort()
                    val unsignedVal = (word ushr lowBit) and mask
                    val storedVal = if (signBit != 0 && (unsignedVal and signBit) != 0) {
                        unsignedVal - signExtendSub
                    } else {
                        unsignedVal
                    }
                    if (storedVal < rawMin) rawMin = storedVal
                    if (storedVal > rawMax) rawMax = storedVal
                    offset += 2
                }

                if (offset < read) {
                    carryByte = chunk[offset].toInt() and 0xFF
                    hasCarryByte = true
                }
            }
            gray16Buf = shorts
        }

        if (rawMin > rawMax) {
            rawMin = 0
            rawMax = 255
        }

        val descriptor = buildImageDescriptor(
            headerAttrs = effectiveAttrs,
            fmi = fmi,
            rows = rows,
            columns = columns,
            samplesPerPixel = samplesPerPixel,
            photometric = photometric,
            bitsAllocated = bitsAllocated,
            bitsStored = bitsStored,
            highBit = highBit,
            pixelRepresentation = pixelRepresentation,
            numberOfFrames = numberOfFrames,
            planarConfiguration = planarConfiguration,
            transferSyntaxUid = transferSyntaxUid,
            transferSyntaxName = transferSyntaxName,
            rawMin = rawMin,
            rawMax = rawMax
        )

        val decodedFrame = DecodedDicomFrame(
            filePath = filePath,
            frameIndex = safeFrameIndex,
            descriptor = descriptor,
            grayscale8Buffer = gray8Buf,
            grayscale16Buffer = gray16Buf,
            rgb8Buffer = rgb8Buf
        )

        checkCancelled()
        if (cacheTicket != null) cache?.putIfCurrent(decodedFrame, cacheTicket)
        return DicomPixelDecodeResult.Success(decodedFrame)
    }

    private fun buildImageDescriptor(
        headerAttrs: Attributes,
        fmi: Attributes?,
        rows: Int,
        columns: Int,
        samplesPerPixel: Int,
        photometric: String,
        bitsAllocated: Int,
        bitsStored: Int,
        highBit: Int,
        pixelRepresentation: Int,
        numberOfFrames: Int,
        planarConfiguration: Int,
        transferSyntaxUid: String,
        transferSyntaxName: String,
        rawMin: Int,
        rawMax: Int
    ): DicomImageDescriptor {
        val warnings = ArrayList<String>()

        val sopClassUid = headerAttrs.getString(Tag.SOPClassUID)
            ?: fmi?.getString(Tag.MediaStorageSOPClassUID)
            ?: ""
        val presentationIntent = headerAttrs.getString(Tag.PresentationIntentType, "")
            .trim()
            .uppercase(Locale.ROOT)

        val isForProcessing = presentationIntent == "FOR PROCESSING" ||
            sopClassUid == UID.DigitalMammographyXRayImageStorageForProcessing ||
            sopClassUid == UID.DigitalXRayImageStorageForProcessing ||
            sopClassUid == UID.DigitalIntraOralXRayImageStorageForProcessing

        // 1. Modality LUT Sequence (0028,3000) или Rescale Slope (0028,1053) / Rescale Intercept (0028,1052)
        val modalityLut = parseLutSequence(headerAttrs, Tag.ModalityLUTSequence, "Modality LUT")
        val hasExplicitSlope = headerAttrs.containsValue(Tag.RescaleSlope)
        val hasExplicitIntercept = headerAttrs.containsValue(Tag.RescaleIntercept)
        val rescaleSlope = headerAttrs.getDouble(Tag.RescaleSlope, 1.0)
        val rescaleIntercept = headerAttrs.getDouble(Tag.RescaleIntercept, 0.0)
        if (!rescaleSlope.isFinite() || rescaleSlope == 0.0 || !rescaleIntercept.isFinite())
            throw UnsupportedTransform("Некорректный Rescale Slope/Intercept")

        val minModality: Double
        val maxModality: Double
        if (modalityLut != null) {
            val vMin = modalityLut.lookup(rawMin).toDouble()
            val vMax = modalityLut.lookup(rawMax).toDouble()
            minModality = min(vMin, vMax)
            maxModality = max(vMin, vMax)
        } else {
            val v1 = rawMin * rescaleSlope + rescaleIntercept
            val v2 = rawMax * rescaleSlope + rescaleIntercept
            minModality = min(v1, v2)
            maxModality = max(v1, v2)
        }

        // 2. VOI LUT Sequence (0028,3010) и Window Center (0028,1050) / Window Width (0028,1051)
        val voiLuts = parseAllLutSequences(headerAttrs, Tag.VOILUTSequence, "VOI LUT")
        val rawVoiFunc = headerAttrs.getString(Tag.VOILUTFunction, "LINEAR")
            .trim()
            .uppercase(Locale.ROOT)
        val voiLutFunction = when (rawVoiFunc) {
            "", "LINEAR" -> "LINEAR"
            "LINEAR_EXACT" -> "LINEAR_EXACT"
            "SIGMOID" -> "SIGMOID"
            else -> {
                throw UnsupportedTransform("VOI LUT Function '$rawVoiFunc' не поддерживается")
            }
        }

        val wcDoubles = headerAttrs.getDoubles(Tag.WindowCenter) ?: DoubleArray(0)
        val wwDoubles = headerAttrs.getDoubles(Tag.WindowWidth) ?: DoubleArray(0)
        val explanations = headerAttrs.getStrings(Tag.WindowCenterWidthExplanation) ?: emptyArray()

        val presets = ArrayList<DicomWindowPreset>()
        val pairCount = min(wcDoubles.size, wwDoubles.size)
        if (wcDoubles.size != wwDoubles.size || wcDoubles.any { !it.isFinite() } ||
            wwDoubles.any { !it.isFinite() || it <= 0 || (voiLutFunction == "LINEAR" && it < 1) })
            throw UnsupportedTransform("Некорректные Window Center/Width")
        for (i in 0 until pairCount) {
            val wc = wcDoubles[i]
            val ww = wwDoubles[i]
            if (ww > 0.0) {
                val expl = explanations.getOrNull(i)?.trim()?.takeIf { it.isNotEmpty() }
                val label = if (expl != null) {
                    "$expl (${ww.roundToInt()}/${wc.roundToInt()})"
                } else if (pairCount > 1) {
                    "Окно #${i + 1} (${ww.roundToInt()}/${wc.roundToInt()})"
                } else {
                    "DICOM (${ww.roundToInt()}/${wc.roundToInt()})"
                }
                presets.add(
                    DicomWindowPreset(
                        name = label,
                        windowCenter = wc,
                        windowWidth = ww,
                        isVoiLutSequence = false
                    )
                )
            }
        }

        voiLuts.forEachIndexed { idx, lut ->
            presets.add(
                DicomWindowPreset(
                    name = lut.explanation.ifBlank { "VOI LUT #${idx + 1}" },
                    windowCenter = (minModality + maxModality) / 2.0,
                    windowWidth = (maxModality - minModality).coerceAtLeast(1.0),
                    isVoiLutSequence = true,
                    voiLutIndex = idx
                )
            )
        }

        val autoCenter = (minModality + maxModality) / 2.0
        val autoWidth = (maxModality - minModality).coerceAtLeast(1.0)

        val defaultWc: Double
        val defaultWw: Double

        if (isForProcessing) {
            if (presets.isNotEmpty() && !presets.first().isVoiLutSequence) {
                defaultWc = presets.first().windowCenter
                defaultWw = presets.first().windowWidth
                warnings.add("Изображение типа FOR PROCESSING (сырые данные детектора). Применены значения WW/WL из заголовка DICOM.")
            } else {
                defaultWc = autoCenter
                defaultWw = autoWidth
                warnings.add(
                    "Изображение типа FOR PROCESSING без тегов презентационного окна WW/WL. " +
                        "Окно рассчитано по фактическому диапазону кадра [${minModality.roundToInt()}..${maxModality.roundToInt()}]."
                )
            }
        } else if (presets.isNotEmpty() && !presets.first().isVoiLutSequence) {
            defaultWc = presets.first().windowCenter
            defaultWw = presets.first().windowWidth
        } else if (voiLuts.isNotEmpty()) {
            defaultWc = autoCenter
            defaultWw = autoWidth
            warnings.add("В файле задана таблица VOI LUT Sequence (0028,3010). Доступно переключение между VOI LUT и ручным WW/WL.")
        } else {
            defaultWc = autoCenter
            defaultWw = autoWidth
            if (photometric != "RGB") {
                warnings.add(
                    "Теги Window Center (0028,1050) и Window Width (0028,1051) отсутствуют. " +
                        "Начальное окно выбрано по диапазону значений кадра [${minModality.roundToInt()}..${maxModality.roundToInt()}]."
                )
            }
        }

        // Добавляем пресет авто-диапазона по мин/макс пикселям кадра
        if (photometric != "RGB") {
            presets.add(
                DicomWindowPreset(
                    name = "Авто Мин/Макс (${autoWidth.roundToInt()}/${autoCenter.roundToInt()})",
                    windowCenter = autoCenter,
                    windowWidth = autoWidth,
                    isVoiLutSequence = false
                )
            )
        }

        return DicomImageDescriptor(
            rows = rows,
            columns = columns,
            samplesPerPixel = samplesPerPixel,
            photometricInterpretation = photometric,
            bitsAllocated = bitsAllocated,
            bitsStored = bitsStored,
            highBit = highBit,
            pixelRepresentation = pixelRepresentation,
            numberOfFrames = numberOfFrames,
            planarConfiguration = planarConfiguration,
            transferSyntaxUid = transferSyntaxUid,
            transferSyntaxName = transferSyntaxName,
            sopClassUid = sopClassUid,
            presentationIntentType = presentationIntent.ifEmpty { "FOR PRESENTATION" },
            isForProcessing = isForProcessing,
            rescaleSlope = rescaleSlope,
            rescaleIntercept = rescaleIntercept,
            hasExplicitRescale = hasExplicitSlope || hasExplicitIntercept,
            modalityLut = modalityLut,
            voiLutFunction = voiLutFunction,
            windowPresets = if (photometric == "RGB") emptyList() else presets,
            voiLuts = if (photometric == "RGB") emptyList() else voiLuts,
            minModalityValue = minModality,
            maxModalityValue = maxModality,
            defaultWindowCenter = if (photometric == "RGB") 128.0 else defaultWc,
            defaultWindowWidth = if (photometric == "RGB") 255.0 else defaultWw,
            pipelineWarnings = warnings
        )
    }

    /**
     * Выполняет медицинский конвейер отображения кадра в массив пикселей ARGB_8888:
     * 1. Извлечение исходного значения с учётом BitsStored, HighBit и знакового PixelRepresentation (two's complement).
     * 2. Modality LUT (0028,3000) или Rescale Slope / Rescale Intercept (0028,1052 / 0028,1053).
     * 3. VOI LUT (0028,3010) или Window Center / Window Width (LINEAR, LINEAR_EXACT, SIGMOID по PS3.3 C.11.2.1.2).
     * 4. Учёт MONOCHROME1 (минимум = белый) и MONOCHROME2 (минимум = чёрный).
     * 5. Пользовательская инверсия [userInverted].
     *
     * Для больших маммографических снимков (например, 3580 × 2680) использует предвычисленную таблицу LUT на 65536 значений,
     * что исключает 9.6 миллионов плавающих операций на каждый шаг WW/WL.
     */
    private fun interface PixelMapper { fun map(index: Int): Int }
    private fun pixelMapper(frame: DecodedDicomFrame, ww: Double, wc: Double, inverted: Boolean, voi: Int?): PixelMapper {
        val d = frame.descriptor
        if (d.photometricInterpretation == "RGB") {
            val rgb = requireNotNull(frame.rgb8Buffer)
            return PixelMapper { index -> if (inverted) rgb[index] xor 0x00FFFFFF else rgb[index] }
        }
        val signed = d.pixelRepresentation == 1
        val lut = buildGrayscaleLut(if (d.bitsAllocated == 8) 256 else 65536,
            d.highBit + 1 - d.bitsStored, (1 shl d.bitsStored) - 1,
            if (signed) 1 shl (d.bitsStored - 1) else 0, if (signed) 1 shl d.bitsStored else 0,
            d.modalityLut, d.rescaleSlope, d.rescaleIntercept, voi?.let { d.voiLuts.getOrNull(it) },
            wc, ww, d.voiLutFunction, (d.photometricInterpretation == "MONOCHROME1") xor inverted)
        return PixelMapper { index -> lut[frame.grayscale8Buffer?.let { it[index].toInt() and 255 }
            ?: (requireNotNull(frame.grayscale16Buffer)[index].toInt() and 65535)] }
    }

    fun renderFrameToArgbPixels(frame: DecodedDicomFrame, windowWidth: Double, windowCenter: Double,
        userInverted: Boolean, useVoiLutIndex: Int? = null): IntArray {
        val count = frame.descriptor.rows.toLong() * frame.descriptor.columns
        require(count * 4 <= MAX_DECODED_FRAME_BYTES) { "Полноразмерный ARGB превышает лимит памяти" }
        val pixel = pixelMapper(frame, windowWidth, windowCenter, userInverted, useVoiLutIndex)
        return IntArray(count.toInt()) { pixel.map(it) }
    }

    data class RenderSize(val width: Int, val height: Int)
    fun renderSize(columns: Int, rows: Int, maxTextureDimension: Int = 4096): RenderSize {
        require(columns > 0 && rows > 0 && maxTextureDimension > 0)
        val scale = max(1.0, max(max(columns, rows).toDouble() / maxTextureDimension,
            kotlin.math.sqrt(columns.toDouble() * rows / min(MAX_BITMAP_PIXELS.toLong(), Runtime.getRuntime().maxMemory() / 32))))
        return RenderSize((columns / scale).toInt().coerceAtLeast(1), (rows / scale).toInt().coerceAtLeast(1))
    }

    /** Same color mapping at all sizes; row writes avoid a full second ARGB buffer. */
    fun renderFrameToBitmap(frame: DecodedDicomFrame, windowWidth: Double, windowCenter: Double,
        userInverted: Boolean, useVoiLutIndex: Int? = null, maxTextureDimension: Int = 4096,
        checkCancelled: () -> Unit = {}): Bitmap {
        checkCancelled()
        val d = frame.descriptor
        val size = renderSize(d.columns, d.rows, maxTextureDimension)
        val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
        try {
            val pixel = pixelMapper(frame, windowWidth, windowCenter, userInverted, useVoiLutIndex)
            val row = IntArray(size.width)
            for (y in 0 until size.height) {
                checkCancelled()
                val sy = (y.toLong() * d.rows / size.height).toInt()
                for (x in row.indices) row[x] = pixel.map(sy * d.columns + (x.toLong() * d.columns / size.width).toInt())
                bitmap.setPixels(row, 0, size.width, 0, y, size.width, 1)
            }
            checkCancelled()
            return bitmap
        } catch (e: Throwable) { bitmap.recycle(); throw e }
    }

    private fun buildGrayscaleLut(
        tableSize: Int,
        lowBit: Int,
        mask: Int,
        signBit: Int,
        signExtendSub: Int,
        modalityLut: DicomLutTable?,
        rescaleSlope: Double,
        rescaleIntercept: Double,
        activeVoiLut: DicomLutTable?,
        windowCenter: Double,
        windowWidth: Double,
        voiLutFunction: String,
        invertPolarity: Boolean
    ): IntArray {
        val lut = IntArray(tableSize)
        val voiScaleMax = if (activeVoiLut != null) {
            ((1 shl activeVoiLut.bitsPerEntry.coerceIn(8, 16)) - 1).coerceAtLeast(255).toDouble()
        } else {
            255.0
        }

        for (rawWord in 0 until tableSize) {
            val unsignedVal = (rawWord ushr lowBit) and mask
            val storedVal = if (signBit != 0 && (unsignedVal and signBit) != 0) {
                unsignedVal - signExtendSub
            } else {
                unsignedVal
            }

            // Шаг 2: Modality LUT или Rescale Slope / Intercept
            val modalityVal: Double = if (modalityLut != null) {
                modalityLut.lookup(storedVal).toDouble()
            } else {
                storedVal * rescaleSlope + rescaleIntercept
            }

            // Шаг 3: VOI LUT или Window Center / Window Width
            val voiOut8: Int = if (activeVoiLut != null) {
                val rawLutOut = activeVoiLut.lookup(modalityVal.roundToInt())
                ((rawLutOut.toDouble() / voiScaleMax) * 255.0).roundToInt().coerceIn(0, 255)
            } else {
                applyWindowFormula(modalityVal, windowCenter, windowWidth, voiLutFunction)
            }

            // Шаг 4 и 5: MONOCHROME1 / MONOCHROME2 и пользовательская инверсия
            val gray8 = if (invertPolarity) (255 - voiOut8) else voiOut8
            lut[rawWord] = (0xFF shl 24) or (gray8 shl 16) or (gray8 shl 8) or gray8
        }
        return lut
    }

    /**
     * Формулы DICOM PS3.3 Section C.11.2.1.2.1 (LINEAR), C.11.2.1.3.2 (LINEAR_EXACT) и C.11.2.1.3.1 (SIGMOID).
     */
    fun applyWindowFormula(
        x: Double,
        c: Double,
        w: Double,
        function: String = "LINEAR"
    ): Int {
        return when (function) {
            "LINEAR_EXACT" -> {
                val safeW = if (w <= 0.0) 1.0 else w
                val norm = ((x - c) / safeW) + 0.5
                (norm.coerceIn(0.0, 1.0) * 255.0).roundToInt().coerceIn(0, 255)
            }
            "SIGMOID" -> {
                val safeW = if (w <= 0.0) 1.0 else w
                val v = 255.0 / (1.0 + exp(-4.0 * (x - c) / safeW))
                v.roundToInt().coerceIn(0, 255)
            }
            else -> {
                // Стандартная формула DICOM LINEAR (PS3.3 C.11.2.1.2.1):
                if (w <= 1.0) {
                    if (x <= c - 0.5) 0 else 255
                } else {
                    val lower = c - 0.5 - (w - 1.0) / 2.0
                    val upper = c - 0.5 + (w - 1.0) / 2.0
                    when {
                        x <= lower -> 0
                        x > upper -> 255
                        else -> (((x - (c - 0.5)) / (w - 1.0) + 0.5) * 255.0)
                            .roundToInt()
                            .coerceIn(0, 255)
                    }
                }
            }
        }
    }

    private fun parseLutSequence(attrs: Attributes, seqTag: Int, defaultName: String): DicomLutTable? {
        return parseAllLutSequences(attrs, seqTag, defaultName).firstOrNull()
    }

    private fun parseAllLutSequences(attrs: Attributes, seqTag: Int, defaultName: String): List<DicomLutTable> {
        val seq = attrs.getSequence(seqTag) ?: return emptyList()
        val result = ArrayList<DicomLutTable>()
        for (item in seq) {
            val desc = item.getInts(Tag.LUTDescriptor)
            if (desc == null || desc.size != 3) throw UnsupportedTransform("Некорректный LUT Descriptor")
            val rawNumEntries = desc[0] and 0xFFFF
            val numEntries = if (rawNumEntries == 0) 65536 else rawNumEntries
            val firstValueMapped = desc[1]
            val bitsPerEntry = desc[2]
            if (bitsPerEntry !in 8..16) throw UnsupportedTransform("Неподдерживаемая разрядность LUT")
            val explanation = item.getString(Tag.LUTExplanation, defaultName).trim()

            val lutValues = extractLutDataArray(item, numEntries) ?: throw UnsupportedTransform("Недостаточная длина LUT Data")
            result.add(
                DicomLutTable(
                    numberOfEntries = numEntries,
                    firstValueMapped = firstValueMapped,
                    bitsPerEntry = bitsPerEntry,
                    explanation = explanation,
                    values = lutValues
                )
            )
        }
        return result
    }

    private fun extractLutDataArray(item: Attributes, expectedEntries: Int): IntArray? {
        val ints = runCatching { item.getInts(Tag.LUTData) }.getOrNull()
        if (ints != null && ints.isNotEmpty()) {
            if (ints.size >= expectedEntries) {
                return IntArray(expectedEntries) { ints[it] and 0xFFFF }
            }
            // Иногда 16-битные слова упакованы по 2 в 32-битные int
            val bytes = runCatching { item.getBytes(Tag.LUTData) }.getOrNull()
            if (bytes != null && bytes.size >= expectedEntries * 2) {
                return IntArray(expectedEntries) { idx ->
                    val b0 = bytes[idx * 2].toInt() and 0xFF
                    val b1 = bytes[idx * 2 + 1].toInt() and 0xFF
                    (b1 shl 8) or b0
                }
            }
            return ints
        }
        val bytes = runCatching { item.getBytes(Tag.LUTData) }.getOrNull() ?: return null
        if (bytes.size >= expectedEntries * 2) {
            return IntArray(expectedEntries) { idx ->
                val b0 = bytes[idx * 2].toInt() and 0xFF
                val b1 = bytes[idx * 2 + 1].toInt() and 0xFF
                (b1 shl 8) or b0
            }
        }
        return null
    }

    private fun skipFully(stream: InputStream, bytesToSkip: Long) {
        var remaining = bytesToSkip
        val skipBuf = ByteArray(32768)
        while (remaining > 0L) {
            val skipped = stream.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else {
                val toRead = min(skipBuf.size.toLong(), remaining).toInt()
                val read = stream.read(skipBuf, 0, toRead)
                if (read <= 0) throw EOFException("Не удалось перемотать Pixel Data к нужному кадру")
                remaining -= read.toLong()
            }
        }
    }

    private fun readFully(stream: InputStream, target: ByteArray, offset: Int, length: Int) {
        var totalRead = 0
        while (totalRead < length) {
            val read = stream.read(target, offset + totalRead, length - totalRead)
            if (read <= 0) throw EOFException("Неожиданный конец потока Pixel Data (прочитано $totalRead из $length байт)")
            totalRead += read
        }
    }
}
