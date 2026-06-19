plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.billai"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.billai"
        minSdk = 26 // Votre minSdk original
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        compose = true
        viewBinding = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.3"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/INDEX.LIST"
            )
        }
    }

}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")

    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.0")
    implementation("androidx.activity:activity-ktx:1.9.0")

    // Compose UI (si vous l'utilisez toujours)
    implementation("androidx.activity:activity-compose:1.7.2")
    implementation("androidx.compose.ui:ui:1.5.3")
    implementation("androidx.compose.material3:material3:1.1.0")
    implementation("androidx.compose.ui:ui-tooling-preview:1.5.3")
    implementation(libs.espresso.core)
    debugImplementation("androidx.compose.ui:ui-tooling:1.5.3")

    // Exemple: image picker (si besoin)
    implementation("com.github.dhaval2404:imagepicker:2.1")

    // Ta lib generative AI
    implementation("com.google.ai.client.generativeai:generativeai:0.6.0")

    // Pour les tests unitaires JVM
    testImplementation("junit:junit:4.13.2")

    // Pour les tests instrumentés Android
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")

    // PhotoView
    implementation("com.github.chrisbanes:PhotoView:2.3.0")

    // OkHttp
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // --- Dépendances Apache POI Mises à Jour et Complètes (corrigées) ---
    implementation("org.apache.poi:poi:5.2.5") // Core POI
    implementation("org.apache.poi:poi-ooxml:5.2.5") // OOXML pour XLSX
    // SUPPRIMEZ la ligne suivante, car elle cause les doublons avec poi-ooxml:
    // implementation("org.apache.poi:poi-ooxml-full:5.2.5")
    implementation("org.apache.commons:commons-collections4:4.4") // Dépendance de POI
    implementation("commons-io:commons-io:2.15.0") // Utilitaires I/O

    // Note : Inutile d'ajouter explicitement poi-ooxml-schemas ou xmlbeans si poi-ooxml les inclut déjà transitivement.
    // Si vous rencontrez des problèmes de classes manquantes après ce changement, on pourra les rajouter.


    // Google Drive API (avec support Android)
    implementation("com.google.api-client:google-api-client:2.4.0")
    implementation("com.google.http-client:google-http-client-gson:1.44.1")
    implementation("com.google.apis:google-api-services-drive:v3-rev20240722-2.0.0")

    // Auth pour compte de service
    implementation("com.google.auth:google-auth-library-oauth2-http:1.24.0")

    implementation("com.google.android.gms:play-services-tasks:18.1.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

}