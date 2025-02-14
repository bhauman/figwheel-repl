(ns figwheel.server.jetty-websocket
  (:require
   [clojure.string :as string]
   [ring.adapter.jetty :as jt]
   [ring.websocket :as ws]))

(defn set-log-level! [log-lvl]
  (when log-lvl
    (println (format "run-jetty: Skipping (set-log-level! %s). Jetty now uses SLF4J to configure logging."
                     log-lvl))))

(defn run-jetty [handler {:keys [log-level configurator] :as options}]
  (let [configurator'
        (if-let [config-fn
                 (and (symbol? configurator)
                      (try
                        (resolve configurator)
                        (catch Throwable t
                          )))]
          config-fn
          (do
            (when configurator
              (println "Unable to resolve Jetty :configurator"
                       (pr-str configurator)))
            identity))]
    (jt/run-jetty
     handler
     (assoc options
            :configurator
            (fn [server]
              (configurator' server)
              (set-log-level! log-level))))))

(defn websocket-middleware [handler path {:keys [on-connect on-receive on-close] :as abstract-conn}]
  (fn [request]
    ;; were going to put this on a context handler
    (if (and (= (:uri request) path) (ws/upgrade-request? request))
      {::ws/listener
       {:on-open    (fn [socket]
                      (on-connect
                       {:request request
                        :send-fn
                        (fn
                          ([string-message] (ws/send socket string-message))
                          ([string-message succ-f fail-f]
                           (ws/send socket string-message succ-f fail-f)))
                        :close-fn (fn [] (ws/close socket))
                        :is-open-fn (fn [conn] (ws/open? socket))}))
        :on-message (fn [socket message] (on-receive message))
        :on-close   (fn [socket reason] (on-close reason))}}
      (handler request))))

;; these default options assume the context of starting a server in development-mode
;; from the figwheel repl
(def default-options {:join? false
                      :thread-idle-timeout (* 1000 60 60 100)
                      :max-idle-time (* 1000 60 60 100) })

(defn run-server [handler options]
  (run-jetty
   handler
   (merge default-options options)))

(comment
  (defonce scratch (atom {}))
  (-> @scratch :adaptor (.. getSession getUpgradeRequest) build-request-map))

#_(def server (run-jetty
               (fn [ring-request]
                 {:status 200
                  :headers {"Content-Type" "text/html"}
                  :body "Received Yep"})
               {:port 9500
                :join? false
                #_:async-handlers #_{"/figwheel-connect"
                                 (fn [ring-request send err]
                                   (swap! scratch assoc :adaptor3 ring-request)
                                   (send {:status 200
                                          :headers {"Content-Type" "text/html"}
                                          :body "Wowza"}))}

                #_:websockets #_{"/" {:on-connect (fn [adapt]
                                                (swap! scratch assoc :adaptor2 adapt))}}
                :configurator 'figwheel.server.jetty-websocket/sample-configurator}))

#_(.stop server)


;; TODOs!!!!
;; * get async up and running if possible
