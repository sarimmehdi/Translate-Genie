import org.jose4j.json.internal.json_simple.parser.JSONParser
import org.jose4j.json.internal.json_simple.JSONObject
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.nio.charset.StandardCharsets
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import org.w3c.dom.Document
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI
import javax.xml.XMLConstants
import java.text.ParseException

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.vanniktechMavenPublishingPlugin)
}

tasks.register("generateTranslations") {
    description = "Reads the default strings.xml, translates all resource types (strings, arrays, plurals) and generates Android XML files for other languages."
    group = "custom"

    val translationConfigJsonProperty = project.objects.property(String::class.java).convention("")

    translationConfigJsonProperty.convention(project.provider { project.findProperty("translationConfigJson") as? String ?: "" })

    inputs.property("translationConfigJson", translationConfigJsonProperty).optional(true)

    outputs.upToDateWhen {
        false
    }

    doLast {
        val taskLogger = this.logger

        taskLogger.lifecycle("Starting 'generateTranslations' task for consuming projects.")

        val libraryProject = project

        val jsonConfigString = translationConfigJsonProperty.get()

        var url = ""
        var sourceLang = ""
        var targetLangs = mutableListOf<String>()
        var apiKey = ""
        var defaultAppLang = ""

        if (jsonConfigString.isNotBlank()) {
            logger.lifecycle("Attempting to parse $jsonConfigString.")
            try {
                val parser = JSONParser()
                val configJson = parser.parse(jsonConfigString) as JSONObject

                (configJson["translateUrl"] as? String)?.let { url = it }
                (configJson["translateSourceLang"] as? String)?.let { sourceLang = it }
                (configJson["translateApiKey"] as? String)?.let { apiKey = it }
                (configJson["defaultAppLang"] as? String)?.let { defaultAppLang = it }

                @Suppress("UNCHECKED_CAST")
                (configJson["translateTargetLangs"] as? List<String>)?.let { langList ->
                    if (langList.all { true }) {
                        targetLangs = langList.toMutableList()
                        logger.lifecycle("Using target languages from JSON: $targetLangs")
                    } else {
                        logger.warn("JSON 'translateTargetLangs' contains non-string elements.")
                    }
                }
            } catch (e: ParseException) {
                logger.error("Error parsing translationConfigJson: ${e.message}.", e)
            } catch (e: Exception) {
                logger.error("Unexpected error processing translationConfigJson: ${e.message}.", e)
            }
        } else {
            throw GradleException("translationConfigJson is blank!")
        }

        if (url.isBlank()) {
            logger.warn("'url' is not set. Skipping translation task.")
            return@doLast
        }
        if (targetLangs.isEmpty()) {
            logger.lifecycle("No target languages specified in 'translateTargetLangs'. Skipping translation.")
            return@doLast
        }

        if (targetLangs.any { it.equals(sourceLang, ignoreCase = true) && !it.equals(defaultAppLang, ignoreCase = true) }) {
            val problematicTarget = targetLangs.first { it.equals(sourceLang, ignoreCase = true) && !it.equals(defaultAppLang, ignoreCase = true) }
            throw GradleException(
                "Configuration error: The source language '$sourceLang' is listed as a target language ('$problematicTarget') " +
                        "but it is not the 'defaultAppLang' ('$defaultAppLang'). " +
                        "If you intend to translate the source language itself, ensure it's different from the source. " +
                        "If '$sourceLang' is your default app language and you want to generate its file, ensure " +
                        "'defaultAppLang' is set to '$sourceLang'. Otherwise, remove '$sourceLang' from 'translateTargetLangs'."
            )
        }

        taskLogger.lifecycle("Iterating projects to find consumers of '${libraryProject.name}'...")
        var anyConsumerProcessedSuccessfully = false

        project.gradle.allprojects {
            if (this.path == libraryProject.path) return@allprojects

            val consumingProject = this
            val dependsOnLibrary = consumingProject.configurations.any { config ->
                config.dependencies.any { dep ->
                    dep is ProjectDependency && dep.dependencyProject.path == libraryProject.path
                }
            }

            if (!dependsOnLibrary) {
                taskLogger.info("[${consumingProject.name}] Does not depend on ${libraryProject.name}. Skipping.")
                return@allprojects
            }

            taskLogger.lifecycle(">>> Processing CONSUMING project: ${consumingProject.name} (path: ${consumingProject.path})")
            anyConsumerProcessedSuccessfully = true

            val consumerProjectDir = consumingProject.projectDir
            val consumerSourceStringsFile = File(consumerProjectDir, "src/main/res/values/strings.xml")

            if (!consumerSourceStringsFile.exists() || !consumerSourceStringsFile.isFile) {
                taskLogger.warn("  [${consumingProject.name}] Default strings.xml not found at: ${consumerSourceStringsFile.absolutePath}. Skipping this consumer.")
                return@allprojects
            }

            taskLogger.info("  [${consumingProject.name}] Found its source strings.xml: ${consumerSourceStringsFile.absolutePath}")

            val sourceDoc: Document
            try {
                val docBuilderFactory = DocumentBuilderFactory.newInstance()
                docBuilderFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                docBuilderFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                docBuilderFactory.isExpandEntityReferences = false
                val docBuilder = docBuilderFactory.newDocumentBuilder()
                sourceDoc = docBuilder.parse(FileInputStream(consumerSourceStringsFile))
                sourceDoc.documentElement.normalize()
            } catch (e: Exception) {
                logger.error("Error parsing source strings file ${consumerSourceStringsFile.absolutePath}: ${e.message}", e)
                throw GradleException("Failed to parse source strings file.", e)
            }

            val sourceRoot = sourceDoc.documentElement
            val sourceChildNodes = sourceRoot.childNodes

            if (sourceChildNodes.length == 0) {
                logger.lifecycle("Source strings file ${consumerSourceStringsFile.name} has no resource elements. Skipping.")
                return@allprojects
            }

            var overallSuccess = true

            targetLangs.forEach { targetLangCode ->
                val shouldTranslateViaApi = !targetLangCode.equals(sourceLang, ignoreCase = true)

                val valuesDirSuffix = if (targetLangCode.isNotEmpty() && targetLangCode != defaultAppLang) "-$targetLangCode" else ""
                val targetResDir = project.layout.projectDirectory.dir("src/main/res/values$valuesDirSuffix")
                targetResDir.asFile.mkdirs()
                val targetXmlFile = targetResDir.file("strings.xml").asFile

                logger.lifecycle("Generating translations for language: '$targetLangCode' -> ${targetXmlFile.absolutePath}")

                val targetDocBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                val targetDoc = targetDocBuilder.newDocument()
                val targetRootElement = targetDoc.createElement("resources")
                targetDoc.appendChild(targetRootElement)
                targetRootElement.appendChild(targetDoc.createComment(
                    " File auto-generated for language '$targetLangCode'"
                ))

                var resourcesProcessedCount = 0

                for (i in 0 until sourceChildNodes.length) {
                    val sourceNode = sourceChildNodes.item(i)

                    if (sourceNode.nodeType != Node.ELEMENT_NODE) {
                        if (sourceNode.nodeType == Node.COMMENT_NODE) {
                            targetRootElement.appendChild(targetDoc.importNode(sourceNode, true))
                        }
                        continue
                    }

                    val sourceElement = sourceNode as Element
                    val resourceName = sourceElement.getAttribute("name")
                    val resourceType = sourceElement.tagName

                    if (resourceName.isNullOrEmpty()) {
                        logger.warn("Skipping resource element <${resourceType}> without a 'name' attribute.")
                        continue
                    }

                    val translatableAttr = sourceElement.getAttribute("translatable")
                    if (translatableAttr.equals("false", ignoreCase = true)) {
                        logger.info("[$targetLangCode] Skipping non-translatable resource '$resourceName' (<$resourceType> translatable=\"false\").")
                        if (targetLangCode.equals(defaultAppLang, ignoreCase = true)) {
                            val importedNode = targetDoc.importNode(sourceElement, true)
                            targetRootElement.appendChild(importedNode)
                            resourcesProcessedCount++
                        }
                        continue
                    }

                    val newTargetElement = targetDoc.createElement(resourceType)
                    newTargetElement.setAttribute("name", resourceName)

                    var currentElementSuccess = true

                    when (resourceType) {
                        "string" -> {
                            val sourceText = sourceElement.textContent.trim()
                            if (sourceText.isNotEmpty()) {
                                if (shouldTranslateViaApi) {
                                    try {
                                        val translatedText = translateText(
                                            sourceText,
                                            sourceLang,
                                            targetLangCode,
                                            url,
                                            apiKey,
                                            logger
                                        )
                                        newTargetElement.textContent = translatedText
                                    } catch (e: Exception) {
                                        logger.error("[$targetLangCode] Error translating string '$resourceName': ${e.message}")
                                        newTargetElement.textContent = sourceText
                                        newTargetElement.appendChild(targetDoc.createComment(" TODO: Translation failed - ${e.message} "))
                                        overallSuccess = false
                                        currentElementSuccess = false
                                    }
                                } else {
                                    newTargetElement.textContent = sourceText
                                }
                            } else {
                                newTargetElement.textContent = ""
                            }
                        }
                        "string-array" -> {
                            val items = sourceElement.getElementsByTagName("item")
                            for (j in 0 until items.length) {
                                val sourceItem = items.item(j) as Element
                                val sourceItemText = sourceItem.textContent.trim()
                                val newItemElement = targetDoc.createElement("item")
                                if (sourceItemText.isNotEmpty()) {
                                    if (shouldTranslateViaApi) {
                                        try {
                                            val translatedItemText = translateText(
                                                sourceItemText,
                                                sourceLang,
                                                targetLangCode,
                                                url,
                                                apiKey,
                                                logger
                                            )
                                            newItemElement.textContent = translatedItemText
                                        } catch (e: Exception) {
                                            logger.error("[$targetLangCode] Error translating item in array '$resourceName': ${e.message}")
                                            newItemElement.textContent = sourceItemText
                                            newItemElement.appendChild(targetDoc.createComment(" TODO: Translation failed "))
                                            overallSuccess = false
                                            currentElementSuccess = false
                                        }
                                    } else {
                                        newItemElement.textContent = sourceItemText
                                    }
                                } else {
                                    newItemElement.textContent = ""
                                }
                                newTargetElement.appendChild(newItemElement)
                            }
                        }
                        "plurals" -> {
                            val items = sourceElement.getElementsByTagName("item")
                            for (j in 0 until items.length) {
                                val sourceItem = items.item(j) as Element
                                val quantity = sourceItem.getAttribute("quantity")
                                val sourceItemText = sourceItem.textContent.trim()

                                val newItemElement = targetDoc.createElement("item")
                                newItemElement.setAttribute("quantity", quantity)

                                if (sourceItemText.isNotEmpty()) {
                                    if (shouldTranslateViaApi) {
                                        try {
                                            val translatedItemText = translateText(
                                                sourceItemText,
                                                sourceLang,
                                                targetLangCode,
                                                url,
                                                apiKey,
                                                logger
                                            )
                                            newItemElement.textContent = translatedItemText
                                        } catch (e: Exception) {
                                            logger.error("[$targetLangCode] Error translating item in plurals '$resourceName' (quantity: $quantity): ${e.message}")
                                            newItemElement.textContent = sourceItemText // Fallback
                                            newItemElement.appendChild(targetDoc.createComment(" TODO: Translation failed "))
                                            overallSuccess = false
                                            currentElementSuccess = false
                                        }
                                    } else {
                                        newItemElement.textContent = sourceItemText
                                    }
                                } else {
                                    newItemElement.textContent = ""
                                }
                                newTargetElement.appendChild(newItemElement)
                            }
                        }
                        else -> {
                            logger.warn("[$targetLangCode] Unsupported resource type '$resourceType' for item '$resourceName'. Copying as is.")
                            if (!shouldTranslateViaApi || targetLangCode.equals(defaultAppLang, ignoreCase=true)) {
                                val importedNode = targetDoc.importNode(sourceElement, true)
                                targetRootElement.appendChild(importedNode)
                                currentElementSuccess = false
                            } else {
                                logger.warn("[$targetLangCode] Cannot translate unknown type '$resourceType' for '$resourceName'. It will be omitted from the generated file for this language unless it's the default language file.")
                                currentElementSuccess = false
                            }
                        }
                    }

                    if(currentElementSuccess || (!shouldTranslateViaApi && resourceType in listOf("string", "string-array", "plurals"))) {
                        if (resourceType in listOf("string", "string-array", "plurals")) {
                            targetRootElement.appendChild(newTargetElement)
                        }
                    } else if (!currentElementSuccess && resourceType in listOf("string", "string-array", "plurals")) {
                        targetRootElement.appendChild(newTargetElement)
                    }
                    resourcesProcessedCount++
                }

                if (targetRootElement.hasChildNodes() && targetRootElement.childNodes.length > 1) {
                    val transformerFactory = TransformerFactory.newInstance()
                    transformerFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                    val transformer = transformerFactory.newTransformer()
                    transformer.setOutputProperty(OutputKeys.INDENT, "yes")
                    transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4")
                    transformer.setOutputProperty(OutputKeys.ENCODING, "utf-8")

                    FileOutputStream(targetXmlFile).use { fos ->
                        transformer.transform(DOMSource(targetDoc), StreamResult(fos))
                    }
                    logger.lifecycle("[$targetLangCode] Successfully generated translations XML: ${targetXmlFile.name} ($resourcesProcessedCount resources attempted)")
                } else {
                    logger.lifecycle("[$targetLangCode] No translatable resources found or processed for this language. Output file '${targetXmlFile.name}' not created/updated.")
                    if (targetXmlFile.exists()) targetXmlFile.delete()
                }
            }

            if (!overallSuccess) {
                logger.warn("One or more translations failed. Check logs for details.")
                throw GradleException("Translation generation failed for one or more strings/languages.")
            } else {
                logger.lifecycle("All specified languages processed for translation generation.")
            }
        }

        if (!anyConsumerProcessedSuccessfully) {
            taskLogger.lifecycle("No consuming projects found or processed. If this is unexpected, verify project dependencies.")
        }
        taskLogger.lifecycle("'generateTranslations' task finished.")
    }
}

