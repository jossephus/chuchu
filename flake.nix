{
  description = "Chuchu";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs";
    devshell.url = "github:numtide/devshell";
    flake-utils.url = "github:numtide/flake-utils";
    android.url = "github:tadfisher/android-nixpkgs";
    zig.url = "github:mitchellh/zig-overlay";
  };

  outputs = {
    self,
    nixpkgs,
    devshell,
    flake-utils,
    android,
    zig,
  }:
    flake-utils.lib.eachSystem ["x86_64-linux" "aarch64-darwin"] (
      system: let
        pkgs = import nixpkgs {
          inherit system;
          config.allowUnfree = true;
          overlays = [
            devshell.overlays.default
            (final: prev: {
              zigpkgs = zig.packages.${system};
              android-sdk = android.sdk.${system} (sdkPkgs:
                with sdkPkgs; [
                  build-tools-34-0-0
                  cmdline-tools-latest
                  platform-tools
                  platforms-android-35
                  ndk-29-0-13113456
                ]);
            })
          ];
        };
      in {
        packages = {
          inherit (pkgs) android-sdk;
        };

        devShell = import ./devshell.nix {inherit pkgs;};
      }
    );
}
