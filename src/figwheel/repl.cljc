(ns figwheel.repl
  (:require
   [clojure.string :as string]
   #?@(:cljs [[figwheel.repl.logging :as log]
              [goog.object :as gobj]
              [goog.storage.mechanism.mechanismfactory :as storage-factory]
              [goog.Uri :as guri]
              [goog.string :as gstring]
              [goog.net.jsloader :as loader]
              [goog.net.XhrIo :as xhrio]
              [goog.array :as garray]
              [goog.json :as gjson]
              [goog.html.legacyconversions :as conv]
              [goog.userAgent.product :as product]]
       :clj [[clojure.data.json :as json]
             [clojure.set :as set]
             [clojure.edn :as edn]
             [clojure.java.browse :as browse]
             [cljs.repl]
             [cljs.stacktrace]
             [clojure.java.io :as io]
             [clojure.string :as string]
             [ring.middleware.cors :as cors]
             [figwheel.server.ring]
             [figwheel.server.jetty]]))
  (:import
   #?@(:cljs [goog.debug.Console
              [goog.Uri QueryData]
              [goog Promise]
              [goog.log]
              [goog.storage.mechanism HTML5SessionStorage]]
       :clj [[java.util.concurrent
              Executors
              ScheduledExecutorService
              TimeUnit
              ThreadFactory]
             java.net.URLDecoder
             [java.lang ProcessBuilder Process]
             [java.util.concurrent LinkedBlockingQueue]])))

(def default-port 9500)
(def default-ssl-port 9533)

#?(:cljs (do

;; TODO dev only

;; --------------------------------------------------
;; Logging
;; --------------------------------------------------
;;
;; Levels
;; goog.debug.Logger.Level.(SEVERE WARNING INFO CONFIG FINE FINER FINEST)
;;
;; set level (.setLevel logger goog.debug.Logger.Level.INFO)
;; disable   (.setCapturing log-console false)

           (defonce logger (log/get-logger "Figwheel REPL"))

           (defn ^:export console-logging []
             (log/console-logging))

           (defn debug [msg]
             (log/debug logger msg))

;; TODO dev
           #_(.setLevel logger goog.debug.Logger.Level.FINEST)

;; --------------------------------------------------------------
;; Bootstrap goog require reloading
;; --------------------------------------------------------------

           (declare queued-file-reload)

           (defn unprovide! [ns]
             (if (some? goog/debugLoader_)
               (let [path (.getPathFromDeps_ goog/debugLoader_ ns)]
                 (gobj/remove (.-written_ goog/debugLoader_) path)
                 (gobj/remove (.-written_ goog/debugLoader_) (str js/goog.basePath path)))
               (let [path (gobj/get js/goog.dependencies_.nameToPath ns)]
                 (gobj/remove js/goog.dependencies_.visited path)
                 (gobj/remove js/goog.dependencies_.written path)
                 (gobj/remove js/goog.dependencies_.written (str js/goog.basePath path)))))

;; this will not work unless bootstrap has been called
           (defn figwheel-require [src reload]
  ;; require is going to be called
             (set! (.-require js/goog) figwheel-require)
             (when (= reload "reload-all")
               (set! (.-cljsReloadAll_ js/goog) true))
             (when (or reload (.-cljsReloadAll_ js/goog))
               (unprovide! src))
             (let [res (.require_figwheel_backup_ js/goog src)]
               (when (= reload "reload-all")
                 (set! (.-cljsReloadAll_ js/goog) false))
               res))

           (defn bootstrap-goog-base
             "Reusable browser REPL bootstrapping. Patches the essential functions
  in goog.base to support re-loading of namespaces after page load."
             []
  ;; The biggest problem here is that clojure.browser.repl might have
  ;; patched this or might patch this afterward
             (when-not js/COMPILED
               (when-not (.-require_figwheel_backup_ js/goog)
                 (set! (.-require_figwheel_backup_ js/goog) (or js/goog.require__ js/goog.require)))
               (set! (.-isProvided_ js/goog) (fn [name] false))
               (when-not (and (exists? js/cljs)
                              (exists? js/cljs.user))
                 (goog/constructNamespace_ "cljs.user"))
               (set! (.-CLOSURE_IMPORT_SCRIPT goog/global) queued-file-reload)
               (set! (.-require js/goog) figwheel-require)))

           (defn patch-goog-base []
             (defonce bootstrapped-cljs (do (bootstrap-goog-base) true)))

;; --------------------------------------------------------------
;; File reloading on different platforms
;; --------------------------------------------------------------

