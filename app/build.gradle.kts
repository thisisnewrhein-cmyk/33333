import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.routescout.mobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.routescout.mobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "0.7.0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.10.00"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.wireguard.android:tunnel:1.0.20260102")
    implementation("com.github.singbox-android:libbox:1.14.4")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.3")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}


val verifyLibboxAar by tasks.registering {
    doLast {
        val artifact = configurations.getByName("debugRuntimeClasspath")
            .resolvedConfiguration.resolvedArtifacts
            .firstOrNull {
                it.moduleVersion.id.group == "com.github.singbox-android" &&
                    it.name == "libbox"
            } ?: error("Pinned sing-box libbox 1.14.4 AAR was not resolved")

        val digest = MessageDigest.getInstance("SHA-256")
        artifact.file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { byte ->
            "%02x".format(byte)
        }
        val expected = "d2685924a657705beda44e94c6dc499e781df1ad20d65474e3591ec2f8b96465"
        check(actual.equals(expected, ignoreCase = true)) {
            "libbox 1.14.4 SHA-256 mismatch: " + actual
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(verifyLibboxAar)
}
