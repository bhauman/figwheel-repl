(defproject com.bhauman/figwheel-repl "0.2.19-JETTY11"
  :description  "Figwheel REPL provides a stable multiplexing REPL for ClojureScript"
  :url "https://github.com/bhauman/figwheel-repl"
  :license {:name "Eclipse Public License - v 1.0"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :scm {:name "git"
        :url "https://github.com/bhauman/figwheel-repl"}
  :dependencies [[org.clojure/clojure "1.10.3"]
                 [org.clojure/clojurescript "1.10.773" :exclusions [commons-codec]]
                 [org.clojure/data.json "2.5.1"]
                 [ring/ring-core "1.13.0"]
                 [ring/ring-defaults "0.5.0"]
                 [ring/ring-devel "1.13.0"]
                 [ring/ring-jetty-adapter "1.13.0"]
                 [org.ring-clojure/ring-jakarta-servlet "1.13.0"]
                 [co.deps/ring-etag-middleware "0.2.1"]
                 [ring-cors "0.1.13"]]

  ;; for figwheel jetty server - these should probably
  :profiles {:dev {:dependencies [[ring "1.13.0"]
                                  [org.eclipse.jetty.websocket/websocket-servlet "11.0.24"]
                                  [org.eclipse.jetty.websocket/websocket-jetty-server "11.0.24"]]
                   :source-paths ["src"]
                   :resource-paths ["resources" "dev-resources"]}
             }
  )
