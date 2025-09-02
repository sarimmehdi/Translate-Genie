package com.sarim.translate_genie

import org.gradle.api.Plugin
import org.gradle.api.Project

class TranslateGeniePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("translateGenie", TranslateGenieExtension::class.java)

        project.afterEvaluate {
            val isAndroidApp = project.plugins.hasPlugin("com.android.application")
            val isAndroidLib = project.plugins.hasPlugin("com.android.library")

            if (isAndroidApp || isAndroidLib) {
                project.tasks.register(
                    "generateProjectTranslations",
                    GenerateTranslationsTask::class.java,
                ) {
                    this
                }
                project.tasks.register("generateProjectTranslations", GenerateTranslationsTask::class.java) {
                    this.description = "Generates translations for this Android project using configured API settings."
                    this.group = "Translation"

                    this.baseApiUrl.set(extension.baseApiUrl)
                    this.httpMethod.set(extension.httpMethod)
                    this.keyForTextsToTranslate.set(extension.keyForTextsToTranslate)
                    this.keyForTargetLanguage.set(extension.keyForTargetLanguage)
                    this.keyForSourceLanguage.set(extension.keyForSourceLanguage)
                    this.fixedSourceLanguageValue.set(extension.fixedSourceLanguageValue)
                    this.defaultAppLanguage.set(extension.defaultAppLanguage)
                    this.additionalJsonBody.set(extension.additionalJsonBody)
                    this.targetLanguagesList.set(extension.targetLanguagesList)
                    this.responseKeyForTranslatedTextsArray.set(extension.responseKeyForTranslatedTextsArray)
                    this.connectionTimeoutMs.set(extension.connectionTimeoutMs)
                    this.readTimeoutMs.set(extension.readTimeoutMs)

                    this.outputBaseResDirectory.set(project.layout.projectDirectory.dir("src/main/res"))
                    this.taskSpecificLogger.set(project.logger)
                }
                project.logger.info("TranslateGeniePlugin: 'generateProjectTranslations' task registered for ${project.name}.")
            } else { /* ... log not an Android project ... */ }
        }
        project.logger.info("TranslateGeniePlugin applied. Configure via 'translateGenie' extension.")
    }
}

