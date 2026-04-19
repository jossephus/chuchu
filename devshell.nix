{pkgs}:
with pkgs;
# Configure your development environment.
#
# Documentation: https://github.com/numtide/devshell
  devshell.mkShell {
    name = "android-project";
    motd = ''
      Entered the Android app development environment.
    '';
    env = [
      {
        name = "ANDROID_HOME";
        value = "${android-sdk}/share/android-sdk";
      }
      {
        name = "ANDROID_NDK_HOME";
        value = "${android-sdk}/share/android-sdk/ndk/29.0.13113456";
      }
      {
        name = "ANDROID_SDK_ROOT";
        value = "${android-sdk}/share/android-sdk";
      }
      {
        name = "ANDROID_NDK_ROOT";
        value = "${android-sdk}/share/android-sdk/ndk";
      }
      {
        name = "JAVA_HOME";
        value = jdk17.home;
      }
    ];
    packages = [
      android-sdk
      gradle
      jdk21
      zigpkgs."0.15.2"
      nasm
    ];
  }
