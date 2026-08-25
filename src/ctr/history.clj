(ns ctr.history
  "Per-container deployment history.

   Everything ctr installs for a container is two store paths: the systemd unit
   and the container conf. Recording that pair on every deploy is enough to put
   a container back the way it was, and keeping the records as symlinks under
   /nix/var/nix/gcroots means the old closures survive garbage collection with
   no other bookkeeping."
  (:require [babashka.fs :as fs]
            [ctr.systemd :as sd])
  (:import [java.time LocalDateTime ZoneId]
           [java.time.format DateTimeFormatter]))

(def history-root "/nix/var/nix/gcroots/ctr-history")

(def default-keep
  "How many generations to keep per container. Each one pins a whole NixOS
   system closure, so this is a disk-space decision, not a correctness one."
  20)

(defn dir [nm] (str (fs/path history-root nm)))

(defn- link-path [d gen kind]
  (str (fs/path d (format "%05d-%s" gen kind))))

(defn- entry-numbers
  "Every generation number with at least one link in `d`, ascending. Includes
   entries that are half written or whose store paths are gone, so that
   numbering never reuses one."
  [d]
  (if-not (fs/directory? d)
    []
    (->> (fs/glob d "*-{service,conf}")
         (keep #(some-> (re-matches #"(\d+)-(?:service|conf)" (fs/file-name %))
                        second parse-long))
         distinct sort vec)))

(defn generations
  "Recorded generations of `d`, oldest first, as
   {:gen :service :conf :system :version :at}.

   Entries missing a link, or whose store paths have been collected, are
   skipped: they cannot be rolled back to, so they are not offered."
  [d]
  (vec
   (for [n (entry-numbers d)
         :let [svc (link-path d n "service")
               cnf (link-path d n "conf")]
         ;; fs/exists? follows the link, so a collected store path reads as
         ;; absent -- which is exactly the question being asked.
         :when (and (fs/exists? svc) (fs/exists? cnf))
         :let [system (sd/system-path cnf)]]
     {:gen     n
      :service (str (fs/real-path svc))
      :conf    (str (fs/real-path cnf))
      :system  system
      ;; Read here, with the rest of the I/O, so `rows` stays pure.
      :version (sd/nixos-version system)
      :at      (fs/last-modified-time svc {:nofollow-links true})})))

(defn record!
  "Append `service` and `conf` to `d` as its newest generation, returning the
   new number. A no-op returning nil when that pair already is the newest, so
   callers can record unconditionally."
  [d service conf]
  (when (and service conf (fs/exists? service) (fs/exists? conf))
    (let [newest (peek (generations d))]
      (when-not (and newest
                     (= (str service) (:service newest))
                     (= (str conf) (:conf newest)))
        (fs/create-dirs d)
        (let [n (inc (or (last (entry-numbers d)) 0))]
          ;; If this is interrupted between the two links, the half-written
          ;; entry is skipped by `generations` and cleaned up by `prune!`.
          (fs/create-sym-link (link-path d n "service") (str service))
          (fs/create-sym-link (link-path d n "conf") (str conf))
          n)))))

(defn prune!
  "Keep the `keep` newest generations of `d`. Dropping the links releases the
   gcroots, letting the old systems be collected; leftover half-written or
   already-collected entries go too."
  [d keep]
  (when (pos? keep)
    (let [alive (set (map :gen (take-last keep (generations d))))]
      (doseq [n (entry-numbers d)
              :when (not (alive n))
              kind ["service" "conf"]]
        (fs/delete-if-exists (link-path d n kind))))))

(defn forget!
  "Drop a container's whole history, freeing its gcroots."
  [d]
  (fs/delete-tree d))

(defn nth-last
  "The generation `idx` places back from the newest, 1-based, so 1 is what is
   deployed now and 2 the deploy before it. nil when out of range."
  [gens idx]
  (when (and (pos? idx) (<= idx (count gens)))
    (nth gens (- (count gens) idx))))

(def ^:private stamp (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm"))

(defn- format-time [ft]
  (if ft
    (.format (LocalDateTime/ofInstant (.toInstant ft) (ZoneId/systemDefault)) stamp)
    "-"))

(defn rows
  "Table rows for `ctr history`, newest first, at most `limit` of them (all of
   them when `limit` is nil or non-positive). The leading number is the index
   `ctr rollback` takes."
  [gens limit]
  (let [newest-first (reverse gens)
        shown (if (and limit (pos? limit)) (take limit newest-first) newest-first)]
    (when (seq shown)
      (into [["#" "GEN" "DEPLOYED" "VERSION" "SYSTEM"]]
            (map-indexed
             (fn [i {:keys [gen at system version]}]
               [(str (inc i)) (str gen) (format-time at) (sd/version-label version)
                ;; format-table does not pad the last column, so the marker can
                ;; simply be appended.
                (str (or system "-") (when (zero? i) "  (current)"))])
             shown)))))
