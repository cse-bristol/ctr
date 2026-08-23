(ns ctr.util-test
  (:require [clojure.test :refer [deftest is testing]]
            [ctr.util :as u]))

;; babashka.process takes its command as varargs after the options map; passing
;; a vector as a single argument stringifies it into a "[bash ..." program name.
;; Every spawn goes through these helpers so that mistake can only be made once.
(deftest exec-status-returns-the-exit-code
  (is (= 0 (u/exec-status {} "true")))
  (is (= 3 (u/exec-status {} "bash" "-c" "exit 3")))
  (testing "arguments are passed through, not concatenated"
    (is (= 0 (u/exec-status {} "bash" "-c" "[ \"$1\" = arg ]" "sh" "arg")))))

(deftest exec-status-passes-extra-env
  (is (= 0 (u/exec-status {:extra-env {"CTR_TEST_VAR" "yes"}}
                          "bash" "-c" "[ \"$CTR_TEST_VAR\" = yes ]")))
  (is (= 1 (u/exec-status {} "bash" "-c" "[ -n \"$CTR_TEST_VAR\" ]"))))

(deftest run-captures-output-without-throwing
  (let [{:keys [exit out err]} (u/run "bash" "-c" "echo out; echo err >&2; exit 7")]
    (is (= 7 exit))
    (is (= "out" out))
    (is (= "err" err)))
  (testing "nested collections and nils are flattened away"
    (is (= "a b" (:out (u/run "echo" ["a" nil "b"]))))))

(deftest check-dies-on-failure
  (is (= "hi" (u/check! "echo" "hi")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Command failed"
                        (u/check! "false"))))

(deftest nix-string-escapes-nix-syntax
  (is (= "\"plain\"" (u/nix-string "plain")))
  (is (= "\"a\\\"b\"" (u/nix-string "a\"b")))
  (is (= "\"\\$x\"" (u/nix-string "$x")) "antiquotation must not be introduced")
  (is (= "\"a\\\\b\"" (u/nix-string "a\\b"))))

(deftest format-table-pads-every-column-but-the-last
  (is (= ["NAME    STATUS  ADDRESS"
          "db      up      10.233.2.2"
          "longer  down    host"]
         (u/format-table [["NAME" "STATUS" "ADDRESS"]
                          ["db" "up" "10.233.2.2"]
                          ["longer" "down" "host"]])))
  (testing "so no line carries trailing whitespace"
    (is (every? #(= % (clojure.string/trimr %))
                (u/format-table [["a" "bbbb"] ["cccc" "d"]])))))

(deftest format-table-renders-nothing-for-no-rows
  ;; cmd-list relies on this: a header alone would look like output.
  (is (empty? (u/format-table []))))
