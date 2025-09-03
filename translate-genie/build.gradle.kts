import org.jlleitschuh.gradle.ktlint.reporter.ReporterType

plugins {
    `kotlin-dsl`
    alias(libs.plugins.ktlintPlugin)
    alias(libs.plugins.detektPlugin)
    alias(libs.plugins.spotlessPlugin)
    alias(libs.plugins.gradlePublishPluginLibrary)
}

detekt {
    parallel = true
    config.setFrom("../config/detekt/detekt.yml")
}

ktlint {
    android = true
    ignoreFailures = false
    reporters {
        reporter(ReporterType.PLAIN)
        reporter(ReporterType.CHECKSTYLE)
        reporter(ReporterType.SARIF)
    }
}

spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint().setEditorConfigPath("../.editorconfig")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

dependencies {
    implementation(gradleApi())
    implementation(libs.jsonSimpleLibrary)
}

group = "io.github.sarimmehdi"
version = "1.0.0"

gradlePlugin {
    website = "https://github.com/sarimmehdi/Translate-Genie"
    vcsUrl = "https://github.com/sarimmehdi/Translate-Genie.git"
    plugins {
        create(libs.versions.translateGeniePluginName.get()) {
            id =
                libs.plugins.translateGeniePluginId
                    .get()
                    .pluginId
            displayName = "Automatically translate and generate the strings.xml files"
            description = "A plugin for querying a backend to generate all translations"
            tags = listOf("translation", "localization", "android", "strings", "automation", "i18n")
            implementationClass = libs.versions.translateGeniePluginClass.get()
        }
    }
}
