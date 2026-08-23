(ns ctr.container
  "Deriving container state from a built NixOS `etc` output, and installing or
   destroying it on the host."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [ctr.systemd :as sd]
            [ctr.util :as u]))

;; The unit directory is the same on every NixOS version; only the config and
;; state directories moved in 22.05. See `dirs` below.
(def service-dir "/etc/systemd-mutable/system")
(def gcroots-dir "/nix/var/nix/gcroots/auto")

(def ^:private modern
  {:legacy? false
   :conf-name "nixos-containers" :other-conf-name "containers"
   :conf-dir "/etc/nixos-containers" :state-dir "/var/lib/nixos-containers"})

(def ^:private legacy
  {:legacy? true
   :conf-name "containers" :other-conf-name "nixos-containers"
   :conf-dir "/etc/containers" :state-dir "/var/lib/containers"})

(defn detect-dirs
  "Decide the convention from the `nixos-container` at `nc`. Public so the
   decision can be tested against fixture scripts."
  [nc]
  (let [src (when nc (try (slurp (str nc)) (catch Exception _ nil)))]
    (cond
      (nil? src)
      (do (u/eprintln "warning: nixos-container is not readable on PATH;"
                      "assuming NixOS >= 22.05 install directories")
          modern)

      (str/includes? src "\"/etc/nixos-containers\"") modern
      (str/includes? src "\"/etc/containers\"")       legacy

      :else
      (do (u/eprintln (str "warning: cannot tell which install directories " nc
                           " uses; assuming NixOS >= 22.05"))
          modern))))

