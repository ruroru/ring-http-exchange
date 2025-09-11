(ns ring-http-exchange.core-test
  (:require
    [babashka.fs :as bfs]
    [clj-http.client :as client]
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as string]
    [clojure.test :refer [are deftest is testing]]
    [ring-http-exchange.core :as server]
    [ring-http-exchange.ssl :as ssl]
    [ring.core.protocols :as protocols])
  (:import (clojure.lang Keyword)
           (java.io ByteArrayInputStream File OutputStream)
           (java.util Base64)
           (java.util.concurrent Executors)
           (sun.net.httpserver FixedLengthInputStream)))

(def default-password "password")
(defrecord Response [body headers status])

(extend-protocol protocols/StreamableResponseBody
  Keyword
  (write-body-to-stream [body _ ^OutputStream output-stream]
    (.write output-stream ^bytes (.getBytes (name body)))
    (.close output-stream)))

(defn- serialize-x509-certificate-array
  [cert-array]
  (if (= (count cert-array) 0)
    :empty-cert
    (map (fn [cert]
           (let [bytes (.getEncoded cert)]
             (String. (.encodeToString (Base64/getEncoder) bytes)))) cert-array)))

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
                              {:insecure?        true
                               :throw-exceptions false})]

     (is (= (:status expected-responses) (:status response)))
     (is (= (:headers expected-responses)
            (->
              (:headers response)
              (dissoc "Date")
              (dissoc "Content-length")
              (dissoc "Transfer-encoding")
              )))
     (is (= (:body expected-responses) (:body response)))
     (server/stop-http-server server))))

(defn verify-response-with-default-status [server-response expected-response]
  (testing (str "Testing: " server-response)
    (verify-response server-response expected-response))

  (let [server-response-without-status (dissoc server-response :status)]
    (testing (str "Testing: " server-response-without-status)
      (verify-response server-response-without-status expected-response))))

(deftest port-defaults-to-8080
  (let [server-response {:status  200
                         :headers {"Content-type" "text/html; charset=utf-8"}
                         :body    "hello world"}

        expected-response {:status  200
                           :headers {"Content-type"   "text/html; charset=utf-8"}
                           :body    "hello world"}]
    (verify-response server-response expected-response)))

(deftest can-use-nil-in-body
  (let [server-response {:status  200
                         :headers {"Content-type" "text/html; charset=utf-8"}
                         :body    nil}
        server-config {:port 8081}
        expected-response {:status  200
                           :headers {"Content-type"      "text/html; charset=utf-8"}
                           :body    ""}]
    (verify-response server-response server-config expected-response)))

(deftest can-override-http-port
  (let [server-response {:status  200
                         :headers {"Content-type" "text/html; charset=utf-8"}
                         :body    "hello world"}
        server-config {:port 8081}
        expected-response {:status  200
                           :headers {"Content-type"   "text/html; charset=utf-8"}
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
                           :headers {"Content-type"   "text/html; charset=utf-8"}
                           :body    "hello world"}]
    (verify-response server-response server-config expected-response)))

(deftest can-use-ssl-context-with-custom-protocol
  (let [tls-versions ["TLS" "SSL" "TLSv1.3"]]
    (doseq [tls-version tls-versions]
      (testing (format "Testing %s protocol" tls-version)
        (let [server-response {:status  200
                               :headers {"Content-type" "text/html; charset=utf-8"}
                               :body    "hello world"}
              server-config {:port        6443
                             :ssl-context (ssl/keystore->ssl-context
                                            (io/resource "keystore.jks")
                                            default-password
                                            (io/resource "truststore.jks")
                                            default-password
                                            tls-version)}
              expected-response {:status  200
                                 :headers {"Content-type"   "text/html; charset=utf-8"}
                                 :body    "hello world"}]
          (verify-response server-response server-config expected-response))))))

(deftest ssl-context-set-t-nil-creates-http-server
  (let [server-response {:status  200
                         :headers {"Content-type" "text/html; charset=utf-8"}
                         :body    "hello world"}
        server-config {:port        6443
                       :ssl-context nil}
        expected-response {:status  200
                           :headers {"Content-type"   "text/html; charset=utf-8"}
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
                           :headers {"Content-type"   "text/html; charset=utf-8"}
                           :body    "Hello world"}]
    (verify-response-with-default-status server-response expected-response)))


