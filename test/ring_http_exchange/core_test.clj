(ns ring-http-exchange.core-test
  (:require
    [babashka.fs :as bfs]
    [clojure.java.io :as io]
    [ring-http-exchange.core :as server]
    [compojure.core :refer [defroutes GET]]
    [clojure.test :refer [deftest is]]
    [clj-http.client :as client]
    [ring-http-exchange.ssl :as ssl]))

(defroutes app (GET "/" [] "hello world"))

(def default-password "password")

(defn verify-https-server [server-config]
  (let [server (server/run-http-server app server-config)
        response (client/get (format "https://localhost:%s" (:ssl-port server-config))
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


(deftest can-send-https-request
  (verify-https-server {:ssl-port       2443
                        :ssl?           true
                        :keystore       (format "%s/test-resources/keystore.jks" (bfs/cwd))
                        :key-password   default-password
                        :truststore     (format "%s/test-resources/truststore.jks" (bfs/cwd))
                        :trust-password default-password}))

(deftest supports-pkcs
  (verify-https-server {:ssl-port       3443
                        :ssl?           true
                        :keystore       (format "%s/test-resources/keystore.p12" (bfs/cwd))
                        :key-password   default-password
                        :truststore     (format "%s/test-resources/truststore.p12" (bfs/cwd))
                        :trust-password default-password}))


(deftest can-load-keystore-from-resource-dir
  (verify-https-server {:ssl-port       5443
                        :ssl?           true
                        :keystore       (io/resource "keystore.jks")
                        :key-password   default-password
                        :truststore     (io/resource "truststore.jks")
                        :trust-password default-password}))


(deftest can-use-external-ssl-context
  (verify-https-server {:ssl-port    6443
                        :ssl?        true
                        :ssl-context (ssl/keystore->ssl-context
                                       (io/resource "keystore.jks")
                                       default-password
                                       (io/resource "truststore.jks")
                                       default-password)}))
