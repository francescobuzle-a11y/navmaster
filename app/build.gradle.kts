import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationParameters
import com.android.build.api.instrumentation.InstrumentationScope
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

plugins {
  alias(libs.plugins.androidApplication)
  alias(libs.plugins.composeCompiler)
  alias(libs.plugins.kotlinSerialization)
}

val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
  namespace = "app.navmaster.truck"
  compileSdk = 36

  defaultConfig {
    applicationId = "app.navmaster.truck"
    minSdk = 29
    targetSdk = 36
    versionCode = buildNumber
    versionName = "0.1.$buildNumber"
    vectorDrawables { useSupportLibrary = true }
    // MapLibre + Valhalla are native: tablets are arm64, the CI emulator is x86_64
    ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    // service keys built into the app (GitHub secrets of the repository, when present): the driver
    // never has to ask for or type a key
    for (k in listOf("TOMTOM_KEY", "HERE_KEY", "TRAFIKVERKET_KEY", "MAPILLARY_TOKEN")) {
      buildConfigField("String", k, "\"" + (System.getenv(k) ?: "").replace("\"", "") + "\"")
    }
  }

  signingConfigs {
    // Same key on every CI build, so a new APK installs over the previous one on the tablet
    create("ci") {
      storeFile = file("../keystore/navmaster-ci.jks")
      storePassword = "navmaster"
      keyAlias = "navmaster"
      keyPassword = "navmaster"
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      signingConfig = signingConfigs.getByName("ci")
    }
    debug { signingConfig = signingConfigs.getByName("ci") }
  }

  compileOptions {
    isCoreLibraryDesugaringEnabled = true
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  buildFeatures {
    compose = true
    buildConfig = true
  }

  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
      // the same licence / module files in several of GraphHopper's libraries
      excludes += listOf("/META-INF/DEPENDENCIES", "/META-INF/LICENSE*", "/META-INF/NOTICE*", "/META-INF/INDEX.LIST",
          "/META-INF/versions/*/module-info.class", "/module-info.class", "/META-INF/versions/*/OSGI-INF/MANIFEST.MF")
    }
    jniLibs { useLegacyPackaging = true }
  }
}

dependencies {
  coreLibraryDesugaring(libs.desugar)

  implementation(libs.core.ktx)
  implementation(libs.activity.compose)
  implementation(libs.lifecycle.runtime)
  implementation(libs.lifecycle.viewmodel)
  implementation(libs.lifecycle.viewmodel.compose)
  implementation(libs.coroutines)
  implementation(libs.serialization.json)

  implementation(platform(libs.compose.bom))
  implementation(libs.compose.ui)
  implementation(libs.compose.ui.graphics)
  implementation(libs.compose.material3)
  implementation(libs.compose.material.icons)

  // navigation core (guidance, rerouting, voice) and its MapLibre map
  implementation(libs.ferrostar.core)
  implementation(libs.ferrostar.ui.compose)
  implementation(libs.ferrostar.ui.maplibre)
  implementation(libs.ferrostar.ui.formatters)
  implementation(libs.maplibre.compose)
  implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")

  // offline routing on the tablet
  // GraphHopper (Apache 2.0) computes the route with the vehicle's measures, offline. Janino (its
  // run-time compiler of custom models) is left out: Android cannot run what it compiles, the
  // rules are written in code instead (routing/gh/NmWeightingFactory)
  implementation("com.graphhopper:graphhopper-core:11.0") {
    exclude(group = "org.codehaus.janino")
  }
  implementation(libs.valhalla.mobile)
  implementation(libs.valhalla.models)
  implementation(libs.valhalla.models.config)

  implementation(platform(libs.okhttp.bom))
  implementation(libs.okhttp)
}

/**
 * GraphHopper's memory-mapped storage calls ByteBuffer.get/put(index, array, offset, length), which
 * Android has only from version 15: those calls are redirected, in GraphHopper's own class, to
 * routing/gh/NioCompat (same result on every Android version).
 */
abstract class NioCompatFactory : AsmClassVisitorFactory<InstrumentationParameters.None> {
  override fun createClassVisitor(classContext: ClassContext, nextClassVisitor: ClassVisitor): ClassVisitor =
      object : ClassVisitor(Opcodes.ASM9, nextClassVisitor) {
        override fun visitMethod(access: Int, name: String?, descriptor: String?, signature: String?, exceptions: Array<out String>?): MethodVisitor? {
          val mv = super.visitMethod(access, name, descriptor, signature, exceptions) ?: return null
          return object : MethodVisitor(Opcodes.ASM9, mv) {
            override fun visitMethodInsn(opcode: Int, owner: String?, name: String?, descriptor: String?, isInterface: Boolean) {
              if (opcode == Opcodes.INVOKEVIRTUAL && (owner == "java/nio/ByteBuffer" || owner == "java/nio/MappedByteBuffer") &&
                  (name == "get" || name == "put") && descriptor == "(I[BII)Ljava/nio/ByteBuffer;") {
                super.visitMethodInsn(Opcodes.INVOKESTATIC, "app/navmaster/truck/routing/gh/NioCompat", name,
                    "(Ljava/nio/ByteBuffer;I[BII)Ljava/nio/ByteBuffer;", false)
              } else {
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
              }
            }
          }
        }
      }

  override fun isInstrumentable(classData: ClassData): Boolean = classData.className == "com.graphhopper.storage.MMapDataAccess"
}

androidComponents {
  onVariants { variant ->
    variant.instrumentation.transformClassesWith(NioCompatFactory::class.java, InstrumentationScope.ALL) {}
    variant.instrumentation.setAsmFramesComputationMode(FramesComputationMode.COPY_FRAMES)
  }
}
