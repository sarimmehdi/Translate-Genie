package com.sarim.translate_genie

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.logging.Logger
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.json.simple.JSONArray
import org.json.simple.JSONObject
import org.json.simple.parser.JSONParser
import org.json.simple.parser.ParseException
import org.w3c.dom.DOMException
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.forEach
import kotlin.collections.isNotEmpty
import kotlin.collections.set
import kotlin.text.isBlank
import kotlin.text.isNotBlank
import kotlin.text.split

@Suppress("LargeClass")
abstract class GenerateTranslationsTask : DefaultTask() {
    @get:Input
    abstract val baseApiUrl: Property<String>

    @get:Input
    abstract val httpMethod: Property<String>

    @get:Input
    abstract val keyForTextsToTranslate: Property<String>

    @get:Input
    abstract val keyForTargetLanguage: Property<String>

    @get:Input
    @get:Optional
    abstract val keyForSourceLanguage: Property<String>

    @get:Input
    @get:Optional
    abstract val fixedSourceLanguageValue: Property<String>

    @get:Input
    abstract val defaultAppLanguage: Property<String>

    @get:Input
    @get:Optional
    abstract val additionalJsonBody: Property<String>

    @get:Input
    abstract val targetLanguagesList: ListProperty<String>

    @get:Input
    abstract val responseKeyForTranslatedTextsArray: Property<String>

    @get:Input
    abstract val connectionTimeoutMs: Property<Int>

    @get:Input
    abstract val readTimeoutMs: Property<Int>

    @get:Input
    abstract val batchSize: Property<Int>

    @get:OutputDirectory
    abstract val outputBaseResDirectory: DirectoryProperty

    @get:Internal
    abstract val taskSpecificLogger: Property<Logger>

    init {
        description = "Generates translations based on user-defined JSON mapping and API."
        group = "translation"
        outputs.upToDateWhen { false }
    }

