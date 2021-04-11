(defproject com.bhauman/figwheel-repl "0.2.14-SNAPSHOT"
  :description  "Figwheel REPL provides a stable multiplexing REPL for ClojureScript"
  :url "https://github.com/bhauman/figwheel-repl"
  :license {:name "Eclipse Public License - v 1.0"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :scm {:name "git"
        :url "https://github.com/bhauman/figwheel-repl"}
  
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [org.clojure/clojurescript "1.10.339"]
                 [ring/ring-core "1.9.1"]
                 [ring/ring-defaults "0.3.2"]
                 [ring/ring-devel "1.9.1"]
                 [co.deps/ring-etag-middleware "0.2.1"]
                 [ring-cors "0.1.13"]]
  
  ;; for figwheel jetty server - these should probably
  :profiles {:dev {:dependencies [[ring "1.9.1"]
                                  [org.eclipse.jetty.websocket/websocket-servlet "9.4.36.v20210114"]
                                  [org.eclipse.jetty.websocket/websocket-server  "9.4.36.v20210114"]                                  ]
                   :resource-paths ["resources" "dev-resources"]}
             }
  )
