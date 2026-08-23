(ns ctr.cli-test
  (:require [clojure.test :refer [deftest is testing]]
            [ctr.main :as main]))

(deftest build-args-consume-every-remaining-argument
  (is (= {:args ["cfg.nix"] :build-args ["--builders" "ssh://w"]}
         (main/split-greedy ["cfg.nix" "--build-args" "--builders" "ssh://w"])))
  (testing "and flags before it are still parsed"
    (is (= {:args [] :build-args ["-j" "4"]}
           (main/split-greedy ["--build-args" "-j" "4"])))))

(deftest argv-without-greedy-options-is-untouched
  (is (= {:args ["a" "-s" "--full-eval"]}
         (main/split-greedy ["a" "-s" "--full-eval"]))))

(deftest parses-every-create-flag
  (let [[args opts] (main/parse ["./c.nix" "-s" "-u" "-r" "-A" "attr"
                                 "-E" "{}" "--full-eval"]
                                @#'main/create-opts)]
    (is (= ["./c.nix"] args))
    (is (= {:start true :update-changed true :restart-changed true
            :attr "attr" :expr "{}" :full-eval true}
           opts))))

(deftest legacy-install-dirs-can-be-forced-either-way
  (testing "absent, so ctr.nix falls back to detecting the host"
    (is (nil? (:legacy-install-dirs (second (main/parse [] @#'main/create-opts))))))
  (is (true?  (:legacy-install-dirs
               (second (main/parse ["--legacy-install-dirs"] @#'main/create-opts)))))
  (is (false? (:legacy-install-dirs
               (second (main/parse ["--no-legacy-install-dirs"] @#'main/create-opts))))))

(deftest parses-new-config-flags
  (let [[args opts] (main/parse ["web" "--address-prefix" "10.1.2"
                                 "--no-network" "--auto-start"]
                                @#'main/newconfig-opts)]
    (is (= ["web"] args))
    (is (= {:address-prefix "10.1.2" :no-network true :auto-start true} opts))))

(deftest destroy-accepts-all
  (let [[args opts] (main/parse ["-a"] @#'main/destroy-opts)]
    (is (= [] args))
    (is (:all opts))))

;; `shell` and `run` bypass babashka.cli so that the container's own command
;; keeps its flags.
(deftest attach-args-take-the-command-verbatim
  (is (= ["web" {:timeout 90} ["ls" "-la" "--color"]]
         (main/parse-attach ["web" "--" "ls" "-la" "--color"])))
  (testing "the -- is optional"
    (is (= ["web" {:timeout 90} ["hostname"]]
           (main/parse-attach ["web" "hostname"]))))
  (testing "and only one is consumed"
    (is (= ["web" {:timeout 90} ["--" "x"]]
           (main/parse-attach ["web" "--" "--" "x"])))))

(deftest attach-args-parse-start-and-timeout-before-the-name
  (is (= ["web" {:timeout 5 :start true} []]
         (main/parse-attach ["--start" "--timeout" "5" "web"])))
  (testing "but not after it, where they belong to the command"
    (is (= ["web" {:timeout 90} ["--start"]]
           (main/parse-attach ["web" "--start"])))))

(deftest attach-args-reject-nonsense
  (is (thrown? clojure.lang.ExceptionInfo (main/parse-attach [])))
  (is (thrown? clojure.lang.ExceptionInfo (main/parse-attach ["--nope" "web"])))
  (is (thrown? clojure.lang.ExceptionInfo (main/parse-attach ["--timeout" "soon" "web"]))))
