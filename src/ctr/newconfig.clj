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

(defn host-cidrs
  "IPv4 CIDRs configured on this host's interfaces."
  []
  (->> (str/split-lines (:out (u/run "ip" "-4" "-o" "addr" "show")))
       (keep #(second (re-find #"\binet (\d+\.\d+\.\d+\.\d+/\d+)" %)))))

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
  [{:keys [name prefix state-version auto-start? date]}]
  (str/join
   "\n"
   (concat
    [(str "# Written by `ctr new-config " name "`"
          (when date (str " on " date)) ".")
     "{"
     (str "  containers." (nix-attr name) " = {")
     (str "    autoStart = " (if auto-start? "true" "false") ";")
     ""]
    (when prefix
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
  "Render a template for `nm`, allocating an address unless told not to."
  [nm {:keys [address-prefix no-network auto-start date] version :state-version}]
  (let [network? (not no-network)]
    (check-name! nm network?)
    (template {:name nm
               :prefix (when network?
                         (or address-prefix (pick-prefix (used-addresses) (host-cidrs))))
               :state-version (or version (state-version))
               :auto-start? auto-start
               :date date})))
