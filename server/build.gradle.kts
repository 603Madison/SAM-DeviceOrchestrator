plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

// One version for the whole project: the Android app's versionName.
val samoVersion: String = Regex("versionName = \"([^\"]+)\"")
    .find(rootProject.file("android/build.gradle.kts").takeIf { it.exists() }?.readText().orEmpty())?.groupValues?.get(1) ?: "dev"

version = samoVersion

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

application {
    mainClass.set("com.orchestrator.server.MainKt")
    applicationName = "samo-server"
}

// The web app, built by `npm run build` in web/ into web/dist, is served from the jar.
val webDist = rootProject.layout.projectDirectory.dir("web/dist")

tasks.processResources {
    inputs.property("version", samoVersion)
    filesMatching("samo-version.properties") { expand("version" to samoVersion) }
    from(webDist) { into("web") }
}

tasks.test {
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    // Netty with its security fixes, over the older one Ktor asks for.
    implementation(platform(libs.netty.bom))
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.forwarded.header)
    implementation(libs.ktor.server.default.headers)
    implementation(libs.ktor.server.compression)
    implementation(libs.ktor.server.body.limit)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)
    implementation(libs.sqlite.jdbc)
    implementation(libs.nimbus.jose.jwt)
    implementation(libs.logback.classic)

    testImplementation(libs.junit)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.websockets)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
