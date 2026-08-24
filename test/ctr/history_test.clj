(ns ctr.history-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [ctr.history :as h]
            [ctr.support :as sup]))

(defn- store
  "A stand-in for a pair of store paths: the unit and conf ctr would install."
  [dir tag & {:keys [system-path]}]
  (let [svc (fs/path dir (str tag ".service"))
        cnf (fs/path dir (str tag ".conf"))]
    (spit (str svc) (str "[Unit]\nDescription=" tag "\n"))
    (spit (str cnf) (sup/conf :system-path (or system-path
                                               (str "/nix/store/" tag "-system"))))
    [(str svc) (str cnf)]))

(deftest generations-are-numbered-from-one-and-kept-oldest-first
  (let [dir (sup/tmpdir)
        d   (str (fs/path dir "history"))
        [s1 c1] (store dir "one")
        [s2 c2] (store dir "two")]
    (is (= 1 (h/record! d s1 c1)))
    (is (= 2 (h/record! d s2 c2)))
    (let [gens (h/generations d)]
      (is (= [1 2] (mapv :gen gens)) "oldest first")
      (is (= [s1 s2] (mapv :service gens)))
      (is (= ["/nix/store/one-system" "/nix/store/two-system"]
             (mapv :system gens))
          "the system path is read out of each conf for display"))
    (fs/delete-tree dir)))

(deftest recording-the-same-pair-twice-is-a-no-op
  ;; install! records unconditionally, so redeploying an unchanged container
  ;; must not fill the history with duplicates.
  (let [dir (sup/tmpdir)
        d   (str (fs/path dir "history"))
        [s c] (store dir "one")]
    (is (= 1 (h/record! d s c)))
    (is (nil? (h/record! d s c)))
    (is (= 1 (count (h/generations d))))
    (testing "but a pair that differs in either half is a new generation"
      (let [[s2 _] (store dir "two")]
        (is (= 2 (h/record! d s2 c)))
        (is (= 3 (h/record! d s c)))))
    (fs/delete-tree dir)))

(deftest recording-needs-both-paths-to-exist
  (let [dir (sup/tmpdir)
        d   (str (fs/path dir "history"))
        [s c] (store dir "one")]
    (is (nil? (h/record! d nil c)))
    (is (nil? (h/record! d s (str (fs/path dir "gone.conf")))))
    (is (empty? (h/generations d)))
    (fs/delete-tree dir)))

(deftest generations-skips-entries-that-cannot-be-rolled-back-to
  (let [dir (sup/tmpdir)
        d   (str (fs/path dir "history"))
        [s1 c1] (store dir "one")
        [s2 c2] (store dir "two")
        [s3 c3] (store dir "three")]
    (run! (fn [[s c]] (h/record! d s c)) [[s1 c1] [s2 c2] [s3 c3]])
    (testing "a half-written entry, interrupted between its two links"
      (fs/delete (fs/path d "00002-conf"))
      (is (= [1 3] (mapv :gen (h/generations d)))))
    (testing "and one whose store path has been garbage collected"
      (fs/delete s1)
      (is (= [3] (mapv :gen (h/generations d)))))
    (fs/delete-tree dir)))

(deftest generation-numbers-are-never-reused
  ;; A reused number would make `ctr history` disagree with itself between two
  ;; runs, and a rollback target mean something different than when it was read.
  (let [dir (sup/tmpdir)
        d   (str (fs/path dir "history"))
        [s1 c1] (store dir "one")
        [s2 c2] (store dir "two")
        [s3 c3] (store dir "three")]
    (h/record! d s1 c1)
    (h/record! d s2 c2)
    (h/prune! d 1)
    (is (= [2] (mapv :gen (h/generations d))))
    (is (= 3 (h/record! d s3 c3)) "not 2 again")
    (fs/delete-tree dir)))

(deftest prune-keeps-the-newest-and-releases-the-rest
  (let [dir (sup/tmpdir)
        d   (str (fs/path dir "history"))]
    (doseq [tag ["one" "two" "three" "four"]]
      (apply h/record! d (store dir tag)))
    (h/prune! d 2)
    (is (= [3 4] (mapv :gen (h/generations d))))
    (is (= #{"00003-conf" "00003-service" "00004-conf" "00004-service"}
           (set (map fs/file-name (fs/list-dir d))))
        "both links of a dropped generation go, so its gcroot is released")
    (testing "keeping more than there are changes nothing"
      (h/prune! d 10)
      (is (= [3 4] (mapv :gen (h/generations d)))))
    (testing "and keeping none is refused rather than wiping the history"
      (h/prune! d 0)
      (is (= [3 4] (mapv :gen (h/generations d)))))
    (fs/delete-tree dir)))

(deftest prune-clears-out-unusable-entries-too
  (let [dir (sup/tmpdir)
        d   (str (fs/path dir "history"))
        [s1 c1] (store dir "one")]
    (h/record! d s1 c1)
    (apply h/record! d (store dir "two"))
    (fs/delete c1)
    (h/prune! d 10)
    (is (= ["00002-conf" "00002-service"]
           (sort (map fs/file-name (fs/list-dir d))))
        "the collected generation's links no longer pin anything")
    (fs/delete-tree dir)))

(deftest forget-drops-the-whole-history
  (let [dir (sup/tmpdir)
        d   (str (fs/path dir "history"))]
    (apply h/record! d (store dir "one"))
    (h/forget! d)
    (is (not (fs/exists? d)))
    (is (empty? (h/generations d)) "and an absent history reads as empty")
    (fs/delete-tree dir)))

;; nth-last and rows are what the `ctr rollback <name> 3` UI is made of, so they
;; are tested against plain maps rather than the filesystem.
(def ^:private fake-gens
  [{:gen 4 :system "/nix/store/a-system"}
   {:gen 5 :system "/nix/store/b-system"}
   {:gen 7 :system "/nix/store/c-system"}])

(deftest nth-last-counts-back-from-the-current-deployment
  (is (= 7 (:gen (h/nth-last fake-gens 1))) "1 is what is deployed now")
  (is (= 5 (:gen (h/nth-last fake-gens 2))) "2 is the one before it")
  (is (= 4 (:gen (h/nth-last fake-gens 3))))
  (testing "and refuses to wrap round or run off either end"
    (is (nil? (h/nth-last fake-gens 4)))
    (is (nil? (h/nth-last fake-gens 0)))
    (is (nil? (h/nth-last fake-gens -1)))
    (is (nil? (h/nth-last [] 1)))))

(deftest rows-number-the-newest-first
  (let [[header & body] (h/rows fake-gens nil)]
    (is (= ["#" "GEN" "DEPLOYED" "SYSTEM"] header))
    (is (= [["1" "7"] ["2" "5"] ["3" "4"]]
           (mapv #(subvec % 0 2) body))
        "the index a user types lines up with nth-last")
    (is (= "-" (nth (first body) 2)) "a generation with no timestamp")
    (is (= "/nix/store/c-system  (current)" (last (first body))))
    (is (= "/nix/store/b-system" (last (second body)))))
  (testing "limit shows only the newest few"
    (is (= [["1" "7"] ["2" "5"]]
           (mapv #(subvec % 0 2) (rest (h/rows fake-gens 2))))))
  (testing "a limit of 0 means all of them"
    (is (= 4 (count (h/rows fake-gens 0)))))
  (testing "and there is nothing to print without generations"
    (is (nil? (h/rows [] 10)))))
