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
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.gradle.api.tasks.PathSensitivity
import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}


tasks.register("generateTranslationsWithLibreTranslate") {
    description = "Reads the default strings.xml, translates strings using LibreTranslate API, and generates Android XML files for other languages."
    group = "custom"

    val libreTranslateUrlProperty = project.objects.property(String::class.java)
    val sourceLanguageProperty = project.objects.property(String::class.java)
    val targetLanguageCodesProperty = project.objects.listProperty(String::class.java)
    val apiKeyProperty = project.objects.property(String::class.java)
    val defaultAppLanguageProperty = project.objects.property(String::class.java)
    val sourceStringsFileProperty = project.objects.fileProperty()
    val ignoredSourceTextIdsProperty = project.objects.listProperty(String::class.java)

    libreTranslateUrlProperty.convention(project.findProperty("libreTranslateUrl")?.toString())
    sourceLanguageProperty.convention(
        project.findProperty("libreTranslateSourceLang")?.toString() ?: "en"
    )
    targetLanguageCodesProperty.convention(
        (project.findProperty("libreTranslateTargetLangs") as? String)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: listOf("es", "fr")
    )
    apiKeyProperty.convention(project.findProperty("libreTranslateApiKey")?.toString() ?: "")
    defaultAppLanguageProperty.convention(
        project.findProperty("defaultAppLang")?.toString() ?: "en"
    )
    sourceStringsFileProperty.convention(
        project.layout.projectDirectory.file("src/main/res/values/strings.xml")
    )
    ignoredSourceTextIdsProperty.convention(
        (project.findProperty("ignoredSourceTextIds") as? String)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
    )

    inputs.property("libreTranslateUrl", libreTranslateUrlProperty)
    inputs.property("sourceLanguage", sourceLanguageProperty)
    inputs.property("targetLanguages", targetLanguageCodesProperty)
    inputs.property("apiKey", apiKeyProperty)
    inputs.property("defaultAppLanguage", defaultAppLanguageProperty)
    inputs.file(sourceStringsFileProperty)
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("sourceStringsXmlFile")
    inputs.property("ignoredSourceTextIds", ignoredSourceTextIdsProperty)


    outputs.upToDateWhen {
        val sourceFile = sourceStringsFileProperty.get().asFile
        if (!sourceFile.exists()) return@upToDateWhen false

        val defaultLang = defaultAppLanguageProperty.get()
        targetLanguageCodesProperty.get().all { langCode ->
            val valuesDirSuffix = if (langCode.isNotEmpty() && langCode != defaultLang) "-$langCode" else ""
            val targetXmlFile = project.layout.projectDirectory.dir("src/main/res/values$valuesDirSuffix")
                .file("strings_libre_generated.xml").asFile
            targetXmlFile.exists()
        }
    }

    doLast {
        val libreUrl = libreTranslateUrlProperty.get()
        val sourceLang = sourceLanguageProperty.get()
        val targetLangs = targetLanguageCodesProperty.get()
        val apiKey = apiKeyProperty.get()
        val defaultAppLang = defaultAppLanguageProperty.get()
        val sourceStringsFile = sourceStringsFileProperty.get().asFile
        val ignoredIds = ignoredSourceTextIdsProperty.get().toSet()

        if (targetLangs.any { it.equals(sourceLang, ignoreCase = true) && !it.equals(defaultAppLang, ignoreCase = true) }) {
            val problematicTarget = targetLangs.first { it.equals(sourceLang, ignoreCase = true) && !it.equals(defaultAppLang, ignoreCase = true) }
            throw GradleException(
                "Configuration error: The source language '$sourceLang' is listed as a target language ('$problematicTarget') " +
                        "but it is not the 'defaultAppLang' ('$defaultAppLang'). " +
                        "If you intend to translate the source language itself, ensure it's different from the source. " +
                        "If '$sourceLang' is your default app language and you want to generate its file, ensure " +
                        "'defaultAppLang' is set to '$sourceLang'. Otherwise, remove '$sourceLang' from 'libreTranslateTargetLangs'."
            )
        }

        if (!sourceStringsFile.exists()) {
            logger.error("Source strings file does not exist: ${sourceStringsFile.absolutePath}. Skipping task.")
            throw GradleException("Source strings file not found: ${sourceStringsFile.absolutePath}")
        }

        val sourceStringsToTranslate = mutableMapOf<String, String>()
        try {
            val docBuilderFactory = DocumentBuilderFactory.newInstance()
            docBuilderFactory.isNamespaceAware = true
            val docBuilder = docBuilderFactory.newDocumentBuilder()
            val sourceDoc = docBuilder.parse(FileInputStream(sourceStringsFile))
            sourceDoc.documentElement.normalize()

            val stringNodes = sourceDoc.getElementsByTagName("string")
            for (i in 0 until stringNodes.length) {
                val node = stringNodes.item(i)
                if (node.nodeType == Node.ELEMENT_NODE) {
                    val element = node as Element
                    val name = element.getAttribute("name")
                    val translatable = element.getAttribute("translatable")

                    if (name.isNotEmpty() && (translatable.isEmpty() || translatable.equals("true", ignoreCase = true))) {
                        val value = element.textContent.trim()
                        if (value.isNotEmpty()) {
                            sourceStringsToTranslate[name] = value
                        }
                    }
                }
            }
            logger.lifecycle("Found ${sourceStringsToTranslate.size} translatable strings in ${sourceStringsFile.name}")
        } catch (e: Exception) {
            logger.error("Error parsing source strings file ${sourceStringsFile.absolutePath}: ${e.message}", e)
            throw GradleException("Failed to parse source strings file.", e)
        }

        if (sourceStringsToTranslate.isEmpty()) {
            logger.lifecycle("No translatable strings found in ${sourceStringsFile.name} (or all were empty/non-translatable). Skipping API calls.")
            return@doLast
        }

        var overallSuccess = true

        targetLangs.forEach { targetLangCode ->
            val valuesDirSuffix = if (targetLangCode.isNotEmpty() && targetLangCode != defaultAppLang) "-$targetLangCode" else ""
            val targetXmlFile = project.layout.projectDirectory.dir("src/main/res/values$valuesDirSuffix")
                .file("strings_libre_generated.xml").asFile

            logger.lifecycle("Generating translations for language: '$targetLangCode'")
            logger.info("Outputting to: ${targetXmlFile.absolutePath}")

            val docBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            val doc = docBuilder.newDocument()
            val rootElement = doc.createElement("resources")
            doc.appendChild(rootElement)
            val comment = doc.createComment(
                "File auto-generated for language '$targetLangCode' by generateTranslationsWithLibreTranslate. Do not edit manually!"
            )
            rootElement.appendChild(comment)

            var stringsAdded = 0

            sourceStringsToTranslate.forEach stringLoop@{ (key, sourceText) ->
                if (key in ignoredIds) {
                    logger.info("Skipping ignored source text ID '$key' for language '$targetLangCode'.")
                    return@stringLoop
                }

                logger.info("Translating '$key' ('$sourceText') from '$sourceLang' to '$targetLangCode'...")
                try {
                    val requestJson = JSONObject()
                    requestJson["q"] = sourceText
                    requestJson["source"] = sourceLang
                    requestJson["target"] = targetLangCode
                    if (apiKey.isNotBlank()) {
                        requestJson["api_key"] = apiKey
                    }

                    val url = URI(libreUrl).toURL()
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.setRequestProperty("Accept", "application/json")
                    conn.doOutput = true
                    conn.connectTimeout = 15000
                    conn.readTimeout = 15000


                    OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { writer ->
                        writer.write(requestJson.toJSONString())
                    }

                    val responseCode = conn.responseCode
                    if (responseCode == HttpURLConnection.HTTP_OK) {
                        val responseBody = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
                        val parser = JSONParser()
                        val responseJson = parser.parse(responseBody) as JSONObject
                        val translatedText = responseJson["translatedText"] as? String

                        if (translatedText != null) {
                            val stringElement = doc.createElement("string")
                            stringElement.setAttribute("name", key)
                            stringElement.appendChild(doc.createTextNode(translatedText))
                            rootElement.appendChild(stringElement)
                            stringsAdded++
                            logger.info("Successfully translated '$key' to '$translatedText'")
                        } else {
                            logger.error("Error for key '$key' (lang '$targetLangCode'): 'translatedText' field missing or not a string in response: $responseBody")
                            overallSuccess = false
                        }
                    } else {
                        val errorBody = conn.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.readText() ?: "No error body"
                        logger.error("Error translating key '$key' (lang '$targetLangCode'): HTTP $responseCode. Response: $errorBody")
                        overallSuccess = false
                    }
                    conn.disconnect()

                } catch (e: Exception) {
                    logger.error("Exception while translating key '$key' (lang '$targetLangCode'): ${e.message}", e)
                    overallSuccess = false
                }
            }

            if (stringsAdded > 0) {
                val transformerFactory = TransformerFactory.newInstance()
                val transformer = transformerFactory.newTransformer()
                transformer.setOutputProperty(OutputKeys.INDENT, "yes")
                transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4")
                transformer.setOutputProperty(OutputKeys.ENCODING, "utf-8")

                targetXmlFile.parentFile.mkdirs()
                FileOutputStream(targetXmlFile).use { fos ->
                    transformer.transform(DOMSource(doc), StreamResult(fos))
                }
                logger.lifecycle("Successfully generated translations XML for '$targetLangCode': ${targetXmlFile.absolutePath} ($stringsAdded strings)")
            } else {
                logger.lifecycle("No strings were added for language '$targetLangCode'. Output file '${targetXmlFile.name}' not created/updated.")
            }
        }

        if (!overallSuccess) {
            logger.warn("One or more translations failed. Check logs for details.")
            throw GradleException("Translation generation failed for one or more strings/languages.")
        } else {
            logger.lifecycle("All specified languages processed for translation generation.")
        }
    }
}

android {
    namespace = "com.sarim.translategenie"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sarim.translategenie"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_23)
        }
    }
    buildFeatures {
        compose = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}