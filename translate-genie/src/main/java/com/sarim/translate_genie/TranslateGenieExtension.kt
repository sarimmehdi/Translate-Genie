package com.sarim.translate_genie

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

abstract class TranslateGenieExtension
    @Inject
    constructor(
        @Suppress("UNUSED_PARAMETER") objects: ObjectFactory,
    ) {
        abstract val baseApiUrl: Property<String>
        abstract val httpMethod: Property<String>

        abstract val keyForTextsToTranslate: Property<String>
        abstract val keyForTargetLanguage: Property<String>
        abstract val keyForSourceLanguage: Property<String>

        abstract val fixedSourceLanguageValue: Property<String>
        abstract val defaultAppLanguage: Property<String>

        abstract val additionalJsonBody: Property<String>
        abstract val targetLanguagesList: ListProperty<String>

        abstract val responseKeyForTranslatedTextsArray: Property<String>

        abstract val connectionTimeoutMs: Property<Int>
        abstract val readTimeoutMs: Property<Int>
        abstract val batchSize: Property<Int>

        init {
            httpMethod.convention(DEFAULT_HTTP_METHOD)
            fixedSourceLanguageValue.convention(DEFAULT_FIXED_SRC_LANG_VAL)
            additionalJsonBody.convention(DEFAULT_ADDITIONAL_JSON_BODY)
            defaultAppLanguage.convention(DEFAULT_APP_LANG)
            connectionTimeoutMs.convention(DEFAULT_CONN_TIMEOUT_MS)
            readTimeoutMs.convention(DEFAULT_READ_TIMEOUT_MS)
            batchSize.convention(DEFAULT_BATCH_SIZE)
        }

        companion object {
            private const val DEFAULT_HTTP_METHOD = "POST"
            private const val DEFAULT_FIXED_SRC_LANG_VAL = ""
            private const val DEFAULT_ADDITIONAL_JSON_BODY = "{}"
            private const val DEFAULT_APP_LANG = "en"
            private const val DEFAULT_CONN_TIMEOUT_MS = 20000
            private const val DEFAULT_READ_TIMEOUT_MS = 20000
            private const val DEFAULT_BATCH_SIZE = 50
        }
    }
