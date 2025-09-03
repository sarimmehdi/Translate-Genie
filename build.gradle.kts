import com.sarim.translate_genie.TranslateGenieExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.gradlePublishPluginLibrary) apply false
    alias(libs.plugins.translateGeniePluginId) apply false
}

subprojects {
    pluginManager.apply(rootProject.libs.plugins.translateGeniePluginId.get().pluginId)

    configure<TranslateGenieExtension> {
        baseApiUrl = "http://127.0.0.1:5000/translate"
        keyForTextsToTranslate = "q"
        keyForTargetLanguage = "target"
        targetLanguagesList = listOf("it", "de", "ja", "fr", "es")
        responseKeyForTranslatedTextsArray = "translatedText"
        keyForSourceLanguage = "source"
    }
}
