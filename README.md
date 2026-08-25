# ctr

Manage declarative NixOS containers like imperative ones, without system
rebuilds.

A [Babashka](https://babashka.org) reimplementation of
[extra-container](https://github.com/erikarvstedt/extra-container).

```bash
sudo ctr create --start <<'EOF'
{
  containers.demo = {
    extra.addressPrefix = "10.250.0";

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
    };
  };
}
EOF

curl --http0.9 10.250.0.2:50   # => hello

# Change the config and re-run the same command: the container is updated in
# place via switch-to-configuration, or restarted if it has to be.

nixos-container status demo    # a perfectly ordinary container
sudo ctr destroy demo
```

## Why

Every declarative container adds a full system module evaluation to every NixOS
rebuild. `ctr` builds only `config.system.build.etc` for the containers you name
and links the resulting units into `/etc/systemd-mutable/system`, so containers
can be created, updated and destroyed without rebuilding the host.

## Install

Add the flake's NixOS module to your configuration:

```nix
{
  inputs.ctr.url = "github:you/ctr";

  outputs = { nixpkgs, ctr, ... }: {
    nixosConfigurations.myhost = nixpkgs.lib.nixosSystem {
      modules = [ ctr.nixosModules.default ./configuration.nix ];
    };
  };
}
```

The module installs `ctr` and sets the `boot.extraSystemdUnitPaths` entry that
lets systemd see dynamically installed units. Without it, `ctr` will tell you
what is missing rather than failing obscurely.

## Commands

```
ctr create     [<config>] [--start|-s] [--update-changed|-u] [--restart-changed|-r]
               [--attr|-A <path>] [--expr|-E <expr>] [--flake <ref>]
               [--nixpkgs-path|--nixos-path <expr>] [--full-eval]
               [--[no-]legacy-install-dirs] [--keep <n>] [--build-args <arg>...]
ctr build      [<config>] [<create options>]
ctr list
ctr history    <name> [--limit|-n <count>]
ctr rollback   <name> [<n>] [--start|-s] [--restart-changed|-r] [--no-activate]
ctr new-config <name> [--address-prefix <a.b.c>] [--network <nat|iface>] [--no-network] [--auto-start]
               [--state-version <ver>]
ctr shell      <name> [--start] [--timeout <seconds>]
ctr run        <name> [--start] [--timeout <seconds>] [--] <cmd> [<arg>...]
ctr start      <container>...
ctr stop       <container>...
ctr restart    <container>...
ctr destroy    <container>... | <config> | --all|-a
```

Run `ctr help` for the full option descriptions.

`<config>` is a NixOS config file defining `containers.<name>`, a flake
reference, a store path from `ctr build`, or `-` (or omitted) to read from
stdin.

`create`, `shell`, `run`, `destroy`, `start`, `stop`, `restart` and `rollback`
re-run themselves under `sudo` when needed. `build`, `list`, `history` and
`new-config` do not touch host state and run as you.

## Starting a container

`ctr new-config` writes a template with a free address and a pinned state
version, so you do not have to pick either by hand:

```bash
$ ctr new-config web
# Written by `ctr new-config web` on 2026-08-22.
{
  containers.web = {
    autoStart = false;

    # host 10.233.1.1, container 10.233.1.2
    extra.addressPrefix = "10.233.1";
    extra.enableWAN = true;

    config = { pkgs, ... }: {
      # Pinned at creation time. Do not change it
      # to "upgrade" the container.
      system.stateVersion = "26.05";

      environment.systemPackages = with pkgs; [ ];
    };
  };
}
```

The address is the lowest free `10.233.n` — the range and the `.1`/`.2`
convention `nixos-container create` uses, so the two cannot collide. Prefixes
already taken by another container, or overlapping an address on one of your own
interfaces, are skipped.

`system.stateVersion` is pinned because the default tracks whichever nixpkgs
built the container, which silently changes under you on an upgrade.

### Bridging onto an interface

Instead of a private, NATted subnet you can join the container to one of the
host's own networks by passing the interface name to `--network`:

```bash
$ ctr new-config web --network=br0
# Written by `ctr new-config web` on 2026-08-22.
{
  containers.web = {
    autoStart = false;

    privateNetwork = true;
    hostBridge = "br0";
    # container 192.168.1.50/24 on the br0 network
    localAddress = "192.168.1.50/24";

    config = { pkgs, ... }: {
      # Pinned at creation time. Do not change it
      # to "upgrade" the container.
      system.stateVersion = "26.05";

      environment.systemPackages = with pkgs; [ ];
    };
  };
}
```

The container joins the interface's own subnet, at a free host address ctr can
find there. The search starts just above the host's own address and wraps round
to the bottom of the subnet if it runs out, so the container lands next to the
host rather than at the foot of the network: a host at `10.1.0.1/8` gives the
container `10.1.0.2/8`, not `10.0.0.2/8`. ctr skips the network base, the `.1`
above it (usually the router or gateway), the broadcast address, the host's own
addresses on that interface, any address another container is already bridged
to, and anything in the kernel's neighbour table for the interface. If the
interface has no IPv4 address, or the subnet is exhausted, `--network` refuses
to emit a config.

Bridged containers share a real LAN, so anything else on it — devices ctr does
not manage, DHCP — can still collide. The neighbour table only records machines
the host has talked to, so it rules addresses out and never rules one in. On a
subnet wider than a `/24` ctr says so on stderr, since it can see so little of
what is really out there; check the LAN, and any DHCP pool, before bringing a
bridged container up.

```bash
ctr new-config web > web.nix
sudo ctr create --start web.nix     # or: ctr new-config web | sudo ctr create -
```

## Inspecting and entering containers

```bash
$ ctr list
NAME  STATUS  ADDRESS     AUTOSTART  VERSION
db    up      10.233.2.2  no         26.05@9f78f44
web   down    10.233.1.2  yes        25.11pre-git
```

`ADDRESS` is the address in the container's conf, so it reads `host` for a
container sharing the host's network and `-` for a bridged or DHCP one, where
there is no configured address to show.

`VERSION` is the NixOS label of the deployed system, read out of the system
closure itself. Built from a flake, that label carries the short nixpkgs commit
— shown here as `26.05@9f78f44` — which is usually the thing you want to know.
Built any other way it cannot: a plain nixpkgs checkout labels itself
`25.11pre-git`, a dirty tree `26.05@dirty`, and a `system.nixos.label` you set
yourself says whatever you chose. The label is shown as it is in those cases
rather than guessed at, and `-` means the system had no label to read.

```bash
sudo ctr shell web                  # a root shell inside the container
sudo ctr run web -- systemctl status # run a command, and exit with its status
```

Both need the container to be running; `--start` starts it first and waits.
Everything after the container name — or after `--`, if the command's own flags
would otherwise be ambiguous — is passed through untouched.

```bash
sudo ctr stop web db                # stop containers
sudo ctr start web                  # and bring them back
```

`start` leaves containers that are already up alone, and `stop` does the same
for ones already down; neither is an error. `stop` waits until the machine has
actually gone rather than just until the unit reports inactive, which
`systemctl stop` on its own does not guarantee (nixpkgs#43652) — the same
reason `ctr restart` exists.

## Rolling back a deployment

Everything `ctr` installs for a container is two store paths — the systemd unit
and the container conf — so every deployment is recorded as that pair, under
`/nix/var/nix/gcroots/ctr-history/<name>/`. Being gcroots, they also keep the
old systems from being garbage collected, which is what makes going back
possible at all.

```bash
$ ctr history web
#  GEN  DEPLOYED          VERSION        SYSTEM
1  9    2026-08-24 14:02  26.05@9f78f44  /nix/store/1i0…-nixos-system-web-26.05  (current)
2  8    2026-08-22 09:15  26.05@3ab12cd  /nix/store/rn4…-nixos-system-web-26.05
3  6    2026-08-19 17:40  25.11pre-git   /nix/store/w8k…-nixos-system-web-25.11

$ sudo ctr rollback web        # the deployment before this one, i.e. 2
$ sudo ctr rollback web 3      # the third-last
```

The leading number counts back from the current deployment; `GEN` is the stable
generation number, which is never reused. A rollback is recorded as a new
deployment rather than moving a pointer backwards, so `1` always means what is
deployed now, and `ctr rollback web` undoes a rollback as readily as it undoes a
deploy.

A running container is put back the same way `ctr create` would move it forward:
switched in place with `switch-to-configuration` when only its system changed,
restarted when its container config changed. `--no-activate` relinks without
touching the running container, and `-s` starts a stopped one.

`--keep <n>` bounds how many deployments are retained per container, on both
`create` and `rollback`; the default is 20. Dropping one releases its gcroot, so
its system becomes collectable again. `ctr destroy` forgets a container's
history entirely.

## Private network helper

The `extra.*` options cover the fiddly parts of private-network containers.
See [nix/eval-config.nix](nix/eval-config.nix) for the full descriptions.

```nix
containers.demo.extra = {
  # privateNetwork = true, hostAddress = 10.250.0.1, localAddress = 10.250.0.2
  addressPrefix = "10.250.0";
  enableWAN = true;           # internet access, via NAT on the host
  firewallAllowHost = true;   # always accept connections from the host
  exposeLocalhost = true;     # reach the container's localhost from outside
};
```

## Flakes

See [examples/flake](examples/flake). `ctr.lib.buildContainers` produces a
container derivation with a `nix run` entry point:

```bash
nix run . -- create --start
nix run . -- destroy
```

`ctr` also accepts flake references directly:

```bash
ctr create --start .#default
```

## Install directories

NixOS < 22.05 kept containers in `/etc/containers` and `/var/lib/containers`;
later versions use the `nixos-` prefixed paths. Which one applies is decided by
the *host's* `system.stateVersion`, and the answer is baked into the
`nixos-container` binary, so `ctr` reads it back from there. Nothing to
configure — but `ctr build` can target the other convention with
`--legacy-install-dirs` or `--no-legacy-install-dirs`, and flake users pass
`legacyInstallDirs` to `lib.buildContainers`.

## Differences from extra-container

- **No SSH support.** extra-container has `--ssh` and `extra.enableSSH`, which
  generate a key in `/tmp` and authorise it in the container. `ctr shell` and
  `ctr run` go through `nixos-container` instead, so there is nothing for it to
  be shorthand for. Configure `services.openssh` yourself if you want sshd.
- **`shell` attaches to an existing container** rather than creating an
  ephemeral one. extra-container's `shell` builds, starts, sessions and destroys
  a throwaway container, with an rcfile of `c`/`cssh`/`h` shortcuts and a SIGINT
  trap to interrupt a slow start. Here `shell` and `run` are conveniences over
  `nixos-container root-login` and `nixos-container run`.
- **`list` shows status and address**, not just names.
- **`new-config`** has no equivalent.
- **`history` and `rollback`** have no equivalent. extra-container overwrites the
  installed unit and conf in place and repoints their single gcroot, so the
  system you were running becomes garbage the moment you deploy over it.
- **`--full-eval`.** `ctr` evaluates containers with a reduced module set, like
  extra-container. That set needs a stub for every option nixpkgs' modules grow
  a reference to, and it does break: adapting it to NixOS 26.05 needed five new
  stubs. `--full-eval` evaluates a complete NixOS system instead, which is
  immune to that churn and measured ~2s slower per build.
- **`build`, `list`, `history` and `new-config` don't need root.**
- **`-A a.b` selects a nested attribute**, as `nix-build -A` does.
  extra-container looks for one attribute literally named `a.b`.
- **A container that drops `autoStart` stops starting at boot.**
  extra-container never removes the `machines.target.wants` link.
- **An unrecognisable `nixos-container` is assumed modern.** extra-container
  reads a missing `/etc/nixos-containers` marker as meaning the legacy layout,
  which would misfire on a wrapper script.
- **No `nixos-container` passthrough.** Unknown subcommands are an error rather
  than being forwarded.

## Development

```bash
nix develop      # babashka, plus `ctr` runnable from source
bb test          # unit tests
nix flake check  # unit tests and the NixOS VM lifecycle test
nix run .#vm     # a VM with ctr installed, for poking at by hand
```

The code is six namespaces under `src/ctr`:

| | |
|---|---|
| `main` | CLI parsing and dispatch, sudo re-exec |
| `nix` | resolving a config source to a store path |
| `container` | install dirs, scanning, classifying, installing, destroying |
| `systemd` | systemctl/machinectl, and the start/update/restart decision |
| `newconfig` | address allocation and the config template |
| `util` | process and formatting helpers |

The logic worth testing is pure: `container/classify` decides whether a
container is unchanged, needs an in-place switch, or needs a restart;
`systemd/plan` turns that plus the set of running containers into what to start,
update and restart; and `newconfig/pick-prefix` chooses a free address.

Two tests only run outside the Nix sandbox, because they need `nix` on `PATH`:
the ones that *evaluate* the generated nixos-path expression and the
`new-config` template. Inside `nix flake check` they print a skip line, so `bb
test` in the dev shell is what actually exercises them.

## License

MIT, as is extra-container, from which the `eval-config.nix` module set and the
container lifecycle behaviour are derived.