fun translateText(
    text: String,
    sourceLang: String,
    targetLang: String,
    translateUrl: String,
    apiKey: String,
    logger: Logger
): String {
    if (text.isBlank()) return ""

    if (text.all { !it.isLetterOrDigit() } && text.contains('%')) {
        logger.info("[$targetLang] Skipping translation for placeholder-like string: \"$text\"")
        return text
    }

    val requestJson = JSONObject()
    requestJson["q"] = text
    if (sourceLang.isNotEmpty()) {
        requestJson["source"] = sourceLang
    }
    if (apiKey.isNotEmpty()) {
        requestJson["api_key"] = apiKey
    }
    requestJson["target"] = targetLang

    val url = URI(translateUrl).toURL()
    val conn = url.openConnection() as HttpURLConnection
    var translatedTextResult: String

    try {
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json")
        conn.doOutput = true
        conn.connectTimeout = 20000
        conn.readTimeout = 20000

        OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { writer ->
            writer.write(requestJson.toJSONString())
        }

        val responseCode = conn.responseCode
        if (responseCode == HttpURLConnection.HTTP_OK) {
            val responseBody = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
            val parser = JSONParser()
            val responseJson = parser.parse(responseBody) as JSONObject
            translatedTextResult = responseJson["translatedText"] as? String ?: text
            if (translatedTextResult == text && responseJson["translatedText"] == null) {
                logger.warn("API response for '$text' did not contain 'translatedText' field. Response: $responseBody")
            }
        } else {
            val errorBody = conn.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.readText() ?: "No error body"
            logger.error("API Error ($responseCode) translating '$text' from $sourceLang to $targetLang: $errorBody")
            throw RuntimeException("API Error $responseCode: $errorBody")
        }
    } finally {
        conn.disconnect()
    }
    return translatedTextResult.replace("'", "\\'")
}

android {
    namespace = "com.sarim.translate_genie"
    compileSdk = 36

    defaultConfig {
        minSdk = 26

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_23
        targetCompatibility = JavaVersion.VERSION_23
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_23)
        }
    }
}

mavenPublishing {
    publishToMavenCentral()

    signAllPublications()

    coordinates("io.github.sarimmehdi", "translate-genie", "1.0.0")

    pom {
        name = "Translate Genie"
        description = "A library for querying a backend to generate all translations using a gradle task"
        inceptionYear = "2025"
        url = "https://github.com/sarimmehdi/Translate-Genie"
        licenses {
            license {
                name = "The Apache License, Version 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                distribution = "https://www.apache.org/licenses/LICENSE-2.0.txt"
            }
        }
        developers {
            developer {
                id = "sarimmehdi"
                name = "Muhammad Sarim Mehdi"
                url = "https://github.com/sarimmehdi"
            }
        }
        scm {
            url = "https://github.com/sarimmehdi/Translate-Genie"
            connection = "scm:git:git://github.com/sarimmehdi/Translate-Genie.git"
            developerConnection = "scm:git:ssh://git@github.com/sarimmehdi/Translate-Genie.git"
        }
    }
}
