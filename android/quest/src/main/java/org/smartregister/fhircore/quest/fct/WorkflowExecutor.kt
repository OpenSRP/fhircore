package org.smartregister.fhircore.quest.fct

import android.content.Context
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.CanonicalType
import org.hl7.fhir.r4.model.CarePlan
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.PlanDefinition
import org.hl7.fhir.r4.model.Resource
import org.smartregister.fhircore.engine.R
import org.smartregister.fhircore.engine.task.WorkflowCarePlanGenerator
import org.smartregister.fhircore.engine.util.extension.asReference
import org.smartregister.fhircore.engine.util.extension.decodeJson
import org.smartregister.fhircore.engine.util.extension.decodeResourceFromString
import org.smartregister.fhircore.engine.util.extension.encodeJson
import org.smartregister.fhircore.engine.util.extension.encodeResourceToString

class WorkflowExecutor(
    private val context: Context,
    private val workflowCarePlanGenerator: WorkflowCarePlanGenerator
) {
    fun execute(arg: String): String {

        return try {

            val workflowRequest = arg.decodeJson<WorkflowRequest>()
            val planDefinition =
                workflowRequest.planDefinition.decodeResourceFromString<PlanDefinition>()
            val subject = workflowRequest.subject.decodeResourceFromString<Resource>()

            val relatedEntityLocationTags =
                subject.meta.tag.filter {
                    it.system == context.getString(R.string.sync_strategy_related_entity_location_system)
                }

            val carePlan = CarePlan().apply {
                this.title = planDefinition.title
                this.description = planDefinition.description
                this.instantiatesCanonical =
                    listOf(CanonicalType(planDefinition.asReference().reference))
                relatedEntityLocationTags.forEach(this.meta::addTag)
            }

            // generate apply workflow
            generateApplyCareplan(
                carePlan = carePlan,
                planDefinition = planDefinition,
                subject = subject as Patient,
                workflowRequest = workflowRequest
            )

        } catch (ex: Exception) {
            return Response(
                error = ex.message ?: "Unknown Error"
            ).encodeJson()
        }
    }

    private fun generateApplyCareplan(
        carePlan: CarePlan,
        planDefinition: PlanDefinition,
        subject: Patient,
        workflowRequest: WorkflowRequest,
    ): String {
        return runBlocking {

            val data = Bundle().apply {
                workflowRequest.otherResource.forEach { resourceString ->
                    addEntry().apply {
                        resource = resourceString.decodeResourceFromString()
                    }
                }
            }

            workflowCarePlanGenerator.applyPlanDefinitionOnPatient(
                planDefinition = planDefinition,
                patient = subject,
                data = data,
                output = carePlan,
            )

            Response(
                error = null,
                result = listOf(
                    carePlan.encodeResourceToString()
                )
            ).encodeJson()
        }
    }

    @Serializable
    private data class Response(
        var error: String?,
        val result: List<String> = listOf()
    )

    @Serializable
    private data class WorkflowRequest(
        val planDefinition: String,
        val subject: String,
        val otherResource: List<String>
    )
}