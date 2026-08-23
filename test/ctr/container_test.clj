(ns ctr.container-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ctr.container :as c]
            [ctr.support :as sup]))

(deftest scan-finds-and-sorts-containers
  (let [dir (sup/tmpdir)
        etc (sup/etc-out dir {"zeta" (sup/conf) "alpha" (sup/conf :auto-start true)})
        cs  (c/scan etc)]
    (is (= ["alpha" "zeta"] (mapv :name cs)) "sorted by name")
    (is (= [true false] (mapv :auto-start? cs)) "AUTO_START=1 is read from the conf")
    (is (= "/etc/systemd-mutable/system/container@alpha.service"
           (:service-dest (first cs))))
    (is (= "/etc/nixos-containers/alpha.conf" (:conf-dest (first cs))))))

(deftest scan-rejects-an-etc-without-containers
  (let [dir (sup/tmpdir)]
    (fs/create-dirs (fs/path dir "etc" "systemd" "system"))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No container services"
                          (c/scan (str dir))))
    (testing "and an etc that doesn't exist at all"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No container services"
                            (c/scan "/nonexistent"))))))

;; classify compares a freshly built container against what is installed. The
;; fixtures put both sides in temp dirs and hand classify the paths directly.
(defn- pair
  "Build src and installed trees for one container, mirroring what install!
   does: the installed paths are symlinks to the built ones, so realpath
   comparison works. Pass dest-* to simulate a previously installed, different
   version."
  [{:keys [src-conf dest-conf src-service dest-service install?]
    :or {install? true}}]
  (let [dir (sup/tmpdir)
        w   (fn [sub content]
              (let [p (fs/path dir sub)]
                (fs/create-dirs (fs/parent p))
                (spit (str p) content)
                (str p)))
        link (fn [sub target]
               (let [p (fs/path dir sub)]
                 (fs/create-dirs (fs/parent p))
                 (fs/create-sym-link p target)
                 (str p)))
        s-src (w "src/service" (or src-service "unit"))
        c-src (w "src/conf" src-conf)]
    (cond-> {:name "t" :service-src s-src :conf-src c-src}
      install?
      (assoc :service-dest (if dest-service
                             (w "old/service" dest-service)
                             (link "dest/service" s-src))
             :conf-dest    (if dest-conf
                             (w "old/conf" dest-conf)
                             (link "dest/conf" c-src)))
      (not install?)
      (assoc :service-dest (str (fs/path dir "dest/service"))
             :conf-dest    (str (fs/path dir "dest/conf"))))))

(deftest classify-detects-no-change
  (is (= :unchanged (c/classify (pair {:src-conf (sup/conf)})))
      "installed symlinks still point at the built store paths")
  (testing "sameness is proved by realpath, not by content"
    ;; Two confs with equal content at different paths fall through to the
    ;; SYSTEM_PATH comparison and come out :system-only, costing a harmless
    ;; in-place switch. In practice equal inputs produce equal store paths, so
    ;; this only shows up with hand-built fixtures like this one.
    (is (= :system-only (c/classify (pair {:src-conf (sup/conf)
                                           :dest-conf (sup/conf)}))))))

(deftest classify-detects-a-changed-system-only
  (let [p (pair {:src-conf  (sup/conf :system-path "/nix/store/new-system")
                 :dest-conf (sup/conf :system-path "/nix/store/old-system")})]
    (is (= :system-only (c/classify p))
        "only SYSTEM_PATH differs, so the container can be switched in place")))

(deftest classify-detects-a-changed-container-config
  (let [p (pair {:src-conf  (sup/conf :private-network true)
                 :dest-conf (sup/conf :private-network false)})]
    (is (= :changed (c/classify p))
        "a real config change needs a restart, not a switch"))
  (testing "a changed unit file alone is enough"
    (let [p (pair {:src-conf (sup/conf) :src-service "a" :dest-service "b"})]
      (is (= :changed (c/classify p))))))

(deftest classify-treats-a-missing-installation-as-changed
  (let [p (pair {:src-conf (sup/conf) :install? false})]
    (is (= :changed (c/classify p)))))

(deftest with-state-annotates-every-container
  (let [dir (sup/tmpdir)
        etc (sup/etc-out dir {"a" (sup/conf) "b" (sup/conf)})]
    (is (= [:changed :changed] (mapv :state (c/with-state (c/scan etc))))
        "nothing is installed under /etc in the test environment")))

