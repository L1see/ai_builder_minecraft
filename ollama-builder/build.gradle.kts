plugins {
    // Версия Loom задаётся в settings.gradle.kts (ключ loom_version в gradle.properties).
    id("fabric-loom")
    java
}

version = providers.gradleProperty("mod_version").get()
group = providers.gradleProperty("maven_group").get()

base {
    archivesName = providers.gradleProperty("archives_base_name")
}

repositories {
    // Loom сам добавляет репозитории Mojang и Fabric, здесь — только запасные.
    mavenCentral()
    maven { url = uri("https://maven.fabricmc.net/") }
}

loom {
    // Весь код мода живёт в src/main/java (так требует структура проекта).
    // Клиентские классы помечены @Environment(EnvType.CLIENT) и регистрируются
    // только из client-entrypoint'а, поэтому на выделенном сервере не загружаются.
    mods {
        register("ollama-builder") {
            sourceSet(sourceSets.main.get())
        }
    }
}

dependencies {
    // Minecraft + Yarn-маппинги + загрузчик Fabric.
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    mappings("net.fabricmc:yarn:${property("yarn_mappings")}:v2")
    modImplementation("net.fabricmc:fabric-loader:${property("loader_version")}")

    // Fabric API — единственная внешняя зависимость.
    // Больше ничего не подключается: HTTP — java.net.http.HttpClient (JDK),
    // JSON — Gson (уже есть в игре).
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
}

tasks.processResources {
    val modVersion = providers.gradleProperty("mod_version").get()
    inputs.property("version", modVersion)
    filesMatching("fabric.mod.json") {
        expand(mutableMapOf("version" to modVersion))
    }
}

java {
    // Loom автоматически добавляет RemapSourcesJar к задаче build.
    withSourcesJar()
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 21
}

tasks.jar {
    from("LICENSE") {
        rename { "${it}_${base.archivesName.get()}" }
    }
}
