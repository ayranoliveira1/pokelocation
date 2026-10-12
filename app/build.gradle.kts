plugins { id("com.android.application") }

android {
    namespace = "com.pokelocation.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.pokelocation.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }
    // Credenciais só em ~/.gradle/gradle.properties (fora do repo); sem elas o release sai sem assinatura.
    val storeFilePath = providers.gradleProperty("POKELOCATION_STORE_FILE").orNull
    if (storeFilePath != null) {
        signingConfigs.create("release") {
            storeFile = file(storeFilePath)
            storePassword = providers.gradleProperty("POKELOCATION_STORE_PASSWORD").get()
            keyAlias = providers.gradleProperty("POKELOCATION_KEY_ALIAS").get()
            keyPassword = providers.gradleProperty("POKELOCATION_KEY_PASSWORD").get()
        }
    }
    buildTypes {
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
        }
        release {
            if (storeFilePath != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // Igual ao release (R8, shrink, não-debuggable), assinado com a chave debug: instalável no emulador para medir
        // RAM como no release (QA-RAM R0/R5). Não é para distribuição.
        create("qa") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    androidResources { localeFilters += listOf("en", "pt-rBR") }
    testOptions { unitTests.isReturnDefaultValues = true }
}

tasks.withType<JavaCompile>().configureEach { options.compilerArgs.addAll(listOf("-Xmaxerrs", "10000")) }

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    testImplementation("junit:junit:4.13.2") // só teste JVM: zero impacto em RAM/APK
    testImplementation("net.sf.kxml:kxml2:2.3.0") // XmlPullParser/XmlSerializer nos testes JVM do gpx (só teste)
}
