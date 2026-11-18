import groovy.json.JsonSlurper

plugins {
    id("com.android.application")
}

// Personal settings and secrets live in config.local.json (not in git); see config.example.json.
val localConfig: Map<*, *> = rootProject.file("config.local.json").let {
    if (it.exists()) JsonSlurper().parse(it) as Map<*, *> else emptyMap<Any, Any>()
}

fun configString(key: String): String {
    val value = (localConfig[key] as? String).orEmpty()
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

android {
    namespace = "dk.adam.tv"
    compileSdk = 37

    defaultConfig {
        applicationId = "dk.adam.tv"
        minSdk = 21
        // Android 7.0 / API 24, the oldest Android TV this was tested on.
        targetSdk = 24
        versionCode = 2
        versionName = "2.0"
        buildConfigField("String", "CALENDAR_URL", configString("calendarUrl"))
        buildConfigField("String", "CHROMECAST_IP", configString("chromecastIp"))
        buildConfigField("String", "OWNER_NAME", configString("name"))
        buildConfigField("String", "LATITUDE", configString("latitude"))
        buildConfigField("String", "LONGITUDE", configString("longitude"))
        buildConfigField("String", "CITY", configString("city"))
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Personal sideload build: sign with the local debug key.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
