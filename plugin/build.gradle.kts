plugins {
    kotlin("jvm") version "2.3.20"
    `java-gradle-plugin`
}

group = property("sdkGroup") as String
version = property("sdkVersion") as String

repositories {
    mavenCentral()
}

dependencies {
    implementation(gradleApi())
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(23)
}

tasks.test {
    useJUnitPlatform()
}

gradlePlugin {
    plugins {
        create("lightSdk") {
            id = "com.thelightphone.light-sdk"
            implementationClass = "com.thelightphone.plugin.LightSdkPlugin"
        }
    }
}
