plugins { id("com.android.application") }

android {
    namespace = "com.pokelocation.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.pokelocation.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 3
        versionName = "1.2.0"
        // Build de teste lado a lado com o app instalado: outro pacote, dados separados.
        providers.gradleProperty("POKELOCATION_ID_SUFFIX").orNull?.let { applicationIdSuffix = it }
        buildConfigField("String", "SOCIAL_API",
            "\"" + (providers.gradleProperty("POKELOCATION_SOCIAL_API").orNull ?: "https://pokelocation.waveplay.xyz") + "\"")
        // abi:sha256:bytes do .so descomprimido, gerado por tools/libsignal/prepare.sh.
        buildConfigField("String", "LIBSIGNAL_VERSION", "\"0.86.5\"")
        buildConfigField("String", "LIBSIGNAL_SO", "\"" +
            "arm64-v8a:2b9188dcbbfbb6716f31e09b54f97e2732e1d3085594fdcf90a8fa222b56e71e:6707408," +
            "armeabi-v7a:008381906eadde5ef97e6d7dcb45842e65fde78ebd0d461aaacb64134f02d276:4942304," +
            "x86_64:7cf4b3990f6bae29259bfa5f72d39796411e2511f3812363c497344d16beae72:7825800\"")
        // Valores reais por -P (~/pokelocation-secrets/firebase-app.txt), nunca no repo; sem eles não chega push.
        fun firebase(name: String, placeholder: String) = buildConfigField("String", name,
            "\"" + (providers.gradleProperty("POKELOCATION_$name").orNull ?: placeholder) + "\"")
        firebase("FIREBASE_APP_ID", "1:000000000000:android:0000000000000000")
        firebase("FIREBASE_API_KEY", "AIzaPlaceholderPlaceholderPlaceholder000")
        firebase("FIREBASE_PROJECT_ID", "pokelocation-placeholder")
        firebase("FIREBASE_SENDER_ID", "000000000000")
    }
    buildFeatures { buildConfig = true }

    // src/teste/res troca o app_name para "PokeLocationTeste" na build de teste.
    if (providers.gradleProperty("POKELOCATION_ID_SUFFIX").isPresent) {
        sourceSets.configureEach { if (name in setOf("debug", "release", "qa", "r8test")) res.srcDir("src/teste/res") }
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
        // Teste local, nunca distribuído. Não pode ser debuggable: isso desliga a renomeação do R8, que é o que quebra
        // os *Store da libsignal. src/r8test confia na CA local do Caddy.
        create("r8test") {
            initWith(getByName("qa"))
            applicationIdSuffix = ".r8test" // nunca o pacote do app do usuário
            matchingFallbacks += listOf("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true // a libsignal usa java.time
    }
    // O libsignal_jni.so real é baixado no login; o stub de src/main/jniLibs vence o do AAR (x86: stub só para instalar).
    packaging {
        jniLibs.excludes += "**/libsignal_jni_testing.so"
        // Só a DataStore multiprocesso carrega este .so; o Firebase usa a de um processo.
        jniLibs.excludes += "**/libdatastore_shared_counter.so"
        resources.excludes += listOf("**/*.dylib", "**/*.dll", "**/libsignal_jni*.so")
        // Lidos só pelo kotlin-reflect e pelas métricas de entrega do FCM (excluídas).
        resources.excludes += listOf("**/*.kotlin_builtins", "messaging_event*.proto")
    }
    androidResources {
        localeFilters += listOf("en", "pt-rBR")
        // Padrão do aapt + libsignal-testing.md; o libsignal.md fica (atribuição de licenças, obrigação legal).
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:<dir>_*:!CVS:!thumbs.db:!picasa.ini:!*~:!libsignal-testing.md"
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

// Orçamento de tamanho: APK de qa/release ≤ 2,5 MB.
val apkDir = layout.buildDirectory.dir("outputs/apk")
listOf("Qa", "Release").forEach { v ->
    val check = tasks.register("checkApkSize$v") {
        doLast {
            val max = 2_550_000L
            apkDir.get().dir(v.lowercase()).asFile.listFiles { f -> f.extension == "apk" }?.forEach {
                if (it.length() > max) throw GradleException("checkApkSize: ${it.name} tem ${it.length()} B (> $max B)")
            }
        }
    }
    tasks.matching { it.name == "assemble$v" }.configureEach { finalizedBy(check) }
}

tasks.withType<JavaCompile>().configureEach { options.compilerArgs.addAll(listOf("-Xmaxerrs", "10000")) }

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    implementation("org.signal:libsignal-android:0.86.5")
    implementation("com.google.firebase:firebase-messaging:26.0.0") {
        // O messaging trata o TransportFactory das métricas de entrega como opcional.
        exclude(group = "com.google.android.datatransport", module = "transport-runtime")
        exclude(group = "com.google.android.datatransport", module = "transport-backend-cct")
    }
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    testImplementation("junit:junit:4.13.2") // só teste JVM: zero impacto em RAM/APK
    testImplementation("net.sf.kxml:kxml2:2.3.0") // XmlPullParser/XmlSerializer nos testes JVM do gpx (só teste)
    testImplementation("org.json:json:20231013") // o org.json do android.jar mockado devolve null
}