(deftest non-existing-body-returns-500-internal-server-error
  (let [server-response {:status  200
                         :headers {"Content-type" "text/html; charset=utf-8"}
                         :body    (File. (str (bfs/cwd) "/test/resources/not-existing"))}

        expected-response {:status  500
                           :headers {"Content-type"   "text/html"}
                           :body    "Internal Server Error"}]
    (verify-response-with-default-status server-response expected-response)))

(deftest can-use-byte-array-as-response-body
  (let [server-response {:status  200
                         :headers {"Content-type" "text/html; charset=utf-8"}
                         :body    (.getBytes "hello world")}

        expected-response {:status  200
                           :headers {"Content-type"   "text/html; charset=utf-8"}
                           :body    "hello world"}]
    (verify-response-with-default-status server-response expected-response)))

(deftest can-use-input-stream-as-response-body
  (let [server-response {:status  201
                         :headers {"Content-type" "text/html; charset=utf-8"}
                         :body    (ByteArrayInputStream. (.getBytes "hello input stream"))}

        expected-response {:status  201
                           :headers {"Content-type"      "text/html; charset=utf-8"}
                           :body    "hello input stream"}]
    (verify-response server-response expected-response)))


(deftest can-use-input-stream-as-response-body-without-response-status
  (let [server-response {:headers {"Content-type" "text/html; charset=utf-8"}
                         :body    (ByteArrayInputStream. (.getBytes "hello input stream"))}

        expected-response {:status  200
                           :headers {"Content-type"      "text/html; charset=utf-8"}
                           :body    "hello input stream"}]
    (verify-response server-response expected-response)))


