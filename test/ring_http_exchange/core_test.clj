(ns ring-http-exchange.core-test
  (:require
    [clj-http.client :as client]
    [clojure.java.io :as io]
    [clojure.string :as string]
    [clojure.test :refer [deftest is]]
    [compojure.core :refer [GET defroutes]]
    [ring-http-exchange.core :as server]
    [ring-http-exchange.ssl :as ssl])
  (:import (java.util.concurrent Executors)))

(defroutes app (GET "/" []
                 (fn [req]
                   (if (= "/error" (:uri req))
                     (throw (Exception. "Internal error"))
                     "hello world"))))

(def default-password "password")

(defn verify-https-server [server-config]
  (let [server (server/run-http-server app server-config)
        response (client/get (format "https://localhost:%s" (:port server-config))
                             {:insecure? true})]
    (is (= 200 (:status response)))
    (is (= "hello world" (:body response)))
    (is (= {"Content-type" "text/html; charset=utf-8"}
           (dissoc (:headers response)
                   "Date"
                   "Content-length")))
    (server/stop-http-server server)))


(deftest port-defaults-to-8080
  (let [port 8080
        server (server/run-http-server app {})
        response (client/get (format "http://localhost:%s" port))]

    (is (= 200 (:status response)))
    (is (= "hello world" (:body response)))
    (is (= {"Content-type" "text/html; charset=utf-8"}
           (dissoc (:headers response)
                   "Date"
                   "Content-length")))
    (server/stop-http-server server)))


(deftest can-override-http-port
  (let [port 8081
        server (server/run-http-server app {:port port})
        response (client/get (format "http://localhost:%s" port))]

    (is (= 200 (:status response)))
    (is (= "hello world" (:body response)))
    (is (= {"Content-type" "text/html; charset=utf-8"} (dissoc (:headers response)
                                                               "Date"
                                                               "Content-length")))
    (server/stop-http-server server)))

(deftest can-use-external-ssl-context
  (verify-https-server {:port        6443
                        :ssl-context (ssl/keystore->ssl-context
                                       (io/resource "keystore.jks")
                                       default-password
                                       (io/resource "truststore.jks")
                                       default-password)}))

(deftest can-use-external-executor
  (let [port 8083
        server (server/run-http-server (fn [_]
                                         {:status  200
                                          :headers {}
                                          :body    (.toString ^Thread (Thread/currentThread))})
                                       {:executor (Executors/newVirtualThreadPerTaskExecutor)
                                        :port     port})
        response (client/get (format "http://localhost:%s/" port))]
    (is (= 200 (:status response)))
    (is (= true (string/starts-with? (:body response) "VirtualThread")))
    (server/stop-http-server server)))


(deftest can-survive-exceptions
  (let [port 8083
        server (server/run-http-server (fn [req]
                                         (if (= (:uri req) "/error")
                                           (throw (Exception. "Internal exception"))
                                           {:status  200
                                            :headers {}
                                            :body    "hello world"}))
                                       {:executor (Executors/newVirtualThreadPerTaskExecutor)
                                        :port     port})
        response1 (client/get (format "http://localhost:%s/error" port)
                              {:throw-exceptions false})
        response2 (client/get (format "http://localhost:%s/" port))]
    (is (= 500 (:status response1)))
    (is (= "Internal Server Error" (:body response1)))

    (is (= 200 (:status response2)))
    (is (= "hello world" (:body response2)))
    (server/stop-http-server server)))