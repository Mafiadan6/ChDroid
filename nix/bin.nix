{
  version,
  lib,
  mkChDroidPackage,
  binSource,
  ...
}: {
  _module.args = {
    mkChDroidPackage = pkgs: hostPkgs:
      hostPkgs.stdenv.mkDerivation {
        pname = "chdroid";
        inherit version;
        src = binSource;

        nativeBuildInputs = [pkgs.gnumake];

        enableParallelBuilding = true;

        # A musl CC is used as is by the native target, host and cross alike
        makeFlags = ["native"];

        installPhase = ''
          install -Dm755 output/chdroid $out/bin/chdroid
        '';

        meta.mainProgram = "chdroid";
      };
  };

  perSystem = {pkgs, ...}: {
    packages.default = mkChDroidPackage pkgs pkgs.pkgsMusl;

    legacyPackages.muslBuilds = {
      aarch64 = mkChDroidPackage pkgs pkgs.pkgsCross.aarch64-multiplatform-musl;
      x86_64 = mkChDroidPackage pkgs pkgs.pkgsCross.musl64;
      armhf = mkChDroidPackage pkgs pkgs.pkgsCross.muslpi;
      x86 = mkChDroidPackage pkgs pkgs.pkgsCross.musl32;
      riscv64 = mkChDroidPackage pkgs pkgs.pkgsCross.riscv64-musl;

      # Experimental
      ppc64 = lib.warn "ppc64 support is experimental" ((mkChDroidPackage pkgs pkgs.pkgsCross.ppc64-musl).overrideAttrs {
        NIX_CFLAGS_COMPILE = "-Wno-overflow";
      });
      ppc64le = lib.warn "ppc64le support is experimental" ((mkChDroidPackage pkgs pkgs.pkgsCross.musl-power).overrideAttrs {
        NIX_CFLAGS_COMPILE = "-Wno-overflow";
      });
    };

    devShells.default = pkgs.mkShell {
      nativeBuildInputs = [pkgs.gnumake pkgs.pkgsMusl.stdenv.cc];
    };
  };
}