(deftest can-survive-exceptions-in-handler
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


(defn verify-request-map [server-config expected-request-map]
  (let [
        server (server/run-http-server (fn [req]
                                         {:status  200
                                          :headers {}
                                          :body    (str (assoc
                                                          (dissoc req :body)
                                                          :headers (dissoc (:headers req) "User-agent")))})
                                       server-config)
        response (client/get (format "http://localhost:%s/" (:port server-config)))]
    (is (= (:status response) 200))
    (is (= (clojure.edn/read-string (:body response)) expected-request-map))
    (server/stop-http-server server)))

(deftest test-request-map-get-request
  (let [server-config {:host "127.0.0.1"
                       :port 8085}
        expected-request-map {
                              :protocol       "HTTP/1.1",
                              :remote-addr    "127.0.0.1",
                              :headers        {"Accept-encoding" "gzip, deflate",
                                               "Connection"      "close",
                                               "Host"            "localhost:8085"},
                              :server-port    8085, :uri "/",
                              :server-name    "127.0.0.1",
                              :query-string   nil,
                              :scheme         :http,
                              :request-method :get}]
    (verify-request-map server-config expected-request-map)))

(deftest test-request-map-head-request
  (let [server-config {:host "localhost"
                       :port 8084}
        expected-request-map {:headers        {"Accept-encoding" "gzip, deflate"
                                               "Connection"      "close"
                                               "Host"            "localhost:8084"}
                              :protocol       "HTTP/1.1"
                              :query-string   nil
                              :remote-addr    "127.0.0.1"
                              :request-method :get
                              :scheme         :http
                              :server-name    "localhost"
                              :server-port    8084
                              :uri            "/"}]
    (verify-request-map server-config expected-request-map)))

(deftest test-request-map-with-query-params
  (let [server-config {:host "localhost"
                       :port 8083}
        expected-request-map {:body           "hello world"
                              :headers        {"Accept-encoding" "gzip, deflate"
                                               "Connection"      "close"
                                               "Content-length"  "11"
                                               "Content-type"    "text/plain; charset=UTF-8"
                                               "Host"            "localhost:8083"}
                              :protocol       "HTTP/1.1"
                              :query-string   "q=query&s=string"
                              :remote-addr    "127.0.0.1"
                              :request-method :put
                              :scheme         :http
                              :server-name    "localhost"
                              :server-port    8083
                              :uri            "/hello-world"}]
    (let [server (server/run-http-server (fn [req]
                                           {:status  200
                                            :headers {}
                                            :body    (str (assoc
                                                            (dissoc req "User-agent")
                                                            :body (String. (.readAllBytes ^FixedLengthInputStream (:body req)))
                                                            :headers (dissoc (:headers req) "User-agent")))})
                                         server-config)
          response (client/put (format "http://localhost:%s/hello-world?q=query&s=string" (:port server-config)) {:body "hello world"})]
      (is (= (:status response) 200))
      (is (= expected-request-map (edn/read-string (:body response))))
      (server/stop-http-server server))))




(deftest test-https-request-headers-with-invalid-certificates
  (let [server-config {:port        9443
                       :ssl-context (ssl/keystore->ssl-context (io/resource "keystore.jks") default-password (io/resource "truststore.jks") default-password)}
        expected-request-map {:body            ""
                              :headers         {"Accept-encoding" "gzip, deflate"
                                                "Connection"      "close"
                                                "Host"            "localhost:9443"}
                              :protocol        "HTTP/1.1"
                              :query-string    nil
                              :request-method  :get
                              :scheme          :https
                              :server-name     "0.0.0.0"
                              :server-port     9443
                              :ssl-client-cert :empty-cert
                              :uri             "/"}]
    (let [server (server/run-http-server (fn [req]
                                           {:status  200
                                            :headers {}
                                            :body    (str (assoc
                                                            req
                                                            :body (String. (.readAllBytes ^FixedLengthInputStream (:body req)))
                                                            :ssl-client-cert (serialize-x509-certificate-array (:ssl-client-cert req))
                                                            :headers (dissoc (:headers req) "User-agent")))})
                                         server-config)
          response (client/get (format "https://localhost:%s/" (:port server-config))
                               {:insecure? true :throw-exceptions false})
          ]
      (is (= (:status response) 200))
      (is (= expected-request-map (dissoc
                                    (edn/read-string (:body response))
                                    :remote-addr
                                    )))
      (server/stop-http-server server))))


(deftest allow-setting-StreamableResponseBody
  (let [server-response {:status  200
                         :headers {"Content-type" "text/html; charset=utf-8"}
                         :body    :false}

        expected-response {:status  200
                           :headers {"Content-type"      "text/html; charset=utf-8"}
                           :body    "false"}]
    (verify-response-with-default-status server-response expected-response)))


(deftest not-supported-body-returns-500-internal-server-error
  (let [server-response {:status  200
                         :headers {"Content-type" "text/htm1l"}
                         :body    1}

        expected-response {:status  500
                           :headers {"Content-type"   "text/html"}
                           :body    "Internal Server Error"}]
    (verify-response server-response expected-response)))


(deftest respose-nil-returns-500-internal-server-error
  (let [server-response nil
        expected-response {:status  500
                           :headers {"Content-type"   "text/html"}
                           :body    "Internal Server Error"}]
    (verify-response server-response expected-response)))


(deftest respose-empty-map-returns-200-empty-response
  (let [server-response {}
        expected-response {:status  200
                           :headers {}
                           :body    ""}]
    (verify-response server-response expected-response)))

(deftest test-invalid-ports-return-nil
  (are [invalid-port] (nil? (server/run-http-server (fn [_] {}) {:port invalid-port}))
                      -1
                      0
                      65537))

(deftest can-use-record-as-response
  (doseq [response-body (list
                          "Hello world"
                          (.getBytes "Hello world")
                          (ByteArrayInputStream. (.getBytes "Hello world"))
                          (File. (str (bfs/cwd) "/test/resources/helloworld")))]

    (let [server-response (Response. response-body {"Content-type" "text/html; charset=utf-8"} 200)
          expected-response {:status  200
                             :headers {"Content-type" "text/html; charset=utf-8"}
                             :body    "Hello world"}]

      (verify-response server-response
                       expected-response))))
