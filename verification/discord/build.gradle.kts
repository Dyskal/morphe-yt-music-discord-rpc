plugins { java }
repositories { mavenCentral() }
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
sourceSets.main { java.srcDir("../../extensions/extension/src/main/java") }
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")
    compileOnly("org.robolectric:android-all:8.0.0_r4-robolectric-r1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
tasks.test { useJUnitPlatform() }
