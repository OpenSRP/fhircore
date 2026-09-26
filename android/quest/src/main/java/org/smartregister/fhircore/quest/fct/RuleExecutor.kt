package org.smartregister.fhircore.quest.fct

import android.database.Cursor
import kotlinx.serialization.Serializable
import org.apache.commons.jexl3.JexlBuilder
import org.apache.commons.jexl3.JexlEngine
import org.apache.commons.jexl3.JexlException
import org.hl7.fhir.r4.model.Resource
import org.jeasy.rules.api.Facts
import org.jeasy.rules.api.Rule
import org.jeasy.rules.api.RuleListener
import org.jeasy.rules.api.Rules
import org.jeasy.rules.core.DefaultRulesEngine
import org.jeasy.rules.jexl.JexlRule
import org.smartregister.fhircore.engine.domain.model.RepositoryResourceData
import org.smartregister.fhircore.engine.domain.model.RuleConfig
import org.smartregister.fhircore.engine.rulesengine.RulesFactory
import org.smartregister.fhircore.engine.util.extension.decodeJson
import org.smartregister.fhircore.engine.util.extension.decodeResourceFromString
import org.smartregister.fhircore.engine.util.extension.encodeJson
import timber.log.Timber

class RuleExecutor(private val rulesFactory: RulesFactory, private val dbBridge: DatabaseBridge) {

    private val jexlEngine: JexlEngine by lazy {
        JexlBuilder()
            .namespaces(
                mutableMapOf<String, Any>(
                    "Timber" to Timber,
                    "StringUtils" to Class.forName("org.apache.commons.lang3.StringUtils"),
                    "RegExUtils" to Class.forName("org.apache.commons.lang3.RegExUtils"),
                    "Math" to Class.forName("java.lang.Math"),
                ),
            )
            .silent(false)
            .strict(false)
            .create()
    }

    fun execute(arg: String): String {

        return try {

            val requestBundle = arg.decodeJson<RequestBundle>()
            val resourceMap = mutableMapOf<String, List<Resource>>()
            var resource: Resource? = null
            var resourceRulesEngineFactId: String? = null

            requestBundle.dataSources.forEach {
                val cursor = dbBridge.runQuery(it.query)

                if (it.isSingle) {
                    resourceRulesEngineFactId = it.id.trim().ifEmpty { null }
                    resource = getSingleRecord(cursor)
                } else {
                    val resourceList = extractResources(cursor)
                    val key = it.id.trim().ifEmpty { it.resourceType }
                    resourceMap[key] = resourceList
                }
            }

            val jexlExceptions = mutableListOf<Pair<String, String>>()
            val rules = generateRules(requestBundle.rules, jexlExceptions)

            val rulesEngineField = rulesFactory.javaClass.superclass.getDeclaredField("rulesEngine")
            rulesEngineField.isAccessible = true
            val rulesEngine = rulesEngineField.get(rulesFactory) as DefaultRulesEngine

            rulesEngine.registerRuleListener(object : RuleListener {
                override fun onFailure(
                    rule: Rule?,
                    facts: Facts?,
                    exception: java.lang.Exception?
                ) {
                    jexlExceptions.add(Pair(rule!!.name, exception?.message ?: "Rule execution error"))
                }
            })

            val result = rulesFactory.fireRules(
                rules = rules,
                params = mutableMapOf(),
                repositoryResourceData = RepositoryResourceData(
                    resourceRulesEngineFactId = resourceRulesEngineFactId,
                    resource = resource!!,
                    relatedResourcesMap = resourceMap
                )
            )

            Response(
                error = null,
                result = result.entries
                    .associate { entry ->
                        Pair(entry.key, "${entry.value}")
                    }
                    .toMutableMap()
                    .let { map ->
                        jexlExceptions.forEach {
                            map[it.first] = it.second
                        }
                        map
                    }
            ).encodeJson()

        } catch (ex: Exception) {
            return Response(
                error = ex.message ?: "Query Error"
            ).encodeJson()
        }

    }

    private fun getSingleRecord(cursor: Cursor): Resource {
        val colIndex = cursor.getColumnIndex("serializedResource")
        cursor.moveToFirst()
        return cursor.getString(colIndex).decodeResourceFromString()
    }

    private fun extractResources(cursor: Cursor): List<Resource> {
        val resourceList = mutableListOf<Resource>()
        if (cursor.moveToPosition(0)) {
            do {
                val colIndex = cursor.getColumnIndex("serializedResource")
                resourceList.add(
                    cursor.getString(colIndex).decodeResourceFromString()
                )
            } while (cursor.moveToNext())
        }
        return resourceList
    }

    private fun generateRules(ruleConfigs: List<RuleConfig>, exceptions: MutableList<Pair<String, String>>): Rules =
        Rules(
            ruleConfigs
                .map { ruleConfig ->
                    val customRule: JexlRule =
                        JexlRule(jexlEngine)
                            .name(ruleConfig.name)
                            .description(ruleConfig.description)
                            .priority(ruleConfig.priority)
                            .`when`(ruleConfig.condition.ifEmpty { "true" })

                    for (action in ruleConfig.actions) {
                        try {
                            customRule.then(action)
                        } catch (jexlException: Exception) {
                            Timber.e(jexlException)
                            exceptions.add(Pair(ruleConfig.name, jexlException.message ?: "jexlException"))
                            continue // Skip action when an error occurs to avoid app force close
                        }
                    }
                    customRule
                }
                .toSet(),
        )

    @Serializable
    private data class Response(
        var error: String?,
        val result: Map<String, String> = mutableMapOf()
    )

    @Serializable
    private data class DataSource(
        val id: String,
        val query: String,
        val resourceType: String,
        val isSingle: Boolean
    )

    @Serializable
    private data class RequestBundle(
        val dataSources: List<DataSource>,
        val rules: List<RuleConfig>
    )
}