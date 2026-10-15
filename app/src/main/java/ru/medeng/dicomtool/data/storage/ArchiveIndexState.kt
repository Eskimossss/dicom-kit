package ru.medeng.dicomtool.data.storage

/** Explicit completeness: inaccessible external files are never presented as a current full archive. */
data class ArchiveIndexState(val isLoading: Boolean = false, val warning: String? = null, val indexedFiles: Int = 0)
