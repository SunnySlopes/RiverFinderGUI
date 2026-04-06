plugins {
    id("java")
}

group = "riverfinder"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
}

tasks.test {
    useJUnitPlatform()
}