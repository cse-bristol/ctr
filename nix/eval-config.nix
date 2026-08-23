# Evaluate a NixOS config that defines `containers.<name>` and expose
# `config.system.build.etc`, which holds the container unit + conf files.
#
# Derived from extra-container's eval-config.nix (MIT, Erik Arvstedt).
# Difference: no SSH support (extra-container's `extra.enableSSH`).
{ nixosPath
, systemConfig
, system ? builtins.currentSystem
  # When false, evaluate a full NixOS system instead of the reduced module set.
  # Slower, but immune to nixpkgs adding module interdependencies.
, reducedModules ? true
  # Pre-22.05 hosts keep containers in /etc/containers and /var/lib/containers.
  # The nixos-containers module picks between the two from the *host's*
  # system.stateVersion, so setting that below is what moves the generated
  # files. It has nothing to do with each container's own stateVersion.
, legacyInstallDirs ? false
}:

let
  # A minimal module set for evaluating container configs. This is what makes
  # evaluation fast: we only need `system.build.etc`, not a bootable system.
  baseModules = [
    (nixosPath + "/modules/misc/assertions.nix")
    (nixosPath + "/modules/misc/nixpkgs.nix")
    (nixosPath + "/modules/misc/extra-arguments.nix")
    (nixosPath + "/modules/system/activation/top-level.nix")
    (nixosPath + "/modules/system/etc/etc.nix")
    (nixosPath + "/modules/system/boot/systemd.nix")
    (nixosPath + "/modules/virtualisation/nixos-containers.nix")
    dummyOptions
  ];

  # Stubs for options that the modules above reference but that we don't need.
  # This is the part that breaks when nixpkgs grows a new interdependency; if it
  # does, `reducedModules = false` is the escape hatch.
  dummyOptions = { pkgs, lib, options, ... }: let
    optionValue = default: lib.mkOption { inherit default; };
    dummy = optionValue [];
  in {
    options = {
      boot.kernel.sysctl = dummy;
      boot.kernelModules = dummy;
      boot.kernelPackages.kernel.version = optionValue "";
      boot.kernelParams = dummy;
      boot.loader.systemd-boot.bootCounting.enable = optionValue false;
      environment.systemPackages = dummy;
      environment.variables = optionValue {};
      i18n.imperativeLocale = optionValue false;
      time.timeZone = optionValue null;
      networking.dhcpcd.denyInterfaces = dummy;
      networking.hosts = dummy;
      networking.extraHosts = dummy;
      networking.proxy.envVars = optionValue {};
      nix.package = optionValue pkgs.nix;
      security = dummy;
      services = {
        dbus = dummy;
        logrotate = dummy;
        udev = dummy;
        rsyslogd.enable = optionValue false;
        syslog-ng.enable = optionValue false;
        openssh.enable = optionValue false;
      };
      system.activationScripts = dummy;
      system.fsPackages = dummy;
      system.nssDatabases = dummy;
      system.nssModules = dummy;
      system.path = optionValue "";
      system.nixos-init.package = optionValue pkgs.nixos-init;
      system.requiredKernelConfig = dummy;
      # Overridden by installDirsModule below; this is only the stub's type.
      system.stateVersion = optionValue "22.05";
      systemd.oomd = dummy;
      systemd.user.generators = optionValue {};
      ids.gids.keys = dummy;
      ids.uids.systemd-coredump = dummy;
      ids.gids.systemd-journal = dummy;
      ids.gids.systemd-journal-gateway = dummy;
      ids.uids.systemd-journal-gateway = dummy;
      ids.gids.systemd-network = dummy;
      ids.uids.systemd-network = dummy;
      ids.uids.systemd-resolve = dummy;
      ids.gids.systemd-resolve = dummy;
      users.users.systemd-coredump = dummy;
      users.users.systemd-network.group = dummy;
      users.users.systemd-network.uid = dummy;
      users.users.systemd-resolve.group = dummy;
      users.users.systemd-resolve.uid = dummy;
      users.users.systemd-journal-gateway.group = dummy;
      users.users.systemd-journal-gateway.uid = dummy;
      users.groups.systemd-coredump = dummy;
      users.groups.systemd-network.gid = dummy;
      users.groups.systemd-resolve.gid = dummy;
      users.groups.keys.gid = dummy;
      users.groups.systemd-journal.gid = dummy;
      users.groups.systemd-journal-gateway.gid = dummy;
    };

    config = {
      systemd.timers = lib.mkForce {};
      systemd.targets = lib.mkForce {};
    } // lib.optionalAttrs (options.systemd ? managerEnvironment) {
      systemd.managerEnvironment = lib.mkForce {};
    };
  };

  containerAssert = cond: name: msg: value:
    if cond then value
    else throw "container '${name}': ${msg}";

  assertNonNull = var: containerAssert (var != null);

  # `extra.*`: shorthands for the fiddly parts of private-network containers.
  extraModule = { config, pkgs, lib, ... }: with lib; {
    options = {
      containers = mkOption {
        type = types.attrsOf (types.submodule (
          { config, name, ... }: {
            options.extra = {
              addressPrefix = mkOption {
                type = with types; nullOr str;
                default = null;
                description = ''
                  Enable privateNetwork and set
                  hostAddress = <addressPrefix>.1
                  localAddress = <addressPrefix>.2
                '';
              };
              enableWAN = mkOption {
                type = types.bool;
                default = false;
                description = ''
                  Enable WAN access inside the container by rewriting container
                  traffic to use the host's address (NAT).

                  Only active when privateNetwork == true.
                '';
              };
              exposeLocalhost = mkOption {
                type = types.bool;
                default = false;
                description = ''
                  Forward requests from the container's external interface to the
                  container's localhost. Useful to test internal services from
                  outside the container.

                  WARNING: This exposes the container's localhost to all users.
                  Only use in a trusted environment.

                  Only active when privateNetwork == true.
                '';
              };
              firewallAllowHost = mkOption {
                type = types.bool;
                default = false;
                description = ''
                  Always allow connections from the container host.

                  Only active when privateNetwork == true.
                '';
              };
            };

            config = mkMerge [
              (let prefix = config.extra.addressPrefix;
               in mkIf (prefix != null) {
                 privateNetwork = true;
                 hostAddress = "${prefix}.1";
                 localAddress = "${prefix}.2";
               })
              {
                config = ({ pkgs, ... }@moduleArgs: mkMerge [
                  {
                    systemd.services.forward-to-localhost =
                      mkIf (config.extra.exposeLocalhost && config.privateNetwork) {
                        wantedBy = [ "network.target" ];
                        script = assertNonNull config.localAddress name
                          "option extra.exposeLocalhost requires localAddress to be non-null."
                          ''
                            ${pkgs.procps}/bin/sysctl -w net.ipv4.conf.all.route_localnet=1
                            ${pkgs.iptables}/bin/iptables -w -t nat -I PREROUTING -p tcp \
                              -d ${config.localAddress} ! --dport 80 -j DNAT --to-destination 127.0.0.1
                          '';
                      };
                    networking.firewall.extraCommands =
                      mkIf (config.extra.firewallAllowHost && config.privateNetwork) (
                        assertNonNull config.hostAddress name
                          "option extra.firewallAllowHost requires hostAddress to be non-null."
                          "iptables -w -A nixos-fw -s ${config.hostAddress} -j ACCEPT\n"
                      );
                    # Silence the system state version warning
                    system.stateVersion = lib.mkDefault moduleArgs.config.system.nixos.release;
                  }
                ]);
              }
            ];
          }
        ));
      };
    };

    # extra.enableWAN: NAT container traffic out of the host's interface.
    config.systemd.services = let
      wanContainers = builtins.filter
        (c: let cfg = config.containers.${c}; in cfg.privateNetwork && cfg.extra.enableWAN)
        (builtins.attrNames config.containers);
      iptables = "${pkgs.iptables}/bin/iptables";
      serviceCfg = c: let addr = config.containers.${c}.localAddress; in
        assertNonNull addr c "option extra.enableWAN requires localAddress to be non-null" {
          preStart = "${iptables} -w -t nat -A POSTROUTING -s ${addr} -j MASQUERADE";
          postStop = "${iptables} -w -t nat -D POSTROUTING -s ${addr} -j MASQUERADE || true";
        };
    in listToAttrs (map (c: nameValuePair "container@${c}" (serviceCfg c)) wanContainers);
  };
  # Picks /etc/containers over /etc/nixos-containers. The nixos-containers
  # module derives its `configurationPrefix` from this, so it has to be set on
  # the pseudo-host we evaluate here -- and it has to work under --full-eval
  # too, which is why it is a module rather than a dummyOptions default.
  installDirsModule = { lib, ... }: {
    system.stateVersion = lib.mkDefault (if legacyInstallDirs then "21.11" else "22.05");
  };
in
import (nixosPath + "/lib/eval-config.nix") ({
  inherit system;
  modules = [ extraModule installDirsModule systemConfig ];
} // (if reducedModules then { inherit baseModules; } else {}))