(deftest scan-walks-the-symlinks-a-real-etc-output-is-made-of
  ;; etc/systemd/system is itself a symlink into the system-units store path,
  ;; and each unit inside it is a symlink too. A tree walk that doesn't follow
  ;; links silently finds nothing here.
  (let [etc (sup/etc-out (sup/tmpdir) {"a" (sup/conf)})
        cs  (c/scan etc)]
    (is (= ["a"] (mapv :name cs)))
    (is (not (some #{"container@.service"} (map fs/file-name (map :service-src cs))))
        "the container@.service template is not a container")))

;; Install directories: /etc/nixos-containers (NixOS >= 22.05) or the legacy
;; /etc/containers. The only runtime record of the host's choice is the
;; nixos-container binary, so detect-dirs is tested against stand-ins for it.
(deftest install-dirs-follow-the-nixos-container-binary
  (let [dir (sup/tmpdir)
        at  (fn [marker] (c/detect-dirs (sup/nixos-container-script dir marker)))]
    (testing "NixOS >= 22.05"
      (let [d (at "my $configurationDirectory = \"/etc/nixos-containers\";")]
        (is (false? (:legacy? d)))
        (is (= "/etc/nixos-containers" (:conf-dir d)))
        (is (= "/var/lib/nixos-containers" (:state-dir d)))
        (is (= "containers" (:other-conf-name d)))))
    (testing "NixOS < 22.05"
      (let [d (at "my $configurationDirectory = \"/etc/containers\";")]
        (is (true? (:legacy? d)))
        (is (= "/etc/containers" (:conf-dir d)))
        (is (= "/var/lib/containers" (:state-dir d)))
        (is (= "nixos-containers" (:other-conf-name d)))))
    (fs/delete-tree dir)))

(deftest an-unrecognisable-nixos-container-warns-and-assumes-modern
  ;; extra-container reads a missing marker as legacy, which would misfire on a
  ;; makeWrapper shim. Requiring the positive legacy marker fails safe instead.
  (let [dir (sup/tmpdir)]
    (doseq [nc [(sup/nixos-container-script dir "exec /nix/store/x/bin/real \"$@\"")
                (str (fs/path dir "does-not-exist"))
                nil]]
      (is (false? (:legacy? (c/detect-dirs nc)))))
    (fs/delete-tree dir)))

(deftest scan-reads-the-legacy-conf-directory-when-the-host-is-legacy
  (let [dir (sup/tmpdir)
        etc (sup/etc-out dir {"a" (sup/conf)} :conf-name "containers")]
    (with-redefs [c/conf-name (constantly "containers")
                  c/conf-dir  (constantly "/etc/containers")]
      (let [cs (c/scan etc)]
        (is (= ["a"] (mapv :name cs)))
        (is (str/ends-with? (:conf-src (first cs)) "/etc/containers/a.conf"))
        (is (= "/etc/containers/a.conf" (:conf-dest (first cs))))))
    (fs/delete-tree dir)))

(deftest installing-a-mismatched-build-explains-the-mismatch
  ;; A config built for the other convention lands in the other directory;
  ;; "file doesn't exist" would send you looking in the wrong place.
  (let [dir (sup/tmpdir)
        etc (sup/etc-out dir {"a" (sup/conf)} :conf-name "containers")
        ctr (with-redefs [c/conf-name (constantly "containers")]
              (first (c/scan etc)))]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"built for NixOS < 22.05 install directories"
         (c/install! (assoc ctr :conf-src (str/replace (:conf-src ctr)
                                                       "/containers/"
                                                       "/nixos-containers/")))))
    (fs/delete-tree dir)))

(deftest read-conf-parses-the-fields-list-needs
  (let [dir (sup/tmpdir)
        at  (fn [content] (let [p (fs/path dir "c.conf")] (spit (str p) content)
                               (c/read-conf (str p))))]
    (testing "a private container with a static address"
      (is (= {:auto-start? true :private-network? true
              :host-bridge nil
              :host-address "10.233.1.1" :local-address "10.233.1.2"}
             (at (sup/conf :auto-start true :private-network true
                           :host-address "10.233.1.1"
                           :local-address "10.233.1.2")))))
    (testing "a host-network container"
      (is (= {:auto-start? false :private-network? false
              :host-bridge nil
              :host-address nil :local-address nil}
             (at (sup/conf)))))
    (testing "private but with no address: bridged or DHCP"
      (is (= {:auto-start? false :private-network? true
              :host-bridge nil
              :host-address nil :local-address nil}
             (at (sup/conf :private-network true)))))
    (testing "a container bridged onto an interface"
      (is (= {:auto-start? false :private-network? true
              :host-bridge "br0"
              :host-address nil :local-address "192.168.1.50"}
             (at "PRIVATE_NETWORK=1\nHOST_BRIDGE=br0\nLOCAL_ADDRESS=192.168.1.50/24\n"))))
    (testing "PRIVATE_NETWORK absent means host network, as the module emits"
      (is (not (:private-network? (at "SYSTEM_PATH=/nix/store/x\n")))))
    (testing "and an explicit 0, as `nixos-container create` writes"
      (is (not (:private-network? (at "PRIVATE_NETWORK=0\n")))))
    (testing "a conf that isn't there yields all-nil rather than throwing"
      (is (= {:auto-start? false :private-network? false
              :host-bridge nil
              :host-address nil :local-address nil}
             (c/read-conf (str (fs/path dir "absent.conf"))))))
    (fs/delete-tree dir)))

(deftest list-rows-renders-status-address-and-autostart
  (let [confs {"db"   {:private-network? true :local-address "10.233.2.2"}
               "prox" {:private-network? false}
               "brg"  {:private-network? true :auto-start? true}}]
    (is (= [["NAME" "STATUS" "ADDRESS" "AUTOSTART"]
            ["db"   "up"     "10.233.2.2" "no"]
            ["prox" "down"   "host"       "no"]
            ["brg"  "up"     "-"          "yes"]]
           (c/list-rows ["db" "prox" "brg"] #{"db" "brg"} confs)))))

(deftest read-conf-handles-ipv6-and-prefix-lengths
  (let [dir (sup/tmpdir)
        at  (fn [content] (let [p (fs/path dir "c.conf")] (spit (str p) content)
                            (c/read-conf (str p))))]
    (testing "an empty v4 line falls through to the v6 one"
      ;; "" is truthy in Clojure, so this has to be an explicit emptiness check.
      (is (= "fd00::2" (:local-address (at "PRIVATE_NETWORK=1\nLOCAL_ADDRESS=\nLOCAL_ADDRESS6=fd00::2\n")))))
    (testing "a prefix length is dropped, as nixos-container show-ip does"
      (is (= "10.233.1.2" (:local-address (at "LOCAL_ADDRESS=10.233.1.2/24\n")))))
    (fs/delete-tree dir)))
