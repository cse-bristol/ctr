(ns ctr.nix-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ctr.nix :as nix]
            [ctr.support :as sup]
            [ctr.util :as u]))

(deftest recognises-flake-references
  (is (nix/flake-ref? ".#containers"))
  (is (nix/flake-ref? "github:user/repo"))
  (is (nix/flake-ref? "path:/tmp/x"))
  (is (not (nix/flake-ref? "./containers.nix")))
  (testing "a directory holding a flake.nix counts even without a fragment"
    (let [dir (sup/tmpdir)]
      (spit (str (fs/path dir "flake.nix")) "{}")
      (is (nix/flake-ref? (str dir))))))

(deftest classifies-config-sources
  (let [dir (sup/tmpdir)
        file (str (fs/path dir "containers.nix"))]
    (spit file "{}")
    (is (= :expr  (nix/source-kind nil {:expr "{}"})))
    (is (= :flake (nix/source-kind nil {:flake ".#x"})))
    (is (= :flake (nix/source-kind ".#x" {})))
    (is (= :file  (nix/source-kind file {})))
    (is (= :unknown (nix/source-kind (str (fs/path dir "nope")) {})))
    (testing "a store path already holding etc/ is used as-is"
      (let [built (sup/etc-out (sup/tmpdir) {"a" (sup/conf)})]
        (is (= :prebuilt (nix/source-kind built {})))))
    (testing "an explicit --flake wins over a positional argument"
      (is (= :flake (nix/source-kind file {:flake ".#x"}))))))

(deftest builds-the-expected-nix-expression
  (let [expr (nix/build-expr "{ containers.a = {}; }" {})]
    (is (str/includes? expr "systemConfig = cfg"))
    (is (str/includes? expr "reducedModules = true"))
    (is (str/includes? expr "config.system.build.etc"))))

(deftest full-eval-switches-off-the-reduced-module-set
  (is (str/includes? (nix/build-expr "{}" {:full-eval true}) "reducedModules = false")))

(deftest attribute-paths-are-split-on-dots
  ;; nix-build -A a.b selects a nested attribute; extra-container's
  ;; `.${''a.b''}` instead looks for one attribute literally named "a.b".
  (is (str/includes? (nix/build-expr "x" {:attr "a.b"}) ".${\"a\"}.${\"b\"}"))
  (is (not (str/includes? (nix/build-expr "x" {}) ".${"))))

(deftest nixpkgs-can-be-overridden
  (is (str/includes? (nix/build-expr "x" {:nixpkgs-path "/tmp/np"})
                     "nixosPath = \"${toString (/tmp/np)}/nixos\""))
  (is (str/includes? (nix/build-expr "x" {:nixos-path "/tmp/np/nixos"})
                     "nixosPath = /tmp/np/nixos")))

(deftest the-default-nixos-path-is-a-lookup-path-including-the-subdirectory
  ;; "<nixpkgs>/nixos" is valid syntax -- it is <nixpkgs> applied to the path
  ;; /nixos -- so it fails only when a build evaluates it.
  (if-let [np (u/getenv "CTR_NIXPKGS")]
    (is (= (str np "/nixos") (#'nix/nixos-path-expr {})))
    (is (= "<nixpkgs/nixos>" (#'nix/nixos-path-expr {})))))

(deftest every-nixos-path-expression-evaluates-to-a-path
  ;; Parsing is not enough to catch the bug above; these have to be evaluated.
  (if-not (fs/which "nix")
    (println "  (skipping nixos-path evaluation: nix is not on PATH)")
    (doseq [opts [{}
                  {:nixpkgs-path (or (u/getenv "CTR_NIXPKGS") "<nixpkgs>")}
                  {:nixos-path (if-let [np (u/getenv "CTR_NIXPKGS")]
                                 (str np "/nixos")
                                 "<nixpkgs/nixos>")}]]
      (let [expr (#'nix/nixos-path-expr opts)
            {:keys [exit out err]} (u/run "nix" "eval" "--impure" "--raw"
                                          "--expr" (str "toString (" expr ")"))]
        (is (zero? exit) (str expr " -> " err))
        (is (str/ends-with? out "/nixos") (str expr " -> " out))))))

(deftest the-install-dir-convention-is-passed-to-the-evaluation
  ;; The nixos-containers module picks /etc/containers vs /etc/nixos-containers
  ;; from the stateVersion of the config being evaluated, so ctr has to say
  ;; which one the target host wants.
  (is (str/includes? (nix/build-expr "x" {:legacy-install-dirs true})
                     "legacyInstallDirs = true"))
  (is (str/includes? (nix/build-expr "x" {:legacy-install-dirs false})
                     "legacyInstallDirs = false"))
  (testing "and defaults to what this host uses"
    (is (str/includes? (nix/build-expr "x" {})
                       (str "legacyInstallDirs = "
                            (if (ctr.container/legacy-install-dirs?) "true" "false"))))))

(deftest a-lone-dash-reads-the-config-from-stdin
  ;; So that `ctr new-config web | ctr create -` works.
  (is (= :stdin (nix/source-kind "-" {}))))
