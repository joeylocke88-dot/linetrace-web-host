import com.android.build.api.variant.VariantOutputConfiguration

plugins {
    id("com.android.application")
}

android {
    namespace = "com.linetrace.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.linetrace.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    buildFeatures {
        resValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        val mainOutput = variant.outputs.single { it.outputType == VariantOutputConfiguration.OutputType.SINGLE }

        val versionCodeTask = project.tasks.register("computeVersionCodeFor${variant.name}", VersionCodeTask::class.java) {
            outputFile.set(project.layout.buildDirectory.file("versionCode${variant.name}.txt"))
        }

        mainOutput.versionCode.set(versionCodeTask.flatMap { it.outputFile.map { it.asFile.readText().trim().toInt() } })
    }
}

abstract class VersionCodeTask : DefaultTask() {
    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun action() {
        outputFile.get().asFile.writeText("2\n")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.core)
    implementation(libs.androidx.foundation.layout)
    implementation(libs.play.services.nearby)
    implementation(libs.okhttp)
    implementation(libs.java.websocket.client)
    implementation(libs.lz4java)
    testImplementation(libs.junit)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.json)
}

configurations.all {
    resolutionStrategy {
        capabilitiesResolution.withCapability("org.lz4:lz4-java") {
            select("org.lz4:lz4-java:1.7.1")
        }
    }
}
