import java.time.Duration

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    api(project(":core:common"))
    // Doar JsonElement (fără plugin de serializare): răspunsurile TheSportsDB se citesc câmp cu câmp.
    implementation(libs.kotlinx.serialization.json)
    // Paginile HTML (LiveSoccerTV) se citesc cu Jsoup, tolerant la schimbări mici de structură.
    implementation(libs.jsoup)
    testImplementation(libs.junit)
}

tasks.test {
    // Un test blocat nu ține tot build-ul CI.
    timeout.set(Duration.ofMinutes(3))
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
