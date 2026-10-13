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
        // Build de teste lado a lado com o app instalado (ex.: -PPOKELOCATION_ID_SUFFIX=.dev): outro pacote, dados separados.
        providers.gradleProperty("POKELOCATION_ID_SUFFIX").orNull?.let { applicationIdSuffix = it }
        // Servidor social; sobrescreva com -PPOKELOCATION_SOCIAL_API=... (ex.: servidor local).
        buildConfigField("String", "SOCIAL_API",
            "\"" + (providers.gradleProperty("POKELOCATION_SOCIAL_API").orNull ?: "https://api.pokelocation.app") + "\"")
        // libsignal baixada (spec §3.1): abi:sha256:bytes do .so descomprimido, saída de tools/libsignal/prepare.sh.
        buildConfigField("String", "LIBSIGNAL_VERSION", "\"0.86.5\"")
        buildConfigField("String", "LIBSIGNAL_SO", "\"" +
            "arm64-v8a:2b9188dcbbfbb6716f31e09b54f97e2732e1d3085594fdcf90a8fa222b56e71e:6707408," +
            "armeabi-v7a:008381906eadde5ef97e6d7dcb45842e65fde78ebd0d461aaacb64134f02d276:4942304," +
            "x86_64:7cf4b3990f6bae29259bfa5f72d39796411e2511f3812363c497344d16beae72:7825800\"")
    }
    buildFeatures { buildConfig = true }
    // Com o sufixo, o app aparece como "PokeLocationTeste" (launcher, recentes, notificação): src/teste/res sobrepõe o
    // app_name do main em todos os build types. Sem a propriedade, nada muda.
    if (providers.gradleProperty("POKELOCATION_ID_SUFFIX").isPresent) {
        sourceSets.configureEach { if (name in setOf("debug", "release", "qa")) res.srcDir("src/teste/res") }
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
        isCoreLibraryDesugaringEnabled = true // a libsignal usa java.time (checkAarMetadata exige)
    }
    // libsignal (spec §3.1): o .so real é baixado no login; no APK fica só o stub de src/main/jniLibs, que vence o
    // lib/<abi>/libsignal_jni.so do AAR (x86 tem stub só para o app instalar; sem .so baixado, o social fica indisponível).
    // Fora: a lib de teste e os nativos de desktop do jar.
    packaging {
        jniLibs.excludes += "**/libsignal_jni_testing.so"
        resources.excludes += listOf("**/*.dylib", "**/*.dll", "**/libsignal_jni*.so")
    }
    androidResources {
        localeFilters += listOf("en", "pt-rBR")
        // Padrão do aapt + a versão -testing; assets/acknowledgments/libsignal.md fica (atribuição de licenças, obrigação legal).
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:<dir>_*:!CVS:!thumbs.db:!picasa.ini:!*~:!libsignal-testing.md"
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

// Orçamento de tamanho (spec §8): assembleQa/assembleRelease falham se o APK da própria variante passar de 2,5 MB.
val apkDir = layout.buildDirectory.dir("outputs/apk")
listOf("Qa", "Release").forEach { v ->
    val check = tasks.register("checkApkSize$v") {
        doLast {
            val max = 2_500_000L
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
    implementation("org.signal:libsignal-android:0.86.5") // parte Java; o nativo é baixado (spec §3.1)
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    testImplementation("junit:junit:4.13.2") // só teste JVM: zero impacto em RAM/APK
    testImplementation("net.sf.kxml:kxml2:2.3.0") // XmlPullParser/XmlSerializer nos testes JVM do gpx (só teste)
    testImplementation("org.json:json:20231013") // org.json real nos testes JVM (o android.jar mockado devolve null; só teste)
}
