(defproject org.clojars.jj/ring-http-exchange "1.0.2"
  :description "Ring adapter for com.sun.net.httpserver"
  :url "https://github.com/ruroru/ring-http-exchange"
  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.12.0"]
                 [org.clojure/tools.logging "1.3.0"]
                 [ring/ring-core "1.13.0"]]

  :source-paths ["src/clojure"]

  :profiles {:test {:resource-paths ["test/resources"]
                    :global-vars {*warn-on-reflection* true}
                    :source-paths   ["test/clojure"]
                    :dependencies   [
                                     [babashka/fs "0.5.22"]
                                     [clj-http "3.13.0"]
                                     ]}}

  :plugins [[lein-ancient "0.7.0"]])
