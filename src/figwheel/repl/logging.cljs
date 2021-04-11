(ns figwheel.repl.logging
  (:require [goog.log :as glog]
            [goog.object :as gobj]
            [clojure.string :as string])
  (:import [goog.debug.Console]
           [goog.debug.Logger]))

(defn cljs-version->value [v]
  (try
    (->> (string/split v #"[^\d]")
         (take 3)
         (map #(js/parseInt %))
         (map * [100000000000 10000 1])
         (reduce +))
    (catch js/Error e
      (* 100000000000 100))))

(defn get-logger [nm]
  (if (>= (cljs-version->value *clojurescript-version*)
          (cljs-version->value "1.10.844"))
    (goog.debug.Logger. nm)
    (.call glog/getLogger nil nm)))

(defn error [log msg-ex]
  (.call glog/error nil log msg-ex))

(defn fine [log msg]
  (.call glog/fine nil log msg))

(defn info [log msg]
  (.call glog/info nil log msg))

(defn warning [log msg]
  (.call glog/warning nil log msg))

(defn debug [log msg]
  (.call glog/log nil log goog.debug.Logger.Level.FINEST msg))

(defn console-logging []
  (when-not (gobj/get goog.debug.Console "instance")
    (let [c (goog.debug.Console.)]
      ;; don't display time
      (doto (.getFormatter c)
        (gobj/set "showAbsoluteTime" false)
        (gobj/set "showRelativeTime" false))
      (gobj/set goog.debug.Console "instance" c)
      c))
  (when-let [console-instance (gobj/get goog.debug.Console "instance")]
    (.setCapturing console-instance true)
    true))

(def log-levels
  (into {}
        (map (juxt
              string/lower-case
              #(gobj/get goog.debug.Logger.Level %))
             (map str '(SEVERE WARNING INFO CONFIG FINE FINER FINEST)))))

(defn set-log-level [logger' level]
  (if-let [lvl (get log-levels level)]
    (do
      (debug logger' (str "setting log level to " level))
      (.setLevel logger' lvl))
    (warning logger'
             (str "Log level " (pr-str level) " doesn't exist must be one of "
                  (pr-str '("severe" "warning" "info" "config" "fine" "finer" "finest"))))))

(defonce log-console (console-logging))


