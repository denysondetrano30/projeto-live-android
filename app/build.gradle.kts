plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "live.projeto.camera"
    compileSdk = 36

    defaultConfig {
        applicationId = "live.projeto.camera"
        minSdk = 26 // Android 8
        targetSdk = 35
        versionCode = 3
        versionName = "0.3"
    }

    // Chave de TESTE (pública, no repositório): permite atualizar o app por cima sem desinstalar.
    // Para publicar na loja, trocar por uma chave guardada em segredo.
    signingConfigs {
        create("teste") {
            storeFile = file("../keystore/projeto-live-teste.jks")
            storePassword = "projetolive"
            keyAlias = "projetolive"
            keyPassword = "projetolive"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("teste")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("io.github.thibaultbee.srtdroid:srtdroid-core:1.9.5")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
