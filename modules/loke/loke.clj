(ns loke
  "Local invoke, store dev and other invocations in a file just for you"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [babashka.process :as proc]
            [clojure.edn :as edn]))

(def shell (or (System/getenv "SHELL") "bash"))

(defmulti read-lokefile identity)

(defmethod read-lokefile "lokefile.edn"
  [filename]
  (with-open [r (java.io.PushbackReader. (io/reader filename))]
    (edn/read r)))

(defmethod read-lokefile "lokefile"
  [filename]
  (with-open [r (io/reader filename)]
    (reduce
     (fn [result line]
       (let [[cmd-name cmd] (str/split line #"=")
             name (-> cmd-name
                      (str/replace #"_CMD" "")
                      (str/lower-case)
                      keyword)]
         (update result name conj cmd)))
     {}
     (line-seq r))))

(def exit-reasons
  {:no-lokefile "No lokefile in directory"
   :bad-lokefile "Lokefile invalid"
   :lokefile-cmd-not-found "Lokefile command not found"
   :other "Unexcepted issue"})

(defn exit!
  ([reason]
   (exit! reason nil))
  ([reason context]
   (with-bindings [*out* *err*]
     (if context
       (println (reason exit-reasons (:other exit-reasons)))
       (println (reason exit-reasons (:other exit-reasons)) (pr-str context))))
   (System/exit 1)))

(defn wrap-find-lokefile
  [handler]
  (fn [context]
    (if-let [lokefile (->> ["lokefile.edn" "lokefile"]
                           (filter #(.exists (io/file %)))
                           first)]
      (handler (assoc context :lokefile-path lokefile))
      (exit! :no-lokefile))))


(def color-order [:red :green :blue :yellow :magenta :cyan])

(defn parse-lokefile
  [lokefile]
  (reduce-kv (fn [m k v]
               (->> (cond
                      (string? v) [{:name (name k)
                                    :cmd v}]

                      (and (sequential? v)
                           (string? (first v)))
                      (map-indexed (fn [i cmd]
                                     {:name (str (name k) "-" (inc i))
                                      :cmd cmd
                                      :color (nth (cycle color-order) i)}) v)

                      (and (sequential? v)
                           (map? (first v))
                           (contains? (first v) :cmd))
                      (map-indexed (fn [i v]
                                     (assoc v :color (nth (cycle color-order) i)))
                                   v)
                      :else
                      (throw (ex-info "Unknown configuration" {:key k
                                                               :value v})))
                    (assoc m k)))
             {}
             lokefile))

(defn wrap-add-lokefile
  [handler]
  (fn [{:keys [lokefile-path] :as context}]
    (handler (assoc context :lokefile (-> lokefile-path
                                          read-lokefile
                                          parse-lokefile)))))

(defn print-help
  [context]
  (let [cmd-name (str/replace (System/getenv "_") #"^.*/" "")]
    (println (str (format "%s help" cmd-name)))))

(defn list-commands
  [{:keys [lokefile]}]
  (println "loke - list commands")
  (doseq [[cmd-name cmds] lokefile]
    (println (name cmd-name))
    (doseq [cmd cmds]
      (println (apply str (repeat (count (name cmd-name)) " ")) "-" cmd))))

(defn process-stream-lines!
  [s f]
  (future
    (with-open [r (io/reader s)]
      (loop []
        (if-let [line (.readLine r)]
          (do (f line)
              (recur))
          (f :eof))))))

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

(defn print-line-wrapped
  [{:keys [name prompt-color text-color]}]
  (fn [line]
    (when (not= :eof line)
      (printf "%s %s\n"
              (color prompt-color (str "[" name "]"))
              (if text-color
                (color text-color line)
                line)))
    (flush)))

(defn start-output!
  [{:keys [name color]} {:keys [out err] :as proc}]
  (process-stream-lines! out (print-line-wrapped {:name name
                                                  :prompt-color color}))
  (process-stream-lines! err (print-line-wrapped {:name name
                                                  :prompt-color color})))

(defn run-one
  [{:keys [cmd dir]}]
  @(proc/process [shell "-c" cmd]
                 {:dir dir
                  :inherit true
                  :shutdown proc/destroy-tree}))

(defn run-many
  [cmds]
  (let [procs (for [{:keys [cmd dir]} cmds]
                (proc/process [shell "-c" cmd]
                              {:dir dir
                               :shutdown proc/destroy-tree}))]
    (doseq [[cmd proc] (zipmap cmds procs)]
      (start-output! cmd proc))
    (doseq [proc procs]
      (deref proc))))

(defn run-command
  [{:keys [lokefile]
    :as context} command]
  (let [cmds (get lokefile (keyword command))]
    (doseq [{:keys [cmd]} cmds]
      (println cmd))
    (if (= 1 (count cmds))
      (run-one (first cmds))
      (run-many cmds))))

(def commands
  {nil {:id :root
        :arguments [[:command {}]]
        :options {:help {}
                  :list {}}
        :handler (fn [{:keys [options arguments] :as context}]
                   (cond
                     (:help options)
                     (print-help context)
                     (:list options)
                     (list-commands context)
                     (:command arguments)
                     (run-command context (:command arguments))))}})

(defn parse-cmd-opts
  [commands args]
  (let [{:keys [options arguments]}
        (group-by (fn [arg]
                    (if (.startsWith arg "-")
                      :options
                      :arguments))
                  args)
        options (map #(str/replace % #"-" "") options)
        command (get commands
                     (keyword (first arguments))
                     (get commands nil))]
    (-> command
        (update :arguments #(reduce (fn [result [[arg-name _arg-spec] arg]]
                                      (assoc result arg-name arg))
                                    {}
                                    (map vector % arguments)))
        (update :options #(reduce (fn [result [opt-name _opt-spec]]
                                    (if (contains? (set options) (name opt-name))
                                      (assoc result opt-name true)
                                      result))
                                  {}
                                  %))
        (assoc :commands commands))))

(defn -main
  [& args]
  (let [command (parse-cmd-opts commands args)]
    ((-> (:handler command)
         (wrap-add-lokefile)
         (wrap-find-lokefile))
     command)))

(apply -main *command-line-args*)

;; Local Variables:
;; mode: clojure
;; End:
