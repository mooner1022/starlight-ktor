import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("io.github.mooner1022.slplugin")
}

// major.minor follows the bundled Ktor; dependent plugins declare dependency("ktor", "<major.minor>.0").
val pluginVersion = "3.6.2"

android {
    namespace = "dev.mooner.starlightktor"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.mooner.starlightktor"
        minSdk = 26
        targetSdk = 33
        versionCode = 3
        // The plugin version in the generated starlight.json.
        versionName = pluginVersion
    }

    buildTypes {
        release {
            // Every Ktor class must stay: dependent plugins load them from this plugin by name.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all { test ->
            // The StarLight SLF4J provider in main needs PluginCore, which tests don't have.
            test.systemProperty("slf4j.provider", "org.slf4j.helpers.NOP_FallbackServiceProvider")
        }
    }
    packaging {
        resources {
            // Keeps META-INF/services (SLF4J provider), which is needed at runtime.
            excludes += listOf("META-INF/*", "META-INF/versions/**", "META-INF/native-image/**", "META-INF/rewrite/**", "META-INF/maven/**")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

starlight {
    sdk {
        // Build these from the StarLight repository: ./gradlew :PluginCore:assembleRelease :ConfigDSL:assembleRelease
        pluginCore = file("libs/PluginCore-release.aar")
        configDsl = file("libs/ConfigDSL-release.aar")
        excludeHostKotlinLibraries = true
    }
    // Generates assets/starlight.json; the version is versionName.
    manifest {
        id = "ktor"
        name = "Ktor"
        mainClass = "dev.mooner.starlightktor.StarlightKtorPlugin"
        // The PluginCore AAR here predates the API version table, so it can't be worked out from the code.
        apiVersion = "0.3.2"
        authors.add("mooner")
        description = "Shared Ktor (CIO server) runtime for other StarLight plugins"
        category = "library"
    }
    deploy {
        // Pass -Pstarlight.device=<serial or ip:port> or set STARLIGHT_DEVICE.
        // Restarting force-stops StarLight on the device; opt in per run with -Pstarlight.restartApp=true.
    }
}

// The API dependent plugins compile against (KtorServers): copy the jar to their app/libs and declare it compileOnly.
// It includes META-INF/*.kotlin_module, without which top-level functions don't resolve.
tasks.register<Jar>("apiJar") {
    val compileKotlin = tasks.named<KotlinCompile>("compileReleaseKotlin")
    dependsOn(compileKotlin)
    from(compileKotlin.flatMap { it.destinationDirectory }) {
        include("dev/mooner/starlightktor/server/**")
        include("META-INF/*.kotlin_module")
    }
    archiveFileName = "ktor-api-$pluginVersion.jar"
    destinationDirectory = layout.buildDirectory.dir("outputs/api")
}

dependencies {
    // Provided by StarLight at runtime.
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Shared with dependent plugins, which declare these as compileOnly.
    // Upgrading Ktor changes the plugin version too, so dependent plugins must be checked against it.
    api("io.ktor:ktor-server-cio:3.6.0")

    testImplementation("junit:junit:4.13.2")
    // The compiler's stdlib; without it the test classpath settles on the older one Ktor asks for.
    testImplementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.20")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
}
