(ns figwheel.server.jetty-websocket
  (:require
   [clojure.string :as string]
   [ring.adapter.jetty :as jt]
   [ring.util.jakarta.servlet :as servlet]
   [ring.websocket :as ws])
  (:import
   [java.time Duration]
   [jakarta.servlet AsyncContext]
   [jakarta.servlet.http HttpServlet HttpServletRequest HttpServletResponse]
   [org.eclipse.jetty.server Request Handler]
   [org.eclipse.jetty.server.handler HandlerList]
   [org.eclipse.jetty.servlet ServletContextHandler ServletHandler ServletHolder]
   ;; [org.eclipse.jetty.util.log Log StdErrLog] ;; no longer in jetty11
   [org.eclipse.jetty.websocket.api
    WebSocketAdapter
    WebSocketPingPongListener
    Session
    #_UpgradeRequest
    #_RemoteEndpoint]
   [org.eclipse.jetty.websocket.server
    #_JettyServerUpgradeRequest
    #_JettyServerUpgradeResponse
    JettyWebSocketCreator
    JettyWebSocketServerContainer]
   [org.eclipse.jetty.websocket.server.config
    JettyWebSocketServletContainerInitializer]))

;; ------------------------------------------------------
;; Jetty 9 Websockets
;; ------------------------------------------------------
;; basic code and patterns borrowed from
;; https://github.com/sunng87/ring-jetty9-adapter

(defn- do-nothing [& args])

(defn set-log-level! [log-lvl]
  (when log-lvl
    (println (format "run-jetty: Skipping (set-log-level! %s). Jetty now uses SLF4J to configure logging."
                     log-lvl))))

;; ------------------------------------------------------

#_(defn- proxy-ws-adapter
  [{:as ws-fns
    :keys [on-connect on-error on-text on-close on-bytes]
    :or {on-connect do-nothing
         on-error do-nothing
         on-text do-nothing
         on-close do-nothing
         on-bytes do-nothing}}]
  (proxy [WebSocketAdapter WebSocketPingPongListener] []
    (onWebSocketConnect [^Session session]
      (let [^WebSocketAdapter this this]
        (proxy-super onWebSocketConnect session))
      (on-connect this))
    (onWebSocketError [^Throwable e]
      (on-error this e))
    (onWebSocketText [^String message]
      (on-text this message))
    (onWebSocketClose [statusCode ^String reason]
      (let [^WebSocketAdapter this this]
        (proxy-super onWebSocketClose statusCode reason))
      (on-close this statusCode reason))
    (onWebSocketBinary [^bytes payload offset len]
      (on-bytes this payload offset len))))

#_(defn- reify-default-ws-creator
  [ws-fns]
  (reify JettyWebSocketCreator
    (createWebSocket [this _ _]
      (proxy-ws-adapter ws-fns))))

#_(defn proxy-ws-servlet
  [ws {:as _
       :keys [ws-max-idle-time
              ws-max-text-message-size]
       :or {ws-max-idle-time (* 7 24 60 60 1000) ;; a week long timeout; 16MB size
            ws-max-text-message-size (* 16 1024 1024)}}]
  (ServletHolder.
   (proxy [HttpServlet] []
     (doGet [req res]
       (let [creator (reify-default-ws-creator ws)
             container (JettyWebSocketServerContainer/getContainer (.getServletContext ^HttpServlet this))]
         (.setIdleTimeout container (Duration/ofMillis ws-max-idle-time))
         (.setMaxTextMessageSize container ws-max-text-message-size)
         (.upgrade container creator req res))))))

;; ------------------------------------------------------
;; borrowed from ring/ring-jetty-adapter

#_(defn- async-jetty-raise [^AsyncContext context ^HttpServletResponse response]
  (fn [^Throwable exception]
    (.sendError response 500 (.getMessage exception))
    (.complete context)))

#_(defn- async-jetty-respond [^AsyncContext context request response]
  (fn [response-map]
    (if (ring.websocket/websocket-response? response-map)
      (assert false "async websocket-response not implemented")
      (servlet/update-servlet-response response context response-map))))

#_(defn- async-proxy-handler ^ServletHandler [handler timeout]
  (proxy [ServletHandler] []
    (doHandle [_ ^Request base-request ^HttpServletRequest request response]
      (let [^AsyncContext context (.startAsync request)]
        (.setTimeout context timeout)
        (try
          (handler
           (servlet/build-request-map request)
           (async-jetty-respond context request response)
           (async-jetty-raise context response))
          (finally
            (.setHandled base-request true)))))))

;; ------------------------------------------------------

