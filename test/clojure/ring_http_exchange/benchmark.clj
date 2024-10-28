(ns ring-http-exchange.benchmark
  (:require
    [babashka.http-client :as client]
    [clojure.test :refer [deftest testing]]
    [criterium.core :as criterium]
    [ring-http-exchange.core :as server])
  (:import (java.util.concurrent Executors)))


(def ^:const ^:private response-body "hello world")

(deftest testing-benchmark
  (let [run-test false]
    (when run-test
      (let [port 5924
            route "/"
            srv (server/run-http-server (fn [req]
                                          {:status  200
                                           :headers {}
                                           :body    (do
                                                      response-body)})
                                        {:executor (Executors/newVirtualThreadPerTaskExecutor)
                                         :port     port})]

        (testing (format "testing route %s" route)
          (criterium/report-result
            (criterium/bench (client/get (format "http://localhost:%s%s" (str port) route)))))
        (server/stop-http-server srv)))))



