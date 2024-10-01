(ns ring-http-exchange.core-test
  (:require
    [babashka.fs :as bfs]
    [clj-http.client :as client]
    [clojure.java.io :as io]
    [clojure.string :as string]
    [clojure.test :refer [deftest is]]
    [compojure.core :refer [GET defroutes]]
    [ring-http-exchange.core :as server]
    [ring-http-exchange.ssl :as ssl])
  (:import (java.io ByteArrayInputStream File)
           (java.util.concurrent Executors)))

(defroutes app (GET "/" [] "hello world"))

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


(deftest can-use-nil-in-body
  (let [port 8080
        server (server/run-http-server (fn [_]
                                         {:status  200
                                          :headers {"Content-type" "text/html; charset=utf-8"}
                                          :body    nil}) {})
        response (client/get (format "http://localhost:%s" port))]

    (is (= 200 (:status response)))
    (is (= "" (:body response)))
    (is (= {"Content-type"      "text/html; charset=utf-8"
            "Transfer-encoding" "chunked"}
           (dissoc (:headers response)
                   "Date")))
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

(deftest can-use-ssl-context
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


(deftest can-use-file-as-response-body
  (let [port 8080
        server (server/run-http-server (fn [_]
                                         {:status  200
                                          :headers {"Content-type" "text/html; charset=utf-8"}
                                          :body    (File. (str (bfs/cwd) "/test/resources/helloworld"))})
                                       {})
        response (client/get (format "http://localhost:%s" port))]

    (is (= 200 (:status response)))
    (is (= "hello from file" (:body response)))
    (is (= {"Content-length" "15"
            "Content-type"   "text/html; charset=utf-8"}
           (dissoc (:headers response) "Date")))
    (server/stop-http-server server)))

(deftest can-use-nil-as-response-body
  (let [port 8080
        server (server/run-http-server (fn [_]
                                         {:status  200
                                          :headers {"Content-type" "text/html; charset=utf-8"}
                                          :body    nil})
                                       {})
        response (client/get (format "http://localhost:%s" port))]

    (is (= 200 (:status response)))
    (is (= "" (:body response)))
    (is (= {"Transfer-encoding" "chunked"
            "Content-type"      "text/html; charset=utf-8"}
           (dissoc (:headers response) "Date")))
    (server/stop-http-server server)))


(deftest can-use-byte-array-as-response-body
  (let [port 8080
        server (server/run-http-server (fn [_]
                                         {:status  200
                                          :headers {"Content-type" "text/html; charset=utf-8"}
                                          :body    (.getBytes "hello world")})
                                       {})
        response (client/get (format "http://localhost:%s" port))]

    (is (= 200 (:status response)))
    (is (= "hello world" (:body response)))
    (is (= {"Content-length" "11"
            "Content-type"   "text/html; charset=utf-8"}
           (dissoc (:headers response) "Date")))
    (server/stop-http-server server)))

(deftest can-use-input-stream-as-response-body-without-content-length-header
  (let [port 8080
        byte-array (.getBytes "hello input stream")

        server (server/run-http-server (fn [_]
                                         {:status  201
                                          :headers {
                                                    "Content-type" "text/html; charset=utf-8"
                                                    }
                                          :body    (ByteArrayInputStream. byte-array)})
                                       {})
        response (client/get (format "http://localhost:%s" port))]

    (is (= 201 (:status response)))
    (is (= "hello input stream" (:body response)))
    (is (= {"Transfer-encoding" "chunked"
            "Content-type"      "text/html; charset=utf-8"}
           (dissoc (:headers response) "Date")))
    (server/stop-http-server server)))

(deftest input-stream-without-content-length-sends-chunked-response
  (let [
        byte-array (.getBytes "hello input stream")
        headers {"Content-length" 18
                 "Content-type"   "text/html; charset=utf-8"
                 }
        port 8080
        server (server/run-http-server (fn [_]
                                         {:status  201
                                          :headers headers
                                          :body    (ByteArrayInputStream. byte-array)})
                                       {})
        response (client/get (format "http://localhost:%s" port))
        ]

    (is (= 201 (:status response)))
    (is (= {"Content-type"   "text/html; charset=utf-8"
            "Content-length" "18"}
           (dissoc (:headers response) "Date")))
    (is (= "hello input stream" (:body response)))
    (server/stop-http-server server)))


