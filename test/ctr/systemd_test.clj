(ns ctr.systemd-test
  (:require [clojure.test :refer [deftest is testing]]
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
