(ns ecs-exec
  (:require ["@aws-sdk/client-ecs"
             :refer [ECSClient ListClustersCommand ListTasksCommand
                     DescribeTasksCommand]]
            ["execa" :refer [execa]]
            ["ink" :refer [render Text Box]]
            ["ink-select-input$default.default" :as SelectInput]
            ["ink-spinner$default.default" :as Spinner]
            ["ink-text-input" :refer [UncontrolledTextInput]]
            [applied-science.js-interop :as j]
            [reagent.core :as r]
            [promesa.core :as p]
            [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [tggreene.oeuvre :as o]
            [goog.string :refer [format]]))

(def term
  {:columns js/process.stdout.columns
   :rows js/process.stdout.rows})

(defonce state
  (r/atom {:stage :loading
           :clusters []}))

(def stages
  {:loading "Loading"
   :select-cluster "Select Cluster"
   :select-container "Select Container"
   :select-command "Select Command"
   :select-ports "Select Ports"})

(def commands
  [{:label "Shell"
    :value :shell}
   {:label "Port Forwarding"
    :value :port-forwarding}])

(defn select
  [{:keys [items label-fn on-select]
    :or {label-fn :label}}]
  [:> SelectInput
   {:items (for [item items]
             #js {:label (label-fn item)
                  :value item})
    :on-select #(on-select (.-value %))}])

(defn find-first
  [p coll]
  (if (map? p)
    (let [[k v] (first p)]
      (first (filter #(= v (k %)) coll)))
    (first (filter p coll))))

(def port-forward-doc-name
  "AWS-StartPortForwardingSession")

(defn port-forward-params
  [{:keys [target-port local-port]}]
  (-> {:portNumber [target-port]
       :localPortNumber [local-port]}
      clj->js
      js/JSON.stringify))

(defn ssm-target-string
  [{:keys [cluster-id task-id runtime-id]}]
  (str "ecs:" cluster-id "_" task-id "_" runtime-id))

(defn run-command!
  [{:keys [selected-command selected-container port-config ink]}]
  (when ink
    (.clear ink)
    (.unmount ink))
  (let [ssm-target (ssm-target-string selected-container)
        [command & args]
        (into ["aws"
               "ssm"
               "start-session"
               "--target"
               ssm-target]
              (when (= :port-forwarding selected-command)
                ["--document-name" port-forward-doc-name
                 "--parameters" (port-forward-params port-config)]))]
    (execa command
           (clj->js args)
           #js {:stdio "inherit"})))

(defn app
  []
  (let [{:keys [stage clusters selected-cluster selected-container]
         {:keys [local-port target-port]} :port-config} @state]
    (when (not= :run-command stage)
      [:> Box {:flex-direction :column}
       [:> Box
        [:> Text {:color :blue} (get stages stage)]]
       [:> Box {:margin-x 2
                :margin-y 1}
        (case stage
          :loading
          [:> Text {:color :green}
           [:> Spinner {:type :dots}]]

          :select-cluster
          [select {:items clusters
                   :label-fn :name
                   :on-select #(swap! state
                                      assoc
                                      :selected-cluster %
                                      :stage :select-container)}]

          :select-container
          [select {:items (->> clusters
                               (find-first {:arn (:arn selected-cluster)})
                               :containers)
                   :label-fn #(str (:name %) " (" (:instance-id %) ")")
                   :on-select #(swap! state
                                      assoc
                                      :selected-container %
                                      :stage :select-command)}]

          :select-command
          [select {:items commands
                   :on-select
                   (fn [{:keys [value]}]
                     (let [next-stage (if (= :port-forwarding value)
                                        :select-ports
                                        :run-command)]
                       (cond-> (swap! state
                                      assoc
                                      :selected-command value
                                      :stage next-stage)
                         (= :run-command next-stage)
                         (run-command!))))}]
          :select-ports
          [:> Box {:flex-direction :column}
           [:> Box
            [:> Box {:margin-right 1}
             [:> Text "Target Port:"]]
            [:> UncontrolledTextInput
             {:value (or target-port "")
              :focus (nil? target-port)
              :on-submit #(swap! state assoc-in [:port-config :target-port] %)}]]
           [:> Box
            [:> Box {:margin-right 1}
             [:> Text "Local Port:"]]
            [:> UncontrolledTextInput
             {:value (or local-port "")
              :focus (and (not (nil? target-port))
                          (nil? local-port))
              :on-submit #(do (swap! state assoc-in [:port-config :local-port] %)
                              (run-command! (swap! state assoc :stage :run-command)))}]]]
          nil)]
       [:> Box {:flex-direction :column}
        (when selected-cluster
          [:> Text {:color :yellow} (str "Cluster: " (:name selected-cluster))])
        (when selected-container
          [:> Text {:color :yellow} (str "Container: "
                                         (:name selected-container)
                                         " (" (:instance-id selected-container) ")")])]])))


(def ecs-client (ECSClient.))

(defn strip-arn-prefix
  [s]
  (str/replace s #"arn.+/" ""))

(defn strip-cluster-suffix
  [s]
  (str/replace s #"-Cluster.+" ""))

(defn cluster-arn->name
  [s]
  (-> s
      (strip-arn-prefix)
      (strip-cluster-suffix)))

(defn task-arn->task-id
  [s]
  (strip-arn-prefix s))

(defn task-arn->cluster-id
  [s]
  (second (str/split s #"/")))

(defn get-clusters
  []
  (p/let [cluster-arns
          (p/-> ecs-client
                (.send (ListClustersCommand. #js {}))
                (j/lookup)
                :clusterArns)]
    (for [cluster-arn cluster-arns
          :let [name (-> cluster-arn
                         strip-arn-prefix
                         strip-cluster-suffix)]]
      {:name name
       :arn cluster-arn})))

(defn get-containers
  [cluster]
  (p/let [tasks (p/-> ecs-client
                      (.send (ListTasksCommand. #js {:cluster cluster}))
                      (j/lookup)
                      :taskArns)]
    (when (seq tasks)
      (p/-> ecs-client
            (.send (DescribeTasksCommand. #js {:cluster cluster
                                               :tasks tasks}))
            (j/lookup)
            :tasks
            (->> (mapcat (comp :containers j/lookup))
                 (map (j/fn [^:js {:keys [name runtimeId taskArn] :as x}]
                        {:name name
                         :runtime-id runtimeId
                         :instance-id (str/replace runtimeId #".+-" "")
                         :task-id (task-arn->task-id taskArn)
                         :cluster-id (task-arn->cluster-id taskArn)})))))))

(defn cleanup-and-exit
  []
  (swap! state (fn [{:keys [ink]}]
                 (when ink
                   (.clear ink)
                   (.unmount ink))))
  (js/process.exit 1))


(defn start!
  [{:keys [cluster container ports]}]
  (p/let [_credentials (p/catch
                           (-> ecs-client .-config .credentials)
                           (fn [ex]
                             (println (.-message ex))
                             (cleanup-and-exit)))
          clusters (get-clusters)
          clusters
          (p/all
           (for [{:keys [arn] :as cluster} clusters]
             (p/let [containers (get-containers arn)]
               (assoc cluster :containers containers))))
          selected-cluster (when cluster
                             (o/find-first clusters #(re-find (re-pattern cluster) (:name %))))
          selected-container (when (and cluster container)
                               (o/find-first (:containers
                                              (find-first {:arn (:arn selected-cluster)}
                                                          clusters))
                                             #(re-find (re-pattern container) (:name %))))
          port-config (when ports
                        (zipmap [:target-port :local-port] (str/split ports #":")))
          selected-command (when (and selected-cluster
                                      selected-container)
                             (if port-config
                               :port-forwarding
                               :shell))
          stage (cond
                  selected-command :run-command
                  selected-container :select-command
                  selected-cluster :select-container
                  :else :select-cluster)]
    (cond->
        (swap! state
               merge
               {:clusters clusters
                :stage stage
                :selected-command (when (and selected-cluster
                                             selected-container)
                                    (if port-config
                                      :port-forwarding
                                      :shell))
                :selected-cluster selected-cluster
                :selected-container selected-container
                :port-config port-config})
      selected-command (run-command!))
    (when-not selected-command
      (swap! state assoc :ink (render (r/as-element [app]))))))

(defn spacer
  [n]
  (apply str (repeat n " ")))

(defn subs-seq
  [s n]
  (loop [xs []
         s s]
    (let [next-xs (conj xs (subs s 0 n))]
      (if-let [next-s (not-empty (subs s n))]
        (recur next-xs next-s)
        next-xs))))

(defn wrap-line
  [s limit left]
  (let [soft-limit (- limit left)]
    (loop [tokens (->> (str/split s #"\s")
                       (mapcat #(subs-seq % soft-limit)))
           current ""
           result ""]
      (if (seq tokens)
        (let [next (str current " " (first tokens))]
          (if (< (count next) soft-limit)
            (recur (rest tokens) next result)
            (recur tokens "" (str result "\n" (spacer left) current))))
        (str/trim (str result "\n" (spacer left) current))))))

(defn format-option
  [width [option description]]
  (prn 'dl (count description))
  (prn 'd description)
  (if description
    (format (str "    %-" width "s    %s\n")
            option
            (wrap-line description (:columns term) (+ width 8)))
    (format "    %s\n" option)))

(defn format-options
  [options]
  (let [options (->> options
                     (map (fn [[short long description]]
                            [(->> [short long]
                                  (remove nil?)
                                  (str/join ", "))
                             description])))
        width (apply max (map (comp count first) options))]
    (map #(format-option width %) options)))

(def cli-config
  {:name "ecs-exec"
   :description "Execute commands on ecs containers"
   :version "0.1.0"
   :options [["-cl" "--cluster CLUSTER" "Cluster name (matches against AWS results)"]
             ["-cn" "--container CONTAINER" "Container name (matches against AWS results)"]
             ["-p" "--ports PORTS" "Starts port-forwarding session, ports configuration, TARGET_PORT:LOCAL_PORT (e.g. -p 4000:4000)"]
             ["-h" "--help"]
             [nil "--version"]]})

(defn format-help
  []
  (let [{:keys [name version description options]} cli-config]
    (->> (format-options options)
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
      (:help options) (println (format-help))
      (:version options) (println (:version cli-config))
      :else (start! options))))

(apply -main *command-line-args*)
