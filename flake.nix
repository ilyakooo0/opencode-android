{
  description = "Dev environment for opencode-android (Crux Rust core + Kotlin / Jetpack Compose shell)";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        # Versions kept in sync with the Gradle project (compileSdk / build-tools).
        platformVersion   = "36";
        buildToolsVersion = "36.0.0";

        pkgs = import nixpkgs {
          inherit system;
          config = {
            allowUnfree = true;            # Android SDK is unfree
            android_sdk.accept_license = true;
          };
        };

        android = pkgs.androidenv.composeAndroidPackages {
          platformVersions    = [ platformVersion ];
          buildToolsVersions  = [ buildToolsVersion ];
          cmdLineToolsVersion = "latest";

          includeEmulator     = false;
          includeSystemImages = false;
          systemImageTypes    = [ "google_apis" ];
          abiVersions         = [ "x86_64" ];

          includeNDK = false;
        };

        sdk = android.androidsdk;
        sdkRoot = "${sdk}/libexec/android-sdk";

        aapt2Path = "${sdkRoot}/build-tools/${buildToolsVersion}/aapt2";
      in {
        devShells.default = pkgs.mkShell {
          buildInputs = [
            pkgs.jdk17
            pkgs.gradle
            pkgs.rustc
            pkgs.cargo
            sdk
          ];

          ANDROID_HOME     = sdkRoot;
          ANDROID_SDK_ROOT = sdkRoot;
          JAVA_HOME        = "${pkgs.jdk17}";

          GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${aapt2Path}";

          shellHook = ''
            echo "opencode-android dev shell"
            echo "  JDK:         $(java -version 2>&1 | head -1)"
            echo "  ANDROID_HOME: $ANDROID_HOME"
            echo "  aapt2:        ${aapt2Path}"
            echo "  Rust:         $(rustc --version)"
            echo ""
            echo "Build:  ./gradlew assembleDebug"
            echo "Core:   cd shared && cargo test"
            echo "Device: adb devices"
          '';
        };
      });
}
