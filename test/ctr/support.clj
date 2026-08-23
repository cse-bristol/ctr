(ns ctr.support
  "Fixture helpers: build throwaway `etc` trees on disk."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(defn conf
  "A container conf resembling what the nixos-containers module emits."
  [& {:keys [system-path auto-start private-network host-address local-address]
      :or {system-path "/nix/store/aaaa-nixos-system" auto-start false
           private-network false}}]
  (str/join "\n" (concat [(str "SYSTEM_PATH=" system-path)]
                         ;; nixpkgs only emits AUTO_START when autoStart is set
                         (when auto-start ["AUTO_START=1"])
                         ;; The module emits PRIVATE_NETWORK only when it is
                         ;; set; `nixos-container create` writes an explicit 0.
                         (when private-network ["PRIVATE_NETWORK=1"])
                         [(str "HOST_ADDRESS=" (or host-address ""))
                          (str "LOCAL_ADDRESS=" (or local-address ""))
                          ""])))

(defn etc-out
  "Create a store-path-shaped dir containing `etc/` for the given containers.
   `containers` maps a name to its conf text.

   Faithful to a real system.build.etc output, which matters for anything that
   walks the tree:
     etc/systemd/system      is a symlink to the system-units directory
     etc/systemd/system/*    are symlinks to per-unit directories
     etc/<conf-name>/*       are symlinks to per-conf derivations"
  [dir containers & {:keys [conf-name] :or {conf-name "nixos-containers"}}]
  (let [store (fs/path dir "store")
        units (fs/path dir "system-units")
        confd (fs/path dir "etc" conf-name)]
    (run! fs/create-dirs [store units confd (fs/path dir "etc" "systemd")])
    (fs/create-sym-link (fs/path dir "etc" "systemd" "system") units)
    (let [link (fn [from to content]
                 (spit (str from) content)
                 (fs/create-sym-link to from))]
      ;; The unit template is always present and must never be taken for a
      ;; container, which is why the glob requires at least one name character.
      (link (fs/path store "container@.service")
            (fs/path units "container@.service")
            "[Unit]\nDescription=Container template\n")
      (doseq [[nm conf-text] containers]
        (link (fs/path store (str "container@" nm ".service"))
              (fs/path units (str "container@" nm ".service"))
              (str "[Unit]\nDescription=Container " nm "\n"))
        (link (fs/path store (str nm ".conf"))
              (fs/path confd (str nm ".conf"))
              conf-text)))
    (str dir)))

(defn nixos-container-script
  "A stand-in for the nixos-container binary carrying the given marker line."
  [dir marker]
  (let [f (fs/path dir "nixos-container")]
    (spit (str f) (str "#! /usr/bin/env perl\n" marker "\n"))
    (str f)))

(defn tmpdir [] (str (fs/create-temp-dir {:prefix "ctr-test"})))
