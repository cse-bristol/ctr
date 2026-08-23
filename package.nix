{ lib
, stdenvNoCC
, makeWrapper
, babashka
, nixos-container
, e2fsprogs
  # Store path of the nixpkgs used to build containers when neither
  # --nixpkgs-path nor --nixos-path is given.
, nixpkgs
, src ? lib.cleanSource ./.
}:

stdenvNoCC.mkDerivation {
  pname = "ctr";
  version = "0.1.0";
  inherit src;

  nativeBuildInputs = [ makeWrapper babashka ];

  buildPhase = ''
    runHook preBuild
    export XDG_CACHE_HOME=$TMPDIR/cache
    # Bundle every namespace into one dependency-free script.
    bb uberscript ctr.clj -m ctr.main
    runHook postBuild
  '';

  installPhase = ''
    runHook preInstall
    share=$out/share/ctr
    install -Dm444 ctr.clj $share/ctr.clj
    install -Dm444 nix/eval-config.nix $share/eval-config.nix

    # systemctl, machinectl and nix deliberately come from the ambient
    # environment: they have to match the running host, not this closure.
    # The packages below are only fallbacks, hence --suffix.
    makeWrapper ${babashka}/bin/bb $out/bin/ctr \
      --add-flags $share/ctr.clj \
      --set CTR_SELF $out/bin/ctr \
      --set-default CTR_EVAL_CONFIG $share/eval-config.nix \
      --set-default CTR_NIXPKGS ${nixpkgs} \
      --suffix PATH : ${lib.makeBinPath [ nixos-container e2fsprogs ]}
    runHook postInstall
  '';

  meta = {
    description = "Run declarative NixOS containers without full system rebuilds";
    longDescription = ''
      A Babashka reimplementation of extra-container.
    '';
    license = lib.licenses.mit;
    platforms = lib.platforms.linux;
    mainProgram = "ctr";
  };
}
