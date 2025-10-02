rootProject.name = "nym"

pluginManagement {
    repositories {
        // 阿里云 Gradle 插件仓库镜像
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        // 阿里云 Maven 中央仓库镜像
        maven("https://maven.aliyun.com/repository/central")
        // 阿里云 JCenter 仓库镜像
        maven("https://maven.aliyun.com/repository/jcenter")
        // 备用：Gradle 插件门户
        gradlePluginPortal()
        // 备用：Maven 中央仓库
        mavenCentral()
    }
}