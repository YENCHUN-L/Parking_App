plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val generatedPayexAssetsDir = layout.buildDirectory.dir("generated/assets/payex")

val syncPayexAsset by tasks.registering(Copy::class) {
    val sourceFile = rootProject.file("Output/payex_structured.json")
    from(sourceFile)
    into(generatedPayexAssetsDir)
    doFirst {
        if (!sourceFile.exists()) {
            throw GradleException("Missing required file: Output/payex_structured.json")
        }
    }
}

androidComponents {
    beforeVariants(selector().withBuildType("release")) { variantBuilder ->
        variantBuilder.enable = false
    }
}

android {
    namespace = "com.example.parkingapp"
    compileSdk = 34

    sourceSets {
        getByName("main") {
            assets.srcDir(generatedPayexAssetsDir)
        }
    }

    defaultConfig {
        applicationId = "com.example.parkingapp"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

tasks.named("preBuild") {
    dependsOn(syncPayexAsset)
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.google.android.gms:play-services-ads:23.3.0")
    implementation("org.osmdroid:osmdroid-android:6.1.18")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
