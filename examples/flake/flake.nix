# See ./usage.sh for how this flake is used.
{
  inputs.ctr.url = "github:you/ctr";
  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-26.05";

  outputs = { ctr, ... }@inputs:
    ctr.lib.eachSupportedSystem (system: {
      packages.default = ctr.lib.buildContainers {
        # The system of the container host
        inherit system;

        # Optional. If unset, ctr's own nixpkgs input is used.
        nixpkgs = inputs.nixpkgs;

        # Set this if a nixpkgs change breaks the reduced module set.
        # reducedModules = false;

        # Set this to disable `nix run` support.
        # addRunner = false;

        # Set this if the container host is NixOS < 22.05, which keeps its
        # containers in /etc/containers rather than /etc/nixos-containers.
        # legacyInstallDirs = true;

        config = {
          containers.demo = {
            # Sets privateNetwork, hostAddress = 10.250.0.1
            # and localAddress = 10.250.0.2
            extra.addressPrefix = "10.250.0";
            # Give the container internet access
            extra.enableWAN = true;

            # Useful for importing flakes from container modules
            # specialArgs = { inherit inputs; };

            config = { pkgs, ... }: {
              systemd.services.hello = {
                wantedBy = [ "multi-user.target" ];
                script = ''
                  while true; do
                    echo hello | ${pkgs.netcat}/bin/nc -lN 50
                  done
                '';
              };
              networking.firewall.allowedTCPPorts = [ 50 ];
              system.stateVersion = "26.05";
            };
          };
        };
      };
    });
}
