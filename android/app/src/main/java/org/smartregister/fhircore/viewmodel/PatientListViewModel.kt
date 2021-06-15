/*
 * Copyright 2021 Ona Systems Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.smartregister.fhircore.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import ca.uhn.fhir.rest.param.ParamPrefixEnum
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.ResourceNotFoundException
import com.google.android.fhir.search.Order
import com.google.android.fhir.search.search
import com.google.android.fhir.sync.SyncConfiguration
import com.google.android.fhir.sync.SyncData
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import org.hl7.fhir.r4.model.Immunization
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.ResourceType
import org.smartregister.fhircore.data.SamplePatients
import org.smartregister.fhircore.domain.Pagination
import org.smartregister.fhircore.util.Utils

private const val OBSERVATIONS_JSON_FILENAME = "sample_observations_bundle.json"

/**
 * The ViewModel helper class for PatientItemRecyclerViewAdapter, that is responsible for preparing
 * data for UI.
 */
class PatientListViewModel(application: Application, private val fhirEngine: FhirEngine) :
  AndroidViewModel(application) {

  // Make sample Fhir Patients and Observations available, in case needed for demo.
  private val jsonStringObservations = getAssetFileAsString(OBSERVATIONS_JSON_FILENAME)

  private val samplePatients = SamplePatients()

  private val observations = samplePatients.getObservationItems(jsonStringObservations)
  private val liveObservations: MutableLiveData<List<ObservationItem>> =
    MutableLiveData(observations)

  val liveSearchedPaginatedPatients: MutableLiveData<Pair<List<PatientItem>, Pagination>> by lazy {
    MutableLiveData<Pair<List<PatientItem>, Pagination>>()
  }

  fun getObservations(): LiveData<List<ObservationItem>> {
    return liveObservations
  }

  val liveSearchPatient: MutableLiveData<PatientItem> by lazy { MutableLiveData<PatientItem>() }

  val liveSearchImmunization: MutableLiveData<List<Immunization>> by lazy {
    MutableLiveData<List<Immunization>>()
  }

  fun searchResults(query: String? = null, page: Int = 0, pageSize: Int = 10) {
    viewModelScope.launch {
      var searchResults: List<Patient> =
        fhirEngine.search {
          Utils.addBasePatientFilter(this)

          apply {
            if (query?.isNotBlank() == true) {
              filter(Patient.FAMILY) {
                prefix = ParamPrefixEnum.EQUAL
                value = query.trim()
              }
            }
          }

          sort(Patient.GIVEN, Order.ASCENDING)
        }

      searchResults =
        searchResults.filter { it.nameMatchesFilter(query) || it.idMatchesFilter(query) }
      var startIndex = page * pageSize
      startIndex = if (searchResults.size > startIndex) startIndex else 0
      var endIndex = pageSize + (page * pageSize)
      endIndex = if (searchResults.size > endIndex) endIndex else searchResults.size
      searchResults = searchResults.subList(startIndex, endIndex)

      liveSearchedPaginatedPatients.value =
        Pair(
          samplePatients.getPatientItems(searchResults),
          Pagination(totalItems = count(query), pageSize = pageSize, currentPage = page)
        )
    }
  }

  fun fetchPatientStatus(id: String): LiveData<PatientStatus> {
    val status = MutableLiveData<PatientStatus>()
    val formatter = SimpleDateFormat("dd-MM-yy", Locale.US)

    viewModelScope.launch {
      val searchResults: List<Immunization> =
        fhirEngine.search { filter(Immunization.PATIENT) { value = "Patient/$id" } }

      val computedStatus = computeVaccineStatus(searchResults)

      status.value =
        PatientStatus(
          status = computedStatus,
          details =
            if (searchResults.isNotEmpty())
              formatter.format(searchResults[searchResults.size - 1].recorded)
            else ""
        )
    }
    return status
  }

  internal fun computeVaccineStatus(searchResults: List<Immunization>): VaccineStatus {
    // check database for immunizations
    val cal: Calendar = Calendar.getInstance()
    cal.add(Calendar.DATE, -28)
    val overDueStart: Date = cal.time

    return when {
      searchResults.isEmpty() -> VaccineStatus.DUE
      searchResults.size == 1 && searchResults[0].recorded.before(overDueStart) ->
        VaccineStatus.OVERDUE
      searchResults.size == 1 -> VaccineStatus.PARTIAL
      else -> VaccineStatus.VACCINATED
    }
  }

  protected fun Patient.nameMatchesFilter(filter: String?): Boolean {
    return (filter == null ||
      (!this.name.isEmpty() &&
        (this.name.first().family.contains(filter, true) ||
          this.name.first().given?.first()?.asStringValue()?.contains(filter, true) == true)))
  }

  protected fun Patient.idMatchesFilter(filter: String?): Boolean {
    return (filter == null || filter.equals(this.idElement.idPart))
  }

  /** Basic search for immunizations */
  fun searchImmunizations(patientId: String? = null) {
    viewModelScope.launch {
      val searchResults: List<Immunization> =
        fhirEngine.search { filter(Immunization.PATIENT) { value = "Patient/$patientId" } }
      liveSearchImmunization.value = searchResults
    }
  }

  /** Returns number of records in database */
  // TODO This is a wasteful query that will be replaced by implementation of
  // https://github.com/google/android-fhir/issues/458
  // https://github.com/opensrp/fhircore/issues/107
  private suspend fun count(query: String? = null): Int {
    val searchResults: List<Patient> =
      fhirEngine.search {
        Utils.addBasePatientFilter(this)

        apply {
          if (query?.isNotBlank() == true) {
            filter(Patient.FAMILY) {
              prefix = ParamPrefixEnum.EQUAL
              value = query.trim()
            }
          }
        }
        sort(Patient.GIVEN, Order.ASCENDING)
        count = 10000
        from = 0
      }
    return searchResults.size
  }

  fun getPatientItem(id: String) {
    var patientItems: List<PatientItem>? = null
    viewModelScope.launch {
      val patient = fhirEngine.load(Patient::class.java, id)
      patientItems = samplePatients.getPatientItems(listOf(patient))
    }

    liveSearchPatient.value = patientItems?.get(0)
  }

  fun runSync() {
    viewModelScope.launch {
      fhirEngine.syncUpload()

      /** Download Immediately from the server */
      val syncData =
        listOf(
          SyncData(
            resourceType = ResourceType.Patient,
            params = mapOf("address-city" to "NAIROBI")
          ),
          SyncData(resourceType = ResourceType.Immunization)
        )
      fhirEngine.sync(SyncConfiguration(syncData = syncData))
    }
  }

  fun isPatientExists(id: String): LiveData<Result<Boolean>> {
    val result = MutableLiveData<Result<Boolean>>()
    viewModelScope.launch {
      try {
        fhirEngine.load(Patient::class.java, id)
        result.value = Result.success(true)
      } catch (e: ResourceNotFoundException) {
        result.value = Result.failure(e)
      }
    }
    return result
  }

  fun clearPatientList() {
    liveSearchedPaginatedPatients.value =
      Pair(emptyList(), Pagination(totalItems = 0, pageSize = 1, currentPage = 0))
  }

  private fun getAssetFileAsString(filename: String): String {
    return this.getApplication<Application>()
      .applicationContext
      .assets
      .open(filename)
      .bufferedReader()
      .use { it.readText() }
  }

  /** The Patient's details for display purposes. */
  data class PatientItem(
    val id: String,
    val name: String,
    val gender: String,
    val dob: String,
    val html: String,
    val phone: String,
    val logicalId: String
  ) {
    override fun toString(): String = name
  }

  data class PatientStatus(val status: VaccineStatus, val details: String)

  enum class VaccineStatus {
    VACCINATED,
    PARTIAL,
    OVERDUE,
    DUE
  }

  /** The Observation's details for display purposes. */
  data class ObservationItem(
    val id: String,
    val code: String,
    val effective: String,
    val value: String
  ) {
    override fun toString(): String = code
  }
}

class PatientListViewModelFactory(
  private val application: Application,
  private val fhirEngine: FhirEngine
) : ViewModelProvider.Factory {
  override fun <T : ViewModel?> create(modelClass: Class<T>): T {
    if (modelClass.isAssignableFrom(PatientListViewModel::class.java)) {
      return PatientListViewModel(application, fhirEngine) as T
    }
    throw IllegalArgumentException("Unknown ViewModel class")
  }
}
