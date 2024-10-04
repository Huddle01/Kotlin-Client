plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    id("com.google.protobuf") version "0.9.4"
    `maven-publish`
}

android {
    namespace = "com.huddle01.kotlin_client"
    compileSdk = 34

    defaultConfig {
        minSdk = 21

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    sourceSets {
        getByName("main") {
            java.srcDirs("java/com/kotlin/kotlin_client/proto")
        }
    }
    viewBinding.enable = true
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    implementation(libs.java.websocket)
    implementation(libs.ktor.client.android)
    implementation(libs.gson)
    // protobuf
    api(libs.protobuf.kotlin)
    // mediasoup
    api(libs.libmediasoup.android)
    api(libs.libwebrtc.ktx)
    // timber
    api(libs.timber)
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.21.7"
    }
    plugins {
        protoc {
            artifact = "com.google.protobuf:protoc:3.21.7"
        }
        generateProtoTasks {
            all().forEach {
                it.builtins {
                    create("kotlin") {
                        option("lite")
                    }
                    create("java") {
                        option("lite")
                    }
                }
            }
        }
    }
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.huddle01.kotlin_client"
            artifactId = "kotlin-client"
            version = "1.0.0"
            afterEvaluate {
                from(components["release"])
            }
        }
    }
}