plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.7.1"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.0"
    id("com.github.ben-manes.versions") version "0.50.0" // 自动依赖更新检查
    alias(libs.plugins.detekt) // 使用版本目录管理的 detekt 插件
}

group = "me.cw2"
version = "1.0.0"

repositories {
    // 阿里云 Maven 中央仓库镜像
    maven("https://maven.aliyun.com/repository/central")
    // 阿里云 JCenter 仓库镜像
    maven("https://maven.aliyun.com/repository/jcenter")
    // 阿里云 Spring 仓库镜像
    maven("https://maven.aliyun.com/repository/spring")
    // 阿里云 Apache Snapshots 仓库镜像
    maven("https://maven.aliyun.com/repository/apache-snapshots")
    // 备用：Maven 中央仓库
    mavenCentral()
    
    intellijPlatform {
        // 阿里云 Maven 中央仓库镜像（优先使用）
        maven("https://maven.aliyun.com/repository/central")
        // 阿里云 JCenter 仓库镜像
        maven("https://maven.aliyun.com/repository/jcenter")
        // 阿里云 Spring 仓库镜像
        maven("https://maven.aliyun.com/repository/spring")
        // 阿里云 Apache Snapshots 仓库镜像
        maven("https://maven.aliyun.com/repository/apache-snapshots")
        // 阿里云公共仓库镜像
        maven("https://maven.aliyun.com/repository/public")
        // 使用默认 IntelliJ 仓库作为备用
        defaultRepositories()
    }
}

// Configure IntelliJ Platform Gradle Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    intellijPlatform {
        create("IC", "2025.1.4.1")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

        // Add necessary plugin dependencies for compilation here:
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.kotlin")
    }

    // AI 服务集成依赖 - AI service integration dependencies
    implementation("com.theokanning.openai-gpt3-java:service:0.18.2")
    implementation("com.azure:azure-ai-openai:1.0.0-beta.8")
    
    // 网络通信依赖 - Network communication dependencies
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    
    // 序列化和工具依赖 - Serialization and utility dependencies
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")
    implementation("com.google.guava:guava:32.1.3-jre")
    
    // 测试框架依赖 - Testing framework dependencies
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testImplementation("org.testcontainers:testcontainers:1.19.3")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "251"
        }

        changeNotes = """
            Initial version
        """.trimIndent()
    }
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }

    runIde {
        2
        jvmArgumentProviders += CommandLineArgumentProvider {
            listOf("-Didea.kotlin.plugin.use.k2=true")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        // 显式使用 Kotlin 2.1 (K2 编译器默认启用)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_1)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_1)
    }
}