    @Suppress("NestedBlockDepth", "CyclomaticComplexMethod", "LongMethod")
    @TaskAction
    fun execute() {
        val logger = taskSpecificLogger.getOrElse(project.logger)
        logger.lifecycle("Starting 'GenerateTranslationsTask' with BATCH processing...")

        if (batchSize.get() <= 0) throw GradleException("batchSize must be a positive integer.")
        if (baseApiUrl.get().isBlank()) throw GradleException("baseApiUrl must be set.")
        if (keyForTextsToTranslate.get().isBlank()) throw GradleException("keyForTextsToTranslate must be set.")
        if (keyForTargetLanguage.get().isBlank()) throw GradleException("keyForTargetLanguage must be set.")
        if (targetLanguagesList.get().isEmpty()) {
            throw GradleException("No target languages specified in targetLanguagesList. Skipping.")
        }
        val defaultAppLang = defaultAppLanguage.get()
        if (defaultAppLang.isBlank()) {
            logger.warn("defaultAppLanguage is blank, defaulting to 'en' for folder naming.")
            defaultAppLanguage.set("en")
        }

        val baseJson: JSONObject =
            try {
                val additionalBody = additionalJsonBody.getOrElse("{}")
                if (additionalBody.isNotBlank() && additionalBody != "{}") {
                    JSONParser().parse(additionalBody) as JSONObject
                } else {
                    JSONObject()
                }
            } catch (e: ParseException) {
                throw GradleException("Error parsing additionalJsonBody: ${e.message}", e)
            }

        var detectedOrFixedSourceLanguage = fixedSourceLanguageValue.getOrElse("").ifBlank { null }
        if (detectedOrFixedSourceLanguage == null) {
            detectedOrFixedSourceLanguage = defaultAppLanguage.get()
            logger.lifecycle(
                "No fixedSourceLanguageValue set. Using defaultAppLanguage " +
                    "('$detectedOrFixedSourceLanguage') as the source language for API calls.",
            )
        } else {
            logger.lifecycle(
                "Using fixedSourceLanguageValue ('$detectedOrFixedSourceLanguage') " +
                    "as the source language for API calls.",
            )
        }

        val consumingProject = project
        logger.lifecycle(">>> Processing project: ${consumingProject.name}")

        val consumerSourceStringsFile = File(consumingProject.projectDir, "src/main/res/values/strings.xml")
        if (!consumerSourceStringsFile.exists()) {
            logger.warn(
                "  [${consumingProject.name}] Default strings.xml not found at: " +
                    "${consumerSourceStringsFile.absolutePath}. Skipping.",
            )
            return
        }

        val sourceDoc: Document =
            try {
                val docBuilderFactory = DocumentBuilderFactory.newInstance()
                docBuilderFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                docBuilderFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                docBuilderFactory.isExpandEntityReferences = false
                val docBuilder = docBuilderFactory.newDocumentBuilder()
                docBuilder.parse(FileInputStream(consumerSourceStringsFile)).also { it.documentElement.normalize() }
            } catch (e: Exception) {
                throw GradleException(
                    "Failed to parse source strings file for " +
                        "${consumingProject.name}: ${e.message}",
                    e,
                )
            }

        val sourceRoot = sourceDoc.documentElement
        val sourceChildNodes = sourceRoot.childNodes
        if (sourceChildNodes.length == 0) {
            logger.lifecycle(
                "  [${consumingProject.name}] The source strings.xml file " +
                    "(${consumerSourceStringsFile.absolutePath}) has no child nodes under the " +
                    "<resources> tag. Nothing to translate. Skipping.",
            )
            return
        }

        var overallSuccess = true
        val resolvedFixedSourceLang = fixedSourceLanguageValue.getOrElse("")
        val resolvedKeyForSourceLang = keyForSourceLanguage.getOrElse("")
        val apiKeyForTextBatch = keyForTextsToTranslate.get()

        targetLanguagesList.get().forEach { targetLangCode ->
            val shouldTranslateViaApi =
                resolvedFixedSourceLang.isBlank() || !targetLangCode.equals(resolvedFixedSourceLang, ignoreCase = true)

            val valuesDirSuffix =
                if (targetLangCode.isNotEmpty() &&
                    !targetLangCode.equals(defaultAppLang, ignoreCase = true)
                ) {
                    "-$targetLangCode"
                } else {
                    ""
                }
            val targetResDir = outputBaseResDirectory.get().dir("values$valuesDirSuffix")
            targetResDir.asFile.mkdirs()
            val targetXmlFile = targetResDir.file("strings.xml").asFile
            logger.lifecycle("Generating for language '$targetLangCode' -> ${targetXmlFile.absolutePath}")

            val targetDoc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()
            val targetRootElement = targetDoc.createElement("resources")
            targetDoc.appendChild(targetRootElement)
            val currentTimestamp =
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.getDefault()).format(
                    Date(),
                )
            targetRootElement.appendChild(
                targetDoc.createComment(
                    " Auto-generated for '$targetLangCode' " +
                        "by TranslateGenie on $currentTimestamp ",
                ),
            )

            val itemsToTranslateForThisLang = mutableListOf<BatchItem>()
            val parentElementsInTargetDoc = mutableMapOf<String, Element>()
            val directlyCopiedOrCreatedElements = mutableListOf<Element>()

            sourceChildNodes.toList().forEach forEachSomeChildNodes@{ node ->
                if (node.nodeType != Node.ELEMENT_NODE) {
                    if (node.nodeType == Node.COMMENT_NODE) {
                        targetRootElement.appendChild(targetDoc.importNode(node, true))
                    }
                    return@forEachSomeChildNodes
                }

                val sourceElement = node as Element
                val resourceName = sourceElement.getAttribute("name")
                val resourceType = sourceElement.tagName

                if (resourceName.isNullOrEmpty()) {
                    logger.warn(
                        "[$targetLangCode] Skipping <$resourceType> without a 'name'. File: " +
                            "${consumerSourceStringsFile.absolutePath}",
                    )
                    return@forEachSomeChildNodes
                }

                if (sourceElement.getAttribute("translatable") == "false" || !shouldTranslateViaApi) {
                    val copiedNode = targetDoc.importNode(sourceElement, true)
                    targetRootElement.appendChild(copiedNode)
                    directlyCopiedOrCreatedElements.add(copiedNode as Element)
                    if (sourceElement.getAttribute("translatable") == "false") {
                        logger.debug("[$targetLangCode] Copied non-translatable '$resourceName' as is.")
                    } else {
                        logger.debug(
                            "[$targetLangCode] Copied '$resourceName' as is (API translation " +
                                "skipped for this language).",
                        )
                    }
                    return@forEachSomeChildNodes
                }

                when (resourceType) {
                    "string" -> {
                        val sourceText = sourceElement.textContent.trim()
                        if (sourceText.isNotEmpty()) {
                            itemsToTranslateForThisLang.add(
                                BatchItem(
                                    id = BatchItem.generateUniqueId(resourceName, resourceType, null, null),
                                    originalResourceType = resourceType,
                                    sourceText = sourceText,
                                    originalSourceElement = sourceElement,
                                ),
                            )
                        } else {
                            val emptyTargetString = targetDoc.createElement(resourceType)
                            emptyTargetString.setAttribute("name", resourceName)
                            emptyTargetString.textContent = ""
                            targetRootElement.appendChild(emptyTargetString)
                            directlyCopiedOrCreatedElements.add(emptyTargetString)
                        }
                    }
                    "string-array", "plurals" -> {
                        val newParentTargetElement = targetDoc.createElement(resourceType)
                        newParentTargetElement.setAttribute("name", resourceName)
                        parentElementsInTargetDoc[resourceName] = newParentTargetElement

                        var hasAnyTranslatableItem = false
                        sourceElement.getElementsByTagName("item").toList().forEachIndexed { index, itemNode ->
                            val itemElement = itemNode as Element
                            val sourceItemText = itemElement.textContent.trim()
                            val quantity = if (resourceType == "plurals") itemElement.getAttribute("quantity") else null
                            val itemId = BatchItem.generateUniqueId(resourceName, "item", index, quantity)

                            if (sourceItemText.isNotEmpty()) {
                                itemsToTranslateForThisLang.add(
                                    BatchItem(
                                        id = itemId,
                                        originalResourceType = "item",
                                        sourceText = sourceItemText,
                                        originalSourceElement = itemElement,
                                        parentResourceName = resourceName,
                                        quantityKey = quantity,
                                    ),
                                )
                                hasAnyTranslatableItem = true
                            } else {
                                val emptyTargetItem = targetDoc.createElement("item")
                                if (quantity != null) emptyTargetItem.setAttribute("quantity", quantity)
                                emptyTargetItem.textContent = ""
                                newParentTargetElement.appendChild(emptyTargetItem)
                            }
                        }
                        if (!hasAnyTranslatableItem) {
                            targetRootElement.appendChild(newParentTargetElement)
                            directlyCopiedOrCreatedElements.add(newParentTargetElement)
                        }
                    }
                    else -> {
                        logger.warn(
                            "[$targetLangCode] Unsupported resource '$resourceName' (<$resourceType>). " +
                                "Copying as is if this is the default language.",
                        )
                        if (targetLangCode.equals(defaultAppLang, ignoreCase = true)) {
                            val copiedNode = targetDoc.importNode(sourceElement, true)
                            targetRootElement.appendChild(copiedNode)
                            directlyCopiedOrCreatedElements.add(copiedNode as Element)
                        }
                    }
                }
            }

            val translatedDataMap = mutableMapOf<String, String>()

            if (itemsToTranslateForThisLang.isNotEmpty()) {
                val batchSizeLimit = batchSize.get()

                itemsToTranslateForThisLang.chunked(batchSizeLimit).forEachIndexed { batchIndex, currentBatchOfItems ->
                    logger.info(
                        "[$targetLangCode] Preparing batch ${batchIndex + 1}/${itemsToTranslateForThisLang.chunked(
                            batchSizeLimit,
                        ).size} with ${currentBatchOfItems.size} items for API call.",
                    )

                    val textsOnlyForApi = JSONArray()
                    currentBatchOfItems.forEach { textsOnlyForApi.add(it.sourceText) }

                    val jsonPayloadForBatch = baseJson.clone() as JSONObject
                    jsonPayloadForBatch[apiKeyForTextBatch] = textsOnlyForApi
                    jsonPayloadForBatch[keyForTargetLanguage.get()] = targetLangCode

                    var effectiveSourceLangCode = resolvedFixedSourceLang

                    if (effectiveSourceLangCode.isBlank()) {
                        val autoDetectionIsEnabled = true
                        if (autoDetectionIsEnabled && defaultAppLang.isNotBlank()) {
                            effectiveSourceLangCode = defaultAppLang
                            logger.info(
                                "[$targetLangCode] No 'fixedSourceLanguageValue' provided. Using " +
                                    "auto-detected source language: '$effectiveSourceLangCode' " +
                                    "(from defaultAppLanguage).",
                            )
                        }
                    }

                    if (effectiveSourceLangCode.isNotBlank()) {
                        if (resolvedKeyForSourceLang.isNotBlank()) {
                            jsonPayloadForBatch[resolvedKeyForSourceLang] = effectiveSourceLangCode
                            logger.info(
                                "[$targetLangCode] Sending source language using configured key: " +
                                    "$resolvedKeyForSourceLang = $effectiveSourceLangCode",
                            )
                        } else {
                            logger.warn(
                                "[$targetLangCode] An effective source language " +
                                    "('$effectiveSourceLangCode') was determined (fixed or " +
                                    "auto-detected), but the 'keyForSourceLanguage' property " +
                                    "(which defines the JSON key for the source language) is not " +
                                    "set or is blank in the plugin configuration. Therefore, this " +
                                    "source language cannot be added to the API request payload " +
                                    "by default. Ensure 'keyForSourceLanguage' is also configured " +
                                    "if you intend to send the source language.",
                            )
                        }
                    } else if (resolvedKeyForSourceLang.isNotBlank()) {
                        if (!jsonPayloadForBatch.containsKey(resolvedKeyForSourceLang)) {
                            logger.warn(
                                "[$targetLangCode] The 'keyForSourceLanguage' ('$resolvedKeyForSourceLang') " +
                                    "was specified in the plugin configuration, but no source " +
                                    "language code was determined (neither from " +
                                    "'fixedSourceLanguageValue' nor via auto-detection). The key " +
                                    "'$resolvedKeyForSourceLang' is also not found in " +
                                    "'additionalJsonBody'. The API might attempt to auto-detect " +
                                    "the source language, or this specific source language key " +
                                    "might be missing its value.",
                            )
                        } else {
                            logger.info(
                                "[$targetLangCode] The 'keyForSourceLanguage' ('$resolvedKeyForSourceLang') " +
                                    "is configured and was found already present in 'additionalJsonBody'. " +
                                    "Value: ${jsonPayloadForBatch[resolvedKeyForSourceLang]}",
                            )
                        }
                    }

                    try {
                        val translatedTextsInBatch =
                            translateTextArrayApiCall(
                                baseUrl = baseApiUrl.get(),
                                httpMethod = httpMethod.get(),
                                jsonPayloadToSend = jsonPayloadForBatch,
                                expectedInputSize = currentBatchOfItems.size,
                                logger = logger,
                                contextualTargetLang = targetLangCode,
                            )

                        if (translatedTextsInBatch.size == currentBatchOfItems.size) {
                            currentBatchOfItems.forEachIndexed { index, batchItem ->
                                translatedDataMap[batchItem.id] = translatedTextsInBatch[index]
                            }
                        } else {
                            logger.error(
                                "[$targetLangCode] Batch ${batchIndex + 1} translation result size mismatch. " +
                                    "Fallbacks will be used.",
                            )
                            currentBatchOfItems.forEachIndexed { _, batchItem ->
                                translatedDataMap[batchItem.id] = batchItem.sourceText
                            }
                            overallSuccess = false
                        }
                    } catch (e: Exception) {
                        logger.error("[$targetLangCode] Error translating batch ${batchIndex + 1}: ${e.message}")
                        currentBatchOfItems.forEach { batchItem ->
                            translatedDataMap[batchItem.id] = batchItem.sourceText
                        }
                        overallSuccess = false
                    }
                }
            }

            itemsToTranslateForThisLang.forEach { batchItem ->
                val translatedText = translatedDataMap[batchItem.id]

                when (batchItem.originalResourceType) {
                    "string" -> {
                        val newTargetString = targetDoc.createElement("string")
                        newTargetString.setAttribute("name", batchItem.originalSourceElement.getAttribute("name"))
                        if (translatedText != null && translatedText != batchItem.sourceText) {
                            newTargetString.textContent = translatedText
                        } else {
                            newTargetString.textContent = batchItem.sourceText
                            if (shouldTranslateViaApi) {
                                try {
                                    newTargetString.appendChild(
                                        targetDoc.createComment(" TODO: Translation failed "),
                                    )
                                } catch (_: DOMException) {
                                }
                                overallSuccess = false
                            }
                        }
                        targetRootElement.appendChild(newTargetString)
                    }
                    "item" -> {
                        val parentElementName = batchItem.parentResourceName ?: ""
                        val parentElement =
                            parentElementsInTargetDoc.getOrPut(parentElementName) {
                                logger.warn(
                                    "[$targetLangCode] Parent element '$parentElementName' for item " +
                                        "'${batchItem.id}' was not pre-created. Creating now.",
                                )
                                val newParent =
                                    targetDoc.createElement(
                                        batchItem.originalSourceElement.parentNode.nodeName,
                                    )
                                newParent.setAttribute("name", parentElementName)
                                targetRootElement.appendChild(newParent)
                                newParent
                            }

                        val newTargetItem = targetDoc.createElement("item")
                        if (batchItem.quantityKey != null) {
                            newTargetItem.setAttribute("quantity", batchItem.quantityKey)
                        }

                        if (translatedText != null && translatedText != batchItem.sourceText) {
                            newTargetItem.textContent = translatedText
                        } else {
                            newTargetItem.textContent = batchItem.sourceText
                            if (shouldTranslateViaApi) {
                                try {
                                    newTargetItem.appendChild(
                                        targetDoc.createComment(" TODO: Translation failed "),
                                    )
                                } catch (_: DOMException) {
                                }
                                overallSuccess = false
                            }
                        }
                        parentElement.appendChild(newTargetItem)
                    }
                }
            }

            parentElementsInTargetDoc.forEach { (_, parentElement) ->
                if (parentElement.parentNode == null && parentElement.hasChildNodes()) {
                    targetRootElement.appendChild(parentElement)
                } else if (parentElement.parentNode == null && !parentElement.hasChildNodes()) {
                    targetRootElement.appendChild(parentElement)
                }
            }
            val resourcesProcessedCount: Int = countElements(targetRootElement)

            if (targetRootElement.hasChildNodes() && countElements(targetRootElement) > 0) {
                val transformerFactory = TransformerFactory.newInstance()
                transformerFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                val transformer = transformerFactory.newTransformer()
                transformer.setOutputProperty(OutputKeys.INDENT, "yes")
                transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4")
                FileOutputStream(targetXmlFile).use { fos ->
                    transformer.transform(DOMSource(targetDoc), StreamResult(fos))
                }
                logger.lifecycle(
                    "[$targetLangCode] Generated: ${targetXmlFile.name} " +
                        "($resourcesProcessedCount resources)",
                )
            } else {
                logger.lifecycle(
                    "[$targetLangCode] No translatable resources were processed or generated for this language. " +
                        "The file ${targetXmlFile.name} would be empty or contain only comments.",
                )

                if (targetXmlFile.exists()) {
                    val deleted = targetXmlFile.delete()
                    if (deleted) {
                        logger.info(
                            "[$targetLangCode] Deleted empty/non-resource file: " +
                                "${targetXmlFile.absolutePath}",
                        )

                        val parentDir = targetXmlFile.parentFile
                        if (parentDir != null && parentDir.isDirectory && parentDir.listFiles()?.isEmpty() == true) {
                            val outputBaseResPath = outputBaseResDirectory.get().asFile.canonicalPath
                            if (parentDir.canonicalPath != outputBaseResPath && parentDir.name.startsWith("values-")) {
                                val parentDeleted = parentDir.delete()
                                if (parentDeleted) {
                                    logger.info(
                                        "[$targetLangCode] Deleted empty parent directory: " +
                                            "${parentDir.absolutePath}",
                                    )
                                } else {
                                    logger.warn(
                                        "[$targetLangCode] Could not delete empty parent directory: " +
                                            "${parentDir.absolutePath}. It might be locked or not truly empty.",
                                    )
                                }
                            }
                        }
                    } else {
                        logger.warn(
                            "[$targetLangCode] Could not delete empty/non-resource file:" +
                                " ${targetXmlFile.absolutePath}. It might be locked.",
                        )
                    }
                } else {
                    logger.info(
                        "[$targetLangCode] No file was written for ${targetXmlFile.name} " +
                            "as no resources were generated.",
                    )
                }
            }
        }

        if (!overallSuccess) {
            val errorMessage =
                "One or more translations failed during the 'GenerateTranslationsTask' " +
                    "for project '${consumingProject.name}'. Check the logs above for specific " +
                    "errors (search for '[ERROR]'). Files with failures will contain " +
                    "'TODO: Translation failed' comments next to the affected resources."
            logger.error("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!")
            logger.error("!!! TRANSLATION TASK COMPLETED WITH ERRORS for ${consumingProject.name} !!!")
            logger.error(errorMessage)
            logger.error("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!")
            throw GradleException(errorMessage)
        } else {
            logger.lifecycle("All specified languages processed successfully for ${consumingProject.name}.")
        }
    }

    fun String.escapeForAndroidXml(): String =
        this
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("'", "\\'")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace(Regex("(?<!%)%(?![\\d${'$'}sSdfFcbBxXonT])"), "%%")

    private fun countElements(node: Node): Int {
        var count = 0
        val childNodes = node.childNodes
        for (i in 0 until childNodes.length) {
            if (childNodes.item(i).nodeType == Node.ELEMENT_NODE) count++
        }
        return count
    }

    @Suppress("LongMethod", "LongParameterList")
    private fun translateTextArrayApiCall(
        baseUrl: String,
        httpMethod: String,
        jsonPayloadToSend: JSONObject,
        expectedInputSize: Int,
        logger: Logger,
        contextualTargetLang: String? = null,
    ): List<String> {
        val textsSentToApi =
            jsonPayloadToSend[keyForTextsToTranslate.get()] as? JSONArray
                ?: throw GradleException(
                    "Payload for batch API call does not contain the expected JSONArray " +
                        "at key '${keyForTextsToTranslate.get()}'.",
                )

        if (textsSentToApi.size != expectedInputSize) {
            logger.warn(
                "[$contextualTargetLang] Internal mismatch: textsSentToApi size " +
                    "(${textsSentToApi.size}) != expectedInputSize ($expectedInputSize).",
            )
        }

        val effectiveTargetLang =
            contextualTargetLang
                ?: jsonPayloadToSend[keyForTargetLanguage.get()] as? String
                ?: "unknown_target"
        logger.info("[$effectiveTargetLang] Attempting to translate batch of ${textsSentToApi.size} strings.")

        val originalTexts = textsSentToApi.mapNotNull { it?.toString() }
        if (originalTexts.size != textsSentToApi.size) {
            logger.warn("[$effectiveTargetLang] Some original texts in the batch were null or not strings.")
        }

        var translatedTexts: List<String> = originalTexts.map { it.escapeForAndroidXml() }

        val (finalUrlString, finalPayload) =
            prepareRequestDetails(
                baseUrl,
                httpMethod,
                jsonPayloadToSend,
                effectiveTargetLang,
                logger,
            )

        val conn: HttpURLConnection
        try {
            conn = URI(finalUrlString).toURL().openConnection() as HttpURLConnection
            configureConnection(conn, httpMethod, finalPayload, jsonPayloadToSend, effectiveTargetLang, logger)
        } catch (e: Exception) {
            logger.error(
                "[$effectiveTargetLang] Exception preparing connection: ${e.message}. " +
                    "URL: $finalUrlString",
                e,
            )
            return translatedTexts
        }

        try {
            if (finalPayload != null && conn.doOutput) {
                sendPayload(conn, finalPayload)
            }

            val responseCode = conn.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val responseBody = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
                translatedTexts = parseSuccessfulResponse(
                    responseBody,
                    expectedInputSize,
                    responseKeyForTranslatedTextsArray.get(),
                    effectiveTargetLang,
                    logger,
                ) ?: translatedTexts // Keep default if parsing fails
            } else {
                logApiError(conn, responseCode, finalUrlString, finalPayload, effectiveTargetLang, logger)
            }
        } catch (e: Exception) {
            logger.error(
                "[$effectiveTargetLang] Exception during API call for batch: ${e.message}. " +
                    "URL: $finalUrlString, Payload: $finalPayload",
                e,
            )
        } finally {
            conn.disconnect()
        }

        return translatedTexts
    }

    @Suppress("NestedBlockDepth")
    private fun prepareRequestDetails(
        baseUrl: String,
        httpMethod: String,
        jsonPayloadToSend: JSONObject,
        effectiveTargetLang: String,
        logger: Logger,
    ): Pair<String, String?> {
        val finalUrlString: String
        val finalPayload: String?

        if (httpMethod.equals("GET", ignoreCase = true)) {
            val queryParams = StringBuilder()
            jsonPayloadToSend.forEach { (key, value) ->
                if (key != "_headers") {
                    if (queryParams.isNotEmpty()) queryParams.append("&")
                    queryParams.append(
                        "${URLEncoder.encode(key.toString(), StandardCharsets.UTF_8.name())}=" +
                            "${URLEncoder.encode(value.toString(), StandardCharsets.UTF_8.name())}",
                    )
                }
            }
            finalUrlString =
                if (baseUrl.contains("?")) {
                    "$baseUrl&$queryParams"
                } else {
                    "$baseUrl?$queryParams"
                }
            finalPayload = null
            logger.info("[$effectiveTargetLang] GET $finalUrlString")
        } else {
            finalUrlString = baseUrl
            val payloadWithoutHeaders = JSONObject()
            jsonPayloadToSend.forEach { (key, value) ->
                if (key != "_headers") {
                    payloadWithoutHeaders[key] = value
                }
            }
            finalPayload = payloadWithoutHeaders.toJSONString()
            logger.info(
                "[$effectiveTargetLang] $httpMethod $finalUrlString with payload: $finalPayload",
            )
        }
        return Pair(finalUrlString, finalPayload)
    }

    @Suppress("LongParameterList")
    private fun configureConnection(
        conn: HttpURLConnection,
        httpMethod: String,
        finalPayload: String?,
        jsonPayloadToSend: JSONObject,
        effectiveTargetLang: String,
        logger: Logger,
    ) {
        conn.requestMethod = httpMethod.uppercase()
        conn.setRequestProperty("Accept", "application/json")
        if (finalPayload != null && !httpMethod.equals("GET", ignoreCase = true)) {
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.doOutput = true
        }
        conn.connectTimeout = connectionTimeoutMs.get()
        conn.readTimeout = readTimeoutMs.get()

        (jsonPayloadToSend["_headers"] as? JSONObject)?.forEach { headerKey, headerValue ->
            if (headerKey is String && headerValue is String) {
                conn.setRequestProperty(headerKey, headerValue)
                logger.info(
                    "[$effectiveTargetLang] Added header from _headers: $headerKey = $headerValue",
                )
            }
        }
    }

    private fun sendPayload(
        conn: HttpURLConnection,
        payload: String,
    ) {
        OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { writer ->
            writer.write(payload)
            writer.flush()
        }
    }

    @Suppress("LongMethod")
    private fun parseSuccessfulResponse(
        responseBody: String,
        expectedInputSize: Int,
        responseArrayKeyPath: String,
        effectiveTargetLang: String,
        logger: Logger,
    ): List<String>? {
        val parser = JSONParser()
        val responseJson =
            parser.parse(responseBody) as? JSONObject ?: run {
                logger.error(
                    "[$effectiveTargetLang] API Error: Response body is not a valid JSON object. " +
                        "Body: $responseBody",
                )
                return null
            }

        var result: List<String>? = null

        var extractedValue: Any? = responseJson
        var pathTraversalSuccess = true
        responseArrayKeyPath.split('.').forEach { keyComponent ->
            if (!pathTraversalSuccess) return@forEach

            val currentStageValue = extractedValue
            if (currentStageValue is JSONObject) {
                extractedValue = currentStageValue[keyComponent]
            } else {
                logger.error(
                    "[$effectiveTargetLang] API Error: Could not find key '$keyComponent' " +
                        "at path '$responseArrayKeyPath' in response. Response: $responseBody",
                )
                pathTraversalSuccess = false
            }
        }

        if (pathTraversalSuccess) {
            val finalExtractedValue = extractedValue
            if (finalExtractedValue !is JSONArray) {
                logger.error(
                    "[$effectiveTargetLang] API Error: Expected a JSONArray at key " +
                        "'$responseArrayKeyPath' in response, but found " +
                        "${finalExtractedValue?.javaClass?.simpleName}. Response: $responseBody",
                )
            } else if (finalExtractedValue.size != expectedInputSize) {
                logger.error(
                    "[$effectiveTargetLang] API Error: Translated texts array size " +
                        "(${finalExtractedValue.size}) does NOT match expected input size " +
                        "($expectedInputSize) for response key '$responseArrayKeyPath'. " +
                        "Response: $responseBody",
                )
            } else {
                val mappedResults = finalExtractedValue.mapNotNull { it?.toString() }
                if (mappedResults.size != expectedInputSize) {
                    logger.error(
                        "[$effectiveTargetLang] API response array contained nulls or non-string items. " +
                            "Expected $expectedInputSize strings, got ${mappedResults.size} " +
                            "valid strings.",
                    )
                } else {
                    logger.info(
                        "[$effectiveTargetLang] Successfully translated batch of " +
                            "$expectedInputSize strings.",
                    )
                    result = mappedResults.map { it.escapeForAndroidXml() }
                }
            }
        }
        return result
    }

    @Suppress("LongParameterList")
    private fun logApiError(
        conn: HttpURLConnection,
        responseCode: Int,
        finalUrlString: String,
        finalPayload: String?,
        effectiveTargetLang: String,
        logger: Logger,
    ) {
        val errorBodyStream = conn.errorStream ?: conn.inputStream
        val errorBody =
            errorBodyStream?.bufferedReader(StandardCharsets.UTF_8)?.readText()
                ?: "No error body"
        logger.error(
            "[$effectiveTargetLang] API Error ($responseCode) for batch. " +
                "URL: $finalUrlString, Payload: $finalPayload, Response: $errorBody",
        )
    }
}
