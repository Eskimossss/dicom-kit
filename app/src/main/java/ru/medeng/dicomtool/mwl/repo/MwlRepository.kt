package ru.medeng.dicomtool.mwl.repo

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.medeng.dicomtool.mwl.model.MwlPatient
import ru.medeng.dicomtool.mwl.model.MwlScheduledStudy

data class MwlSnapshot(val patients: List<MwlPatient>, val studies: List<MwlScheduledStudy>)

interface MwlRepository {
    /** A failed load preserves the last readable state but blocks snapshots/commits. */
    val readErrorFlow: StateFlow<String?> get() = NO_READ_ERROR
    companion object {
        private val NO_READ_ERROR = MutableStateFlow<String?>(null).asStateFlow()
    }
    fun snapshot(): MwlSnapshot = MwlSnapshot(getAllPatients(), getAllStudies())
    val patientsFlow: StateFlow<List<MwlPatient>>
    val studiesFlow: StateFlow<List<MwlScheduledStudy>>

    fun getAllPatients(): List<MwlPatient>
    fun getPatientById(id: String): MwlPatient?
    fun savePatient(patient: MwlPatient): MwlPatient
    fun deletePatient(patientInternalId: String, cascadeStudies: Boolean = true): Boolean

    fun getAllStudies(): List<MwlScheduledStudy>
    fun getStudyById(id: String): MwlScheduledStudy?
    fun getStudiesForPatient(patientInternalId: String): List<MwlScheduledStudy>
    fun saveStudy(study: MwlScheduledStudy): MwlScheduledStudy
    fun deleteStudy(studyId: String): Boolean
    fun duplicateStudy(studyId: String): MwlScheduledStudy?

    fun addSyntheticBatch(items: List<Pair<MwlPatient, MwlScheduledStudy>>)
    fun clearAll()
    fun exportSyntheticJson(): String
    fun importSyntheticJson(json: String): Int
    fun reload()
}
