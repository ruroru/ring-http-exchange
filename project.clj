(defproject org.clojars.jj/ring-http-exchange "1.2.3-SNAPSHOT"
  :description "Ring adapter for com.sun.net.httpserver"
  :url "https://github.com/ruroru/ring-http-exchange"
  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.12.2"]
                 [org.clojure/tools.logging "1.3.0"]
                 [org.ring-clojure/ring-core-protocols "1.14.2"]]

  :source-paths ["src/clojure"]

  :deploy-repositories [["clojars" {:url      "https://repo.clojars.org"
                                    :username :env/clojars_user
                                    :password :env/clojars_pass}]]

  :profiles {:test {:resource-paths ["test/resources"]
                    :source-paths   ["test/clojure"]
                    :dependencies   [[babashka/fs "0.5.27"]
                                     [clj-http "3.13.1"]]}}

  :plugins [[org.clojars.jj/bump "1.0.4"]
            [org.clojars.jj/bump-md "1.1.0"]
            [org.clojars.jj/strict-check "1.0.2"]])
