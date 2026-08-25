(ns ctr.newconfig
  "Generating a starting-point container config with a free address and a
   pinned state version."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [ctr.container :as c]
            [ctr.util :as u]))

;; The range `nixos-container create` allocates from, one /24 per container
;; with the host at .1 and the container at .2. Matching it means ctr-managed
;; and nixos-container-managed containers cannot collide, and it is what
;; `extra.addressPrefix` expands to.
(def ^:private base "10.233")

(defn- ip->long [s]
  (reduce (fn [acc octet] (+ (* acc 256) (parse-long octet)))
          0 (str/split s #"\.")))

(defn- long->ip [n]
  (str/join "." [(bit-and (bit-shift-right n 24) 0xff)
                 (bit-and (bit-shift-right n 16) 0xff)
                 (bit-and (bit-shift-right n 8) 0xff)
                 (bit-and n 0xff)]))

(defn cidr-range
  "Inclusive [first last] of a dotted-quad CIDR, as longs."
  [cidr]
  (let [[ip len] (str/split cidr #"/")
        len  (parse-long (or len "32"))
        size (bit-shift-left 1 (- 32 len))
        net  (bit-and (ip->long ip) (- 0x100000000 size))]
    [net (+ net size -1)]))

(defn- overlaps? [[a b] [c d]] (and (<= a d) (<= c b)))

(defn pick-prefix
  "Lowest free `10.233.n` given the addresses already in use and the CIDRs
   already configured on host interfaces.

   The interface check is an addition to what nixos-container does; it stops us
   handing out an address that collides with a real network. If it rules out
   everything -- a host inside 10.0.0.0/8, say -- fall back to the address
   check alone rather than refusing to emit a config."
  [used-addresses host-cidrs]
  (let [used   (set used-addresses)
        ranges (mapv cidr-range host-cidrs)
        free?  (fn [n] (let [p (str base "." n)]
                         (and (not (used (str p ".1")))
                              (not (used (str p ".2"))))))
        clear? (fn [n] (let [r (cidr-range (str base "." n ".0/24"))]
                         (not-any? #(overlaps? r %) ranges)))]
    (if-let [n (first (filter #(and (free? %) (clear? %)) (range 1 255)))]
      (str base "." n)
      (if-let [n (first (filter free? (range 1 255)))]
        (do (u/eprintln (str "warning: every free " base ".x/24 overlaps an address on a"
                             " host interface;\n         using " base "." n
                             " anyway -- check it does not clash."))
            (str base "." n))
        (u/die (str "No free address prefix in " base ".0.0/16;"
                    " pass --address-prefix explicitly."))))))

(defn used-addresses
  "Every host and container address recorded in the installed container confs.
   Includes containers ctr did not create, exactly as nixos-container does."
  []
  (let [dir (c/conf-dir)]
    (when (fs/directory? dir)
      (->> (fs/glob dir "*.conf")
           (mapcat (fn [f] (let [{:keys [host-address local-address]} (c/read-conf (str f))]
                             [host-address local-address])))
           (remove nil?)))))

(defn host-interface-cidrs
  "IPv4 CIDRs configured on this host's interfaces, as [iface cidr] pairs.
   `ip -4 -o addr show` emits one line per address, with the interface name
   before `inet`."
  []
  (->> (str/split-lines (:out (u/run "ip" "-4" "-o" "addr" "show")))
       (keep #(let [[_ iface cidr] (re-find #"^\d+: (\S+)\s+inet ([\d.]+\/[\d.]+)" %)]
                (when (and iface cidr) [iface cidr])))))

(defn host-cidrs
  "IPv4 CIDRs configured on this host's interfaces."
  []
  (mapv second (host-interface-cidrs)))

(defn host-cidr-for
  "The first IPv4 CIDR configured on interface `iface`. Dies if it has none,
   because there is then nothing to pick a bridged address from."
  [iface]
  (or (some (fn [[i cidr]] (when (= i iface) cidr)) (host-interface-cidrs))
      (u/die (str "Interface '" iface "' has no IPv4 address, so there is"
                  " nothing to bridge onto; check the interface name."))))

(defn- host-ips-on
  "This host's bare IPv4 addresses configured on interface `iface`."
  [iface]
  (->> (host-interface-cidrs)
       (keep (fn [[i cidr]] (when (= i iface) (first (str/split cidr #"/")))))))

(defn- used-bridge-ips
  "Bare local addresses of installed containers bridged onto `iface`."
  [iface]
  (let [dir (c/conf-dir)]
    (when (fs/directory? dir)
      (->> (fs/glob dir "*.conf")
           (keep (fn [f] (let [{:keys [host-bridge local-address]} (c/read-conf (str f))]
                           (when (= host-bridge iface) local-address))))))))

(defn- ip4-long
  "Dotted quad, with an optional prefix length, as a long."
  [s]
  (ip->long (str/replace s #"/.*$" "")))

(defn neighbour-ips
  "IPv4 addresses this host has seen on `iface`, from the kernel's ARP cache.
   That is a record of who has been talked to, not a scan, so it under-reports
   quiet machines: it can rule an address out, never rule one in."
  [iface]
  (->> (str/split-lines (:out (u/run "ip" "-4" "neigh" "show" "dev" iface)))
       (keep #(re-find #"^\d+\.\d+\.\d+\.\d+" %))))

(defn free-bridge-address
  "A free address inside CIDR `cidr` for a bridged container, as a bare dotted
   quad.

   The scan starts just above the host's own address in `cidr` rather than at
   the bottom of the network, and wraps round to the network base if it runs
   out. On a /24 that is the same answer as counting up from the base; on a
   wide network -- a host at 10.1.0.1/8, say -- it keeps the container next to
   the host instead of stranding it 65k addresses away in 10.0.0.x.

   The network base, the address above it (conventionally a router), the
   broadcast, `host-ips` and `used-ips` are all skipped. `used-ips` is
   everything else known to be spoken for: containers already bridged onto the
   interface, and whatever the neighbour table has seen."
  [cidr used-ips host-ips]
  (let [[net last] (cidr-range cidr)
        used   (set (concat (map ip4-long used-ips) (map ip4-long host-ips)))
        lo     (+ net 2)
        hi     (dec last)
        anchor (let [a (inc (ip4-long cidr))] (if (<= lo a hi) a lo))]
    (or (some-> (first (remove used (concat (range anchor (inc hi))
                                            (range lo anchor))))
                long->ip)
        (u/die (str "No free address in '" cidr "' to bridge a container onto.")))))

(defn- pick-bridge-address
  "The address -- `ip/len` -- for a new container bridged to `iface`."
  [iface]
  (let [cidr (host-cidr-for iface)
        len  (parse-long (or (second (str/split cidr #"/")) "32"))
        ip   (free-bridge-address cidr
                                  (concat (used-bridge-ips iface)
                                          (neighbour-ips iface))
                                  (host-ips-on iface))]
    ;; A /24 is small enough that the confs plus the neighbour table are most of
    ;; the picture. Anything wider is a real network ctr cannot see the whole of,
    ;; so say so -- on stderr, since the config itself goes to stdout.
    (when (< len 24)
      (u/eprintln (str "warning: " iface " is a /" len ", so ctr cannot tell which of its"
                       " addresses are free.\n         " ip " clashes with no container,"
                       " no address on " iface " and nothing\n         in the neighbour"
                       " table -- check it is outside any DHCP pool.")))
    (str ip "/" len)))

(defn- release-of
  "The nixpkgs release, read straight from its .version file -- which is what
   lib.trivial.release is, so this needs no evaluation."
  [nixpkgs]
  (let [f (fs/path nixpkgs ".version")]
    (when (fs/regular-file? f) (str/trim (slurp (str f))))))

(defn state-version
  "The release `ctr` would build this container with."
  []
  (or (some-> (u/getenv "CTR_NIXPKGS") release-of)
      (let [{:keys [exit out]} (u/run "nix" "eval" "--raw" "--impure"
                                      "--expr" "toString <nixpkgs>")]
        (when (zero? exit) (release-of out)))
      (second (re-find #"^(\d+\.\d+)" (:out (u/run "nixos-version"))))
      (u/die (str "Cannot determine the nixpkgs release to pin as"
                  " system.stateVersion; pass --state-version."))))

(defn- nix-attr
  "A container name as an attribute. Nix identifiers cannot start with a digit
   or a dash, but hostnames can, so those names have to be quoted."
  [nm]
  (if (re-matches #"[A-Za-z_][A-Za-z0-9_'-]*" nm) nm (u/nix-string nm)))

(defn template
  "The generated config. Pure, so it can be evaluated in a test."
  [{:keys [name prefix bridge local state-version auto-start? date]}]
  (str/join
   "\n"
   (concat
    [(str "# Written by `ctr new-config " name "`"
          (when date (str " on " date)) ".")
     "{"
     (str "  containers." (nix-attr name) " = {")
     (str "    autoStart = " (if auto-start? "true" "false") ";")
     ""]
    (cond
      bridge
      [;; Bridged: the container joins the interface's own network.
       "    privateNetwork = true;"
       (str "    hostBridge = \"" bridge "\";")
       (str "    # container " local " on the " bridge " network")
       (str "    localAddress = \"" local "\";")
       ""]

      prefix
      [(str "    # host " prefix ".1, container " prefix ".2")
       (str "    extra.addressPrefix = \"" prefix "\";")
       "    extra.enableWAN = true;"
       ""])
    ["    config = { pkgs, ... }: {"
     "      # Pinned at creation time. Do not change it"
     "      # to \"upgrade\" the container."
     (str "      system.stateVersion = \"" state-version "\";")
     ""
     "      environment.systemPackages = with pkgs; [ ];"
     "    };"
     "  };"
     "}"
     ""])))

(defn- check-name! [nm network?]
  (when-not (re-matches #"[A-Za-z0-9_-]+" (str nm))
    (u/die (str "Invalid container name '" nm
                "': only letters, digits, '_' and '-' are allowed.")))
  ;; privateNetwork creates a `ve-<name>` interface, and Linux caps interface
  ;; names at 15 bytes.
  (when (and network? (> (count nm) 11))
    (u/eprintln (str "warning: '" nm "' is longer than 11 characters, so the"
                     " ve-" nm " interface\n         name will be too long for"
                     " privateNetwork."))))

(defn generate
  "Render a template for `nm`, unless told not to.

   The network mode comes from `:network`: `nat` (or missing) gives the usual
   private 10.233.x subnet with the host at .1 and container at .2, NATted out
   of the host's interface; any other value is a bridge interface, and a free
   address is allocated on that interface's own network."
  [nm {:keys [address-prefix no-network auto-start date network] version :state-version}]
  (let [bridge?  (and (some? network) (not= network "nat"))
        private? (and (not no-network) (not bridge?))]
    (when (and bridge? no-network)
      (u/die (str "--network " network " and --no-network cannot be combined.")))
    (when (and bridge? address-prefix)
      (u/die (str "Cannot combine --network " network " with --address-prefix:"
                  " bridged containers take their address from the bridge"
                  " interface, not from a prefix.")))
    (check-name! nm (not no-network))
    (template
     (merge {:name          nm
             :state-version (or version (state-version))
             :auto-start?   auto-start
             :date          date}
            (if bridge?
              {:bridge network :local (pick-bridge-address network)}
              {:prefix (when private?
                         (or address-prefix
                             (pick-prefix (used-addresses) (host-cidrs))))})))))
