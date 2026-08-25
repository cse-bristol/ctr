(ns ctr.systemd-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [ctr.support :as sup]
            [ctr.systemd :as sd]))

(defn- cs [& pairs]
  (mapv (fn [[nm state]] {:name nm :state state}) (partition 2 pairs)))

(deftest unit-name
  (is (= "container@demo.service" (sd/unit "demo"))))

;; `plan` replaces extra-container's three `comm -12`/`comm -23` invocations
;; over sorted temp files.
(deftest start-only-touches-stopped-containers
  (let [p (sd/plan (cs "a" :changed "b" :changed) #{"b"} {:start true})]
    (is (= ["a"] (:start p)))
    (is (= ["b"] (:restart p)) "a running container that changed is restarted")))

(deftest a-running-container-whose-system-changed-is-updated-in-place
  (let [p (sd/plan (cs "a" :system-only) #{"a"} {:start true})]
    (is (= [] (:start p)))
    (is (= ["a"] (:update p)))
    (is (= [] (:restart p)) "switch-to-configuration avoids the restart")))

(deftest restart-changed-forces-a-restart-instead-of-an-update
  (let [p (sd/plan (cs "a" :system-only) #{"a"} {:start true :restart true})]
    (is (= [] (:update p)))
    (is (= ["a"] (:restart p)))))

(deftest unchanged-containers-are-left-alone
  (let [p (sd/plan (cs "a" :unchanged) #{"a"} {:start true})]
    (is (= {:start [] :update [] :restart []} p))))

(deftest a-stopped-container-is-never-updated-or-restarted
  (testing "it is only started, and only when --start was given"
    (is (= {:start ["a"] :update [] :restart []}
           (sd/plan (cs "a" :system-only) #{} {:start true})))
    (is (= {:start [] :update [] :restart []}
           (sd/plan (cs "a" :system-only) #{} {:update true})))))

(deftest update-changed-without-start-still-updates-running-containers
  (is (= {:start [] :update ["a"] :restart ["b"]}
         (sd/plan (cs "a" :system-only "b" :changed) #{"a" "b"} {:update true}))))

(deftest containers-running-but-not-in-this-config-are-ignored
  (is (= {:start ["a"] :update [] :restart []}
         (sd/plan (cs "a" :changed) #{"unrelated"} {:start true}))))

(deftest results-are-deterministic
  (let [p (sd/plan (cs "z" :changed "a" :changed "m" :system-only) #{"z" "a" "m"} {:start true})]
    (is (= ["m"] (:update p)))
    (is (= ["a" "z"] (:restart p)) "sorted, so output and `comm` semantics are stable")))

;; The nixpkgs commit a container was built from is only ever as good as the
;; label the system closure carries, so the interesting cases are the ones
;; where there is no commit in it to show.
(deftest version-label-shortens-a-flake-label-to-release-and-rev
  (is (= "26.05@9f78f44" (sd/version-label "26.05.20260812.9f78f44")))
  (testing "including a nixpkgs tree that was dirty when it was built"
    (is (= "26.05@dirty" (sd/version-label "26.05.20260812.dirty"))))
  (testing "a label with no commit in it is shown as it is"
    (is (= "25.11pre-git" (sd/version-label "25.11pre-git")))
    (is (= "25.11" (sd/version-label "25.11"))))
  (testing "as is one the user chose themselves"
    (is (= "my-label" (sd/version-label "my-label"))))
  (testing "and an unreadable system reads as absent, not as an error"
    (is (= "-" (sd/version-label nil)))
    (is (= "-" (sd/version-label "")))
    (is (= "-" (sd/version-label "   ")))))

(deftest nixos-version-reads-the-label-out-of-a-system-closure
  (let [dir (sup/tmpdir)
        system (str (fs/path dir "abcd-nixos-system-web-26.05"))]
    (fs/create-dirs system)
    (spit (str (fs/path system "nixos-version")) "26.05.20260812.9f78f44\n")
    (is (= "26.05.20260812.9f78f44" (sd/nixos-version system)))
    (testing "a collected or absent system yields nil rather than throwing"
      (is (nil? (sd/nixos-version (str (fs/path dir "gone")))))
      (is (nil? (sd/nixos-version nil)))
      (is (nil? (sd/nixos-version ""))))
    (testing "as does a system with no label file, as a hand-built one may be"
      (let [bare (str (fs/path dir "bare"))]
        (fs/create-dirs bare)
        (is (nil? (sd/nixos-version bare)))))
    (fs/delete-tree dir)))

;; `systemctl stop` returns before the machine is necessarily gone, so the
;; terminate is the part that matters -- and restart! must not lose it.
(deftest stopping-a-container-waits-for-the-machine-to-go
  (let [calls (atom [])
        record (fn [& args] (swap! calls conj (vec (flatten args))) {:exit 0 :out "" :err ""})]
    (with-redefs [ctr.util/check! record
                  ctr.util/run   record]
      (sd/stop-containers! ["web"])
      (is (= [["systemctl" "stop" "container@web.service"]
              ["machinectl" "terminate" "web"]]
             @calls)))))

(deftest restart-stops-terminates-then-starts
  (let [calls (atom [])
        record (fn [& args] (swap! calls conj (vec (flatten args))) {:exit 0 :out "" :err ""})]
    (with-redefs [ctr.util/check! record
                  ctr.util/run   record]
      (sd/restart! ["web" "db"])
      (is (= [["systemctl" "stop" "container@web.service" "container@db.service"]
              ["machinectl" "terminate" "web" "db"]
              ["systemctl" "start" "container@web.service" "container@db.service"]]
             @calls)))))
