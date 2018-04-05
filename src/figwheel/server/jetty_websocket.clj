(ns figwheel.server.jetty-websocket
  (:require
   [clojure.string :as string]
   [ring.adapter.jetty :as jt])
  (:import
   [org.eclipse.jetty.websocket.api
    WebSocketAdapter
    Session
    #_UpgradeRequest
    #_RemoteEndpoint]
   [org.eclipse.jetty.websocket.server WebSocketHandler]
   [org.eclipse.jetty.server Request Handler]
   [org.eclipse.jetty.server.handler
    ContextHandler
    ContextHandlerCollection
    HandlerList]
   [org.eclipse.jetty.websocket.servlet
    WebSocketServletFactory WebSocketCreator
    ServletUpgradeRequest ServletUpgradeResponse]))

;; ------------------------------------------------------
;; Jetty 9 Websockets
;; ------------------------------------------------------
;; basic code and patterns borrowed from
;; https://github.com/sunng87/ring-jetty9-adapter

(defn- do-nothing [& args])

(defn- proxy-ws-adapter
  [{:as ws-fns
    :keys [on-connect on-error on-text on-close on-bytes]
    :or {on-connect do-nothing
         on-error do-nothing
         on-text do-nothing
         on-close do-nothing
         on-bytes do-nothing}}]
  (proxy [WebSocketAdapter] []
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

(defn- reify-default-ws-creator
  [ws-fns]
  (reify WebSocketCreator
    (createWebSocket [this _ _]
      (proxy-ws-adapter ws-fns))))

(defn proxy-ws-handler
  "Returns a Jetty websocket handler"
  [{:as ws-fns
    :keys [ws-max-idle-time]
    :or {ws-max-idle-time (* 7 24 60 60 1000)}}] ;; a week long timeout
  (proxy [WebSocketHandler] []
    (configure [^WebSocketServletFactory factory]
      (-> (.getPolicy factory)
          (.setIdleTimeout ws-max-idle-time))
      (.setCreator factory (reify-default-ws-creator ws-fns)))
    (handle [^String target, ^Request request req res]
      (let [wsf (proxy-super getWebSocketFactory)]
        (if (.isUpgradeRequest wsf req res)
          (if (.acceptWebSocket wsf req res)
            (.setHandled request true)
            (when (.isCommitted res)
              (.setHandled request true)))
          (proxy-super handle target request req res))))))



(defn async-websocket-configurator [{:keys [websockets async-handlers]}]
  (fn [server]
    (let [existing-handler (.getHandler server)
          ws-proxy-handlers
          (map (fn [[context-path handler-map]]
                 (doto (ContextHandler. context-path)
                   (.setAllowNullPathInfo
                    (get handler-map :allow-null-path-info true))
                   (.setHandler (proxy-ws-handler handler-map))))
               websockets)
          async-proxy-handlers
          (map
           (fn [[context-path async-handler]]
             (let [{:keys [allow-null-path-info async-timeout]
                    :or {allow-null-path-info true async-timeout 0}}
                   (meta async-handler)]
               (doto (ContextHandler. context-path)
                 (.setAllowNullPathInfo allow-null-path-info)
                 (.setHandler (#'jt/async-proxy-handler async-handler async-timeout)))))
           async-handlers)
          contexts (doto (HandlerList.)
                     (.setHandlers
                      (into-array Handler (reverse (conj (concat ws-proxy-handlers
                                                                 async-proxy-handlers)
                                                         existing-handler)))))
          ;; good to know about this other pattern
          #_contexts #_(doto (ContextHandlerCollection.)
                         (.setHandlers (into-array [(doto (ContextHandler. path)
                                                      (.setAllowNullPathInfo true)
                                                      (.setHandler ws-handler))

                                                    (doto (ContextHandler. "/")
                                                      (.setHandler existing-handler))])))]
      (.setHandler server contexts))))

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

(defn websocket-connection-data [^WebSocketAdapter websocket-adaptor]
  {:request (build-request-map (.. websocket-adaptor getSession getUpgradeRequest))
   :send-fn (fn [string-message]
              (.. websocket-adaptor getRemote (sendString string-message)))
   :close-fn (fn [] (.. websocket-adaptor getSession close))
   :is-open-fn (fn [conn] (.. websocket-adaptor getSession isOpen))})

;; TODO translate on close status's
;; TODO translate receiving bytes to on-receive
(defn adapt-figwheel-ws [{:keys [on-connect on-receive on-close] :as ws-fns}]
  (assert on-connect on-receive)
  (-> ws-fns
      (dissoc :on-connect :on-receive)
      (assoc :on-connect (fn [websocket-adaptor]
                           (on-connect (websocket-connection-data websocket-adaptor)))
             :on-text (fn [_ data] (on-receive data)))))

(defn run-jetty [handler {:keys [websockets async-handlers] :as options}]
  (jt/run-jetty
   handler
   (cond-> options
     (or (not-empty websockets) (not-empty async-handlers))
     (assoc :configurator (async-websocket-configurator
                           (select-keys options
                                        [:websockets :async-handlers]))))))

(comment
  (defonce scratch (atom {}))
  (-> @scratch :adaptor (.. getSession getUpgradeRequest) build-request-map)
  )


#_(def server (run-jetty
               (fn [ring-request]
                 {:status 200
                  :headers {"Content-Type" "text/html"}
                  :body "Received Yep"})
               {:port 9500
                :join? false
                :async-handlers {"/figwheel-connect"
                                 (fn [ring-request send err]
                                   (swap! scratch assoc :adaptor3 ring-request)
                                   (send {:status 200
                                          :headers {"Content-Type" "text/html"}
                                          :body "Wowza"})
                                   )}
                :websockets {"/" {:on-connect (fn [adapt]
                                                (swap! scratch assoc :adaptor2 adapt ))}}
                #_:configurator #_(websocket-configurator )}))

#_(.stop server)