;; this assumes no query string on url
           (defn add-cache-buster [url]
             (.makeUnique (guri/parse url)))

           (def gloader
             (cond
               (exists? loader/safeLoad)
               #(loader/safeLoad (conv/trustedResourceUrlFromString (str %1)) %2)
               (exists? loader/load) #(loader/load (str %1) %2)
               :else (throw (ex-info "No remote script loading function found." {}))))

           (defn reload-file-in-html-env
             [request-url callback]
             {:pre [(string? request-url) (not (nil? callback))]}
             (doto (gloader (add-cache-buster request-url) #js {:cleanupWhenDone true})
               (.addCallback #(apply callback [true]))
               (.addErrback  #(apply callback [false]))))

           (def ^:export write-script-tag-import reload-file-in-html-env)

           (defn ^:export worker-import-script [request-url callback]
             {:pre [(string? request-url) (not (nil? callback))]}
             (callback (try
                         (do (.importScripts js/self (add-cache-buster request-url))
                             true)
                         (catch js/Error e
                           (log/error logger (str  "Figwheel: Error loading file " request-url))
                           (log/error logger e)
                           false))))

           (defn ^:export create-node-script-import-fn []
             (let [node-path-lib (js/require "path")
        ;; just finding a file that is in the cache so we can
        ;; figure out where we are
                   util-pattern (str (.-sep node-path-lib)
                                     (.join node-path-lib "goog" "bootstrap" "nodejs.js"))
                   util-path (gobj/findKey js/require.cache (fn [v k o] (gstring/endsWith k util-pattern)))
                   parts     (-> (string/split util-path #"[/\\]") pop pop)
                   root-path (string/join (.-sep node-path-lib) parts)]
               (fn [request-url callback]
                 (assert (string? request-url) (not (nil? callback)))
                 (let [cache-path (.resolve node-path-lib root-path request-url)]
                   (gobj/remove (.-cache js/require) cache-path)
                   (callback (try
                               (js/require cache-path)
                               (catch js/Error e
                                 (log/error logger (str  "Figwheel: Error loading file " cache-path))
                                 (log/error logger e)
                                 false)))))))

           (def host-env
             (cond
               (not (nil? goog/nodeGlobalRequire)) :node
               (not (nil? goog/global.document)) :html
               (and (exists? goog/global.navigator)
                    (= goog/global.navigator.product "ReactNative"))
               :react-native
               (and
                (nil? goog/global.document)
                (exists? js/self)
                (exists? (.-importScripts js/self)))
               :worker))

           (def reload-file*
             (condp = host-env
               :node (create-node-script-import-fn)
               :html write-script-tag-import
               :worker worker-import-script
               (fn [a b] (throw "Reload not defined for this platform"))))

;; TODO Should just leverage the import script here somehow
           (defn reload-file [{:keys [request-url] :as file-msg} callback]
             {:pre [(string? request-url) (not (nil? callback))]}
             (log/fine logger (str "Attempting to load " request-url))
             ((or (gobj/get goog.global "FIGWHEEL_IMPORT_SCRIPT") reload-file*)
              request-url
              (fn [success?]
                (if success?
                  (do
                    (log/fine logger (str "Successfully loaded " request-url))
                    (apply callback [(assoc file-msg :loaded-file true)]))
                  (do
                    (log/error logger (str  "Error loading file " request-url))
                    (apply callback [file-msg]))))))

;; for goog.require consumption
           (defonce reload-promise-chain (atom (Promise. #(%1 true))))

           (defn queued-file-reload
             ([url] (queued-file-reload url nil))
             ([url opt-source-text]
   ;; guard against reloading goog/base.js
   ;; as it will blow away import figwheel reloading hooks
              (if (string/ends-with? url "goog/base.js")
                true
                (when-let [next-promise-fn
                           (cond opt-source-text
                                 #(.then %
                                         (fn [_]
                                           (Promise.
                                            (fn [r _]
                                              (try (js/eval opt-source-text)
                                                   (catch js/Error e
                                                     (log/error logger e)))
                                              (r true)))))
                                 url
                                 #(.then %
                                         (fn [_]
                                           (Promise.
                                            (fn [r _]
                                              (reload-file {:request-url url}
                                                           (fn [file-msg]
                                                             (r true))))))))]
                  (swap! reload-promise-chain next-promise-fn)))))

           (defn ^:export after-reloads [f]
             (swap! reload-promise-chain #(.then % f)))

;; --------------------------------------------------------------
;; REPL print forwarding
;; --------------------------------------------------------------

           (goog-define client-log-level "info")
           (goog-define print-output "console,repl")

           (defn print-receivers [outputs]
             (->> (string/split outputs #",")
                  (map string/trim)
                  (filter (complement string/blank?))
                  (map keyword)
                  set))

           (defmulti out-print (fn [k args] k))
           (defmethod out-print :console [_ args]
             (.apply (.-log js/console) js/console (garray/clone (to-array args))))

           (defmulti err-print (fn [k args] k))
           (defmethod err-print :console [_ args]
             (.apply (.-error js/console) js/console (garray/clone (to-array args))))

           (defn setup-printing! []
             (let [printers (print-receivers print-output)]
               (set-print-fn! (fn [& args] (doseq [p printers] (out-print p args))))
               (set-print-err-fn! (fn [& args] (doseq [p printers] (err-print p args))))))

           (defn forward-client-logging-to-repl
             "Forward client logging to REPL"
             []
             (goog.log/addHandler
              logger
              (fn [log-record]
                (out-print :repl [(aget log-record "msg_")]))))

           #_(printing-receivers "console,repl")

;; --------------------------------------------------------------
;; REPL Connection
;; --------------------------------------------------------------

           (goog-define connect-url "http://[[client-hostname]]:[[client-port]]/figwheel-connect")

           (def state (atom {}))

;; returns nil if not available
           (def storage (storage-factory/createHTML5SessionStorage "figwheel.repl"))

           (defn set-state [k v]
             (swap! state assoc k v)
             (when storage (.set storage (str k) v)))

           (defn get-state [k]
             (if storage (.get storage (str k)) (get @state k)))

           (defn ^:export session-name [] (get-state ::session-name))
           (defn ^:export session-id [] (get-state ::session-id))

           (defn response-for [{:keys [uuid]} response-body]
             (cond->
              {:session-id   (session-id)
               :session-name (session-name)
               :response response-body}
               uuid (assoc :uuid uuid)))

;; this is a fire and forget POST
           (def http-post
             (condp = host-env
               :node
               (fn [url post-data]
                 (let [http-mod (if (gstring/startsWith url "https") (js/require "https") (js/require "http"))
                       data (volatile! "")
                       uri (guri/parse (str url))]
                   (-> (.request http-mod
                                 #js {:host (.getDomain uri)
                                      :port (.getPort uri)
                                      :path (str (.getPath uri) (when-let [q (.getQuery uri)]
                                                                  (str "?" q)))
                                      :method "POST"
                                      :headers #js {"Content-Length" (js/Buffer.byteLength post-data)}}
                                 (fn [x]))
                       (.on "error" #(js/console.error %))
                       (doto
                        (.write post-data)
                        (.end)))))
               (fn [url response]
                 (js/goog.net.XhrIo.send
                  url
                  (fn [e] (debug "Response Posted"))
                  "POST"
                  response))))

           (defn respond-to [{:keys [http-url] :as old-msg} response-body]
             (let [response (binding [*print-readably* true]
                              ;; `pr-str` here can run inside `println`'s `*print-readably* false`
                              ;; binding, which would emit unquoted strings and produce a message the
                              ;; server cannot read back as EDN.
                              (pr-str (response-for old-msg response-body)))]
               (http-post http-url response)))

           (defn respond-to-connection [response-body]
             (respond-to (:connection @state) response-body))

           (defmulti message :op)
           (defmethod message "naming" [msg]
             (when-let [sn  (:session-name msg)] (set-state ::session-name sn))
             (when-let [sid (:session-id msg)]   (set-state ::session-id sid))
             (log/info logger (str "Session ID: "   (session-id)))
             (log/info logger (str "Session Name: " (session-name))))

           (defmethod message "ping" [msg] (respond-to msg {:pong true}))

           (defn get-ua-product []
             (cond
               (not (nil? goog/nodeGlobalRequire)) :chrome
               product/SAFARI    :safari
               product/CHROME    :chrome
               product/FIREFOX   :firefox
               product/IE        :ie))

           (let [print-to-console? ((print-receivers print-output) :console)]
             (defn eval-javascript** [code]
               (let [ua-product (get-ua-product)]
                 (try
                   (let [sb (js/goog.string.StringBuffer.)]
          ;; TODO capture err as well?
                     (binding [cljs.core/*print-newline* true
                               cljs.core/*print-fn* (fn [x] (.append sb x))]
                       (let [result-value (js/eval code)
                  ;; the result needs to be readable
                             result-value (if-not (string? result-value)
                                            (try
                                              (pr-str result-value)
                                              (catch js/Error e
                                                nil))
                                            result-value)
                             output-str (str sb)]
                         (when (and print-to-console? (not (zero? (.getLength sb))))
                           (js/setTimeout #(out-print :console [output-str]) 0))
                         {:status :success
                          :out output-str
                          :ua-product ua-product
                          :value result-value})))
                   (catch js/Error e
          ;; logging errors to console helpful
                     (when (and (exists? js/console) (exists? js/console.error))
                       (js/console.error "REPL eval error" e))
                     {:status :exception
                      :value (pr-str e)
                      :ua-product ua-product
                      :stacktrace (.-stack e)})
                   (catch :default e
                     {:status :exception
                      :ua-product ua-product
                      :value (pr-str e)
                      :stacktrace "No stacktrace available."})))))

           (defmethod message "eval" [{:keys [code] :as msg}]
             (let [result (eval-javascript** code)]
               (respond-to msg result)))

           (defmethod message "messages" [{:keys [messages http-url]}]
             (doseq [msg messages]
               (message (cond-> (js->clj msg :keywordize-keys true)
                          http-url (assoc :http-url http-url)))))

           (defn fill-url-template [connect-url']
             (if (= host-env :html)
               (-> connect-url'
                   (string/replace "[[client-hostname]]" js/location.hostname)
                   (string/replace "[[client-port]]" js/location.port))
               connect-url'))

           (defn make-url [connect-url']
             (let [uri (guri/parse (fill-url-template (or connect-url' connect-url)))
                   domain (.getDomain uri)]
               (when (string/ends-with? domain ":")
                 (.setDomain uri (subs domain 0 (dec (count domain)))))
               (cond-> (.add (.getQueryData uri) "fwsid" (or (session-id) (random-uuid)))
                 (session-name) (.add "fwsname" (session-name)))
               uri))

           (defn exponential-backoff
             ([attempt] (exponential-backoff attempt 1000))
             ([attempt base-ms]
              (min (* base-ms (js/Math.pow 2 attempt)) 20000)))

           (defn hook-repl-printing-output! [respond-msg]
             (defmethod out-print :repl [_ args]
               (respond-to respond-msg
                           {:output true
                            :stream :out
                            :args (mapv #(if (string? %) % (gjson/serialize %)) args)}))
             (defmethod err-print :repl [_ args]
               (respond-to respond-msg
                           {:output true
                            :stream :err
                            :args (mapv #(if (string? %) % (gjson/serialize %)) args)}))
             (setup-printing!))

           (defn connection-established! [url]
             (when (= host-env :html)
               (let [target (.. goog.global -document -body)]
                 (.dispatchEvent
                  target
                  (doto (js/Event. "figwheel.repl.connected" target)
                    (gobj/add "data" {:url url}))))))

           (defn connection-closed! [url]
             (when (= host-env :html)
               (let [target (.. goog.global -document -body)]
                 (.dispatchEvent
                  target
                  (doto (js/Event. "figwheel.repl.disconnected" target)
                    (gobj/add "data" {:url url}))))))

;; -----------------------------------------------------------
;; EventSource abstraction + polyfills
;; -----------------------------------------------------------

           (defn parse-sse-frame [frame-text]
             (let [lines (-> frame-text
                             (string/replace #"\r\n?" "\n")
                             (string/split #"\n"))
                   {:keys [event retry data has-data?]}
                   (reduce (fn [acc line]
                             (cond
                               (string/starts-with? line "event: ")
                               (assoc acc :event (subs line 7))

                               (string/starts-with? line "event:")
                               (assoc acc :event (subs line 6))

                               (string/starts-with? line "retry: ")
                               (if-let [retry-val (js/parseInt (subs line 7) 10)]
                                 (if (js/isNaN retry-val) acc (assoc acc :retry retry-val))
                                 acc)

                               (string/starts-with? line "retry:")
                               (if-let [retry-val (js/parseInt (subs line 6) 10)]
                                 (if (js/isNaN retry-val) acc (assoc acc :retry retry-val))
                                 acc)

                               (string/starts-with? line "data: ")
                               (-> acc
                                   (update :data conj (subs line 6))
                                   (assoc :has-data? true))

                               (string/starts-with? line "data:")
                               (-> acc
                                   (update :data conj (subs line 5))
                                   (assoc :has-data? true))

                               :else
                               acc))
                           {:data [] :has-data? false}
                           lines)]
               (cond-> {}
                 event (assoc :event event)
                 (some? retry) (assoc :retry retry)
                 has-data? (assoc :data (string/join "\n" data)))))

           (defn split-sse-frames [buffer]
             (let [last-boundary (->> ["\r\n\r\n" "\n\n" "\r\r"]
                                      (keep (fn [separator]
                                              (let [idx (.lastIndexOf buffer separator)]
                                                (when (<= 0 idx)
                                                  (+ idx (count separator))))))
                                      sort
                                      last)]
               (if-not last-boundary
                 {:frames [] :buffer buffer}
                 {:frames (remove string/blank?
                                  (string/split (subs buffer 0 last-boundary)
                                                #"\r\n\r\n|\n\n|\r\r"))
                  :buffer (subs buffer last-boundary)})))

           (defn dispatch-event! [source listeners type event]
             (doseq [listener (get @listeners type)]
               (try
                 (listener event)
                 (catch :default e
                   (log/error logger e))))
             (when-let [handler (gobj/get source (str "on" type))]
               (try
                 (handler event)
                 (catch :default e
                   (log/error logger e)))))

           (defn xhr-event-source [url]
             (let [source (js-obj)
                   listeners (atom {})
                   reconnect-timer (atom nil)
                   closed? (atom false)
                   retry-ms (atom 1000)
                   attempt (atom 0)
                   xhr* (atom nil)]
               (letfn [(clear-reconnect! []
                         (when-let [timer @reconnect-timer]
                           (js/clearTimeout timer)
                           (reset! reconnect-timer nil)))
                       (close-xhr! []
                         (when-let [xhr @xhr*]
                           (reset! xhr* nil)
                           (.abort xhr)))
                       (schedule-reconnect! []
                         (when-not @closed?
                           (clear-reconnect!)
                           (let [wait-time (exponential-backoff @attempt @retry-ms)]
                             (swap! attempt inc)
                             (log/info logger (str "Connection lost. Reconnecting in " (/ wait-time 1000) " seconds"))
                             (reset! reconnect-timer
                                     (js/setTimeout
                                      (fn []
                                        (reset! reconnect-timer nil)
                                        (open!))
                                      wait-time)))))
                       (process-frame! [frame]
                         (let [{:keys [event retry data] :as frame-data} (parse-sse-frame frame)]
                           (when retry
                             (reset! retry-ms retry))
                           (when (contains? frame-data :data)
                             (dispatch-event!
                              source
                              listeners
                              (or event "message")
                              #js {:type (or event "message")
                                   :data data
                                   :url url}))))
                       (open! []
                         (when-not @closed?
                           (clear-reconnect!)
                           (close-xhr!)
                           (gobj/set source "readyState" 0)
                           (let [xhr (js/XMLHttpRequest.)
                                 last-index (atom 0)
                                 buffer* (atom "")
                                 opened? (atom false)
                                 open-connection!
                                 (fn []
                                   (when-not @opened?
                                     (reset! opened? true)
                                     (reset! attempt 0)
                                     (gobj/set source "readyState" 1)
                                     (dispatch-event! source listeners "open" #js {:type "open"})))]
                             (reset! xhr* xhr)
                             (.open xhr "GET" url true)
                             (.setRequestHeader xhr "Accept" "text/event-stream")
                             (.setRequestHeader xhr "Cache-Control" "no-cache")
                             (set! (.-onreadystatechange xhr)
                                   (fn []
                                     (when-not @closed?
                                       (let [ready-state (.-readyState xhr)
                                             status (.-status xhr)]
                                         (cond
                                           (and (or (= ready-state js/XMLHttpRequest.LOADING)
                                                    (= ready-state js/XMLHttpRequest.DONE))
                                                (<= 200 status 399))
                                           (let [response-text (or (.-responseText xhr) "")
                                                 new-text (subs response-text @last-index)]
                                             (reset! last-index (count response-text))
                                             (open-connection!)
                                             (when-not (string/blank? new-text)
                                               (swap! buffer* str new-text)
                                               (let [{:keys [frames buffer]} (split-sse-frames @buffer*)]
                                                 (reset! buffer* buffer)
                                                 (doseq [frame frames]
                                                   (process-frame! frame))))
                                             (when (= ready-state js/XMLHttpRequest.DONE)
                                               (gobj/set source "readyState" 0)
                                               (schedule-reconnect!)))

                                           (and (= ready-state js/XMLHttpRequest.DONE)
                                                (not= status 0))
                                           (do
                                             (gobj/set source "readyState" 0)
                                             (dispatch-event!
                                              source
                                              listeners
                                              "error"
                                              #js {:type "error"
                                                   :status status
                                                   :message (str "HTTP " status)})
                                             (schedule-reconnect!)))))))
                             (set! (.-onerror xhr)
                                   (fn [e]
                                     (when-not @closed?
                                       (gobj/set source "readyState" 0)
                                       (dispatch-event! source listeners "error" #js {:type "error" :error e})
                                       (schedule-reconnect!))))
                             (.send xhr))))]
                 (gobj/set source "readyState" 0)
                 (gobj/set source "addEventListener"
                           (fn [type listener]
                             (swap! listeners update type (fnil conj []) listener)))
                 (gobj/set source "removeEventListener"
                           (fn [type listener]
                             (swap! listeners update type
                                    (fn [handlers]
                                      (vec (remove #(identical? % listener) handlers))))))
                 (gobj/set source "close"
                           (fn []
                             (reset! closed? true)
                             (clear-reconnect!)
                             (close-xhr!)
                             (gobj/set source "readyState" 2)
                             (dispatch-event! source listeners "close" #js {:type "close"})))
                 (open!)
                 source)))

           (defn fetch-event-source [url]
             (let [source (js-obj)
                   listeners (atom {})
                   reconnect-timer (atom nil)
                   closed? (atom false)
                   retry-ms (atom 1000)
                   attempt (atom 0)
                   controller* (atom nil)]
               (letfn [(clear-reconnect! []
                         (when-let [timer @reconnect-timer]
                           (js/clearTimeout timer)
                           (reset! reconnect-timer nil)))
                       (close-request! []
                         (when-let [controller @controller*]
                           (reset! controller* nil)
                           (.abort controller)))
                       (schedule-reconnect! []
                         (when-not @closed?
                           (clear-reconnect!)
                           (let [wait-time (exponential-backoff @attempt @retry-ms)]
                             (swap! attempt inc)
                             (log/info logger (str "Connection lost. Reconnecting in " (/ wait-time 1000) " seconds"))
                             (reset! reconnect-timer
                                     (js/setTimeout
                                      (fn []
                                        (reset! reconnect-timer nil)
                                        (open!))
                                      wait-time)))))
                       (process-frame! [frame]
                         (let [{:keys [event retry data] :as frame-data} (parse-sse-frame frame)]
                           (when retry
                             (reset! retry-ms retry))
                           (when (contains? frame-data :data)
                             (dispatch-event!
                              source
                              listeners
                              (or event "message")
                              #js {:type (or event "message")
                                   :data data
                                   :url url}))))
                       (open! []
                         (when-not @closed?
                           (clear-reconnect!)
                           (close-request!)
                           (gobj/set source "readyState" 0)
                           (let [controller (js/AbortController.)
                                 decoder (js/TextDecoder.)
                                 buffer* (atom "")]
                             (reset! controller* controller)
                             (-> (js/fetch url
                                           #js {:headers #js {"Accept" "text/event-stream"
                                                              "Cache-Control" "no-cache"}
                                                :signal (.-signal controller)})
                                 (.then (fn [response]
                                          (if-not (and (.-ok response) (.-body response))
                                            (throw (js/Error. (str "HTTP " (.-status response))))
                                            (do
                                              (reset! attempt 0)
                                              (gobj/set source "readyState" 1)
                                              (dispatch-event! source listeners "open" #js {:type "open"})
                                              (let [reader (.getReader (.-body response))]
                                                (letfn [(read-loop []
                                                          (-> (.read reader)
                                                              (.then (fn [result]
                                                                       (if (.-done result)
                                                                         (do
                                                                           (gobj/set source "readyState" 0)
                                                                           (schedule-reconnect!))
                                                                         (do
                                                                           (let [text (.decode decoder (.-value result) #js {:stream true})]
                                                                             (swap! buffer* str text)
                                                                             (let [{:keys [frames buffer]} (split-sse-frames @buffer*)]
                                                                               (reset! buffer* buffer)
                                                                               (doseq [frame frames]
                                                                                 (process-frame! frame))))
                                                                           (read-loop)))))
                                                              (.catch (fn [e]
                                                                        (when-not @closed?
                                                                          (gobj/set source "readyState" 0)
                                                                          (dispatch-event! source listeners "error" #js {:type "error" :error e})
                                                                          (schedule-reconnect!))))))]
                                                  (read-loop)))))))
                                 (.catch (fn [e]
                                           (when-not @closed?
                                             (gobj/set source "readyState" 0)
                                             (dispatch-event! source listeners "error" #js {:type "error" :error e})
                                             (schedule-reconnect!))))))))]
                 (gobj/set source "readyState" 0)
                 (gobj/set source "addEventListener"
                           (fn [type listener]
                             (swap! listeners update type (fnil conj []) listener)))
                 (gobj/set source "removeEventListener"
                           (fn [type listener]
                             (swap! listeners update type
                                    (fn [handlers]
                                      (vec (remove #(identical? % listener) handlers))))))
                 (gobj/set source "close"
                           (fn []
                             (reset! closed? true)
                             (clear-reconnect!)
                             (close-request!)
                             (gobj/set source "readyState" 2)
                             (dispatch-event! source listeners "close" #js {:type "close"})))
                 (open!)
                 source)))

           (defn create-event-source [url]
             (cond
               (exists? js/EventSource)
               (js/EventSource. url)

               (= host-env :react-native)
               (xhr-event-source url)

               (= host-env :node)
               (fetch-event-source url)

               (and (exists? js/globalThis.fetch)
                    (exists? js/ReadableStream))
               (fetch-event-source url)

               (exists? js/XMLHttpRequest)
               (xhr-event-source url)

               :else
               (throw (js/Error. "No EventSource transport available for this platform."))))

           (defn connect-event-source! [connect-url']
             (let [url (make-url connect-url')
                   surl (str url)]
               (doto (.getQueryData url)
                 (.add "fwinit" "true"))
               (let [source (create-event-source (str url))]
                 (.addEventListener source "open"
                                    (fn [_]
                                      (connection-established! surl)
                                      (swap! state assoc :connection {:http-url surl
                                                                      :event-source source})
                                      (hook-repl-printing-output! {:http-url surl})))
                 (.addEventListener source "message"
                                    (fn [e]
                                      (try
                                        (let [msg (js->clj (js/JSON.parse (.-data e)) :keywordize-keys true)]
                                          (debug (pr-str msg))
                                          (message (assoc msg :http-url surl)))
                                        (catch js/Error err
                                          (log/error logger err)))))
                 (.addEventListener source "error"
                                    (fn [_]
                                      (when (= 2 (gobj/get source "readyState"))
                                        (connection-closed! surl))))
                 source)))

           (defn init-log-level! []
             (doseq [logger' (cond-> [logger]
                               (exists? js/figwheel.core)
                               (conj js/figwheel.core.logger))]
               (log/set-log-level logger' client-log-level)))

           (defn connect* [connect-url']
             (init-log-level!)
             (patch-goog-base)
             (let [url (string/trim (or connect-url' connect-url))
                   ;; Rewrite ws:// -> http://, wss:// -> https://
                   url (cond
                         (gstring/startsWith url "ws://")  (str "http://" (subs url 5))
                         (gstring/startsWith url "wss://") (str "https://" (subs url 6))
                         :else url)]
               (connect-event-source! url)))

           (defn connect [& [connect-url']]
             (defonce connected
               (do (connect* connect-url') true)))))

;; end :cljs

#?(:clj (do

          (let [scheduler (Executors/newScheduledThreadPool
                           1
                           (reify ThreadFactory
                             (newThread [_ runnable]
                               (let [thread (Thread. runnable)]
                                 (.setDaemon thread true)
                                 thread))))]
            (defn schedule-task! [^Runnable f ms]
              (.schedule scheduler f ms TimeUnit/MILLISECONDS)))

          (defn wait! [ms]
            (let [prom (promise)]
              (schedule-task! #(deliver prom true) ms)
              @prom))

          (defonce ^:private listener-set (atom {}))
          (defn add-listener
            ([f]   (add-listener f f))
            ([k f] (swap! listener-set assoc k f) nil))
          (defn remove-listener [k] (swap! listener-set dissoc k) nil)
          (defn clear-listeners []
            (reset! listener-set {}))

          (declare name-list)

          (defn log [& args]
            (spit "server.log" (apply prn-str args) :append true))

          (defonce scratch (atom {}))

          (def ^:dynamic *server* nil)

          (defn parse-query-string [qs]
            (when (string? qs)
              (into {} (for [[_ k v] (re-seq #"([^&=]+)=([^&]+)" qs)]
                         [(keyword k) (java.net.URLDecoder/decode v)]))))

;; ------------------------------------------------------------------
;; Connection management
;; ------------------------------------------------------------------

          (defonce ^:dynamic *connections* (atom {}))

          (defn taken-names [connections]
            (set (mapv :session-name (vals connections))))

          (defn available-names [connections]
            (set/difference name-list (taken-names connections)))

          (defn negotiate-id [ring-request connections]
            (let [query (parse-query-string (:query-string ring-request))
                  sid (:fwsid query (str (java.util.UUID/randomUUID)))
                  sname (or (some-> connections (get sid) :session-name)
                            (when-let [chosen-name (:fwsname query)]
                              (when-not ((taken-names connections) chosen-name)
                                chosen-name))
                            (rand-nth (seq (available-names connections))))]
              [sid sname]))

          (defn create-connection! [ring-request options]
            (let [[sess-id sess-name] (negotiate-id ring-request @*connections*)
                  conn (merge (select-keys ring-request [:server-port :scheme :uri :server-name :query-string :request-method])
                              (cond-> {:session-name sess-name
                                       :session-id sess-id
                                       ::alive-at (System/currentTimeMillis)
                                       :created-at (System/currentTimeMillis)}
                                (:query-string ring-request)
                                (assoc :query (parse-query-string (:query-string ring-request))))
                              options)]
              (swap! *connections* assoc sess-id conn)
              conn))

          (defn remove-connection! [{:keys [session-id] :as conn}]
            (swap! *connections* dissoc session-id))

          (defn receive-message! [data]
            (when-let [data
                       (try
                         (edn/read-string data)
                         (catch Throwable t
                           (binding [*out* *err*] (clojure.pprint/pprint (Throwable->map t)))))]
              (doseq [[_ f] @listener-set]
                (try (f data) (catch Throwable ex)))))

          (defn naming-response [{:keys [session-name session-id type] :as conn}]
            (json/write-str {:op :naming
                             :session-name session-name
                             :session-id session-id
                             :connection-type type}))

          (defn json-response [json-body]
            {:status 200
             :headers {"Content-Type" "application/json"}
             :body json-body})

          (declare send-for-response)

          (defn ping [conn] (send-for-response [conn] {:op :ping}))

          (defn ping-task [connections fwsid {:keys [interval
                                                     ping-timeout]
                                              :or {interval 15000
                                                   ping-timeout 2000}}]
            (let [task (fn self []
                         (when-let [conn (get @connections fwsid)]
                           (if-not (try
                                     (deref (ping conn) ping-timeout false)
                                     (catch Throwable e
                                       false))
                             (swap! connections dissoc fwsid)
                             (schedule-task! self interval))))]
              (schedule-task! task interval)))

;; may turn this into a multi method
          (defn connection-send [{:keys [send-fn] :as conn} data]
            (send-fn conn data))

          (defn send-for-response* [prom conn msg]
            (let [uuid (str (java.util.UUID/randomUUID))
                  listener (fn listen [msg]
                             (when (= uuid (:uuid msg))
                               (when-let [result (:response msg)]
                                 (deliver prom
                                          (if (instance? clojure.lang.IMeta result)
                                            (vary-meta result assoc ::message msg)
                                            result)))
                               (remove-listener listen)))]
              (add-listener listener)
              (try
                (connection-send
                 conn
                 (json/write-str
                  (-> (select-keys conn [:session-id :session-name])
                      (merge msg)
                      (assoc :uuid uuid))))
                (catch Throwable t
                  (remove-listener listener)
                  (throw t)))))

          (def no-connection-result
            (vary-meta
             {:status :exception
              :value "Expected REPL Connections Evaporated!"
              :stacktrace "No stacktrace available."}
             assoc ::no-connection-made true))

          (defn broadcast-for-response [connections msg]
            (let [prom (promise)
                  cnt  (->> connections
                            (mapv #(try
                                     (send-for-response* prom % msg)
                                     true
                                     (catch Throwable t
                                       nil)))
                            (filter some?)
                            count)]
              (when (zero? cnt)
                (deliver prom no-connection-result))
              prom))

          (defn send-for-response [connections msg]
            (let [prom (promise)
                  sent (loop [[conn & xc] connections]
                         (when conn
                           (if-not (try
                                     (send-for-response* prom conn msg)
                                     true
                                     (catch Throwable t
                                       (println (ex-message t))
                                       false))
                             (recur xc)
                             true)))]
              (when-not sent
                (deliver prom no-connection-result))
              prom))

;; ------------------------------------------------------------------
;; SSE (Server-Sent Events) streaming
;; ------------------------------------------------------------------

          (defn sse-send
            "Non-blocking: puts an SSE data frame onto the connection's queue.
             Ring 1.15+ flushes ISeq bodies after each element, so data flows
             to the client immediately."
            [conn json-data]
            (.put ^java.util.concurrent.LinkedBlockingQueue (::queue conn)
                  (str "data: " json-data "\n\n")))

          (defn sse-connect [ring-request respond raise]
            (let [queue (java.util.concurrent.LinkedBlockingQueue.)
                  closed? (volatile! false)
                  conn (create-connection! ring-request
                                           {:type :sse
                                            ::queue queue
                                            ::closed? closed?
                                            :is-open-fn (fn [conn] (not @(::closed? conn)))
                                            :send-fn (fn [conn data]
                                                       (sse-send conn data))
                                            :close-fn (fn []
                                                        (vreset! closed? true)
                                                        ;; unblock the take with a poison pill
                                                        (.put queue ::closed))})]
              ;; Queue initial frames
              (.put queue "retry: 3000\n\n")
              (sse-send conn (naming-response conn))
              ;; Respond with a lazy seq body — Ring 1.15 flushes after each element.
              ;; The seq blocks on .take, producing elements as they are queued.
              (respond {:status 200
                        :headers {"Content-Type" "text/event-stream"
                                  "Cache-Control" "no-cache"
                                  "Connection" "keep-alive"
                                  "X-Accel-Buffering" "no"}
                        :body (take-while #(not= % ::closed)
                                          (repeatedly #(.take queue)))})
              (ping-task *connections* (:session-id conn) {:interval 15000 :ping-timeout 4000})))

          (defn sse-middleware [handler path connections]
            (fn [ring-request respond raise]
              (let [uri-match (.startsWith (:uri ring-request) path)
                    method (:request-method ring-request)]
                (cond
                  (and uri-match (= method :get))
                  (binding [*connections* connections]
                    (try
                      (sse-connect ring-request respond raise)
                      (catch Throwable e
                        (raise e))))
                  (and uri-match (= method :post))
                  (binding [*connections* connections]
                    (try
                      (receive-message! (slurp (:body ring-request)))
                      (respond {:status 200
                                :headers {"Content-Type" "text/html"}
                                :body "Received"})
                      (catch Throwable e
                        (raise e))))
                  :else
                  (try
                    (respond (handler ring-request))
                    (catch Throwable t
                      (raise t)))))))

;; ---------------------------------------------------
;; ReplEnv implmentation
;; ---------------------------------------------------

          (defn open-connections []
            (filter (fn [{:keys [is-open-fn] :as conn}]
                      (try (or (nil? is-open-fn) (is-open-fn conn))
                           (catch Throwable t
                             false)))
                    (vals @*connections*)))

          (defn connections-available [repl-env]
            (sort-by
             :created-at >
             (filter (or (some-> repl-env :connection-filter)
                         identity)
                     (open-connections))))

          (defn wait-for-connection [repl-env]
            (let [prom (promise)
                  task (fn self []
                         (if (empty? (connections-available repl-env))
                           (schedule-task! self 500)
                           (deliver prom true)))]
              (task)
              @prom))

          (defn send-for-eval [{:keys [focus-session-name ;; just here for consideration
                                       broadcast] :as repl-env} connections js]
            (if broadcast
              (broadcast-for-response connections {:op :eval :code js})
              (send-for-response connections {:op :eval :code js})))

          (defn eval-connections [{:keys [focus-session-name] :as repl-env}]
            (let [connections (connections-available repl-env)
          ;; session focus
                  connections (if-let [focus-conn
                                       (and @focus-session-name
                                            (first (filter (fn [{:keys [session-name]}]
                                                             (= @focus-session-name
                                                                session-name))
                                                           connections)))]
                                [focus-conn]
                                (do
                                  (reset! focus-session-name nil)
                                  connections))]
              connections))

          (defn trim-last-newline [args]
            (if-let [args (not-empty (filter string? args))]
              (conj (vec (butlast args))
                    (string/trim-newline (last args)))
              args))

          (defn print-to-stream [stream args]
            (condp = stream
              :out (apply println args)
              :err (binding [*out* *err*]
                     (apply println args))))

          (defn repl-env-print [repl-env stream args]
            (when-let [args (not-empty (filter string? args))]
              (when (and (:out-print-fn repl-env) (= :out stream))
                (apply (:out-print-fn repl-env) args))
              (when (and (:err-print-fn repl-env) (= :err stream))
                (apply (:err-print-fn repl-env) args))
              (let [args (trim-last-newline args)]
                (when (:print-to-output-streams repl-env)
                  (if-let [bprinter @(:bound-printer repl-env)]
                    (bprinter stream args)
                    (print-to-stream stream args))))))

          (let [timeout-val (Object.)]
            (defn evaluate [{:keys [focus-session-name ;; just here for consideration
                                    repl-eval-timeout
                                    broadcast] :as repl-env} js]
              (reset! (:bound-printer repl-env)
                      (bound-fn [stream args]
                        (print-to-stream stream args)))
              (wait-for-connection repl-env)
              (let [ev-connections (eval-connections repl-env)
                    result (let [v (deref (send-for-eval repl-env ev-connections js)
                                          (or repl-eval-timeout 8000)
                                          timeout-val)]
                             (cond (= timeout-val v)
                                   (do
                                     (when @focus-session-name
                                       (reset! focus-session-name nil))
                                     {:status :exception
                                      :value "Eval timed out!"
                                      :stacktrace "No stacktrace available."})
                                   (::no-connection-made (meta v))
                                   (do
                                     (when @focus-session-name
                                       (reset! focus-session-name nil))
                                     v)
                                   :else v))]
                (when-let [out (:out result)]
                  (when (not (string/blank? out))
                    (repl-env-print repl-env :out [(string/trim-newline out)])))
                result)))

;; TODO more precise error when loaded but fn doesn't exist
          (defn dynload [ns-sym-str]
            (try
              (let [sym (symbol ns-sym-str)]
                (when-let [ns (namespace sym)]
                  (try
                    (require (symbol ns))
                    (resolve sym))))
              (catch Throwable e
                (throw (ex-info (str "Figwheel: Unable to dynamicly load " ns-sym-str)
                                {:not-loaded ns-sym-str}
                                e)))))

;; taken from ring server
          (defn try-port
            "Try running a server under one port or a list of ports. If a list of ports
  is supplied, try each port until it succeeds or runs out of ports."
            [port server-fn]
            (if-not (sequential? port)
              (server-fn port)
              (try (server-fn (first port))
                   (catch java.net.BindException ex
                     (if-let [port (next port)]
                       (try-port port server-fn)
                       (throw ex))))))

          (defn run-default-server*
            [options connections]
  ;; require and run figwheel server
            (let [server-fn (or (when-let [server-fn-symbol (get options :ring-server)]
                                  (dynload server-fn-symbol))
                                figwheel.server.jetty/run-server)
                  stack-fn (or (when-let [stack-fn (get options :ring-stack)]
                                 (dynload stack-fn))
                               figwheel.server.ring/default-stack)
        ;; TODO this should only work for the default target of browser
                  stack-options
                  (cond-> (:ring-stack-options options)
                    (:cljsjs-resources options)
                    (assoc-in [:figwheel.server.ring/dev :figwheel.server.ring/cljsjs-resources] true)
          ;; do we need a default index?
                    (and
                     (contains? #{nil :browser :bundle} (:target options))
                     (:output-to options)
                     (not (get-in (:ring-stack-options options)
                                  [:figwheel.server.ring/dev :figwheel.server.ring/system-app-handler])))
                    (assoc-in
                     [:figwheel.server.ring/dev :figwheel.server.ring/system-app-handler]
                     #(figwheel.server.ring/default-index-html
                       %
                       (figwheel.server.ring/index-html (select-keys options [:output-to])))))
                  figwheel-connect-path (get options :figwheel-connect-path "/figwheel-connect")
                  server (server-fn
                          (-> (stack-fn (:ring-handler options) stack-options)
                              (sse-middleware figwheel-connect-path connections)
                              (figwheel.server.ring/wrap-async-cors
                               :access-control-allow-origin #".*"
                               :access-control-allow-methods
                               [:head :options :get :put :post :delete :patch]))
                          (-> (get options :ring-server-options)
                              (assoc :async? true)))]
              server))

          (defn run-default-server [options connections]
            (run-default-server* (update options :ring-server-options
                                         #(merge (select-keys options [:host :port]) %))
                                 connections))

          (defn fill-server-url-template [url-str {:keys [host port]}]
            (-> url-str
                (string/replace "[[server-hostname]]" (or host "localhost"))
                (string/replace "[[server-port]]" (str port))))

          (defn launch-js-helper [script
                                  repl-env
                                  {:keys [output-to output-dir target open-url output-log-file] :as data}]
            (let [output-log-file (or (and output-log-file (io/file output-log-file))
                                      (io/file (or output-dir "out") "js-environment.log"))
                  input-data (dissoc data :output-dir :target)]
              (if (or (symbol? script) (var? script) (fn? script))
      ;; TODO consider logging here or let script fn handle it
                (let [v (if (symbol? script)
                          (dynload script)
                          script)]
                  (doto (Thread. (fn [] (v input-data)))
                    (.setDaemon true)
                    (.start)))
                (let [shell-command-vector
                      (cond
                        (coll? script)
                        (mapv (fn [x] (if (keyword? x)
                                        (get input-data x "")
                                        x)) script)
                        (string? script)
                        [script (if (= target :nodejs)
                                  output-to
                                  open-url)])]
                  (.start
                   (cond-> (ProcessBuilder. (into-array shell-command-vector))
                     output-log-file (.redirectError  (io/file output-log-file))
                     output-log-file (.redirectOutput (io/file output-log-file))))))))

          (defn launch-js [script repl-env {:keys [output-dir] :or {output-dir "out"} :as opts}]
            (let [output-log-file (str (io/file output-dir "js-environment.log"))]
              (println "Launching Javascript environment with script: " (pr-str script))
              (reset! (:node-proc repl-env)
                      (launch-js-helper script repl-env
                                        (assoc opts :output-log-file output-log-file)))
              (when (not (symbol? script))
                (println "Environment output being logged to:" output-log-file))))

          (defn launch-node [opts repl-env input-path & [output-log-file]]
            (let [xs (cond-> [(get repl-env :node-command "node")]
                       (:inspect-node repl-env true) (conj "--inspect")
                       input-path (conj input-path))
                  proc (cond-> (ProcessBuilder. (into-array xs))
                         output-log-file (.redirectError  (io/file output-log-file))
                         output-log-file (.redirectOutput (io/file output-log-file)))]
              (.start proc)))

          (defn launch-browser [open-url]
            (try
              (browse/browse-url open-url)
              (catch Throwable t
                (println "Failed to open browser:" (.getMessage t)))))

;; when doing a port search
;; - what needs to know the port afterwards?
;; - auto open the browser, this is easy enough.
;; - the connect-url needs to know, but it can use browser port
;; - the default index.html needs to find the main.js (it can inline it)

;; XXX refactor as we need to breakdown these actions and allow
;; the consumer to inject this behavior via a :setup-fn
          (defn setup [repl-env opts]
            (when (and
                   (or (not (bound? #'*server*))
                       (nil? *server*))
                   (nil? @(:server repl-env)))
              (let [server (run-default-server
                  ;; this strange merging order is to ensure that the repl-env
                  ;; :target is prefered as it can be :bundle when the opts :target
                  ;; is :nodejs
                            (merge
                             (select-keys opts [:target :output-to])
                             (select-keys repl-env [:port
                                                    :host
                                                    :target
                                                    :output-to
                                                    :ring-handler
                                                    :cljsjs-resources
                                                    :ring-server
                                                    :ring-server-options
                                                    :ring-stack
                                                    :ring-stack-options]))
                            *connections*)]
                (reset! (:server repl-env) server)))
  ;; printing
            (when-not @(:printing-listener repl-env)
              (let [print-listener
                    (bound-fn [{:keys [session-id session-name uuid response] :as msg}]
                      (when (and session-id (not uuid) (get response :output))
                        (let [session-ids (set (map :session-id (eval-connections repl-env)))]
                          (when (session-ids session-id)
                            (let [{:keys [stream args]} response]
                              (when (and stream (not-empty args))
                      ;; when printing a result from several sessions mark it
                                (let [args (if-not (= 1 (count session-ids))
                                             (cons (str "[Session:-----:" session-name "]\n") args)
                                             args)]
                                  (repl-env-print repl-env stream args))))))))]
                (reset! (:printing-listener repl-env) print-listener)
                (add-listener print-listener)))
  ;; have to get target from repl-env because it holds the original target
            (let [target (get repl-env :target)
                  {:keys [output-to output-dir]}
                  (apply merge
                         (map #(select-keys % [:output-to :output-dir]) [opts repl-env]))
                  open-url (and (:open-url repl-env)
                                (fill-server-url-template
                                 (:open-url repl-env)
                                 (merge (select-keys repl-env [:host :port])
                                        (select-keys (:ring-server-options repl-env) [:host :port]))))]
              (cond
                (:launch-js repl-env)
                (do
                  (wait! (:open-url-wait-ms repl-env 1500))
                  (launch-js
                   (:launch-js repl-env)
                   repl-env
                   {:output-to output-to
                    :open-url open-url
                    :output-dir output-dir
                    :target target}))

      ;; Node REPL
                (and (= :nodejs target)
                     (:launch-node repl-env true)
                     output-to)
                (let [output-file (io/file output-dir "node.log")]
                  (println "Starting node ... ")
                  (reset! (:node-proc repl-env) (launch-node opts repl-env output-to output-file))
                  (println "Node output being logged to:" (str output-file))
                  (when (:inspect-node repl-env true)
                    (println "For a better development experience:")
                    (println "  1. Open chrome://inspect/#devices ... (in Chrome)")
                    (println "  2. Click \"Open dedicated DevTools for Node\"")))

      ;; open a url
                (and (not (= :nodejs target))
                     open-url)
                (if-let [open (:open-url-fn repl-env)]
                  (open open-url)
                  (do
                    (println "Opening URL" open-url)
                    (schedule-task! #(launch-browser open-url)
                                    (:open-url-wait-ms repl-env 1500))))
                (and (nil? target)
                     (not (:launch-js repl-env))
                     (false? open-url))
                (println "JavaScript environment will not launch automatically when :open-url is false")
                (and (= :nodejs target)
                     (not (:launch-js repl-env))
                     (false? (:launch-node repl-env)))
                (println "JavaScript environment will not launch automatically when :launch-node is false")
                :else nil)))

          (defn tear-down-server [{:keys [server]}]
            (when-let [svr @server]
              (reset! server nil)
              (.stop svr)))

          (defn tear-down-everything-but-server [{:keys [printing-listener node-proc]}]
            (when-let [proc @node-proc]
              (if (instance? Thread proc)
                (.stop proc)
                (.destroy proc))

              #_(.waitFor proc)) ;; ?

            (when-let [listener @printing-listener]
              (remove-listener listener)))

          (defrecord FigwheelReplEnv []
            cljs.repl/IJavaScriptEnv
            (-setup [this opts]
              (setup this opts)
              #_(wait-for-connection this))
            (-evaluate [this _ _  js]
    ;; print where eval occurs
              (evaluate this js))
            (-load [this provides url]
    ;; load a file into all the appropriate envs
              (when-let [js-content (try (slurp url) (catch Throwable t))]
                (evaluate this js-content)))
            (-tear-down [repl-env]
              (when-not (:prevent-server-tear-down repl-env)
                (tear-down-server repl-env))
              (tear-down-everything-but-server repl-env))
            cljs.repl/IReplEnvOptions
            (-repl-options [this]
              (let [main-fn (resolve 'figwheel.main/default-main)]
                (cond-> {;:browser-repl true
                         :preloads '[[figwheel.repl.preload]]
                         :cljs.cli/commands
                         {:groups {::repl {:desc "Figwheel REPL options"}}
                          :init
                          {["-H" "--host"]
                           {:group ::repl :fn #(assoc-in %1 [:repl-env-options :host] %2)
                            :arg "address"
                            :doc "Address to bind"}
                           ["-p" "--port"]
                           {:group ::repl :fn #(assoc-in %1 [:repl-env-options :port] (Integer/parseInt %2))
                            :arg "number"
                            :doc "Port to bind"}
                           ["-rh" "--ring-handler"]
                           {:group ::repl :fn #(assoc-in %1 [:repl-env-options :ring-handler]
                                                         (when %2
                                                           (dynload %2)))
                            :arg "string"
                            :doc "Ring Handler for default REPL server EX. \"example.server/handler\" "}}}}
                  (:output-dir this) (assoc :output-dir (:output-dir this))
                  main-fn (assoc :cljs.cli/main @main-fn))))
            cljs.repl/IParseStacktrace
            (-parse-stacktrace [this st err opts]
              (cljs.stacktrace/parse-stacktrace this st err opts)))

          (defn repl-env* [{:keys [port open-url connection-filter]
                            :or {connection-filter identity
                                 open-url "http://[[server-hostname]]:[[server-port]]"
                                 port default-port} :as opts}]
            (merge (FigwheelReplEnv.)
         ;; TODO move to one atom
                   {:server (atom nil)
                    :printing-listener (atom nil)
                    :bound-printer (atom nil)
                    :open-url open-url
          ;; helpful for nrepl so you can easily
          ;; translate output into messages
                    :out-print-fn nil
                    :err-print-fn nil
                    :node-proc (atom nil)
                    :print-to-output-streams true
                    :connection-filter connection-filter
                    :focus-session-name (atom nil)
                    :broadcast false
                    :port port}
                   opts))

          (defn repl-env [& {:as opts}]
            (repl-env* opts))

;; ------------------------------------------------------
;; Connection management
;; ------------------------------------------------------
;;  mostly for use from the REPL

          (defn list-connections []
            (let [conns (connections-available cljs.repl/*repl-env*)
                  longest-name (apply max (cons (count "Session Name")
                                                (map (comp count :session-name) conns)))]
              (println (format (str "%-" longest-name "s %7s %s")
                               "Session Name"
                               "Age"
                               "URL"))
              (doseq [{:keys [session-name uri query-string created-at]} conns]
                (println (format (str "%-" longest-name "s %6sm %s")
                                 session-name
                                 (Math/round (/ (- (System/currentTimeMillis) created-at) 60000.0))
                                 uri)))))

          (defn will-eval-on []
            (if-let [n @(:focus-session-name cljs.repl/*repl-env*)]
              (println "Focused On: " n)
              (println "Will Eval On: " (->> (connections-available cljs.repl/*repl-env*)
                                             first
                                             :session-name))))

          (defn conns* []
            (will-eval-on)
            (list-connections))

          (defmacro conns []
            (conns*))

          (defn focus* [session-name]
            (let [names (map :session-name (connections-available cljs.repl/*repl-env*))
                  session-name (name session-name)]
              (if ((set names) session-name)
                (str "Focused On: " (reset! (:focus-session-name cljs.repl/*repl-env*) session-name))
                (str "Error: " session-name " not in " (pr-str names)))))

          (defmacro focus [session-name]
            (focus* session-name))

;; TODOS
;; - try https setup
;; - make work on node and other platforms
;; - find open port
;; - repl args from main

;; TODO figwheel-repl-core package
;; TODO figwheel-repl package that includes default server
          (comment

            (def serve (run-default-server {:ring-handler
                                            (fn [r]
                                              (throw (ex-info "Testing" {}))
                                              #_{:status 404
                                                 :headers {"Content-Type" "text/html"}
                                                 :body "Yeppers now"})
                                            :port 9500}
                                           *connections*))

            (.stop serve)

            scratch

            (do
              (cljs.repl/-tear-down re)
              (def re (repl-env* {:output-to "dev-resources/public/out/main.js"}))
              (cljs.repl/-setup re {}))

            (connections-available re)
            (open-connections)
            (evaluate (assoc re :broadcast true)
                      "88")

            (evaluate re "setTimeout(function() {cljs.core.prn(\"hey hey\")}, 1000);")

            (= (mapv #(:value (evaluate re (str %)))
                     (range 100))
               (range 100))

            (def x (ping (first (vals @*connections*))))

            (negotiate-id (:ring-request @scratch) @*connections*)

            (def channel (:body (:async-request @scratch)))

            (.isReady channel)

            (ping ((vals @*connections*)))

            (swap! *connections* dissoc "99785176-1793-4814-938a-93bf071acd2f")

            (swap! scratch dissoc :print-msg)
            scratch
            *connections*
            (deref)

            (swap! *connections* dissoc "d9ffc9ac-b2ec-4660-93c1-812afd1cb032")
            (parse-query-string (:query-string (:ring-request @scratch)))
            (negotiate-name (:ring-request @scratch) @*connections*)
            (reset! *connections* (atom {}))

            (binding [cljs.repl/*repl-env* re]
              (conns*)
              #_(focus* 'Judson)))

          (def name-list
            (set (map str '[Sal Julietta Dodie Janina Krista Freeman Angila Cathy Brant Porter Marty Jerrell Stephan Glenn Palmer Carmelina Monroe Eufemia Ciara Thu Stevie Dee Shamika Jazmin Doyle Roselle Lucien Laveta Marshall Rosy Hilde Yoshiko Nicola Elmo Tana Odelia Gigi Mac Tanner Johnson Roselia Gilberto Marcos Shelia Kittie Bruno Leeanne Elicia Miyoko Lilliana Tatiana Steven Vashti Rolando Korey Selene Emilio Fred Marvin Eduardo Jolie Lorine Epifania Jeramy Eloy Melodee Lilian Kim Cory Daniel Grayce Darin Russ Vanita Yan Quyen Kenda Iris Mable Hong Francisco Abdul Judson Boyce Bridget Cecil Dirk Janetta Kelle Shawn Rema Rosie Nakesha Dominick Jerald Shawnda Enrique Jose Vince])))

          #_(defonce ^:private message-loop
              (doto (Thread.
                     #(let [x (.take messageq)
                            listeners @listener-set]
                        (doseq [f listeners]
                          (try
                            (f x)
                            (catch Throwable ex)))
                        (recur))
                     (str ::message-loop))
                (.setDaemon true)
                (.start)))))
