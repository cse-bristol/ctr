(ns ctr.newconfig-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ctr.newconfig :as nc]
            [ctr.support :as sup]
            [ctr.util :as u]))

(deftest the-first-container-gets-the-first-prefix
  (is (= "10.233.1" (nc/pick-prefix [] []))))

(deftest addresses-already-in-use-are-skipped
  (testing "either end of the pair is enough to rule a prefix out"
    (is (= "10.233.2" (nc/pick-prefix ["10.233.1.1"] [])))
    (is (= "10.233.2" (nc/pick-prefix ["10.233.1.2"] []))))
  (is (= "10.233.3" (nc/pick-prefix ["10.233.1.1" "10.233.2.2"] [])))
  (testing "gaps are reused"
    (is (= "10.233.2" (nc/pick-prefix ["10.233.1.1" "10.233.3.1"] [])))))

(deftest prefixes-overlapping-a-host-interface-are-skipped
  (is (= "10.233.2" (nc/pick-prefix [] ["10.233.1.7/24"])))
  (testing "an unrelated interface changes nothing"
    (is (= "10.233.1" (nc/pick-prefix [] ["192.168.1.4/24" "127.0.0.1/8"])))))

(deftest a-host-swallowing-the-whole-range-falls-back-rather-than-failing
  ;; 10.0.0.0/8 covers every candidate. Warning on stderr, but still a usable
  ;; prefix -- refusing to emit a config would be worse.
  (is (= "10.233.1" (nc/pick-prefix [] ["10.1.2.3/8"]))))

(deftest an-exhausted-range-is-an-error
  (let [used (mapcat (fn [n] [(str "10.233." n ".1")]) (range 1 255))]
    (is (thrown? clojure.lang.ExceptionInfo (nc/pick-prefix used [])))))

(deftest cidr-ranges-cover-the-whole-network
  (testing "the host bits are masked off, and the range is inclusive"
    (is (= (nc/cidr-range "10.233.1.0/32") (mapv identity [183042304 183042304])))
    (is (= (nc/cidr-range "10.233.1.7/24") [183042304 183042559])))
  (testing "a /8 spans sixteen million addresses"
    (let [[lo hi] (nc/cidr-range "10.1.2.3/8")]
      (is (= (- (Math/pow 2 24) 1) (double (- hi lo)))))))

(deftest used-addresses-reads-both-ends-of-every-conf
  (let [dir (sup/tmpdir)]
    (spit (str (fs/path dir "a.conf"))
          (sup/conf :private-network true
                    :host-address "10.233.4.1" :local-address "10.233.4.2"))
    (spit (str (fs/path dir "b.conf")) (sup/conf))
    (with-redefs [ctr.container/conf-dir (constantly dir)]
      (is (= #{"10.233.4.1" "10.233.4.2"} (set (nc/used-addresses)))))
    (fs/delete-tree dir)))

(def ^:private opts
  {:name "web" :prefix "10.233.1" :state-version "26.05" :date "2026-08-22"})

(deftest the-template-pins-the-state-version-and-the-address
  (let [t (nc/template opts)]
    (is (str/includes? t "containers.web = {"))
    (is (str/includes? t "extra.addressPrefix = \"10.233.1\";"))
    (is (str/includes? t "system.stateVersion = \"26.05\";"))
    (is (str/includes? t "autoStart = false;"))
    (is (str/includes? t "# host 10.233.1.1, container 10.233.1.2"))))

(deftest the-template-omits-the-network-when-there-is-no-prefix
  (let [t (nc/template (assoc opts :prefix nil :auto-start? true))]
    (is (not (str/includes? t "addressPrefix")))
    (is (not (str/includes? t "enableWAN")))
    (is (str/includes? t "autoStart = true;"))))

(deftest the-template-is-a-nix-expression-that-evaluates
  ;; Parsing alone is not enough: `<nixpkgs>/nixos` parses fine and fails only
  ;; on evaluation. Both forms of the template are checked.
  (if-not (fs/which "nix")
    (println "  (skipping template evaluation: nix is not on PATH)")
    (doseq [t [(nc/template opts) (nc/template (assoc opts :prefix nil))]]
      (let [dir (sup/tmpdir)
            f   (str (fs/path dir "c.nix"))]
        (spit f t)
        (let [{:keys [exit err]} (u/run "nix-instantiate" "--parse" f)]
          (is (zero? exit) err))
        ;; `containers.web.config` is a function, so force the attrset only.
        (let [{:keys [exit out err]}
              (u/run "nix" "eval" "--impure" "--raw" "--expr"
                     (str "builtins.concatStringsSep \",\""
                          " (builtins.attrNames (import " f ").containers)"))]
          (is (zero? exit) err)
          (is (= "web" out)))
        (fs/delete-tree dir)))))

(deftest container-names-are-validated
  (is (thrown? clojure.lang.ExceptionInfo (nc/generate "a b" {:no-network true})))
  (is (thrown? clojure.lang.ExceptionInfo (nc/generate "a/b" {:no-network true})))
  (testing "a valid name with an explicit version needs no host access"
    (is (str/includes? (nc/generate "a-b_1" {:no-network true :state-version "25.05"})
                       "containers.a-b_1"))))

(deftest names-that-are-not-nix-identifiers-are-quoted
  ;; Hostnames may start with a digit; Nix attribute names may not.
  (is (str/includes? (nc/template (assoc opts :name "9web")) "containers.\"9web\" = {"))
  (is (str/includes? (nc/template (assoc opts :name "web-1")) "containers.web-1 = {"))
  (when (fs/which "nix")
    (let [dir (sup/tmpdir)
          f   (str (fs/path dir "c.nix"))]
      (spit f (nc/template (assoc opts :name "9web")))
      (is (zero? (:exit (u/run "nix-instantiate" "--parse" f))))
      (fs/delete-tree dir))))
