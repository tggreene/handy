(ns envx
  (:require
   [babashka.curl :as curl]
   [babashka.process :refer [process] :as proc]
   [clojure.edn :as edn]
   [clojure.java.shell :as shell]
   [clojure.java.io :as io]
   [clojure.pprint :as pprint]
   [clojure.string :as str]))

(def default-config-paths
  '("envx.edn" "envx.local.edn"))

(defn resolve-config-path
  ([]
   (resolve-config-path nil))
  ([path]
   (cond->> default-config-paths
     path (cons path)
     :always (filter #(.exists (io/file %)))
     :always first)))

(defn sh
  [cmd]
  (let [{:keys [out exit err]} (apply shell/sh cmd)]
    (if (zero? exit)
      (str/trim out)
      (throw (ex-info err {:cmd cmd
                           :err (str/trim err)
                           :exit exit
                           :out (str/trim out)})))))

(defn get-from-pass
  [key]
  (sh ["pass" key]))

(defn read-config
  [config-path]
  (edn/read-string (slurp config-path)))

(def protocols
  {"unix-pass" "(unix-pass)://"
   "curl" "(curl)-(?=https?://)"
   "exec" "(exec)://"
   "load-file" "(load-file)://"})

(def protocol-regex
  (re-pattern (str/join "|" (vals protocols))))

(defn get-protocol
  [s]
  (->> (re-find protocol-regex s)
       (remove nil?)
       (last)))

(defn strip-protocol
  [s]
  (str/replace s protocol-regex ""))

(defn remove-blank-keys
  [m]
  (reduce-kv (fn [m k v]
               (if (and (string? v) (str/blank? v))
                  m
                  (assoc m k v)))
             {}
             m))

(defn prepend-str
  [s to-prepend]
  (->> (str/replace s
                    #"\n"
                    (fn [s]
                      (str s to-prepend)))
       (str to-prepend)))

(defn indent-str
  [s n]
  (prepend-str s (apply str (repeat n " "))))

(defn color
  [color s]
  (let [color (case color
                :black "\u001b[30m"
                :red "\u001b[31m"
                :green "\u001b[32m"
                :yellow "\u001b[33m"
                :blue "\u001b[34m"
                :magenta "\u001b[35m"
                :cyan "\u001b[36m"
                :white "\u001b[37m"
                "")
        reset "\u001b[0m"]
    (str color s reset)))

(defn printlnerr
  [s]
  (println (color :red (prepend-str s "[envx] "))))

(defn format-sh-ex
  [ex]
  (-> ex
      ex-data
      remove-blank-keys
      pprint/pprint
      with-out-str
      (indent-str 4)))

(defmulti resolve-value get-protocol)

(defmethod resolve-value :default [v] v)

(defmethod resolve-value "unix-pass"
  [v]
  (try
    (-> v strip-protocol get-from-pass)
    (catch Exception e
      (printlnerr (format "Couldn't resolve %s from unix-pass:\n\n%s" v (format-sh-ex e)))
      (flush))))

(defmethod resolve-value "curl"
  [v]
  (-> v strip-protocol curl/get :body str/trim))

(defmethod resolve-value "load-file"
  [v]
  (-> v strip-protocol io/file slurp str/trim))

(defmethod resolve-value "exec"
  [v]
  (-> v strip-protocol (str/split #"\s") sh))

(defn resolve-env
  [env]
  (reduce-kv (fn [result k v]
               (assoc result (name k) (resolve-value v)))
             {}
             env))

(defn run-with-env!
  [cmd env]
  (process [(System/getenv "SHELL") "-c" (str/join " " cmd)]
           {:inherit true
            :shutdown proc/destroy-tree
            :extra-env env}))

(defn -main
  [& args]
  (if-let [config-path (resolve-config-path)]
    (let [config (read-config config-path)
          env (resolve-env config)]
      @(run-with-env! args env))
    (println "No config file found"))
  nil)

(apply -main *command-line-args*)
