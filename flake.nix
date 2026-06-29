{
  description = "Dev environment for opencode-android (Kotlin / Jetpack Compose client for the opencode serve API)";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        # Versions kept in sync with the Gradle project (compileSdk / build-tools).
        platformVersion   = "35";
        buildToolsVersion = "35.0.0";

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

          # Flip these on if the host has /dev/kvm and you want an AVD.
          includeEmulator     = false;
          includeSystemImages = false;
          systemImageTypes    = [ "google_apis" ];
          abiVersions         = [ "x86_64" ];

          includeNDK = false;
        };

        sdk = android.androidsdk;
        sdkRoot = "${sdk}/libexec/android-sdk";

        # Gradle's bundled aapt2 binary is dynamically linked against a loader
        # path that does not exist on NixOS, so it segfaults. Point Gradle and
        # Android Studio at the Nix-built aapt2 instead.
        aapt2Path = "${sdkRoot}/build-tools/${buildToolsVersion}/aapt2";
      in {
        devShells.default = pkgs.mkShell {
          buildInputs = [
            pkgs.jdk17
            pkgs.gradle
            sdk
          ];

          ANDROID_HOME     = sdkRoot;
          ANDROID_SDK_ROOT = sdkRoot;
          JAVA_HOME        = "${pkgs.jdk17}";

          # Override Gradle's bundled aapt2 (see note above).
          GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${aapt2Path}";

          shellHook = ''
            echo "opencode-android dev shell"
            echo "  JDK:         $(java -version 2>&1 | head -1)"
            echo "  ANDROID_HOME: $ANDROID_HOME"
            echo "  aapt2:        ${aapt2Path}"
            echo ""
            echo "Build:  ./gradlew assembleDebug"
            echo "Device: adb devices"
          '';
        };
      });
}
