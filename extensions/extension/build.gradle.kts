extension {
    name = "extensions/discord.mpe"
}

android {
    namespace = "me.dyskal.ytmrpc"
    defaultConfig { minSdk = 26 }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
