plugins {
    kotlin("jvm") version "2.0.21"
    application
}

group = "com.equisaas"
version = "1.0.0"

repositories { mavenCentral() }

dependencies {
    testImplementation(kotlin("test"))
}

kotlin { jvmToolchain(21) }

application { mainClass.set("com.equisaas.warehouse.MainKt") }

tasks.test { useJUnitPlatform() }

