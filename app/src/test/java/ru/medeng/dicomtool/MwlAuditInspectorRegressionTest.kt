package ru.medeng.dicomtool

import org.junit.Assert.*
import org.junit.Test
import org.dcm4che3.data.*
import ru.medeng.dicomtool.mwl.engine.MwlDatasetInspector

class MwlAuditInspectorRegressionTest {
    @Test fun audit13_arbitrarySequencesItemsUnknownVrAndMultipleValuesAreVisible() {
        val ds = Attributes().apply {
            newSequence(Tag.RequestedProcedureCodeSequence, 2).apply {
                add(Attributes().apply { setString(Tag.CodeValue, VR.SH, "CODE-A"); newSequence(Tag.ConceptCodeSequence, 1).add(Attributes().apply { setString(Tag.CodeMeaning, VR.LO, "DEEP") }) })
                add(Attributes().apply { setString(Tag.CodeValue, VR.SH, "CODE-B") })
            }
            setBytes(0x77770010, VR.UN, byteArrayOf(1, 2, 3))
            setString(Tag.ImageType, VR.CS, "ORIGINAL", "PRIMARY")
        }
        val lines = MwlDatasetInspector.lines(ds).joinToString("\n")
        assertTrue(lines.contains("SQ")); assertTrue(lines.contains("Item #1"))
        assertTrue(lines.contains("CODE-A")); assertTrue(lines.contains("CODE-B")); assertTrue(lines.contains("DEEP"))
        assertTrue(lines.contains("UN")); assertTrue(lines.contains("3 bytes")); assertTrue(lines.contains("ORIGINAL\\PRIMARY"))
    }
}