(def ^:private dirs
  "Which install-directory convention this host uses.

   The NixOS module derives the prefix from the host's `system.stateVersion`:

     configurationPrefix = optionalString (versionAtLeast … \"22.05\") \"nixos-\";

   and substitutes the result into the `nixos-container` binary at build time,
   so that binary is the only runtime record of the choice. extra-container
   probes it the same way, but treats a *missing* marker as legacy; if
   nixos-container is ever a makeWrapper shim that misfires silently, so we
   require the positive legacy marker instead and warn when neither is found.

   Probing the filesystem would be wrong: podman owns /etc/containers too."
  (delay (detect-dirs (fs/which "nixos-container"))))

(defn legacy-install-dirs? [] (:legacy? @dirs))
(defn conf-name  [] (:conf-name @dirs))
(defn conf-dir   [] (:conf-dir @dirs))
(defn state-dir  [] (:state-dir @dirs))

(defn- wants-dir [] (str (fs/path service-dir "machines.target.wants")))
(defn gcroot [nm] (str (fs/path gcroots-dir (str "ctr-" nm))))

(defn- name-from-service [path]
  (second (re-matches #"container@(.+)\.service" (fs/file-name path))))

(defn read-conf
  "Parse the interesting fields out of a container conf. Missing file yields
   all-nil, which is what an uninstalled container looks like."
  [conf]
  (let [vals (if-not (fs/exists? conf)
               {}
               (into {} (keep #(let [[_ k v] (re-matches #"([A-Z0-9_]+)=(.*)" (str/trim %))]
                                 (when k [k v])))
                     (str/split-lines (slurp (str conf)))))
        ;; The conf may carry a prefix length; nixos-container's show-ip drops
        ;; it, and an address with one would not compare equal in new-config.
        addr (fn [k] (some-> (some #(not-empty (vals %)) [k (str k "6")])
                             (str/replace #"/\d+$" "")))]
    {:auto-start?      (= "1" (vals "AUTO_START"))
     ;; The declarative module writes PRIVATE_NETWORK=1 or nothing at all;
     ;; `nixos-container create` writes an explicit 0. Only 1 means private.
     :private-network? (= "1" (vals "PRIVATE_NETWORK"))
     :host-address     (addr "HOST_ADDRESS")
     :local-address    (addr "LOCAL_ADDRESS")}))

(defn scan
  "Derive container maps from a store path holding a NixOS `etc` output.
   `etc-out` is the derivation output; the files live under `<etc-out>/etc`."
  [etc-out]
  (let [root (fs/path etc-out "etc")
        sysd (fs/path root "systemd" "system")
        svcs (when (fs/directory? sysd)
               (sort (map str (fs/glob sysd "container@?*.service" {:follow-links true}))))]
    (when (empty? svcs)
      (u/die (str "No container services in " sysd)))
    (vec
     (for [svc svcs
           :let [nm   (name-from-service svc)
                 conf (str (fs/path root (conf-name) (str nm ".conf")))]]
       {:name         nm
        :service-src  svc
        :conf-src     conf
        :service-dest (str (fs/path service-dir (sd/unit nm)))
        :conf-dest    (str (fs/path (conf-dir) (str nm ".conf")))
        :auto-start?  (:auto-start? (read-conf conf))}))))

(defn- realpath [p]
  (when (fs/exists? p) (str (fs/real-path p))))

(defn- conf-without-system
  "Container conf contents with the SYSTEM_PATH line removed. Two confs that
   differ only in SYSTEM_PATH describe the same container with a new system,
   which can be switched in place instead of restarted."
  [p]
  (->> (str/split-lines (slurp (str p)))
       (remove #(str/starts-with? % "SYSTEM_PATH="))
       (str/join "\n")))

(defn classify
  "Compare a container's built files against what is installed on the host.
   Returns :unchanged, :system-only (only the NixOS system changed) or :changed.

   extra-container's isContainerUnchanged returns a status code *and* appends to
   a global `onlySystemChangedContainers` as a hidden second output; this is the
   same logic as one pure function."
  [{:keys [service-src conf-src service-dest conf-dest]}]
  (cond
    (not (and (fs/exists? service-dest) (fs/exists? conf-dest)))    :changed
    (not= (realpath service-src) (realpath service-dest))           :changed
    (= (realpath conf-src) (realpath conf-dest))                    :unchanged
    (= (conf-without-system conf-src) (conf-without-system conf-dest)) :system-only
    :else                                                           :changed))

(defn with-state
  "Attach :state to each container map."
  [containers]
  (mapv #(assoc % :state (classify %)) containers))

(defn- link! [target link]
  (fs/delete-if-exists link)
  (fs/create-sym-link link target))

(defn installed-names
  "Names of every container ctr has installed on this host."
  []
  (if (fs/directory? service-dir)
    (sort (keep name-from-service
                (map str (fs/glob service-dir "container@?*.service" {:follow-links true}))))
    []))

(defn list-rows
  "Table rows describing the installed containers. `running?` and `conf` are
   injected so this stays pure and testable."
  [names running? conf]
  (into [["NAME" "STATUS" "ADDRESS" "AUTOSTART"]]
        (for [nm names
              :let [{:keys [private-network? local-address auto-start?]} (conf nm)]]
          [nm
           (if (running? nm) "up" "down")
           (cond (not private-network?) "host"
                 local-address          local-address
                 ;; Bridged or DHCP: the conf carries no address at all.
                 :else                  "-")
           (if auto-start? "yes" "no")])))

(defn install!
  "Link a container's unit and conf into the host, with gcroots so the store
   paths survive garbage collection."
  [{:keys [name service-src conf-src service-dest conf-dest auto-start?]}]
  (when-not (fs/exists? conf-src)
    ;; A config built for the other convention lands in the other directory, so
    ;; say that rather than just reporting a missing file (extra-container:754).
    (let [other (str/replace conf-src
                             (str "/" (conf-name) "/")
                             (str "/" (:other-conf-name @dirs) "/"))]
      (if (fs/exists? other)
        (u/die (str "\nError: these containers were built for "
                    (if (legacy-install-dirs?) "NixOS >= 22.05" "NixOS < 22.05")
                    " install directories,\nbut this host uses " (conf-dir) ".\n"
                    "Rebuild with `--" (if (legacy-install-dirs?) "" "no-")
                    "legacy-install-dirs`.\n"))
        (u/die (str "Error: " conf-src " doesn't exist")))))
  (run! fs/create-dirs [service-dir (conf-dir) gcroots-dir])
  (link! (realpath service-src) service-dest)
  (link! (realpath conf-src) conf-dest)
  (link! service-dest (gcroot name))
  (link! conf-dest (str (gcroot name) ".conf"))
  (let [want (fs/path (wants-dir) (sd/unit name))]
    (if auto-start?
      (do (fs/create-dirs (wants-dir))
          (link! (str "../" (sd/unit name)) (str want)))
      ;; extra-container never removes this link, so a container that drops
      ;; autoStart keeps starting at boot until destroyed.
      (fs/delete-if-exists want))))

(defn check-installed!
  "If a container unit resolves to the generic template ExecStart, the host is
   missing boot.extraSystemdUnitPaths and the unit was never really installed."
  [nm]
  (let [{:keys [out]} (u/run "systemctl" "show" "-p" "ExecStart" (sd/unit nm))]
    (when (str/includes? out "/bin/container_-start")
      (u/die (str "\nContainer service installation failed.\n"
                  "Add the following to your NixOS configuration to enable\n"
                  "dynamically installing systemd units:\n\n"
                  "  boot.extraSystemdUnitPaths = [ \"" service-dir "\" ];\n")))))

(defn- unlock-nested!
  "Nested declarative containers leave immutable var/empty files behind, which
   block deletion of the outer container's directory."
  [nm]
  (doseq [p (fs/glob (fs/path (state-dir) nm) "var/lib/*containers/*/var/empty")]
    (u/run "chattr" "-i" (str p))))

(defn destroy!
  "Stop and remove containers. Ordering matters throughout; see the comments."
  [names]
  (when (fs/exists? (conf-dir))
    (let [reload? (atom false)]
      (doseq [nm names
              :let [unit (sd/unit nm)
                    service-file (fs/path service-dir unit)
                    conf-file (fs/path (conf-dir) (str nm ".conf"))]]
        ;; Signal stop before killing, so the killed container doesn't restart.
        (when (sd/stop! unit)
          (sd/kill! unit))
        (fs/delete-if-exists (fs/path (wants-dir) unit))
        (when (fs/sym-link? service-file)
          (fs/delete service-file)
          (reset! reload? true))
        (fs/delete-if-exists (gcroot nm))
        (fs/delete-if-exists (str (gcroot nm) ".conf"))
        ;; Remove the declarative conf, else nixos-container refuses with
        ;; 'cannot destroy declarative container'; then leave an empty one, else
        ;; it stops before destroying the container completely.
        (fs/delete-if-exists conf-file)
        (spit (str conf-file) "")
        (unlock-nested! nm)
        (u/run "nixos-container" "destroy" nm))
      (when @reload? (sd/daemon-reload!)))))
