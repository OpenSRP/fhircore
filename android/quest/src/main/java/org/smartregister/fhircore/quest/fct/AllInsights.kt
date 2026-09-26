package org.smartregister.fhircore.quest.fct

import com.google.android.fhir.FhirEngine
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.smartregister.fhircore.engine.data.remote.model.response.UserInfo
import org.smartregister.fhircore.engine.util.SecureSharedPreference
import org.smartregister.fhircore.engine.util.SharedPreferenceKey
import org.smartregister.fhircore.engine.util.SharedPreferencesHelper
import org.smartregister.fhircore.engine.util.extension.countUnSyncedResources
import org.smartregister.fhircore.engine.util.extension.encodeJson
import org.smartregister.fhircore.quest.BuildConfig

class AllInsights(
    private val fhirEngine: FhirEngine,
    private val dbBridge: DatabaseBridge,
    private val sharedPreferencesHelper: SharedPreferencesHelper,
    private val secureSharedPreference: SecureSharedPreference,
) {

    fun execute(arg: String): String {

        return try {

            val resourceTypeCount = getResourceTypeCount()
            val unSyncedResources = runBlocking { fhirEngine.countUnSyncedResources() }

            Response(
                error = null,
                result = Insight(
                    resourceTypeCount = resourceTypeCount,
                    unSyncedResources = unSyncedResources,
                    userInfo = retrieveUserInfo(),
                    userName = retrieveUsername(),
                    organization = retrieveOrganization(),
                    careTeam = retrieveCareTeam(),
                    location = practitionerLocation(),
                    appVersionCode = BuildConfig.VERSION_CODE.toString(),
                    appVersion = BuildConfig.VERSION_NAME,
                    buildDate = BuildConfig.BUILD_DATE,
                )
            ).encodeJson()

        } catch (ex: Exception) {
            return Response(
                error = ex.message ?: "Query Error"
            ).encodeJson()
        }

    }

    private fun getResourceTypeCount(): Map<String, Int> {
        val query =
            "SELECT resourceType, COUNT(*) as count FROM ResourceEntity GROUP BY resourceType"
        val cursor = dbBridge.runQuery(query)

        val result = mutableMapOf<String, Int>()
        if (cursor.moveToFirst()) {
            do {
                val resourceTypeColIndex = cursor.getColumnIndex("resourceType")
                val countColIndex = cursor.getColumnIndex("count")

                val key = cursor.getString(resourceTypeColIndex)
                val value = cursor.getInt(countColIndex)

                result[key] = value
            } while (cursor.moveToNext())
        }

        return result
    }

    private fun retrieveUserInfo() =
        sharedPreferencesHelper.read<UserInfo>(
            key = SharedPreferenceKey.USER_INFO.name,
        )

    private fun retrieveUsername(): String? = secureSharedPreference.retrieveSessionUsername()

    private fun retrieveOrganization() =
        sharedPreferencesHelper.read(SharedPreferenceKey.ORGANIZATION.name, null)

    private fun retrieveCareTeam() =
        sharedPreferencesHelper.read(SharedPreferenceKey.CARE_TEAM.name, null)

    private fun practitionerLocation() =
        sharedPreferencesHelper.read(SharedPreferenceKey.PRACTITIONER_LOCATION.name, null)

    @Serializable
    private data class Insight(
        val resourceTypeCount: Map<String, Int>,
        val unSyncedResources: List<Pair<String, Int>>,
        val userInfo: UserInfo?,
        val userName: String?,
        val organization: String?,
        val careTeam: String?,
        val location: String?,
        val appVersionCode: String,
        val appVersion: String,
        val buildDate: String,
    )

    @Serializable
    private data class Response(
        var error: String?,
        val result: Insight? = null,
    )

}