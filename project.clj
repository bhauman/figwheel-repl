(defproject com.bhauman/figwheel-repl "0.1.6"
  :description  "Figwheel REPL provides a stable multiplexing REPL for ClojureScript"
  :url "https://github.com/bhauman/figwheel-repl"
  :license {:name "Eclipse Public License - v 1.0"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :scm {:name "git"
        :url "https://github.com/bhauman/figwheel-repl"}
  
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/clojurescript "1.10.238"]
                 [ring/ring-core "1.6.3"]
                 [ring/ring-defaults "0.3.1"]
                 [ring/ring-devel "1.6.3"]
                 [co.deps/ring-etag-middleware "0.2.0"]
                 [ring-cors "0.1.12"]]


  
  ;; for figwheel jetty server - these should probably
  :profiles {:dev {:dependencies [[ring "1.6.3"]
                                  [org.eclipse.jetty.websocket/websocket-servlet "9.2.21.v20170120"]
                                  [org.eclipse.jetty.websocket/websocket-server "9.2.21.v20170120"]]
                   :resource-paths ["resources" "dev-resources"]}
             }
  )
