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

              with subtest("rollback switches back in place when only the system changed"):
                  # v1 and v2 differ only in SYSTEM_PATH, which is the case
                  # rollback should handle without stopping the container.
                  before = started_at()
                  out = machine.succeed("ctr rollback t 2")
                  assert "Updating containers" in out, out
                  assert "v1" in machine.succeed("nixos-container run t -- cat /etc/testFile")
                  assert started_at() == before, "container should not have restarted"

              with subtest("a rollback is itself a deployment, so rolling back again redoes it"):
                  machine.succeed("ctr rollback t 2")
                  assert "v2" in machine.succeed("nixos-container run t -- cat /etc/testFile")

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

              with subtest("history lists every deployment, newest first"):
                  # v1, v2, back to v1, back to v2, then privateNetwork.
                  rows = [l.split() for l in machine.succeed("ctr history t").splitlines()]
                  assert rows[0][:2] == ["#", "GEN"], rows
                  assert [r[0] for r in rows[1:]] == ["1", "2", "3", "4", "5"], rows
                  assert rows[1][-1] == "(current)", rows
                  # Every deployment pins its own system, so none were collected.
                  # DEPLOYED holds a space, so pick the column out by shape.
                  for r in rows[1:]:
                      system = [f for f in r if f.startswith("/nix/store/")]
                      assert len(system) == 1, r
                      machine.succeed(f"test -e {system[0]}")

              with subtest("history is retained as gc roots"):
                  roots = machine.succeed("nix-store --gc --print-roots")
                  assert "/nix/var/nix/gcroots/ctr-history/t/" in roots, roots

              with subtest("--limit shows only the most recent deployments"):
                  out = machine.succeed("ctr history t -n 2")
                  assert [l.split()[0] for l in out.splitlines()[1:]] == ["1", "2"], out

              with subtest("rollback undoes a container config change by restarting"):
                  before = started_at()
                  out = machine.succeed("ctr rollback t 2")
                  assert "Restarting containers" in out, out
                  assert started_at() != before, "container should have restarted"
                  row = [l for l in machine.succeed("ctr list").splitlines()
                         if l.startswith("t ")][0]
                  assert row.split() == ["t", "up", "host", "no"], row
                  # ...and back, to leave t as the later subtests expect it.
                  machine.succeed("ctr rollback t 2")
                  row = [l for l in machine.succeed("ctr list").splitlines()
                         if l.startswith("t ")][0]
                  assert row.split() == ["t", "up", "10.251.0.2", "no"], row

              with subtest("rollback rejects an index it cannot honour"):
                  out = machine.fail("ctr rollback t 99 2>&1")
                  assert "No deployment 99 back" in out, out
                  assert "ctr history t" in out, out
                  out = machine.fail("ctr rollback t 1 2>&1")
                  assert "nothing to undo" in out, out

              with subtest("each container has its own history"):
                  out = machine.succeed("ctr history web")
                  assert [l.split()[0] for l in out.splitlines()[1:]] == ["1"], out
                  out = machine.fail("ctr history nosuch 2>&1")
                  assert "No container named 'nosuch'" in out, out

              with subtest("--keep bounds how many deployments are retained"):
                  machine.succeed(f"ctr create --start --keep 2 -E '{cfg('v1')}'")
                  out = machine.succeed("ctr history t")
                  assert [l.split()[0] for l in out.splitlines()[1:]] == ["1", "2"], out
                  # The dropped generations released their roots, so their
                  # systems are collectable again.
                  roots = machine.succeed("nix-store --gc --print-roots")
                  assert "ctr-history/t/00001-" not in roots, roots

              with subtest("destroy removes the container and its traces"):
                  machine.succeed("ctr destroy t web")
                  machine.fail("nixos-container status t")
                  machine.fail("test -e /etc/systemd-mutable/system/container@t.service")
                  machine.fail("test -e /nix/var/nix/gcroots/auto/ctr-t")
                  machine.fail("test -e /nix/var/nix/gcroots/ctr-history/t")
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
