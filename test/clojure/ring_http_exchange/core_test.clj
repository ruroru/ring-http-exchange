(ns ring-http-exchange.core-test
  (:require
    [babashka.fs :as bfs]
    [clj-http.client :as client]
    [clojure.java.io :as io]
    [clojure.string :as string]
    [clojure.test :refer [deftest is]]
    [ring-http-exchange.core :as server]
    [ring-http-exchange.ssl :as ssl])
  (:import (java.io ByteArrayInputStream File)
           (java.util.concurrent Executors)))

(def default-password "password")

(defn- verify-response
  ([response-body expected-responses]
   (verify-response response-body {} expected-responses))
  ([response-body server-config expected-responses]
   (let [server (server/run-http-server (fn [_]
                                          response-body)
                                        server-config)
         response (client/get (format "http%s://localhost:%s" (if (:ssl-context server-config)
                                                                "s"
                                                                "")
                                      (if (:port server-config)
                                        (:port server-config)
                                        8080))
                              {:insecure? true})
         ]

     (is (= (:status expected-responses) (:status response)))
     (is (= (:headers expected-responses)
            (dissoc (:headers response) "Date")))
     (is (= (:body expected-responses) (:body response)))
     (server/stop-http-server server)))
  )

(deftest port-defaults-to-8080
  (let [server-response {:status  200
                         :headers {"Content-type" "text/html; charset=utf-8"}
                         :body    "hello world"}

        expected-response {:status  200
                           :headers {"Content-length" "11"
                                     "Content-type"   "text/html; charset=utf-8"}
                           :body    "hello world"}]
    (verify-response server-response expected-response)))

(deftest can-use-nil-in-body
  (let [server-response {:status  200
                         :headers {"Content-type" "text/html; charset=utf-8"}
                         :body    nil}
        server-config {:port 8081}
        expected-response {:status  200
                           :headers {"Content-type"      "text/html; charset=utf-8"
                                     "Transfer-encoding" "chunked"}
                           :body    ""}]
    (verify-response server-response server-config expected-response)))

(deftest can-override-http-port
  (let [server-response {:status  200
                         :headers {"Content-type" "text/html; charset=utf-8"}
                         :body    "hello world"}
        server-config {:port 8081}
        expected-response {:status  200
                           :headers {"Content-length" "11"
                                     "Content-type"   "text/html; charset=utf-8"}
                           :body    "hello world"}]
    (verify-response server-response server-config expected-response)))

(deftest can-use-ssl-context
  (let [server-response {:status  200
                         :headers {"Content-type" "text/html; charset=utf-8"}
                         :body    "hello world"}
        server-config {:port        6443
                       :ssl-context (ssl/keystore->ssl-context
                                      (io/resource "keystore.jks")
                                      default-password
                                      (io/resource "truststore.jks")
                                      default-password)}
        expected-response {:status  200
                           :headers {"Content-length" "11"
                                     "Content-type"   "text/html; charset=utf-8"}
                           :body    "hello world"}]
    (verify-response server-response server-config expected-response)))

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

(deftest can-use-file-as-response-body
  (let [server-response {:status  200
                         :headers {"Content-type" "text/html; charset=utf-8"}
                         :body    (File. (str (bfs/cwd) "/test/resources/helloworld"))}

        expected-response {:status  200
                           :headers {"Content-length" "15"
                                     "Content-type"   "text/html; charset=utf-8"}
                           :body    "hello from file"}]
    (verify-response server-response expected-response)))

(deftest can-use-nil-as-response-body
  (let [server-response {:status  200
                         :headers {"Content-type" "text/html; charset=utf-8"}
                         :body    nil}

        expected-response {:status  200
                           :headers {"Transfer-encoding" "chunked"
                                     "Content-type"      "text/html; charset=utf-8"}
                           :body    ""}]
    (verify-response server-response expected-response)))

(deftest can-use-byte-array-as-response-body
  (let [server-response {:status  200
                         :headers {"Content-type" "text/html; charset=utf-8"}
                         :body    (.getBytes "hello world")}

        expected-response {:status  200
                           :headers {"Content-length" "11"
                                     "Content-type"   "text/html; charset=utf-8"}
                           :body    "hello world"}]
    (verify-response server-response expected-response)))

(deftest can-use-input-stream-as-response-body-without-content-length-header
  (let [server-response {:status  201
                         :headers {"Content-type" "text/html; charset=utf-8"}
                         :body    (ByteArrayInputStream. (.getBytes "hello input stream"))}

        expected-response {:status  201
                           :headers {"Transfer-encoding" "chunked"
                                     "Content-type"      "text/html; charset=utf-8"}
                           :body    "hello input stream"}]
    (verify-response server-response expected-response)))

(deftest input-stream-without-content-length-sends-chunked-response
  (let [server-response {:status  201
                         :headers {"Content-length" 18
                                   "Content-type"   "text/html; charset=utf-8"}
                         :body    (ByteArrayInputStream. (.getBytes "hello input stream"))}

        expected-response {:status  201
                           :headers {"Content-type"   "text/html; charset=utf-8"
                                     "Content-length" "18"}
                           :body    "hello input stream"}
        ]
    (verify-response server-response expected-response)))

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