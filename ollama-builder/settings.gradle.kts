// Настройка репозиториев и версии плагина Fabric Loom.
//
// Внутри блока pluginManagement недоступны ни обычные val из скрипта,
// ни `extra`, зато доступен `settings.providers` — им и читаем gradle.properties,
// чтобы версия Loom задавалась в одном месте.
pluginManagement {
    repositories {
        maven {
            name = "Fabric"
            url = uri("https://maven.fabricmc.net/")
        }
        mavenCentral()
        gradlePluginPortal()
    }

    plugins {
        id("fabric-loom") version settings.providers
            .gradleProperty("loom_version")
            .getOrElse("1.18-SNAPSHOT")
    }
}

// Корневой проект — сам мод.
rootProject.name = "ollama-builder"
