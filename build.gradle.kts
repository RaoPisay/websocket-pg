plugins {
    id("java")
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.mockito:mockito-core:5.7.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.7.0")
    testImplementation("org.awaitility:awaitility:4.2.0")
    implementation("io.netty:netty-all:4.2.1.Final")
    implementation("com.caucho:hessian:4.0.66")
}

tasks.test {
    useJUnitPlatform()
}