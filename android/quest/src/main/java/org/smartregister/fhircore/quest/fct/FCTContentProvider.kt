package org.smartregister.fhircore.quest.fct

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.google.android.fhir.FhirEngine
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import org.hl7.fhir.r4.utils.FHIRPathEngine
import org.smartregister.fhircore.engine.configuration.ConfigurationRegistry
import org.smartregister.fhircore.engine.data.local.register.RegisterRepository
import org.smartregister.fhircore.engine.rulesengine.ResourceDataRulesExecutor
import org.smartregister.fhircore.engine.rulesengine.RulesFactory
import org.smartregister.fhircore.engine.task.WorkflowCarePlanGenerator
import org.smartregister.fhircore.engine.util.DispatcherProvider
import org.smartregister.fhircore.engine.util.SecureSharedPreference
import org.smartregister.fhircore.engine.util.SharedPreferencesHelper
import org.smartregister.fhircore.engine.util.helper.TransformSupportServices
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class FCTContentProvider : ContentProvider() {

    private lateinit var fhirEngine: FhirEngine
    private lateinit var rulesFactory: RulesFactory
    private lateinit var workflowCarePlanGenerator: WorkflowCarePlanGenerator
    private lateinit var fhirPathEngine: FHIRPathEngine
    private lateinit var transformSupportService: TransformSupportServices
    private lateinit var sharedPreferencesHelper: SharedPreferencesHelper
    private lateinit var secureSharedPreference: SecureSharedPreference

    @InstallIn(SingletonComponent::class)
    @EntryPoint
    interface FCTProviderEntryPoint {
        fun getFhirEngine(): FhirEngine
        fun getRulesFactory(): RulesFactory
        fun registerRepository(): RegisterRepository
        fun configurationRegistry(): ConfigurationRegistry
        fun sharedPreferenceHelper(): SharedPreferencesHelper
        fun dispatcherProvider(): DispatcherProvider
        fun resourceDataRulesExecutor(): ResourceDataRulesExecutor
        fun workflowCareplanGenerator(): WorkflowCarePlanGenerator
        fun fhirPathEngine(): FHIRPathEngine
        fun transformSupportService(): TransformSupportServices
        fun secureSharedPreference(): SecureSharedPreference
    }

    private fun getFhirEngine(appContext: Context): FhirEngine {
        val hiltEntryPoint = EntryPointAccessors.fromApplication(
            appContext,
            FCTProviderEntryPoint::class.java
        )
        return hiltEntryPoint.getFhirEngine()
    }

    private fun getRulesFactory(appContext: Context): RulesFactory {
        val hiltEntryPoint = EntryPointAccessors.fromApplication(
            appContext,
            FCTProviderEntryPoint::class.java
        )
        return hiltEntryPoint.getRulesFactory()
    }

    private fun getWorkflowCareplanGenerator(appContext: Context): WorkflowCarePlanGenerator {
        val hiltEntryPoint = EntryPointAccessors.fromApplication(
            appContext,
            FCTProviderEntryPoint::class.java
        )
        return hiltEntryPoint.workflowCareplanGenerator()
    }

    private fun getFhirPathEngine(appContext: Context): FHIRPathEngine {
        val hiltEntryPoint = EntryPointAccessors.fromApplication(
            appContext,
            FCTProviderEntryPoint::class.java
        )
        return hiltEntryPoint.fhirPathEngine()
    }

    private fun getTransformSupportService(appContext: Context): TransformSupportServices {
        val hiltEntryPoint = EntryPointAccessors.fromApplication(
            appContext,
            FCTProviderEntryPoint::class.java
        )
        return hiltEntryPoint.transformSupportService()
    }

    private fun getSharedPreferencesHelper(appContext: Context): SharedPreferencesHelper {
        val hiltEntryPoint = EntryPointAccessors.fromApplication(
            appContext,
            FCTProviderEntryPoint::class.java
        )
        return hiltEntryPoint.sharedPreferenceHelper()
    }

    private fun getSecureSharedPreference(appContext: Context): SecureSharedPreference {
        val hiltEntryPoint = EntryPointAccessors.fromApplication(
            appContext,
            FCTProviderEntryPoint::class.java
        )
        return hiltEntryPoint.secureSharedPreference()
    }

    override fun onCreate(): Boolean {

        fhirEngine = getFhirEngine(context!!)
        rulesFactory = getRulesFactory(context!!)
        workflowCarePlanGenerator = getWorkflowCareplanGenerator(context!!)
        fhirPathEngine = getFhirPathEngine(context!!)
        transformSupportService = getTransformSupportService(context!!)
        sharedPreferencesHelper = getSharedPreferencesHelper(context!!)
        secureSharedPreference = getSecureSharedPreference(context!!)
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        Timber.d("--method: $method --arg: $arg --extras: $extras")
        val decompressArg = arg!!.decompress()

        val result = when (method) {

            DB_OPERATION -> {
                val dbBridge = DatabaseBridge(context!!, fhirEngine)
                dbBridge.execute(decompressArg)
            }

            EXECUTE_RULES -> {
                val dbBridge = DatabaseBridge(context!!, fhirEngine)
                val ruleExecutor = RuleExecutor(rulesFactory, dbBridge)
                ruleExecutor.execute(decompressArg)
            }

            EXECUTE_WORKFLOW -> {
                val workflowExecutor = WorkflowExecutor(
                    context!!,
                    workflowCarePlanGenerator
                )
                workflowExecutor.execute(decompressArg)
            }

            GET_INSIGHTS -> {
                val dbBridge = DatabaseBridge(context!!, fhirEngine)
                val allInsights = AllInsights(
                    fhirEngine,
                    dbBridge = dbBridge,
                    sharedPreferencesHelper = sharedPreferencesHelper,
                    secureSharedPreference = secureSharedPreference,
                )
                allInsights.execute(decompressArg)
            }

            else -> "_"

        }

        return Bundle().apply {
            putString(DATA, result.compress())
        }
    }

    private fun String.compress(): String {
        val byteStream = ByteArrayOutputStream()
        GZIPOutputStream(byteStream).bufferedWriter().use { it.write(this) }
        return Base64.getEncoder().encodeToString(byteStream.toByteArray())
    }

    private fun String.decompress(): String {
        val compressedBytes = Base64.getDecoder().decode(this)
        val byteArrayInputStream = ByteArrayInputStream(compressedBytes)
        return GZIPInputStream(byteArrayInputStream).bufferedReader().use { it.readText() }
    }

    companion object {
        const val DATA = "data"
        const val DB_OPERATION = "db_operation"
        const val EXECUTE_RULES = "execute_rules"
        const val EXECUTE_WORKFLOW = "execute_workflow"
        const val GET_INSIGHTS = "get_insights"
    }
}