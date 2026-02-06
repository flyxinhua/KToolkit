import com.vanniktech.maven.publish.SonatypeHost

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.vanniktech.maven.publish)
}

android {
    namespace = "com.sanvar.log"
    compileSdk = 29

    defaultConfig {
        minSdk = 21
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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

mavenPublishing {
    // 只需要定义自己独特的坐标和名称
    coordinates("io.github.flyxinhua", "klog", "1.0.1")
    pom {
        name.set("KLog")
        description.set("A simple and powerful logger for Android and Kotlin.")
    }
}

//mavenPublishing {
//    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
//    signAllPublications()
//    coordinates("io.github.flyxinhua", "klog", "1.0.1")
//
//    pom {
//        name.set("KLog")
//        description.set("A simple and powerful logger for Android and Kotlin.")
//        url.set("https://github.com/flyxinhua/KLog")
//
//        licenses {
//            license {
//                name.set("The Apache License, Version 2.0")
//                url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
//            }
//        }
//        developers {
//            developer {
//                id.set("flyxinhua")
//                name.set("sanvar")
//                email.set("flyxinhua@163.com")
//            }
//        }
//        scm {
//            connection.set("scm:git:github.com/flyxinhua/KLog.git")
//            developerConnection.set("scm:git:ssh://github.com/flyxinhua/KLog.git")
//            url.set("https://github.com/flyxinhua/KLog/tree/main")
//        }
//    }
//}


dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4")
}
