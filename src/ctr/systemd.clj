(ns ctr.systemd
  "Talking to systemd, machinectl and nixos-container, plus the start/update/
   restart decision for a set of containers."
  (:require [babashka.fs :as fs]
            [clojure.set :as set]
            [clojure.string :as str]
            [ctr.util :as u]))

(defn unit [nm] (str "container@" nm ".service"))

(defn daemon-reload! [] (u/check! "systemctl" "daemon-reload"))

(defn- systemctl-tolerating-unloaded
  "Run systemctl, treating 'not loaded' as success. Returns false when the unit
   was not loaded, true otherwise."
  [& args]
  (let [{:keys [exit out err]} (apply u/run "systemctl" args)
        output (str out err)]
    (cond
      (zero? exit)                      true
      (str/includes? output "not loaded") false
      :else (do (u/eprintln (str "systemctl " (str/join " " args) " failed with: " output))
                true))))

(defn stop!
  "Ask a unit to stop without waiting. Returns false if it was not loaded."
  [unit-name]
  (systemctl-tolerating-unloaded "stop" "--no-block" unit-name))

(defn kill! [unit-name]
  (systemctl-tolerating-unloaded "kill" unit-name))

(defn active? [nm]
  (= "active" (:out (u/run "systemctl" "is-active" (unit nm)))))

(defn start!
  "Start container units. With :no-block, return as soon as the jobs are queued."
  [names & {:keys [no-block]}]
  (u/check! "systemctl" "start" (when no-block "--no-block") (map unit names)))

(defn- terminate!
  "machinectl terminate, retried until it succeeds or the machines are gone."
  [names]
  (loop [attempt 1]
    (let [{:keys [exit out err]} (apply u/run "machinectl" "terminate" names)
          output (str/trim (str out "\n" err))]
      (cond
        (zero? exit) nil
        (re-find #"(?i)no .*machine.* known" output) nil
        (>= attempt 20) (u/die "Failed to stop containers.")
        :else (do (println output)
                  (Thread/sleep 1)
                  (recur (inc attempt)))))))

(defn stop-containers!
  "Stop container units and wait until their machines are really gone.

   `systemctl stop` returns once the unit is inactive, but the machine can
   outlive it (nixpkgs#43652), so `terminate!` is the part that settles it."
  [names]
  (u/check! "systemctl" "stop" (map unit names))
  (terminate! names))

(defn restart!
  "`systemctl restart container@x` is broken (nixpkgs#43652), so stop, make sure
   the machine is really gone, then start."
  [names]
  (stop-containers! names)
  (u/check! "systemctl" "start" (map unit names)))

(defn wait-for-machine!
  "Poll until the container registers with machined, or the timeout passes."
  [nm timeout-s]
  (let [deadline (+ (System/currentTimeMillis) (* 1000 timeout-s))]
    (loop []
      (cond
        (zero? (:exit (u/run "machinectl" "show" nm))) :up
        (> (System/currentTimeMillis) deadline)
        (u/die (str "Container " nm " did not come up within " timeout-s "s."))
        :else (do (Thread/sleep 200) (recur))))))

(defn- ensure-running!
  "Precondition for `shell!` and `run!`: the container must be up. With
   :start, start it and wait; otherwise say how to."
  [verb nm {:keys [start timeout] :or {timeout 90}}]
  (when-not (active? nm)
    (when-not start
      (u/die (str "Container '" nm "' is not running"
                  " (start it with `ctr " verb " --start " nm "`).")))
    ;; stderr, so `ctr run` stays usable in a pipeline.
    (u/eprintln (str "Starting container " nm "."))
    ;; Blocking, so a container that fails to start says so rather than timing
    ;; out below. nixos-container then enters via `nsenter -t <leader>`, and the
    ;; leader PID is what machined registration provides -- hence both waits.
    (start! [nm])
    (wait-for-machine! nm timeout)))

(defn shell!
  "Open an interactive root shell inside a running container."
  [nm opts]
  (ensure-running! "shell" nm opts)
  (u/exec-status {} "nixos-container" "root-login" nm))

(defn run!
  "Run a command inside a running container, returning its exit code."
  [nm cmd opts]
  (ensure-running! "run" nm opts)
  (u/exec-status {} "nixos-container" "run" nm "--" cmd))

(defn system-path
  "The SYSTEM_PATH= value from a container conf, or nil if the conf has none or
   is not there. That store path is the human-readable identity of a deploy,
   which is what `ctr history` shows."
  [conf]
  (when (fs/exists? (str conf))
    (some #(second (re-matches #"SYSTEM_PATH=(.*)" %))
          (str/split-lines (slurp (str conf))))))

(defn nixos-version
  "The NixOS version label of a system store path, read from the `nixos-version`
   file every system closure carries. nil when there is no path, or when the
   system has been garbage collected."
  [system]
  (when-not (str/blank? (str system))
    (let [f (str (fs/path (str system) "nixos-version"))]
      (when (fs/exists? f)
        (not-empty (str/trim (slurp f)))))))

(defn version-label
  "Render a NixOS version label for a table cell.

   A flake-built system labels itself `<release>.<date>.<shortrev>`, where the
   rev is the nixpkgs commit -- the part worth seeing -- so that form is
   shortened to `<release>@<rev>`. Anything else is shown verbatim rather than
   guessed at: a plain checkout says `25.11pre-git`, a dirty tree
   `26.05@dirty`, and a `system.nixos.label` the user set says whatever they
   chose."
  [label]
  (if (str/blank? (str label))
    "-"
    (if-let [[_ release rev] (re-matches #"(\d+\.\d+)\.\d{8}\.(.+)" label)]
      (str release "@" rev)
      label)))

(defn update!
  "Switch running containers to a new system in place, without restarting."
  [containers]
  (doseq [{:keys [name conf-dest]} containers]
    (println (str "  Updating " name))
    (let [sp (or (system-path conf-dest)
                 (u/die (str "No SYSTEM_PATH in " conf-dest)))]
      (u/run-indented 2 "nixos-container" "run" name "--"
                      "bash" "-lc" (str sp "/bin/switch-to-configuration test")))
    (println)))

(defn plan
  "Decide what to start, update in place and restart.

   Pure, so the interesting case (a running container whose system changed) is
   testable without systemd. `running` is the set of names currently active.
   extra-container computes this with three `comm` calls over sorted temp files."
  [containers running {:keys [start restart]}]
  (let [names       (mapv :name containers)
        by-state    #(set (keep (fn [c] (when (= % (:state c)) (:name c))) containers))
        changed     (set/union (by-state :changed) (by-state :system-only))
        system-only (by-state :system-only)
        running     (set/intersection (set running) (set names))
        to-start    (if start (remove running names) [])
        touched     (set/intersection running changed)
        to-update   (if restart #{} (set/intersection touched system-only))
        to-restart  (set/difference touched to-update)]
    {:start   (vec to-start)
     :update  (vec (sort to-update))
     :restart (vec (sort to-restart))}))

(defn activate!
  "Apply `plan` to the host."
  [containers {:keys [start update restart] :as opts}]
  (when (or start update restart)
    (let [by-name (into {} (map (juxt :name identity)) containers)
          running (filter active? (map :name containers))
          {:keys [start update restart]} (plan containers running opts)]
      (when (seq start)
        (u/print-list "Starting containers:" start)
        (start! start))
      (when (seq update)
        (u/print-list "Updating containers:" update)
        (update! (map by-name update)))
      (when (seq restart)
        (u/print-list "Restarting containers:" restart)
        (restart! restart)))))
