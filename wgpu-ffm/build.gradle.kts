plugins {
    id("java-library")
    id("module.publication")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    withSourcesJar()
}

publishing { publications { create<MavenPublication>("wgpu-ffm") { from(components["java"]) } } }