#_(defn async-websocket-configurator [{:keys [websockets async-handlers]}]
  (fn [server]
    (let [existing-handler (.getHandler server)
          ws-proxy-handlers
          (map (fn [[context-path handler-map]]
                 (doto (ServletContextHandler.)
                   (.setContextPath context-path)
                   (.setAllowNullPathInfo (get handler-map :allow-null-path-info true))
                   ;; note: need /* given ServletContextHandler context-path
                   (.addServlet ^ServletHolder (proxy-ws-servlet handler-map {}) "/*")
                   (JettyWebSocketServletContainerInitializer/configure nil)))
               websockets)
          async-proxy-handlers
          (map
           (fn [[context-path async-handler]]
             (let [{:keys [allow-null-path-info async-timeout]
                    :or {allow-null-path-info true async-timeout 0}}
                   (meta async-handler)]
               (doto (ServletContextHandler.)
                 (.setContextPath context-path)
                 (.setAllowNullPathInfo allow-null-path-info)
                 (.setHandler (async-proxy-handler async-handler async-timeout))
                 (JettyWebSocketServletContainerInitializer/configure nil))))
           async-handlers)
          contexts (doto (HandlerList.)
                     (.setHandlers
                      (into-array Handler
                                  (concat
                                   ws-proxy-handlers
                                   async-proxy-handlers
                                   [existing-handler]))))]
      (.setHandler server contexts)
      server)))

(defn run-jetty [handler {:keys [websockets async-handlers log-level
                                 configurator] :as options}]
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
     (if (or (not-empty websockets) (not-empty async-handlers))
       (assoc options :configurator
              (fn [server]
                (configurator' server)                
                (set-log-level! log-level)
                #_((async-websocket-configurator
                    (select-keys options [:websockets :async-handlers])) server)))
       (assoc options
              :configurator
              (fn [server]
                (configurator' server)
                (set-log-level! log-level)))))))

;; ------------------------------------------------------
;; Figwheel REPL adapter

(defn build-request-map [request]
  {:uri (.getPath (.getRequestURI request))
   :websocket? true
   :scheme (.getScheme (.getRequestURI request))
   :query-string (.getQueryString request)
   :origin (.getOrigin request)
   :host (.getHost request)
   :port (.getPort (.getRequestURI request))
   :request-method (keyword (.toLowerCase (.getMethod request)))
   :headers (reduce(fn [m [k v]]
                     (assoc m (keyword
                               (string/lower-case k)) (string/join "," v)))
                   {}
                   (.getHeaders request))})

;; TODO translate on close status's
;; TODO translate receiving bytes to on-receive
#_(defn websocket-connection-data [^WebSocketAdapter websocket-adaptor]
  {:request (build-request-map (.. websocket-adaptor getSession getUpgradeRequest))
   :send-fn (fn [string-message]
              (.. websocket-adaptor getRemote (sendString string-message)))
   :close-fn (fn [] (.. websocket-adaptor getSession close))
   :is-open-fn (fn [conn] (.. websocket-adaptor getSession isOpen))})

#_(defn adapt-figwheel-ws [{:keys [on-connect on-receive on-close] :as ws-fns}]
  (assert on-connect on-receive)
  (-> ws-fns
      (dissoc :on-connect :on-receive :on-close)
      (assoc  :on-connect (fn [websocket-adaptor]
                           (on-connect (websocket-connection-data websocket-adaptor)))
              :on-text (fn [_ data] (on-receive data))
              :on-close (fn [_ status reason] (on-close status)))))


#_(defn websocket-connection-data-new [^WebSocketAdapter websocket-adaptor]
  {:request (build-request-map (.. websocket-adaptor getSession getUpgradeRequest))
   :send-fn (fn [string-message]
              (.. websocket-adaptor getRemote (sendString string-message)))
   :close-fn (fn [] (.. websocket-adaptor getSession close))
   :is-open-fn (fn [conn] (.. websocket-adaptor getSession isOpen))})

#_(defn adapt-fig-to-ring [{:keys [on-connect on-receive on-close] :as ws-fns}]
  (assert on-connect on-receive)
  (-> ws-fns
      (dissoc :on-connect :on-receive :on-close)
      (assoc  :on-connect (fn [websocket-adaptor]
                            (on-connect (websocket-connection-data websocket-adaptor)))
              :on-text (fn [_ data] (on-receive data))
              :on-close (fn [_ status reason] (on-close status)))))

(defn websocket-middleware [handler [path {:keys [on-connect on-receive on-close] :as abstract-conn}]]
  (fn [request]
    (if (and (= (:uri request) path) (ws/upgrade-request? request))
      {::ws/listener
         {:on-open    (fn [socket]
                        (on-connect
                         {:request request
                          :send-fn (fn [string-message] (ws/send socket string-message))
                          :close-fn (fn [] (ws/close socket))
                          :is-open-fn (fn [conn] (ws/open? socket))}))
          :on-message (fn [socket message] (on-receive message))
          :on-close   (fn [socket reason] (on-close reason))}}
      (handler request))))

;; these default options assume the context of starting a server in development-mode
;; from the figwheel repl
(def default-options {:join? false})

(defn run-server [handler options]
  (run-jetty
   (cond-> handler
     (:figwheel.repl/abstract-websocket-connections options)
     (websocket-middleware (first (:figwheel.repl/abstract-websocket-connections options))))
   (merge default-options options)
   #_(cond-> (merge default-options options)
     
         (:figwheel.repl/abstract-websocket-connections options)
         (update :websockets
                   merge
                   (into {}
                         (map (fn [[path v]]
                                [path (adapt-figwheel-ws v)])
                              (:figwheel.repl/abstract-websocket-connections options)))))))

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
;; * make sure request maps are equal
;; * check that multiple window behavior is the same
;; * get async up and running if possible
