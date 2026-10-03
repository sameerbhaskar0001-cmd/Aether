import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
}

fun resolveSecretValue(varNames: List<String>): String {
    var rawVal = ""
    for (name in varNames) {
        val sys = System.getenv(name)?.trim()
        if (!sys.isNullOrBlank() && !sys.startsWith("MY_")) {
            rawVal = sys
            break
        }
        val prop = project.findProperty(name)?.toString()?.trim()
        if (!prop.isNullOrBlank() && !prop.startsWith("MY_")) {
            rawVal = prop
            break
        }
    }

    if (rawVal.isBlank() || rawVal.startsWith("MY_")) {
        val envFile = file("${rootDir}/.env")
        if (envFile.exists()) {
            envFile.readLines().forEach { line ->
                if (line.contains("=") && !line.trim().startsWith("#")) {
                    val parts = line.split("=", limit = 2)
                    val k = parts[0].trim()
                    val v = parts[1].trim()
                    if (varNames.contains(k) && v.isNotBlank() && !v.startsWith("MY_")) {
                        rawVal = v
                    }
                }
            }
        }
    }

    var clean = rawVal.trim()
    if (clean.startsWith("export ")) {
        clean = clean.removePrefix("export ").trim()
    }
    if (clean.contains("=")) {
        val parts = clean.split("=", limit = 2)
        clean = parts[1].trim()
    }
    clean = clean.removeSurrounding("\"").removeSurrounding("'").trim()
    return if (clean.startsWith("MY_")) "" else clean
}

val finalGeminiKey = resolveSecretValue(listOf("GEMINI_API_KEY", "GEMINI_KEY", "GEMINI", "Gemini", "gemini_api_key"))
val finalGroqKey = resolveSecretValue(listOf("GROQ_API_KEY", "GROQ_KEY", "GROQ", "Groq", "groq_api_key"))
val finalOpenRouterKey = resolveSecretValue(listOf("OPENROUTER_API_KEY", "OPENROUTER_KEY", "OPENROUTER", "OpenRouter", "openrouter_api_key", "Qwen", "QWEN", "qwen"))
val finalGroqModel = resolveSecretValue(listOf("GROQ_MODEL", "groq_model")).ifEmpty { "openai/gpt-oss-20b" }

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.aether.pxtwbk"
    minSdk = 24
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    buildConfigField("String", "GEMINI_API_KEY", "\"${finalGeminiKey}\"")
    buildConfigField("String", "GROQ_API_KEY", "\"${finalGroqKey}\"")
    buildConfigField("String", "OPENROUTER_API_KEY", "\"${finalOpenRouterKey}\"")
    buildConfigField("String", "Qwen", "\"${finalOpenRouterKey}\"")
    buildConfigField("String", "GROQ_MODEL", "\"${finalGroqModel}\"")
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      val keystoreFile = file(keystorePath)
      if (keystoreFile.exists()) {
        storeFile = keystoreFile
        storePassword = System.getenv("STORE_PASSWORD")
        keyAlias = "upload"
        keyPassword = System.getenv("KEY_PASSWORD")
      }
    }
    create("debugConfig") {
      val debugKeystoreFile = file("${rootDir}/debug.keystore")
      if (debugKeystoreFile.exists()) {
        storeFile = debugKeystoreFile
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
      }
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      val releaseKeystoreExists = file(keystorePath).exists()
      val debugKeystoreExists = file("${rootDir}/debug.keystore").exists()
      if (releaseKeystoreExists) {
        signingConfig = signingConfigs.getByName("release")
      } else if (debugKeystoreExists) {
        signingConfig = signingConfigs.getByName("debugConfig")
      }
    }
    debug {
      val debugKeystoreExists = file("${rootDir}/debug.keystore").exists()
      if (debugKeystoreExists) {
        signingConfig = signingConfigs.getByName("debugConfig")
      }
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  lint {
    checkReleaseBuilds = false
    abortOnError = false
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  packaging {
    resources {
      excludes += "META-INF/DEPENDENCIES"
      excludes += "META-INF/LICENSE"
      excludes += "META-INF/LICENSE.txt"
      excludes += "META-INF/NOTICE"
      excludes += "META-INF/NOTICE.txt"
    }
  }

  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

// Build-time environment secret synchronization for Secrets Gradle Plugin
val rootEnvFile = file("${rootDir}/.env")

val geminiEnv = System.getenv("GEMINI_API_KEY")?.trim() ?: ""
val groqEnv = System.getenv("GROQ_API_KEY")?.trim() ?: ""
val openRouterEnv = System.getenv("OPENROUTER_API_KEY")?.trim() ?: ""
val qwenEnv = System.getenv("Qwen")?.trim() ?: ""
val groqModelEnv = System.getenv("GROQ_MODEL")?.trim() ?: ""

val envProps = mutableMapOf<String, String>()
if (geminiEnv.isNotBlank() && !geminiEnv.startsWith("MY_")) envProps["GEMINI_API_KEY"] = geminiEnv
if (groqEnv.isNotBlank() && !groqEnv.startsWith("MY_")) envProps["GROQ_API_KEY"] = groqEnv
if (groqModelEnv.isNotBlank()) envProps["GROQ_MODEL"] = groqModelEnv
if (openRouterEnv.isNotBlank() && !openRouterEnv.startsWith("MY_")) envProps["OPENROUTER_API_KEY"] = openRouterEnv
if (qwenEnv.isNotBlank() && !qwenEnv.startsWith("MY_")) envProps["Qwen"] = qwenEnv

if (envProps.isNotEmpty()) {
    val existingContent = if (rootEnvFile.exists()) rootEnvFile.readText() else ""
    val existingMap = mutableMapOf<String, String>()
    existingContent.lines().forEach { line ->
        if (line.contains("=") && !line.trim().startsWith("#")) {
            val parts = line.split("=", limit = 2)
            existingMap[parts[0].trim()] = parts[1].trim()
        }
    }
    existingMap.putAll(envProps)
    val updatedLines = existingMap.map { "${it.key}=${it.value}" }.joinToString("\n")
    rootEnvFile.writeText(updatedLines + "\n")
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
  ignoreList.add("GEMINI_API_KEY")
  ignoreList.add("GROQ_API_KEY")
  ignoreList.add("OPENROUTER_API_KEY")
  ignoreList.add("Qwen")
  ignoreList.add("GROQ_MODEL")
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  // implementation(libs.coil.compose)
  implementation(libs.converter.moshi)
  implementation(libs.firebase.ai)
  // Uncomment to use Firestore:
  // implementation(libs.firebase.firestore)

  // Uncomment ALL FOUR of the following dependencies together to use Firebase Auth and Google
  // Sign-In via Credential Manager:
  // implementation(libs.firebase.auth)
  // implementation(libs.androidx.credentials)
  // implementation(libs.androidx.credentials.play.services)
  // implementation(libs.googleid)
  implementation(libs.firebase.appcheck.recaptcha)
  implementation(libs.firebase.appcheck.debug)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  implementation(libs.retrofit)
  implementation(libs.androidx.work.runtime.ktx)
  implementation(libs.pdfbox)

  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.androidx.work.testing)
  testImplementation(libs.robolectric)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}

tasks.withType<Test> {
    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped")
    }
}
