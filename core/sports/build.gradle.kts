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
    testImplementation(libs.junit)
}

tasks.test {
    // Un test blocat nu ține tot build-ul CI.
    timeout.set(java.time.Duration.ofMinutes(3))
    testLogging { events("failed") }
}
