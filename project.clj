(defproject jj/ring-http-exchange "1.0.0"
  :description "Ring adapter for com.sun.net.httpserver"
  :url "https://github.com/ruroru/ring-http-exchange"
  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.11.4"]
                 [ring/ring-core "1.12.2"]]

  :profiles {:test {:resource-paths ["test-resources"]
                    :dependencies   [
                                     [compojure "1.7.1"]
                                     [babashka/fs "0.5.22"]
                                     [clj-http "3.13.0"]
                                     ]}}

  :plugins [[lein-ancient "0.7.0"]])
