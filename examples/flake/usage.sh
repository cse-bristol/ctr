# Usage via `nix run`

#―――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――
# Container lifecycle

# Create and start the container defined by ./flake.nix
nix run . -- create --start
# The same command updates a running container after you change its config.
#
# Arguments after `--` go to the `ctr` binary in PATH, while the flake supplies
# the container definitions.

curl --http0.9 10.250.0.2:50   # => hello

# Enter the running container, or run one command in it
sudo ctr shell demo
sudo ctr run demo -- hostname
sudo ctr run demo -- bash -c 'curl --http0.9 10.250.0.2:50'

# See what is installed and whether it is up
ctr list

# Destroy the container
nix run . -- destroy

#―――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――
# Usage without the runner
# 1. Build the container
nix build . --out-link /tmp/container
# 2. Install and start it
sudo ctr create --start /tmp/container

# ctr also takes flake references directly, without a runner script:
ctr create --start .#default

#―――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――――
# Inspect container configs
nix eval . --apply 'sys: sys.containers.demo.config.networking.hostName'
