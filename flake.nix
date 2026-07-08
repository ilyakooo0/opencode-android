{
  description = "Dev environment for opencode-android — a Crux (Rust) core + Kotlin / Jetpack Compose shell";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        # Kept in sync with the Gradle project (see gradle/libs.versions.toml and
        # app/build.gradle.kts). These are the versions actually provisioned below,
        # so `compileSdk` / `buildToolsVersion` must match or Gradle will try to
        # download SDK components at build time.
        platformVersion   = "35";
        buildToolsVersion = "35.0.0";

        pkgs = import nixpkgs {
          inherit system;
          config = {
            allowUnfree = true;              # the Android SDK is unfree
            android_sdk.accept_license = true;
          };
        };

        android = pkgs.androidenv.composeAndroidPackages {
          platformVersions    = [ platformVersion ];
          buildToolsVersions  = [ buildToolsVersion ];
          cmdLineToolsVersion = "latest";

          includeEmulator     = false;
          includeSystemImages = false;

          # The Rust core is NOT cross-compiled to a JNI .so in this setup: nixpkgs'
          # `rustc` ships no Android std and no NDK is provisioned. The core is built
          # and unit-tested natively (`cargo test`), and the Android shell runs a
          # faithful Kotlin port of the reducer. Flip `includeNDK = true` and add
          # `cargo-ndk` below to build the real .so once a cross-toolchain is present.
          includeNDK = false;
        };

        sdk       = android.androidsdk;
        sdkRoot   = "${sdk}/libexec/android-sdk";
        aapt2Path = "${sdkRoot}/build-tools/${buildToolsVersion}/aapt2";
      in {
        devShells.default = pkgs.mkShell {
          buildInputs = [
            # JVM / Android toolchain
            pkgs.jdk17
            pkgs.gradle
            sdk

            # Rust toolchain for the Crux core (build, test, typegen codegen)
            pkgs.rustc
            pkgs.cargo
          ];

          ANDROID_HOME     = sdkRoot;
          ANDROID_SDK_ROOT = sdkRoot;
          JAVA_HOME        = "${pkgs.jdk17}";

          # AGP resolves aapt2 from a Maven artifact by default; point it at the
          # SDK-provided binary so the build works fully offline / hermetically.
          GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${aapt2Path}";

          shellHook = ''
            echo "opencode-android dev shell"
            echo "  JDK:          $(java -version 2>&1 | head -1)"
            echo "  Rust:         $(rustc --version)"
            echo "  Gradle:       $(gradle --version 2>/dev/null | awk '/^Gradle/{print $2}')"
            echo "  ANDROID_HOME: $ANDROID_HOME"
            echo ""
            echo "  Core:  (cd shared && cargo test)      # Crux core unit tests"
            echo "  Types: (cd shared && cargo run --bin codegen)"
            echo "  App:   ./gradlew assembleDebug        # build debug APK"
            echo "  Unit:  ./gradlew testDebugUnitTest"
          '';
        };
      });
}
