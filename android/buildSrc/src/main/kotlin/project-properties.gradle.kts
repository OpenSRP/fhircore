
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.InputStreamReader
import java.util.Properties

fun readProperties(file: String): Properties {
  val properties = Properties()
  val localProperties = File(file)
  if (localProperties.isFile) {
    InputStreamReader(FileInputStream(localProperties), Charsets.UTF_8).use { reader ->
      properties.load(reader)
    }
  } else  throw FileNotFoundException("\u001B[34mFile $file not found\u001B[0m")

  return properties
}

// Set required FHIR core properties
val requiredFhirProperties =
  listOf(
    "SKIP_AUTHENTICATION",
    "URL",
    "FHIR_BASE_URL",
    "OAUTH_BASE_URL",
    "OAUTH_CLIENT_ID",
    "OAUTH_SCOPE",
    "MAPBOX_SDK_TOKEN",
    "SENTRY_DSN",
    "OPENSRP_APP_ID",
    "GEMINI_API_KEY",
    "SPEECH_TO_TEXT_API_KEY",
  )

val localProperties = readProperties((project.properties["localPropertiesFile"] ?: "${rootProject.projectDir}/local.properties").toString())

requiredFhirProperties.forEach { property ->
  project.extra.set(property, localProperties.getProperty(property, when {
    property.contains("URL") -> "https://sample.url/fhir/"
    property == "OPENSRP_APP_ID" -> """"""""
    property == "SKIP_AUTHENTICATION" -> "false"
    else -> "sample_$property"
  }
  ))
}

// Set required keystore properties
val requiredKeystoreProperties = listOf("KEYSTORE_ALIAS", "KEY_PASSWORD", "KEYSTORE_PASSWORD")
val keystoreProperties = try {
  readProperties((project.properties["keystorePropertiesFile"] ?: "${rootProject.projectDir}/keystore.properties").toString())
} catch (e:FileNotFoundException) {
  if (project.properties["keystorePropertiesFile"] != null) {
    throw e
  } else Properties()
}

requiredKeystoreProperties.forEach { property ->
  project.extra.set(property, keystoreProperties.getProperty(property, "sample_$property"))
}

// Set Sentry properties
val requiredSentryProperties =
  listOf(
    "sentry.org",
    "sentry.project",
    "sentry.auth.token",
    "sentry.url"
  )

val sentryProperties = try {
  readProperties((project.properties["sentryPropertiesFile"] ?: "${rootProject.projectDir}/sentry.properties").toString())
} catch (e: FileNotFoundException) {
  if (project.properties["sentryPropertiesFile"] != null) {
    throw e
  } else Properties()
}

requiredSentryProperties.forEach { property ->
  project.extra.set(property, sentryProperties.getProperty(property, "sentry_$property"))
}
