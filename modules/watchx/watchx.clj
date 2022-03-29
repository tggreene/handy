(ns watchx
  (:require [babashka.pods :as pods]
            [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [clojure.tools.cli :as cli]))

(pods/load-pod 'org.babashka/filewatcher "0.0.1")
(require '[pod.babashka.filewatcher :as fw])

(defn color
  [color s]
  (let [color (case color
                :red "\u001b[31m"
                :green "\u001b[32m"
                :yellow "\u001b[33m"
                :blue "\u001b[34m"
                :magenta "\u001b[35m"
                :cyan "\u001b[36m"
                :white "\u001b[37m"
                :black "\u001b[30m"
                "")
        reset "\u001b[0m"]
    (str color s reset)))

(defn println*
  [& args]
  (apply println (color :blue "[watchx]") args))

(defn watch!
  [{:keys [files exec]}]
  (let [targets
        (for [target files
              :let [cmd-fn (fn [path]
                             (let [cmd-replaced (str/replace exec "{}" path)
                                   cmd-parts (str/split cmd-replaced #" ")]
                               (try
                                 (apply sh cmd-parts)
                                 (catch Exception e
                                   {:exit 1
                                    :err (.getMessage e)}))))]]
          (if (.exists (io/file target))
            (do
              (println* "Watching" target)
              (fw/watch target
                        (fn [{:keys [path]}]
                          (let [{:keys [exit out err] :as x} (cmd-fn path)]
                            (if (zero? exit)
                              (println (str/trim out))
                              (println* (color :red (str/trim err))))))
                        {:delay-ms 50})
              true)
            (do (println* (str "Couldn't find " target))
                false)))]
    ;; Block indefinitely
    (if (some identity targets)
      @(promise)
      (println* "No watch tasks were run"))) )

(def cli-config
  {:name "watchx"
   :version "0.1.0"
   :description "Watch files and run commands when they change"
   :options [["-f" "--files FILES" "Files to watch, repeat for each file to watch"
              :multi true
              :default []
              :update-fn conj]
             ["-x" "--exec CMD" "Command to run, use {} for file reference, surround with string delimiters"]
             ["-h" "--help"]
             [nil "--version"]]})

(defn help
  []
  (let [{:keys [name version description options]} cli-config]
    (->> (for [[short long description] options
               :let [flags (->> [short long]
                                (remove nil?)
                                (str/join ", "))]]
           (if description
             (format "    %-20s %s\n" flags description)
             (format "    %s\n" flags)))
         (into ["NAME:\n"
                "  " name " - " description "\n\n"
                "VERSION:\n"
                "  " version "\n\n"
                "OPTIONS:\n"])
         (apply str))))

(defn -main
  [& args]
  (let [{:keys [options]} (cli/parse-opts args (:options cli-config))]
    (cond
      (:help options) (println (help))
      (and (seq (:files options))
           (seq (:exec options)))
      (watch! options)
      :else (println (help)))))

(apply -main *command-line-args*)
