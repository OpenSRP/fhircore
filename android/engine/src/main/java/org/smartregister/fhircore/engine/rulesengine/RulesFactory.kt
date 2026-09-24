/*
 * Copyright 2021-2024 Ona Systems, Inc
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

package org.smartregister.fhircore.engine.rulesengine

import android.content.Context
import ca.uhn.fhir.context.FhirContext
import com.google.android.fhir.datacapture.extensions.logicalId
import com.google.android.fhir.db.ResourceNotFoundException
import com.google.android.fhir.search.Order
import com.jayway.jsonpath.Configuration
import com.jayway.jsonpath.JsonPath
import com.jayway.jsonpath.Option
import com.jayway.jsonpath.PathNotFoundException
import dagger.hilt.android.qualifiers.ApplicationContext
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.runBlocking
import org.apache.commons.jexl3.JexlEngine
import org.hl7.fhir.r4.model.Base
import org.hl7.fhir.r4.model.Enumerations.DataType
import org.hl7.fhir.r4.model.Reference
import org.hl7.fhir.r4.model.Resource
import org.hl7.fhir.r4.model.ResourceType
import org.hl7.fhir.r4.model.Task
import org.jeasy.rules.api.Facts
import org.jeasy.rules.api.Rule
import org.jeasy.rules.api.Rules
import org.joda.time.DateTime
import org.ocpsoft.prettytime.PrettyTime
import org.smartregister.fhircore.engine.BuildConfig
import org.smartregister.fhircore.engine.configuration.ConfigurationRegistry
import org.smartregister.fhircore.engine.data.local.DefaultRepository
import org.smartregister.fhircore.engine.domain.model.RelatedResourceCount
import org.smartregister.fhircore.engine.domain.model.RepositoryResourceData
import org.smartregister.fhircore.engine.domain.model.RuleConfig
import org.smartregister.fhircore.engine.domain.model.ServiceMemberIcon
import org.smartregister.fhircore.engine.domain.model.ServiceStatus
import org.smartregister.fhircore.engine.rulesengine.services.DateService
import org.smartregister.fhircore.engine.rulesengine.services.LocationService
import org.smartregister.fhircore.engine.util.DispatcherProvider
import org.smartregister.fhircore.engine.util.SharedPreferenceKey
import org.smartregister.fhircore.engine.util.extension.SDF_DD_MMM_YYYY
import org.smartregister.fhircore.engine.util.extension.SDF_E_MMM_DD_YYYY
import org.smartregister.fhircore.engine.util.extension.daysPassed
import org.smartregister.fhircore.engine.util.extension.encodeResourceToString
import org.smartregister.fhircore.engine.util.extension.extractAge
import org.smartregister.fhircore.engine.util.extension.extractBirthDate
import org.smartregister.fhircore.engine.util.extension.extractGender
import org.smartregister.fhircore.engine.util.extension.extractLogicalIdUuid
import org.smartregister.fhircore.engine.util.extension.formatDate
import org.smartregister.fhircore.engine.util.extension.generateRules
import org.smartregister.fhircore.engine.util.extension.isOverDue
import org.smartregister.fhircore.engine.util.extension.parseDate
import org.smartregister.fhircore.engine.util.extension.prettifyDate
import org.smartregister.fhircore.engine.util.extension.translationPropertyKey
import org.smartregister.fhircore.engine.util.fhirpath.FhirPathDataExtractor
import org.smartregister.fhircore.engine.util.helper.LocalizationHelper
import timber.log.Timber

@Singleton
class RulesFactory
@Inject
constructor(
  @ApplicationContext val context: Context,
  val configurationRegistry: ConfigurationRegistry,
  val fhirPathDataExtractor: FhirPathDataExtractor,
  val dispatcherProvider: DispatcherProvider,
  val locationService: LocationService,
  val fhirContext: FhirContext,
  val jexlEngine: JexlEngine,
  val defaultRepository: DefaultRepository,
) : RulesListener() {
  val rulesEngineService = RulesEngineService()

  @get:Synchronized @set:Synchronized private var facts: Facts = Facts()

  fun generateRules(ruleConfigs: List<RuleConfig>): Rules = ruleConfigs.generateRules(jexlEngine)

  /**
   * This function executes the actions defined in the [Rule] s generated from the provided list of
   * [RuleConfig] against the [Facts] populated by the provided FHIR [Resource] s available in the
   * [RepositoryResourceData.resource], [RepositoryResourceData.relatedResourcesMap] and
   * [RepositoryResourceData.relatedResourcesCountMap]. All related resources of same type are
   * flattened in a map for ease of usage in the rule engine.
   */
  @Synchronized
  fun fireRules(
    rules: Rules,
    repositoryResourceData: RepositoryResourceData?,
    params: Map<String, String>,
  ): Map<String, Any> {
    facts.clear() // Reset current facts
    facts =
      Facts().apply {
        put(FHIR_PATH, fhirPathDataExtractor)
        put(DATA, mutableMapOf<String, Any>().apply { putAll(params) })
        put(LOCATION_SERVICE, locationService)
        put(SERVICE, rulesEngineService)
        put(DATE_SERVICE, DateService)
      }
    if (repositoryResourceData != null) {
      with(repositoryResourceData) {
        facts.apply {
          put(resourceConfigId ?: resource.resourceType.name, resource)
          relatedResourcesMap.addToFacts(this)
          relatedResourcesCountMap.addToFacts(this)

          // Populate the facts map with secondary resource data flatten base and related
          // resources
          secondaryRepositoryResourceData
            ?.groupBy { it.resourceConfigId ?: it.resource.resourceType.name }
            ?.forEach { entry -> put(entry.key, entry.value.map { it.resource }) }

          secondaryRepositoryResourceData?.forEach { repoResourceData ->
            repoResourceData.relatedResourcesMap.forEach { entry ->
              val existingRelatedResourceList = get<MutableList<Resource>>(entry.key)
              if (existingRelatedResourceList == null) {
                put(entry.key, mutableListOf<Resource>())
              }
              get<MutableList<Resource>>(entry.key).addAll(entry.value)
            }

            repoResourceData.relatedResourcesCountMap.forEach { entry ->
              val existingRelatedResourceCountList =
                get<MutableList<RelatedResourceCount>>(entry.key)
              if (existingRelatedResourceCountList == null) {
                put(entry.key, mutableListOf<RelatedResourceCount>())
              }
              get<MutableList<RelatedResourceCount>>(entry.key).addAll(entry.value)
            }
          }
        }
      }
    }
    if (BuildConfig.DEBUG) {
      val timeToFireRules = measureTimeMillis { rulesEngine.fire(rules, facts) }
      Timber.d("Rule executed in $timeToFireRules millisecond(s)")
    } else {
      rulesEngine.fire(rules, facts)
    }
    return facts.get(DATA) as Map<String, Any>
  }

  fun fireRules(rules: Rules, baseResource: Resource? = null): Map<String, Any> {
    facts.clear() // Reset current facts
    facts =
      Facts().apply {
        put(FHIR_PATH, fhirPathDataExtractor)
        put(DATA, mutableMapOf<String, Any>())
        put(DATE_SERVICE, DateService)
        if (baseResource != null) {
          put(baseResource.resourceType.name, baseResource)
        }
      }
    if (BuildConfig.DEBUG) {
      val timeToFireRules = measureTimeMillis { rulesEngine.fire(rules, facts) }
      Timber.d("Rule executed in $timeToFireRules millisecond(s)")
    } else {
      rulesEngine.fire(rules, facts)
    }
    return facts.get(DATA) as Map<String, Any>
  }

  /** Provide access to utility functions accessible to the users defining rules in JSON format. */
  inner class RulesEngineService {

    private var conf: Configuration =
      Configuration.defaultConfiguration().apply { addOptions(Option.DEFAULT_PATH_LEAF_TO_NULL) }

    /**
     * This function creates a property key from the string [value] and uses the key to retrieve the
     * correct translation from the string.properties file. If no translation is found, it falls
     * back to the original value.
     */
    fun translate(value: String): String {
      val translationKey = value.translationPropertyKey()
      val template = "{{$translationKey}}"
      val translatedText =
        configurationRegistry.localizationHelper.parseTemplate(
          LocalizationHelper.STRINGS_BASE_BUNDLE_NAME,
          Locale.getDefault(),
          template,
        )
      return if (translatedText == template) value else translatedText
    }

    /**
     * This method retrieves a list of relatedResources for a given resource from the facts map It
     * fetches a list of facts of the given [relatedResourceKey] then iterates through this list in
     * order to return a list of all resources whose subject reference matches the logical Id of the
     * [resource]
     *
     * @param resource The parent resource for which the related resources will be retrieved
     * @param relatedResourceKey The key representing the relatedResources in the map
     * @param referenceFhirPathExpression A fhir path expression used to retrieve the subject
     *   reference Id from the related resources
     */
    @Suppress("UNCHECKED_CAST")
    @JvmOverloads
    fun retrieveRelatedResources(
      resource: Resource,
      relatedResourceKey: String,
      referenceFhirPathExpression: String?,
      relatedResourcesMap: Map<String, List<Resource>>? = null,
      isRevInclude: Boolean = true,
    ): List<Resource> {
      val value: List<Resource> =
        relatedResourcesMap?.get(relatedResourceKey)
          ?: if (facts.getFact(relatedResourceKey) != null) {
            facts.getFact(relatedResourceKey).value as List<Resource>? ?: emptyList()
          } else {
            emptyList()
          }

      if (referenceFhirPathExpression.isNullOrEmpty()) {
        return value
      }

      // Reverse search; look for related resource that references the provided resource
      return if (isRevInclude) {
        value.filter { res ->
          fhirPathDataExtractor.extractData(res, referenceFhirPathExpression).all {
            resource.logicalId == it.primitiveValue().extractLogicalIdUuid()
          }
        }
      } else {
        // Forward search; extract value provided resource, then search resources with matching id
        value.filter { res ->
          fhirPathDataExtractor.extractData(resource, referenceFhirPathExpression).all {
            res.logicalId == it.primitiveValue().extractLogicalIdUuid()
          }
        }
      }
    }

    /**
     * This method retrieve a parentResource for a given relatedResource from the facts map It
     * fetches a list of facts of the given [parentResourceType] then iterates through this list in
     * order to return a resource whose logical id matches the subject reference retrieved via
     * fhirPath from the [childResource]
     * - The logical Id of the parentResource [parentResourceType]
     * - The ResourceType the parentResources belong to [fhirPathExpression]
     * - A fhir path expression used to retrieve the logical Id from the parent resources
     */
    @Suppress("UNCHECKED_CAST")
    fun retrieveParentResource(
      childResource: Resource,
      parentResourceType: String,
      fhirPathExpression: String,
    ): Resource? {
      val value = facts.getFact(parentResourceType).value as List<Resource>
      val parentResourceId =
        fhirPathDataExtractor.extractValue(childResource, fhirPathExpression).extractLogicalIdUuid()
      return value.find { it.logicalId == parentResourceId }
    }

    /**
     * This function returns a true or false value if any ( [matchAll]= false) or all ( [matchAll]=
     * true) of the [resources] satisfy the [conditionalFhirPathExpression] provided
     *
     * [resources] List of resources the expressions are run against [conditionalFhirPathExpression]
     * An expression to run against the provided resources [matchAll] When true the function checks
     * whether all of the resources fulfill the expression provided
     *
     * ```
     *            When false the function checks whether any of the resources fulfills the expression provided
     * ```
     */
    @JvmOverloads
    fun evaluateToBoolean(
      resources: List<Resource>?,
      conditionalFhirPathExpression: String,
      matchAll: Boolean = false,
    ): Boolean =
      if (matchAll) {
        resources?.all { base ->
          fhirPathDataExtractor.extractData(base, conditionalFhirPathExpression).any {
            it.isBooleanPrimitive && it.primitiveValue().toBoolean()
          }
        } ?: false
      } else {
        resources?.any { base ->
          fhirPathDataExtractor.extractData(base, conditionalFhirPathExpression).any {
            it.isBooleanPrimitive && it.primitiveValue().toBoolean()
          }
        } ?: false
      }

    /**
     * This function transform the provided [resources] into a list of [label] given that the
     * [fhirPathExpression] for each of the resources is evaluated to true.
     *
     * Example: To retrieve a list of household member icons, find Patients aged 5yrs and below then
     * return Comma Separated Values of 'CHILD' (to be serialized into [ServiceMemberIcon]) for
     * each.
     */
    @JvmOverloads
    fun mapResourcesToLabeledCSV(
      resources: List<Resource>?,
      fhirPathExpression: String,
      label: String,
      matchAllExtraConditions: Boolean? = false,
      vararg extraConditions: Any? = emptyArray(),
    ): String =
      resources
        ?.mapNotNull { resource ->
          if (
            fhirPathDataExtractor.extractData(resource, fhirPathExpression).any { base ->
              base.isBooleanPrimitive && base.primitiveValue().toBoolean()
            } &&
              if (matchAllExtraConditions == true && extraConditions.isNotEmpty()) {
                extraConditions.all { it is Boolean && it == true }
              } else if (matchAllExtraConditions == false && extraConditions.isNotEmpty()) {
                extraConditions.any { it is Boolean && it == true }
              } else {
                true
              }
          ) {
            label
          } else {
            null
          }
        }
        ?.distinctBy { it }
        ?.joinToString(",") ?: ""

    /**
     * Transforms a [resource] into [label] if the [fhirPathExpression] is evaluated to true.
     *
     * Example: To retrieve the icon for household member who is a child, evaluate their age to be
     * less than 5years, if 'true' return 'CHILD' (to be serialized to [ServiceMemberIcon])
     */
    fun mapResourceToLabeledCSV(
      resource: Resource,
      fhirPathExpression: String,
      label: String,
    ): String = mapResourcesToLabeledCSV(listOf(resource), fhirPathExpression, label)

    /** Extracts a Patient/RelatedPerson's age */
    fun extractAge(resource: Resource): String {
      return resource.extractAge(context)
    }

    /** Extracts and returns a translated string for the gender in the resource */
    fun extractGender(resource: Resource): String {
      return resource.extractGender(context)
    }

    /** This function extracts a Patient/RelatedPerson's DOB from the FHIR resource */
    fun extractDOB(resource: Resource, dateFormat: String): String {
      return SimpleDateFormat(dateFormat, Locale.ENGLISH).run {
        resource.extractBirthDate()?.let { format(it) }
      } ?: ""
    }

    /**
     * This function takes [inputDate] and returns a difference (for examples 7 hours, 2 day, 5
     * months, 3 years etc)
     */
    fun prettifyDate(inputDate: Date): String = inputDate.prettifyDate()

    /**
     * This function takes [inputDate] and returns a difference (for examples 15, 30 etc) between
     * inputDate and the currentDate
     */
    fun daysPassed(inputDate: String, pattern: String = SDF_DD_MMM_YYYY): String =
      inputDate.parseDate(pattern)?.daysPassed().toString()

    /**
     * This function takes [inputDateString] like 2022-7-1 and returns a difference (for examples 7
     * hours ago, 2 days ago, 5 months ago, 3 years ago etc) [inputDateString] can give given as
     * 2022-02 or 2022
     */
    fun prettifyDate(inputDateString: String): String {
      return PrettyTime().format(DateTime(inputDateString).toDate())
    }

    /**
     * This function fetches assignment data separately that is; PractitionerId,
     * PractitionerCareTeam, PractitionerOrganization and PractitionerLocation, using rules on the
     * configs.
     */
    fun extractPractitionerInfoFromSharedPrefs(practitionerKey: String): String? {
      val key = SharedPreferenceKey.valueOf(practitionerKey)
      try {
        return when (key) {
          SharedPreferenceKey.PRACTITIONER_ID ->
            configurationRegistry.sharedPreferencesHelper.read(
              SharedPreferenceKey.PRACTITIONER_ID.name,
              "",
            )
          SharedPreferenceKey.CARE_TEAM ->
            configurationRegistry.sharedPreferencesHelper.read(
              SharedPreferenceKey.CARE_TEAM.name,
              "",
            )
          SharedPreferenceKey.ORGANIZATION ->
            configurationRegistry.sharedPreferencesHelper.read(
              SharedPreferenceKey.ORGANIZATION.name,
              "",
            )
          SharedPreferenceKey.PRACTITIONER_LOCATION ->
            configurationRegistry.sharedPreferencesHelper.read(
              SharedPreferenceKey.PRACTITIONER_LOCATION.name,
              "",
            )
          SharedPreferenceKey.PRACTITIONER_LOCATION_ID ->
            configurationRegistry.sharedPreferencesHelper.read(
              SharedPreferenceKey.PRACTITIONER_LOCATION_ID.name,
              "",
            )
          else -> ""
        }
      } catch (exception: Exception) {
        if (exception is IllegalArgumentException) {
          Timber.e("key is not a member of practitioner keys: ", exception)
        } else {
          Timber.e("An exception occurred while fetching your key from sharedPrefs: ", exception)
        }
      }
      return ""
    }

    /**
     * This function is responsible for formatting a date for whatever expectedFormat we need. It
     * takes an [inputDate] string along with the [inputDateFormat] so it can convert it to the Date
     * and then it gives output in expected Format, [expectedFormat] is by default (Example: Mon,
     * Nov 5 2021)
     */
    @JvmOverloads
    fun formatDate(
      inputDate: String,
      inputDateFormat: String,
      expectedFormat: String = SDF_E_MMM_DD_YYYY,
    ): String? = inputDate.parseDate(inputDateFormat)?.formatDate(expectedFormat)

    /**
     * This function is responsible for formatting a date for whatever expectedFormat we need. It
     * takes an input a [date] as input and then it gives output in expected Format,
     * [expectedFormat] is by default (Example: Mon, Nov 5 2021)
     */
    @JvmOverloads
    fun formatDate(date: Date, expectedFormat: String = SDF_E_MMM_DD_YYYY): String =
      date.formatDate(expectedFormat)

    /**
     * This function generates a random 6-digit integer between a hard-coded range. It may generate
     * duplicate outputs on subsequent function calls.
     *
     * @return An Integer.
     */
    fun generateRandomSixDigitInt(): Int =
      (INCLUSIVE_SIX_DIGIT_MINIMUM..INCLUSIVE_SIX_DIGIT_MAXIMUM).random()

    /**
     * This function filters resources provided the condition extracted from the
     * [conditionalFhirPathExpression] is met. Returns the original source or empty resources list
     * if FHIR path expression is null.
     */
    fun filterResources(
      resources: List<Resource>?,
      conditionalFhirPathExpression: String?,
    ): List<Resource> {
      if (conditionalFhirPathExpression.isNullOrBlank()) {
        return resources ?: emptyList()
      }
      return resources?.filter {
        fhirPathDataExtractor.extractValue(it, conditionalFhirPathExpression).toBoolean()
      } ?: emptyList()
    }

    /**
     * Filters [Resource] s by comparing the given [value] against the value obtained after
     * extracting data on each [Resource] using FHIRPath with the provided [fhirPathExpression]. The
     * value is cast to the [DataType] to facilitate comparison using the [compareTo] function which
     * returns zero if this object is equal to the specified other object, a negative number if it's
     * less than other, or a positive number if it's greater than other.
     *
     * Please NOTE the order of comparison. The value extracted from FHIRPath is compared against
     * the provided [value]
     */
    fun filterResources(
      resources: List<Resource>?,
      fhirPathExpression: String,
      dataType: String,
      value: Any,
      vararg compareToResult: Any,
    ) =
      runCatching {
          resources?.filter {
            fhirPathDataExtractor.extractData(it, fhirPathExpression).any { base ->
              when (DataType.valueOf(dataType)) {
                DataType.BOOLEAN ->
                  base.castToBoolean(base).value.compareTo(value as Boolean) in compareToResult
                DataType.DATE ->
                  base.castToDate(base).value.compareTo(value as Date) in compareToResult
                DataType.DATETIME ->
                  base.castToDateTime(base).value.compareTo(value as Date) in compareToResult
                DataType.DECIMAL ->
                  base.castToDecimal(base).value.compareTo(value as BigDecimal) in compareToResult
                DataType.INTEGER ->
                  base.castToInteger(base).value.compareTo(value as Int) in compareToResult
                DataType.STRING ->
                  base.castToString(base).value.compareTo(value as String) in compareToResult
                else -> {
                  false
                }
              }
            }
          }
        }
        .getOrNull()

    fun filterResourcesByJsonPath(
      resources: List<Resource>?,
      jsonPathExpression: String,
      dataType: String,
      value: Any,
      vararg compareToResult: Any,
    ): List<Resource>? {
      if (resources.isNullOrEmpty() || jsonPathExpression.isBlank()) return null

      val expression =
        if (jsonPathExpression.startsWith("\$")) {
          jsonPathExpression
        } else {
          jsonPathExpression.replace(
            jsonPathExpression.substring(0, jsonPathExpression.indexOf(".")),
            "\$",
          )
        }

      return runCatching {
          resources.filter {
            val document = JsonPath.using(conf).parse(it.encodeResourceToString())
            val result: Any = document.read(expression)

            when (DataType.valueOf(dataType.uppercase())) {
              DataType.BOOLEAN -> (result as Boolean).compareTo(value as Boolean) in compareToResult
              DataType.DATE -> (result as Date).compareTo(value as Date) in compareToResult
              DataType.DATETIME ->
                (result as DateTime).compareTo(value as DateTime) in compareToResult
              DataType.DECIMAL ->
                (result as BigDecimal).compareTo(value as BigDecimal) in compareToResult
              DataType.INTEGER -> (result as Int).compareTo(value as Int) in compareToResult
              DataType.STRING -> (result as String).compareTo(value as String) in compareToResult
              else -> {
                false
              }
            }
          }
        }
        .getOrNull()
    }

    /**
     * This function combines all string indexes to a list separated by the separator and regex
     * defined by the content author
     */
    @JvmOverloads
    fun joinToString(
      sourceString: MutableList<String?>,
      regex: String = DEFAULT_REGEX,
      separator: String = DEFAULT_STRING_SEPARATOR,
    ): String {
      sourceString.removeIf { it == null }
      val inputString = sourceString.joinToString()
      return regex.toRegex().findAll(inputString).joinToString(separator) { it.groupValues[1] }
    }

    /** This function returns a list of resources with a limit of [limit] resources */
    fun limitTo(source: List<Any>?, limit: Int?): List<Any> {
      if (limit == null || limit <= 0) {
        return emptyList()
      }
      return source?.take(limit) ?: emptyList()
    }

    @JvmOverloads
    fun mapResourcesToExtractedValues(
      resources: List<Resource>?,
      fhirPathExpression: String,
    ): List<Any> {
      if (fhirPathExpression.isEmpty()) {
        return emptyList()
      }
      return resources?.map { fhirPathDataExtractor.extractValue(it, fhirPathExpression) }
        ?: emptyList()
    }

    /**
     * This function combines all the string values retrieved from the [resources] using the
     * [fhirPathExpression] to a list separated by the [separator]
     *
     * e.g for a provided list of Patients we can extract a string containing the family names using
     * the 'Patient.name.family' as the [fhirPathExpression] and [ | ] as the [separator] the
     * returned string would be [John | Jane | James]
     */
    @JvmOverloads
    fun mapResourcesToExtractedValues(
      resources: List<Resource>?,
      fhirPathExpression: String,
      separator: String = ",",
    ): String {
      if (fhirPathExpression.isEmpty()) {
        return ""
      }
      val results: List<Any> =
        mapResourcesToExtractedValues(
          resources = resources,
          fhirPathExpression = fhirPathExpression,
        )
      return results.joinToString(separator)
    }

    fun computeTotalCount(relatedResourceCounts: List<RelatedResourceCount>?): Long =
      relatedResourceCounts?.sumOf { it.count } ?: 0

    fun retrieveCount(
      parentResourceId: String,
      relatedResourceCounts: List<RelatedResourceCount>?,
    ): Long =
      relatedResourceCounts
        ?.find { parentResourceId.equals(it.parentResourceId, ignoreCase = true) }
        ?.count ?: 0

    /**
     * This function sorts [resources] by comparing the values extracted by FHIRPath using the
     * [fhirPathExpression]. The [dataType] is required for ordering of the items. You can
     * optionally specify the [Order] of sorting.
     */
    @JvmOverloads
    fun sortResources(
      resources: List<Resource>?,
      fhirPathExpression: String,
      dataType: String,
      order: String = Order.ASCENDING.name,
    ): List<Resource>? =
      runCatching {
          val mappedResources =
            resources?.mapNotNull {
              val extractedValue: Base? =
                fhirPathDataExtractor.extractData(it, fhirPathExpression).firstOrNull()
              val sortingValue: Comparable<*>? =
                when (DataType.valueOf(dataType)) {
                  DataType.BOOLEAN -> extractedValue?.castToBoolean(extractedValue)?.value
                  DataType.DATE -> extractedValue?.castToDate(extractedValue)?.value
                  DataType.DATETIME -> extractedValue?.castToDateTime(extractedValue)?.value
                  DataType.DECIMAL -> extractedValue?.castToDecimal(extractedValue)?.value
                  DataType.INTEGER -> extractedValue?.castToInteger(extractedValue)?.value
                  DataType.STRING -> extractedValue?.castToString(extractedValue)?.value
                  else -> {
                    Timber.e(
                      "Sorting only works for primitive types, sorting by the data type $dataType is not allowed. Implement sorting strategy for the data type $dataType.",
                    )
                    null
                  }
                }
              if (sortingValue != null) Pair(sortingValue, it) else null
            }

          return when (Order.valueOf(order)) {
            Order.ASCENDING ->
              mappedResources?.sortedWith(compareBy { it.first })?.map { it.second }
            Order.DESCENDING ->
              mappedResources?.sortedWith(compareByDescending { it.first })?.map { it.second }
          }
        }
        .getOrNull()

    fun generateTaskServiceStatus(task: Task?): String {
      return when {
        task == null -> ""
        task.isOverDue() -> ServiceStatus.OVERDUE.name
        else -> {
          when (task.status) {
            Task.TaskStatus.NULL,
            Task.TaskStatus.RECEIVED,
            Task.TaskStatus.ENTEREDINERROR,
            Task.TaskStatus.ACCEPTED,
            Task.TaskStatus.REJECTED,
            Task.TaskStatus.DRAFT,
            Task.TaskStatus.ONHOLD, -> {
              Timber.e("Task.status is null", Exception())
              ServiceStatus.UPCOMING.name
            }
            Task.TaskStatus.FAILED -> ServiceStatus.FAILED.name
            Task.TaskStatus.REQUESTED -> ServiceStatus.UPCOMING.name
            Task.TaskStatus.READY -> ServiceStatus.DUE.name
            Task.TaskStatus.CANCELLED -> ServiceStatus.EXPIRED.name
            Task.TaskStatus.INPROGRESS -> ServiceStatus.IN_PROGRESS.name
            Task.TaskStatus.COMPLETED -> ServiceStatus.COMPLETED.name
            else -> ""
          }
        }
      }
    }

    @JvmOverloads
    fun updateResource(
      resource: Resource?,
      path: String?,
      value: Any?,
      purgeAffectedResources: Boolean = false,
      createLocalChangeEntitiesAfterPurge: Boolean = true,
    ) {
      if (resource == null || path.isNullOrEmpty()) return

      val jsonParse = JsonPath.using(conf).parse(resource.encodeResourceToString())

      val updatedResourceDocument =
        try {
          jsonParse.apply {
            // Expression stars with '$' (JSONPath) or ResourceType like in FHIRPath
            if (path.startsWith("\$") && value != null) {
              set(path, value)
            }
            if (
              path.startsWith(
                resource.resourceType.name,
                ignoreCase = true,
              ) && value != null
            ) {
              set(
                path.replace(resource.resourceType.name, "\$"),
                value,
              )
            }

            if (resource.id.startsWith("#")) {
              val idPath = "\$.id"
              set(idPath, resource.id.replace("#", ""))
            }
          }
        } catch (pathNotFoundException: PathNotFoundException) {
          Timber.e(pathNotFoundException, "Path $path not found")
          jsonParse
        }

      val updatedResource =
        fhirContext
          .newJsonParser()
          .parseResource(resource::class.java, updatedResourceDocument.jsonString())

      runBlocking {
        if (purgeAffectedResources) {
          defaultRepository.purge(updatedResource as Resource, forcePurge = true)
        }
        if (createLocalChangeEntitiesAfterPurge) {
          defaultRepository.addOrUpdate(resource = updatedResource as Resource)
        } else {
          defaultRepository.createRemote(resource = arrayOf(updatedResource as Resource))
        }
      }
    }

    fun taskServiceStatusExist(tasks: List<Task>, vararg serviceStatus: String): Boolean {
      return tasks.any {
        val status = generateTaskServiceStatus(it)
        if (status.isNotBlank()) {
          ServiceStatus.valueOf(status) in serviceStatus.map { item -> ServiceStatus.valueOf(item) }
        } else {
          false
        }
      }
    }

    fun getResourceByReference(
      resourceReference: String?,
    ): Resource? {
      if (resourceReference.isNullOrEmpty()) {
        return null
      }

      return runBlocking {
        try {
          defaultRepository.loadResource(Reference().apply { reference = resourceReference })
        } catch (e: ResourceNotFoundException) {
          null
        }
      }
    }

    fun getResourceByIdAndType(
      resourceId: String?,
      resourceType: String?,
    ): Resource? {
      if (resourceId.isNullOrEmpty() || resourceType.isNullOrEmpty()) {
        return null
      }

      return runBlocking {
        try {
          defaultRepository.loadResource(resourceId, ResourceType.valueOf(resourceType))
        } catch (e: ResourceNotFoundException) {
          null
        }
      }
    }

    fun extractLogicalIdUuid(reference: String) = reference.extractLogicalIdUuid()
  }

  companion object {
    private const val SERVICE = "service"
    private const val LOCATION_SERVICE = "locationService"
    private const val DATE_SERVICE = "dateService"
    private const val INCLUSIVE_SIX_DIGIT_MINIMUM = 100000
    private const val INCLUSIVE_SIX_DIGIT_MAXIMUM = 999999
    private const val DEFAULT_REGEX = "(?<=^|,)[\\s,]*(\\w[\\w\\s]*)(?=[\\s,]*$|,)"
    private const val DEFAULT_STRING_SEPARATOR = ", "
  }
}
