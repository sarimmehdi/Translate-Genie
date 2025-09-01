# Translate Genie 🧞‍♂️

[![Maven Central](https://img.shields.io/maven-central/v/io.github.sarimmehdi/translate-genie.svg)](https://search.maven.org/artifact/io.github.sarimmehdi/translate-genie)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

A powerful Android library that automatically generates translations for all your string resources using a simple Gradle task. Say goodbye to manual translation management!

## Features ✨

- **Automatic Translation**: Translates strings, string arrays, and plurals from your default `strings.xml`
- **Multiple Resource Types**: Supports `<string>`, `<string-array>`, and `<plurals>` elements
- **Configurable**: Easy JSON-based configuration for translation settings
- **Multi-Project Support**: Automatically processes all consuming projects in your Gradle build
- **Smart Handling**:
    - Respects `translatable="false"` attributes
    - Preserves XML comments and structure
    - Escapes special characters properly
- **Error Recovery**: Continues processing even if individual translations fail
- **Secure**: Uses secure XML parsing to prevent XXE attacks

## Installation 📦

Add the library to your project's `build.gradle` file:

```kotlin
dependencies {
    implementation("io.github.sarimmehdi:translate-genie:1.0.0")
}
```

Or in Groovy:

```groovy
dependencies {
    implementation 'io.github.sarimmehdi:translate-genie:1.0.0'
}
```

## Configuration ⚙️

Create a translation configuration JSON object with the following structure:

```json
{
  "translateUrl": "https://your-translation-api.com/translate",
  "translateSourceLang": "en",
  "translateTargetLangs": ["es", "fr", "de", "it", "pt"],
  "translateApiKey": "your-api-key-here",
  "defaultAppLang": "en"
}
```

### Configuration Parameters

| Parameter | Description | Required |
|-----------|-------------|----------|
| `translateUrl` | Your translation API endpoint URL | Yes |
| `translateSourceLang` | Source language code (e.g., "en" for English) | Yes |
| `translateTargetLangs` | Array of target language codes to translate to | Yes |
| `translateApiKey` | API key for your translation service | Yes |
| `defaultAppLang` | Default app language (typically same as source) | Yes |

## Usage 🚀

### Basic Usage

1. **Set up your translation configuration** as a Gradle property:

```kotlin
// In your build.gradle.kts
val translationConfig = """
{
  "translateUrl": "https://api.translate.example.com/v1/translate",
  "translateSourceLang": "en",
  "translateTargetLangs": ["es", "fr", "de"],
  "translateApiKey": "your-api-key",
  "defaultAppLang": "en"
}
"""

tasks.named("generateTranslations") {
    systemProperty("translationConfigJson", translationConfig)
}
```

2. **Run the translation task**:

```bash
./gradlew generateTranslations -PtranslationConfigJson='{"translateUrl":"https://api.example.com/translate","translateSourceLang":"en","translateTargetLangs":["es","fr"],"translateApiKey":"your-key","defaultAppLang":"en"}'
```

### Translation API Format

Your translation API should:
- Accept POST requests with JSON body containing:
  ```json
  {
    "q": "text to translate",
    "source": "en",
    "target": "es",
    "api_key": "your-key"
  }
  ```
- Return JSON response with:
  ```json
  {
    "translatedText": "translated text here"
  }
  ```

## Example 📝

### Input (`src/main/res/values/strings.xml`):

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">My Awesome App</string>
    <string name="welcome_message">Welcome to our app!</string>
    <string name="debug_key" translatable="false">DEBUG_MODE</string>
    
    <string-array name="colors">
        <item>Red</item>
        <item>Green</item>
        <item>Blue</item>
    </string-array>
    
    <plurals name="items_count">
        <item quantity="one">%d item</item>
        <item quantity="other">%d items</item>
    </plurals>
</resources>
```

### Output (`src/main/res/values-es/strings.xml`):

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- File auto-generated for language 'es' -->
    <string name="app_name">Mi Aplicación Increíble</string>
    <string name="welcome_message">¡Bienvenido a nuestra aplicación!</string>
    
    <string-array name="colors">
        <item>Rojo</item>
        <item>Verde</item>
        <item>Azul</item>
    </string-array>
    
    <plurals name="items_count">
        <item quantity="one">%d elemento</item>
        <item quantity="other">%d elementos</item>
    </plurals>
</resources>
```

## How It Works 🔧

1. **Project Discovery**: The task automatically finds all projects that depend on your library
2. **Resource Parsing**: Reads the default `strings.xml` from each consuming project
3. **Translation**: Sends translatable text to your configured translation API
4. **File Generation**: Creates properly formatted XML files for each target language
5. **Error Handling**: Logs failures and continues processing, marking failed translations with comments

## Advanced Features 🎯

### Non-Translatable Resources

Mark resources as non-translatable using the `translatable="false"` attribute:

```xml
<string name="api_endpoint" translatable="false">https://api.example.com</string>
```

### Multi-Module Projects

Translate Genie automatically processes all modules in your project that depend on the library, making it perfect for multi-module Android applications.

## Error Handling 🛠️

- **Network failures**: Logged with details, original text preserved
- **API errors**: Full error response logged, fallback to original text
- **Malformed XML**: Detailed parsing error messages
- **Missing files**: Graceful handling with informative warnings

## Contributing 🤝

Contributions are welcome! Please feel free to submit a Pull Request. For major changes, please open an issue first to discuss what you would like to change.

## Support 💬

If you have any questions or run into issues, please:
1. Check the existing [GitHub Issues](https://github.com/sarimmehdi/Translate-Genie/issues)
2. Create a new issue with detailed information about your problem
3. Include your configuration and any relevant error logs

---

Made with ❤️ by [Muhammad Sarim Mehdi](https://github.com/sarimmehdi)