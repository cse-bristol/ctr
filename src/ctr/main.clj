(ns ctr.main
  "Command line entry point."
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [ctr.container :as c]
            [ctr.newconfig :as nc]
            [ctr.nix :as nix]
            [ctr.systemd :as sd]
            [ctr.util :as u])
  (:import [java.time LocalDate]))

(def usage "Usage:

ctr create [<config>] [--start|-s] [--update-changed|-u] [--restart-changed|-r]
           [--attr|-A <path>] [--expr|-E <expr>] [--flake <ref>]
           [--nixpkgs-path|--nixos-path <expr>] [--full-eval]
           [--legacy-install-dirs|--no-legacy-install-dirs]
           [--build-args <arg>...]

    <config> is one of:
      a NixOS config file defining 'containers.<name> = { ... }'
      a flake reference, e.g. '.#containers' or '.'
      a store path previously produced by 'ctr build'
      '-', or omitted, to read the config from stdin

    --start | -s            Start created containers. Running containers that
                            changed are updated in place, or restarted if
                            --restart-changed was given.
    --update-changed | -u   Update running containers whose system changed by
                            running switch-to-configuration inside them.
                            Restart containers whose container config changed.
    --restart-changed | -r  Restart running containers that changed.
    --attr | -A <path>      Select an attribute of the config expression.
    --expr | -E <expr>      Provide the container config as an argument.
    --flake <ref>           Build containers from a flake output.
    --nixpkgs-path <expr>   Nix expression returning the nixpkgs source to build
                            containers with.
    --nixos-path <expr>     Like --nixpkgs-path, but for the NixOS source.
    --full-eval             Evaluate a complete NixOS system instead of the
                            reduced module set. Slower, but a way out if a
                            nixpkgs change breaks the reduced set.
    --[no-]legacy-install-dirs
                            Build for /etc/containers (NixOS < 22.05) or
                            /etc/nixos-containers. Defaults to whatever this
                            host's nixos-container uses.
    --build-args <arg>...   All following args are passed to 'nix build'.

ctr build [<config>] [<create options>]
    Build the containers and print the resulting store path.

ctr list
    List the containers ctr has installed, with their status and address.

ctr new-config <name> [--address-prefix <a.b.c>] [--network <nat|iface>]
               [--no-network] [--auto-start] [--state-version <ver>]

    Print a starting-point config for a new container. The address is chosen
    to clash with neither an existing container nor a host interface, and
    system.stateVersion is pinned to the release ctr would build with.

    --network nat      Give the container a private 10.233.n subnet with the
                       host at .1 and container at .2, NATted out of the host
                       interface (the default).
    --network <iface>  Bridge the container onto interface <iface>. A free
                       address is picked on that interface's own network.

ctr shell <name> [--start] [--timeout <seconds>]
    Open a root shell inside a running container.

ctr run <name> [--start] [--timeout <seconds>] [--] <cmd> [<arg>...]
    Run a command inside a running container and exit with its status.

    --start           Start the container first if it is not running.
    --timeout <secs>  How long to wait for it to register. Default 90.

ctr restart <container>...
    Restart containers, working around nixpkgs issue #43652.

ctr destroy <container>...
ctr destroy <config>
ctr destroy --all|-a
    Destroy containers, either by name, by the config that defines them, or all
    of them.
")

(def ^:private build-opts
  {:expr                {:alias :E}
   :attr                {:alias :A}
   :flake               {}
   :nixpkgs-path        {}
   :nixos-path          {}
   :full-eval           {:coerce :boolean}
   :legacy-install-dirs {:coerce :boolean}})

(def ^:private create-opts
  (merge build-opts
         {:start           {:alias :s :coerce :boolean}
          :update-changed  {:alias :u :coerce :boolean}
          :restart-changed {:alias :r :coerce :boolean}}))

(def ^:private destroy-opts
  (merge build-opts {:all {:alias :a :coerce :boolean}}))

(def ^:private newconfig-opts
  {:address-prefix {}
   :network        {}
   :state-version  {}
   :no-network     {:coerce :boolean}
   :auto-start     {:coerce :boolean}})

(defn split-greedy
  "Pull `--build-args ...` out of argv before flag parsing.

   It consumes every following argument, which no general option parser can
   express; extra-container handles it inside its 55-line parsing loop."
  [args]
  (let [[kept build-args] (split-with #(not= "--build-args" %) args)]
    (cond-> {:args (vec kept)}
      (seq build-args) (assoc :build-args (vec (rest build-args))))))

(defn parse
  "Parse a subcommand's argv into [positional-args opts]."
  [args spec]
  (let [{:keys [args build-args]} (split-greedy args)
        {:keys [opts args]} (cli/parse-args args {:spec spec})]
    [(or args [])
     (cond-> opts build-args (assoc :build-args build-args))]))

(defn- install-all!
  "Link every container from a built etc output into the host."
  [etc]
  (let [containers (c/with-state (c/scan etc))]
    (println)
    (println "Installing containers:")
    (doseq [{:keys [name state] :as ctr} containers]
      (if (= :unchanged state)
        (println (str name " (unchanged, skipped)"))
        (do (println name)
            (c/install! ctr))))
    (when-let [changed (seq (remove #(= :unchanged (:state %)) containers))]
      (sd/daemon-reload!)
      (c/check-installed! (:name (first changed))))
    (println)
    containers))

(defn cmd-build [args opts]
  (println (nix/resolve-etc (first args) opts)))

(defn cmd-create [args opts]
  (let [containers (install-all! (nix/resolve-etc (first args) opts))]
    (sd/activate! containers {:start   (:start opts)
                              :update  (:update-changed opts)
                              :restart (:restart-changed opts)})))

(defn cmd-list [_ _]
  ;; The header would otherwise make "no containers" look like output.
  (doseq [line (u/format-table
                (let [names (c/installed-names)]
                  (when (seq names)
                    (c/list-rows names sd/active?
                                 #(c/read-conf (str (c/conf-dir) "/" % ".conf"))))))]
    (println line)))

(defn cmd-new-config [args opts]
  (when (not= 1 (count args))
    (u/die "Usage: ctr new-config <name>"))
  (print (nc/generate (first args) (assoc opts :date (str (LocalDate/now))))))

(defn cmd-restart [args _]
  (when (empty? args) (u/die "No container name specified"))
  (sd/restart! args))

(defn- destroy-target?
  "True when destroy's arguments describe a container config rather than names."
  [args opts]
  (or (:expr opts) (:flake opts)
      (and (seq args) (re-find #"^[-./]" (first args)))
      (and (empty? args) (u/getenv "CTR_ETC"))))

(defn cmd-destroy [args opts]
  (let [names (cond
                (:all opts) (c/installed-names)

                (destroy-target? args opts)
                (let [names (mapv :name (c/scan (nix/resolve-etc (first args) opts)))]
                  (u/print-list "Destroying containers:" names)
                  names)

                (seq args) args
                :else (u/die "No container name specified"))]
    (c/destroy! names)))

(defn parse-attach
  "Parse `[--start] [--timeout N] <name> [--] <cmd>...`.

   The command is taken verbatim: running it through an option parser would eat
   its own flags. Returns [name opts command]."
  [argv]
  (loop [[a & more] argv, opts {:timeout 90}]
    (cond
      (= "--start" a)   (recur more (assoc opts :start true))
      (= "--timeout" a) (if-let [t (parse-long (str (first more)))]
                          (recur (rest more) (assoc opts :timeout t))
                          (u/die "--timeout needs a number of seconds"))
      (nil? a)          (u/die "No container name specified")
      (str/starts-with? (str a) "-") (u/die (str "Unknown option: " a))
      :else [a opts (vec (if (= "--" (first more)) (rest more) more))])))

(defn- check-exists!
  "Distinguish a typo from a stopped container. The conf, not ctr's own unit
   list, so containers created with `nixos-container create` work too."
  [nm]
  (when-not (fs/exists? (fs/path (c/conf-dir) (str nm ".conf")))
    (u/die (str "No container named '" nm "' in " (c/conf-dir) "."))))

(defn cmd-shell [argv]
  (let [[nm opts cmd] (parse-attach argv)]
    (when (seq cmd)
      (u/die "Command 'shell' takes no command; use 'ctr run' instead."))
    (check-exists! nm)
    (System/exit (sd/shell! nm opts))))

(defn cmd-run [argv]
  (let [[nm opts cmd] (parse-attach argv)]
    (when (empty? cmd) (u/die "No command specified"))
    (check-exists! nm)
    (System/exit (sd/run! nm cmd opts))))

;; Commands whose arguments go through babashka.cli.
(def ^:private commands
  {"build"      [cmd-build      build-opts]
   "create"     [cmd-create     create-opts]
   "add"        [cmd-create     create-opts]
   "list"       [cmd-list       {}]
   "new-config" [cmd-new-config newconfig-opts]
   "restart"    [cmd-restart    {}]
   "destroy"    [cmd-destroy    destroy-opts]})

;; Commands that parse their own argv, because a verbatim command follows.
(def ^:private raw-commands
  {"shell" cmd-shell
   "run"   cmd-run})

(def ^:private needs-root
  "Commands that mutate host state or need privileged access to a container.
   build, list and new-config are read-only, so they run as the calling user --
   extra-container re-execs for everything."
  #{"create" "add" "shell" "run" "destroy" "restart"})

(defn- reexec-as-root!
  "Re-run ourselves under sudo, preserving the variables the run depends on."
  [argv]
  (let [self (or (u/getenv "CTR_SELF") (System/getProperty "babashka.file"))]
    (when-not self
      (u/die "Cannot determine own path for sudo re-exec; set CTR_SELF"))
    (apply p/exec
           (concat ["sudo"]
                   (for [k ["PATH" "NIX_PATH" "CTR_ETC" "CTR_SELF"
                            "CTR_NIXPKGS" "CTR_EVAL_CONFIG"]
                         :let [v (System/getenv k)]
                         :when v]
                     (str k "=" v))
                   [self] argv))))

(defn -main [& argv]
  (try
    (let [[cmd & rest-args] argv]
      (cond
        (or (nil? cmd) (#{"help" "-h" "--help"} cmd))
        (print usage)

        ;; Before the sudo re-exec, so `ctr shell --help` doesn't ask for a
        ;; password. Only the first argument, since `ctr run x -- y --help`
        ;; passes --help to the container's command.
        (#{"-h" "--help"} (first rest-args))
        (print usage)

        (not (or (commands cmd) (raw-commands cmd)))
        (do (u/eprintln (str "Unknown command: " cmd "\n"))
            (u/eprintln usage)
            (System/exit 1))

        (and (needs-root cmd) (not (u/root?)))
        (reexec-as-root! argv)

        (raw-commands cmd)
        ((raw-commands cmd) rest-args)

        :else
        (let [[handler spec] (commands cmd)
              [args opts] (parse rest-args spec)]
          (handler args opts))))
    (catch clojure.lang.ExceptionInfo e
      (if-let [code (:ctr/exit (ex-data e))]
        (do (u/eprintln (ex-message e))
            (System/exit code))
        (throw e)))))
