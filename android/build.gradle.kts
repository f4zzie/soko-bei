plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Base URLs come from Gradle properties:
//   debug   -PsokoDebugBaseUrl=...    default http://10.0.2.2:8080 (emulator -> host)
//   staging -PsokoBaseUrl=...         default http://localhost:8080 (physical phone with `adb reverse tcp:8080 tcp:8080`)
//   release -PsokoReleaseBaseUrl=...  required: building a release artifact fails while it is the placeholder (or not https)
val releasePlaceholderUrl = "https://api.sokobei.example"
val releaseBaseUrl = (project.findProperty("sokoReleaseBaseUrl") as String?) ?: releasePlaceholderUrl
val stagingBaseUrl = (project.findProperty("sokoBaseUrl") as String?) ?: "http://localhost:8080"
val debugBaseUrl = (project.findProperty("sokoDebugBaseUrl") as String?) ?: "http://10.0.2.2:8080"

android {
    namespace = "ke.sokobei.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "ke.sokobei.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        debug {
            buildConfigField("String", "BASE_URL", "\"$debugBaseUrl\"")
            // Cleartext is allowed only for localhost and 10.0.2.2 (see network_security_config_dev.xml).
            manifestPlaceholders["usesCleartext"] = "false"
            manifestPlaceholders["networkSecurityConfig"] = "@xml/network_security_config_dev"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("String", "BASE_URL", "\"$releaseBaseUrl\"")
            manifestPlaceholders["usesCleartext"] = "false"
            manifestPlaceholders["networkSecurityConfig"] = "@xml/network_security_config_release"
        }
        // Same R8/minify/shrink and keep rules as release, signed with the debug keystore, installs next to debug.
        create("staging") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            applicationIdSuffix = ".staging"
            versionNameSuffix = "-staging"
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("String", "BASE_URL", "\"$stagingBaseUrl\"")
            manifestPlaceholders["usesCleartext"] = "false"
            manifestPlaceholders["networkSecurityConfig"] = "@xml/network_security_config_dev"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all {
                // Robolectric 4.17 (android-all 36) reflects into JDK internals on JDK 21.
                it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED", "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
                it.maxHeapSize = "1g"
            }
        }
    }

    sourceSets {
        // Room exported schemas are checked in for review.
        getByName("test").assets.srcDir("$projectDir/schemas")
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties")
    }
}

// Release artifacts must point at a real https backend. Enforced for explicit release tasks
// (assembleRelease / bundleRelease / packageRelease); `build` does not assemble release (see below).
val verifyReleaseBaseUrl by tasks.registering {
    val url = releaseBaseUrl
    val placeholder = releasePlaceholderUrl
    doLast {
        check(url != placeholder) {
            "Release base URL is still the placeholder $placeholder. Pass -PsokoReleaseBaseUrl=https://your.backend"
        }
        check(url.startsWith("https://")) { "Release base URL must be https, got $url" }
    }
}
tasks.matching { it.name in setOf("assembleRelease", "bundleRelease", "packageRelease") }.configureEach {
    dependsOn(verifyReleaseBaseUrl)
}

// `assemble` (and so `build`) produces debug and staging only; release artifacts are built explicitly so that
// nobody ships an APK with the placeholder URL by running a plain `./gradlew build`.
afterEvaluate {
    tasks.named("assemble") { setDependsOn(listOf("assembleDebug", "assembleStaging")) }
}

// Build-time proof that release forbids cleartext: inspects the real merged release manifest and the
// network security config it references. Runs as part of `check`.
val verifyReleaseNetworkSecurity by tasks.registering {
    dependsOn("processReleaseMainManifest")
    val manifests = layout.buildDirectory.dir("intermediates")
    val resXml = layout.projectDirectory.dir("src/main/res/xml")
    doLast {
        val merged = manifests.get().asFile.walkTopDown()
            .filter { it.name == "AndroidManifest.xml" && it.path.contains("/release/") && it.path.contains("merged_manifest") }
            .firstOrNull() ?: error("merged release manifest not found")
        val text = merged.readText()
        check(Regex("""android:usesCleartextTraffic="false"""").containsMatchIn(text)) { "release manifest must set usesCleartextTraffic=false" }
        check("network_security_config_release" in text) { "release manifest must use network_security_config_release" }
        val nsc = resXml.file("network_security_config_release.xml").asFile.readText()
        check(Regex("""<base-config[^>]*cleartextTrafficPermitted="false"""").containsMatchIn(nsc)) { "release base-config must forbid cleartext" }
        check("cleartextTrafficPermitted=\"true\"" !in nsc) { "release config must not permit cleartext anywhere" }
    }
}
tasks.named("check") { dependsOn(verifyReleaseNetworkSecurity) }

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":shared"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.auth)
    implementation(libs.ktor.client.contentnegotiation.android)
    implementation(libs.ktor.serialization.kotlinx.json.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.ktor.client.mock)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)

    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.turbine)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.ktor.client.mock)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
}
