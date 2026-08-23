{
  description = "Run declarative NixOS containers without full system rebuilds";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-26.05";

  outputs = { self, nixpkgs }@inputs:
    let
      inherit (nixpkgs) lib;
      supportedSystems = [ "x86_64-linux" "aarch64-linux" ];

      # Same shape as flake-utils.lib.eachSystem, without the extra input:
      # (system -> { packages.default = x; }) -> { packages.<system>.default = x; }
      eachSupportedSystem = f:
        builtins.foldl'
          (acc: system: lib.recursiveUpdate acc
            (lib.mapAttrs' (k: v: lib.nameValuePair k { ${system} = v; }) (f system)))
          { }
          supportedSystems;

      # Only the files the build actually needs, so the vendored
      # extra-container/ copy and the docs do not invalidate it.
      source = lib.fileset.toSource {
        root = ./.;
        fileset = lib.fileset.unions [ ./src ./test ./nix ./bb.edn ];
      };

      pkgFor = pkgs: pkgs.callPackage ./package.nix {
        nixpkgs = inputs.nixpkgs;
        src = source;
      };
    in
    {
      overlays.default = final: prev: { ctr = pkgFor final; };

      nixosModules.default = { pkgs, ... }: {
        environment.systemPackages = [ (pkgFor pkgs) ];
        # Without this, systemd never sees the units ctr links into place.
        boot.extraSystemdUnitPaths = [ "/etc/systemd-mutable/system" ];
      };

      lib = {
        inherit supportedSystems eachSupportedSystem;

        evalContainers =
          { system
          , config
          , nixpkgs ? inputs.nixpkgs
          , reducedModules ? true
            # Pre-22.05 hosts keep containers in /etc/containers. Flake
            # consumers have to say so; there is no host to detect here.
          , legacyInstallDirs ? false
          }:
          import ./nix/eval-config.nix {
            inherit system reducedModules legacyInstallDirs;
            nixosPath = nixpkgs + "/nixos";
            systemConfig = config;
          };

        # Build containers from a flake, optionally with a `nix run` entry point.
        buildContainers =
          { system
          , config
          , nixpkgs ? inputs.nixpkgs
          , reducedModules ? true
          , legacyInstallDirs ? false
          , addRunner ? true
          }:
          let
            containers = self.lib.evalContainers {
              inherit system config nixpkgs reducedModules legacyInstallDirs;
            };
            etc = containers.config.system.build.etc;
            withRunner = etc.overrideAttrs (old: {
              name = "container";
              buildCommand = old.buildCommand + "\n" + ''
                install -D -m700 <(printf '${''
                  #!/usr/bin/env bash
                  if ! type -p ctr >/dev/null; then
                    >&2 echo "Error: ctr is not installed"
                    exit 1
                  fi
                  CTR_ETC=%s exec ctr "$@"
                ''}' "$out") $out/bin/container
              '';
            });
          in
          (if addRunner then withRunner else etc) // {
            inherit (containers) config;
            inherit (containers.config) containers;
          };
      };
    }
    // eachSupportedSystem (system:
      let
        pkgs = import nixpkgs { inherit system; };
        ctr = pkgFor pkgs;
      in
      rec {
        packages = {
          default = ctr;
          inherit ctr;

          # Unit tests, as a derivation so `nix flake check` runs them.
          unitTests = pkgs.runCommand "ctr-unit-tests"
            { nativeBuildInputs = [ pkgs.babashka pkgs.bash ]; }
            ''
              cp -r ${source}/* .
              export XDG_CACHE_HOME=$TMPDIR/cache HOME=$TMPDIR
              bb test
              touch $out
            '';

          # End-to-end lifecycle test. This is the only hermetic way to exercise
          # root, systemd and real containers.
          vmTest = pkgs.testers.nixosTest {
            name = "ctr";

            nodes.machine = { config, pkgs, ... }: {
              imports = [ self.nixosModules.default ];
              # NixOS does not install nixos-container by default; the test
              # script needs it to inspect containers. ctr itself does not,
              # because its wrapper puts nixos-container on PATH.
              environment.systemPackages = [ pkgs.nixos-container ];
              virtualisation.memorySize = 2048;
              virtualisation.diskSize = 4096;
              nix.nixPath = [ "nixpkgs=${nixpkgs}" ];
              nix.settings.experimental-features = [ "nix-command" "flakes" ];
              system.stateVersion = config.system.nixos.release;
              # Pre-build everything the test builds at runtime, so the VM needs
              # no network and no evaluation of a full nixpkgs at test time.
              system.extraDependencies =
                let
                  container = text: (self.lib.evalContainers {
                    inherit system;
                    config.containers.t.config.environment.etc.testFile.text = text;
                  }).config.system.build.etc;
                  restartable = (self.lib.evalContainers {
                    inherit system;
                    config.containers.t = {
                      privateNetwork = true;
                      hostAddress = "10.251.0.1";
                      localAddress = "10.251.0.2";
                      config.environment.etc.testFile.text = "v2";
                    };
                  }).config.system.build.etc;
                  # Must stay derivation-identical to what `ctr new-config web
                  # --address-prefix 10.233.1` emits, or the VM has to build it
                  # at test time.
                  generated = (self.lib.evalContainers {
                    inherit system;
                    config.containers.web = {
                      autoStart = false;
                      extra.addressPrefix = "10.233.1";
                      extra.enableWAN = true;
                      config = { pkgs, ... }: {
                        system.stateVersion = nixpkgs.lib.trivial.release;
                        environment.systemPackages = with pkgs; [ ];
                      };
                    };
                  }).config.system.build.etc;
                in
                [ (container "v1") (container "v2") restartable generated ];
            };

            testScript = ''
              def cfg(text):
                  return '{ containers.t.config.environment.etc.testFile.text = "%s"; }' % text

              def started_at():
                  # /etc/machine-id persists in the container state directory, so
                  # it cannot detect a restart. The host unit's start timestamp can.
                  return machine.succeed(
                      "systemctl show -p ActiveEnterTimestampMonotonic --value"
                      " container@t.service"
                  ).strip()

              machine.wait_for_unit("multi-user.target")

              with subtest("create --start brings a container up"):
                  machine.succeed(f"ctr create --start -E '{cfg('v1')}'")
                  assert "v1" in machine.succeed("nixos-container run t -- cat /etc/testFile")

              with subtest("list reports the container"):
                  out = machine.succeed("ctr list")
                  assert "NAME" in out and "STATUS" in out, out
                  row = [l for l in out.splitlines() if l.startswith("t ")][0]
                  # No privateNetwork yet, so it shares the host's network.
                  assert row.split() == ["t", "up", "host", "no"], row

              with subtest("a rebuild switches the system in place"):
                  before = started_at()
                  out = machine.succeed(f"ctr create --start -E '{cfg('v2')}'")
                  assert "Updating containers" in out, out
                  assert "v2" in machine.succeed("nixos-container run t -- cat /etc/testFile")
                  assert started_at() == before, "container should not have restarted"

              with subtest("a container config change restarts the container"):
                  before = started_at()
                  out = machine.succeed(
                      "ctr create --start -E '{ containers.t = { privateNetwork = true;"
                      ' hostAddress = "10.251.0.1"; localAddress = "10.251.0.2";'
                      " config.environment.etc.testFile.text = \"v2\"; }; }'"
                  )
                  assert "Restarting containers" in out, out
                  assert started_at() != before, "container should have restarted"

              with subtest("unchanged containers are skipped"):
                  out = machine.succeed(
                      "ctr create --start -E '{ containers.t = { privateNetwork = true;"
                      ' hostAddress = "10.251.0.1"; localAddress = "10.251.0.2";'
                      " config.environment.etc.testFile.text = \"v2\"; }; }'"
                  )
                  assert "unchanged, skipped" in out, out

              with subtest("list shows the configured address"):
                  row = [l for l in machine.succeed("ctr list").splitlines()
                         if l.startswith("t ")][0]
                  assert row.split() == ["t", "up", "10.251.0.2", "no"], row

              with subtest("run executes a command in a running container"):
                  assert "t" in machine.succeed("ctr run t -- hostname")

              with subtest("run propagates the command's exit status"):
                  machine.fail("ctr run t -- false")
                  machine.succeed("ctr run t -- true")

              with subtest("run passes flags through to the command"):
                  # An option parser in ctr would have swallowed --version.
                  assert "GNU" in machine.succeed("ctr run t -- bash --version")

              with subtest("shell rejects a trailing command"):
                  machine.fail("ctr shell t -- hostname")

              with subtest("a name that is not a container is distinguished"):
                  out = machine.fail("ctr run nosuch -- true 2>&1")
                  assert "No container named 'nosuch'" in out, out

              with subtest("new-config emits a config that builds and runs"):
                  out = machine.succeed("ctr new-config web --address-prefix 10.233.1")
                  assert 'system.stateVersion = "${nixpkgs.lib.trivial.release}";' in out, out
                  assert 'extra.addressPrefix = "10.233.1";' in out, out
                  machine.succeed(
                      "ctr new-config web --address-prefix 10.233.1 > /tmp/web.nix"
                  )
                  machine.succeed("ctr create --start /tmp/web.nix")
                  assert "web" in machine.succeed("ctr run web -- hostname")
                  row = [l for l in machine.succeed("ctr list").splitlines()
                         if l.startswith("web ")][0]
                  assert row.split() == ["web", "up", "10.233.1.2", "no"], row

              with subtest("new-config avoids addresses already in use"):
                  # web holds 10.233.1.{1,2}, so the next one has to move on.
                  out = machine.succeed("ctr new-config other")
                  assert 'extra.addressPrefix = "10.233.2";' in out, out

              with subtest("run refuses a stopped container unless told to start it"):
                  machine.succeed("systemctl stop container@web.service")
                  machine.fail("ctr run web -- true")
                  machine.succeed("ctr run --start web -- true")
                  assert "up" in machine.succeed("ctr list")

              with subtest("destroy removes the container and its traces"):
                  machine.succeed("ctr destroy t web")
                  machine.fail("nixos-container status t")
                  machine.fail("test -e /etc/systemd-mutable/system/container@t.service")
                  machine.fail("test -e /nix/var/nix/gcroots/auto/ctr-t")
                  assert machine.succeed("ctr list").strip() == ""
            '';
          };
        };

        devShells.default = pkgs.mkShell {
          packages = with pkgs; [ babashka nixos-container e2fsprogs ];
          shellHook = ''
            export CTR_EVAL_CONFIG="$PWD/nix/eval-config.nix"
            export CTR_NIXPKGS="${nixpkgs}"
            export CTR_SELF="$PWD/ctr"
            export PATH="$PWD:$PATH"
          '';
        };

        checks = { inherit (packages) unitTests vmTest; };

        apps.vm = {
          type = "app";
          program = toString (pkgs.writers.writeBash "run-vm" ''
            set -euo pipefail
            export NIX_DISK_IMAGE=/tmp/ctr-vm-img
            rm -f $NIX_DISK_IMAGE
            trap "rm -f $NIX_DISK_IMAGE" EXIT
            export QEMU_OPTS="-smp $(nproc) -m 2048"
            ${packages.vm}/bin/run-*-vm
          '');
        };

        packages.vm = (import "${nixpkgs}/nixos" {
          inherit system;
          configuration = { config, modulesPath, ... }: {
            imports = [
              self.nixosModules.default
              "${modulesPath}/virtualisation/qemu-vm.nix"
            ];
            virtualisation.graphics = false;
            virtualisation.memorySize = 2048;
            virtualisation.diskSize = 8192;
            services.getty.autologinUser = "root";
            nix.nixPath = [ "nixpkgs=${nixpkgs}" ];
            nix.settings.experimental-features = [ "nix-command" "flakes" ];
            system.stateVersion = config.system.nixos.release;
            documentation.enable = false;
            systemd.services."serial-getty@".preStop = ''
              echo o >/proc/sysrq-trigger
            '';
          };
        }).config.system.build.vm;
      });
}
