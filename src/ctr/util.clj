(ns ctr.util
  "Process and error-handling helpers shared by the other namespaces."
  (:require [babashka.process :as p]
            [clojure.string :as str]))

(defn eprintln [& xs]
  (binding [*out* *err*] (apply println xs)))

(defn die
  "Abort with a message. Caught in ctr.main, which sets the exit code."
  [& msg]
  (throw (ex-info (str/join " " (map str msg)) {:ctr/exit 1})))

(defn- argv
  "Flatten nested seqs and drop nils, so callers can splice in collections."
  [args]
  (->> args flatten (remove nil?) (mapv str)))

(defn run
  "Run a command, capturing output. Returns {:exit :out :err} with output
   trimmed. Never throws on a non-zero exit."
  [& args]
  (let [{:keys [exit out err]} @(apply p/process {:out :string :err :string} (argv args))]
    {:exit exit :out (str/trim (or out "")) :err (str/trim (or err ""))}))

(defn check!
  "Run a command, capturing output, and die if it fails. Returns stdout."
  [& args]
  (let [{:keys [exit out err]} (apply run args)]
    (when-not (zero? exit)
      (die (str "Command failed: " (str/join " " (argv args)) "\n" err)))
    out))

(defn run-inherit!
  "Run a command with stdio connected to the terminal. Dies if it fails."
  [& args]
  (let [args (argv args)]
    (when-not (zero? (:exit @(apply p/process {:inherit true} args)))
      (die (str "Command failed: " (str/join " " args))))))

(defn run-indented
  "Run a command, echoing its combined output indented by `n` spaces.
   Failures are reported but not fatal, matching extra-container's `|| true`."
  [n & args]
  (let [pad (apply str (repeat n \space))
        {:keys [out err]} (apply run args)]
    (doseq [line (remove str/blank? (str/split-lines (str out "\n" err)))]
      (println (str pad line)))))

(defn root? []
  (= "0" (:out (run "id" "-u"))))

(defn getenv
  ([k] (getenv k nil))
  ([k default] (or (not-empty (System/getenv k)) default)))

(defn print-list
  "Print a heading followed by one item per line, then a blank line."
  [heading items]
  (println heading)
  (doseq [i items] (println i))
  (println))

(defn shell-quote
  "Single-quote a string for safe interpolation into a bash command."
  [s]
  (str "'" (str/replace (str s) "'" "'\\''") "'"))

(defn format-table
  "Render rows of strings as space-padded columns, two spaces between them.
   Returns a seq of lines; the last column is not padded, so there is never
   trailing whitespace."
  [rows]
  (when (seq rows)
    (let [widths (apply mapv (fn [& col] (apply max (map count col))) rows)]
      (for [row rows]
        (->> (map-indexed (fn [i cell]
                            (if (= i (dec (count row)))
                              cell
                              (format (str "%-" (widths i) "s") cell)))
                          row)
             (str/join "  "))))))

(defn capture-out!
  "Run a command with stderr connected to the terminal (so build progress is
   visible) and stdout captured. Dies if the command fails."
  [{:keys [extra-env]} & args]
  (let [args (->> args flatten (remove nil?) (mapv str))
        {:keys [exit out]} @(apply p/process
                                   (cond-> {:out :string :err :inherit}
                                     extra-env (assoc :extra-env extra-env))
                                   args)]
    (when-not (zero? exit)
      (die (str "Command failed: " (str/join " " args))))
    (str/trim out)))

(defn nix-string
  "Quote a string as a Nix string literal."
  [s]
  (str \" (-> (str s)
              (str/replace "\\" "\\\\")
              (str/replace "\"" "\\\"")
              (str/replace "$" "\\$"))
       \"))

(defn exec-status
  "Run a command with stdio connected to the terminal and return its exit code.
   Unlike run-inherit!, a non-zero exit is a result rather than an error."
  [{:keys [extra-env]} & args]
  (:exit @(apply p/process
                 (cond-> {:inherit true} extra-env (assoc :extra-env extra-env))
                 (->> args flatten (remove nil?) (mapv str)))))
