(ns ctr.nix
  "Turning a container config source -- flake ref, expression, file, stdin or a
   pre-built store path -- into a store path holding a NixOS `etc` output."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [ctr.container :as c]
            [ctr.util :as u]))

;; Set by the Nix wrapper (see package.nix) and by the dev shell.
(defn- eval-config-path []
  (or (u/getenv "CTR_EVAL_CONFIG")
      (let [local (fs/path (fs/cwd) "nix" "eval-config.nix")]
        (if (fs/exists? local)
          (str local)
          (u/die "CTR_EVAL_CONFIG is not set and ./nix/eval-config.nix was not found")))))

(defn- nixos-path-expr
  "A Nix expression for the NixOS source tree to build containers against."
  [{:keys [nixos-path nixpkgs-path]}]
  (cond
    nixos-path   nixos-path
    nixpkgs-path (str "\"${toString (" nixpkgs-path ")}/nixos\"")
    :else        (if-let [np (u/getenv "CTR_NIXPKGS")]
                   (str np "/nixos")
                   ;; Not "<nixpkgs>/nixos": that parses as applying <nixpkgs>
                   ;; to the path /nixos. The lookup path has to carry the
                   ;; subdirectory itself.
                   "<nixpkgs/nixos>")))

(defn- attr-expr
  "Select an attribute path, split on dots like `nix-build -A`."
  [attr]
  (if (str/blank? attr)
    ""
    (->> (str/split attr #"\.")
         (map #(str ".${" (u/nix-string %) "}"))
         str/join)))

(defn legacy-install-dirs?
  "Whether to build for the pre-22.05 /etc/containers layout. Defaults to what
   this host uses, but must be overridable: `ctr build` may target another host."
  [{:keys [legacy-install-dirs]}]
  (if (some? legacy-install-dirs)
    legacy-install-dirs
    (c/legacy-install-dirs?)))

(defn build-expr
  "The full Nix expression built for non-flake config sources."
  [config-expr {:keys [attr full-eval] :as opts}]
  (format (str "let cfg = (%s)%s; in (import %s { nixosPath = %s;"
               " reducedModules = %s; legacyInstallDirs = %s;"
               " systemConfig = cfg; }).config.system.build.etc")
          config-expr
          (attr-expr attr)
          (eval-config-path)
          (nixos-path-expr opts)
          (if full-eval "false" "true")
          (if (legacy-install-dirs? opts) "true" "false")))

(defn- build-expression!
  [config-expr {:keys [build-args] :as opts}]
  (u/eprintln "Building containers...")
  ;; NIX_PATH gains `pwd` so that `-E` configs can reach the working directory
  ;; via <pwd/file.nix>.
  (let [env {"NIX_PATH" (str (u/getenv "NIX_PATH" "") ":pwd=" (fs/cwd))}]
    (u/capture-out! {:extra-env env}
                    "nix" "build" "--impure" "--no-link" "--print-out-paths"
                    "--expr" (build-expr config-expr opts)
                    build-args)))

(defn- build-flake! [ref {:keys [build-args]}]
  (u/eprintln "Building containers...")
  (u/capture-out! {} "nix" "build" "--no-link" "--print-out-paths" ref build-args))

(defn flake-ref? [s]
  (boolean (or (str/includes? s "#")
               (re-find #"^[a-z][a-z0-9+.-]*:" s)
               (fs/exists? (fs/path s "flake.nix")))))

(defn nix-source? [s]
  (boolean (or (fs/regular-file? s)
               (fs/exists? (fs/path s "default.nix")))))

(defn prebuilt? [s]
  (fs/directory? (fs/path s "etc")))

(defn source-kind
  "Classify a config source without touching the network or the store.
   Split out from resolve-etc so it can be unit tested."
  [arg {:keys [expr flake]}]
  (cond
    flake            :flake
    expr             :expr
    (= "-" arg)      :stdin
    (some? arg)      (cond
                       (flake-ref? arg)  :flake
                       (nix-source? arg) :file
                       (prebuilt? arg)   :prebuilt
                       :else             :unknown)
    (u/getenv "CTR_ETC") :env
    :else            :stdin))

(defn resolve-etc
  "Resolve a config source to a store path whose `etc/` holds the container
   units and confs. `arg` is the single positional argument, if any."
  [arg {:keys [expr flake] :as opts}]
  (case (source-kind arg opts)
    :flake    (build-flake! (or flake arg) opts)
    :expr     (build-expression! expr opts)
    :file     (build-expression! (str "import " (u/nix-string (fs/absolutize arg))) opts)
    :prebuilt (str arg)
    :env      (u/getenv "CTR_ETC")
    :stdin    (let [e (slurp *in*)]
                (when (str/blank? e) (u/die "No containers specified"))
                (build-expression! e opts))
    :unknown  (u/die (str "Don't know how to build containers from '" arg
                          "': not a flake ref, a Nix file, or a directory containing etc/"))))
