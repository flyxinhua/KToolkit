// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.vanniktech.maven.publish) apply false
}

// 2. 统一配置所有子模块
subprojects {
    // 只有应用了发布插件的模块才执行此逻辑
    plugins.withType<com.vanniktech.maven.publish.MavenPublishPlugin> {
        val extension = extensions.getByType<com.vanniktech.maven.publish.MavenPublishBaseExtension>()

        extension.apply {
            publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL)
            signAllPublications()

            pom {
                url.set("https://github.com/flyxinhua/KToolkit")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("flyxinhua")
                        name.set("sanvar")
                        email.set("flyxinhua@163.com")
                    }
                }
                scm {
                    connection.set("scm:git:github.com/flyxinhua/KToolkit.git")
                    developerConnection.set("scm:git:ssh://github.com/flyxinhua/KToolkit.git")
                    url.set("https://github.com/flyxinhua/KToolkit/tree/main")
                }
            }
        }
    }
}