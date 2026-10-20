package ru.medeng.dicomtool.mwl.engine

import org.dcm4che3.data.Attributes
import org.dcm4che3.data.ElementDictionary
import org.dcm4che3.data.VR

object MwlDatasetInspector {
    fun lines(attributes: Attributes): List<String> = buildList {
        fun visit(ds: Attributes, depth: Int) {
            if (depth > 32) { add("  ".repeat(depth) + "[maximum display depth]"); return }
            for (tag in ds.tags()) {
                val indent = "  ".repeat(depth)
                val vr = ds.getVR(tag)
                val name = ElementDictionary.keywordOf(tag, ds.privateCreatorOf(tag)).orEmpty()
                val label = "(%04X,%04X) %s %s".format(tag ushr 16, tag and 0xffff, vr?.name ?: "UN", name)
                if (vr == VR.SQ) {
                    add(indent + label + " (Sequence)")
                    ds.getSequence(tag)?.forEachIndexed { index, item -> add(indent + "  Item #$index"); visit(item, depth + 2) }
                } else {
                    val value = if (vr?.isStringType == true || vr in setOf(VR.US, VR.SS, VR.UL, VR.SL, VR.FL, VR.FD, VR.AT)) {
                        runCatching { ds.getStrings(tag)?.joinToString("\\").orEmpty() }.getOrElse { "[unreadable value]" }
                    } else runCatching { "[${ds.getBytes(tag)?.size ?: 0} bytes]" }.getOrDefault("[binary value]")
                    add(indent + label + " = " + value)
                }
            }
        }
        visit(attributes, 0)
    }
}
