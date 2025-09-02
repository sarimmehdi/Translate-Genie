plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(gradleApi())
    implementation(libs.jsonSimpleLibrary)
}

gradlePlugin {
    website = "https://github.com/sarimmehdi/Translate-Genie"
    vcsUrl = "https://github.com/sarimmehdi/Translate-Genie.git"
    plugins {
        create(libs.versions.translateGeniePluginName.get()) {
            id = libs.plugins.translateGeniePluginId.get().pluginId
            displayName = "Automatically translate and generate the strings.xml files"
            description = "A plugin for querying a backend to generate all translations"
            tags = listOf("translation", "localization", "android", "strings", "automation", "i18n")
            implementationClass = libs.versions.translateGeniePluginClass.get()
        }
    }
}
