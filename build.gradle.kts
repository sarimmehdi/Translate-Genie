import org.jlleitschuh.gradle.ktlint.KtlintExtension
import org.jlleitschuh.gradle.ktlint.reporter.ReporterType
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import com.diffplug.gradle.spotless.SpotlessExtension
import com.sarim.translate_genie.TranslateGenieExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.gradlePublishPluginLibrary) apply false
    alias(libs.plugins.ktlintPlugin) apply false
    alias(libs.plugins.detektPlugin) apply false
    alias(libs.plugins.spotlessPlugin) apply false
    alias(libs.plugins.translateGeniePluginId) apply false
}

subprojects {
    pluginManager.apply(rootProject.libs.plugins.ktlintPlugin.get().pluginId)
    pluginManager.apply(rootProject.libs.plugins.detektPlugin.get().pluginId)
    pluginManager.apply(rootProject.libs.plugins.spotlessPlugin.get().pluginId)

    configure<DetektExtension> {
        parallel = true
        config.setFrom("${project.rootDir}/config/detekt/detekt.yml")
    }

    configure<KtlintExtension> {
        android = true
        ignoreFailures = false
        reporters {
            reporter(ReporterType.PLAIN)
            reporter(ReporterType.CHECKSTYLE)
            reporter(ReporterType.SARIF)
        }
    }

    configure<SpotlessExtension> {
        kotlin {
            target("src/**/*.kt")
            ktlint().setEditorConfigPath("${project.rootDir}/.editorconfig")
            trimTrailingWhitespace()
            endWithNewline()
        }
    }

    configure<TranslateGenieExtension> {
        baseApiUrl = "http://127.0.0.1:5000/translate"
        keyForTextsToTranslate = "q"
        keyForTargetLanguage = "target"
        targetLanguagesList = listOf("it", "de")
    }
}
