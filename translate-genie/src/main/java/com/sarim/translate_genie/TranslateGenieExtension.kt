package com.sarim.translate_genie

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

abstract class TranslateGenieExtension {
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

    init {
        httpMethod.convention("POST")
        keyForSourceLanguage.convention("")
        fixedSourceLanguageValue.convention("")
        additionalJsonBody.convention("{}")
        defaultAppLanguage.convention("en")
        connectionTimeoutMs.convention(20000)
        readTimeoutMs.convention(20000)
    }
}