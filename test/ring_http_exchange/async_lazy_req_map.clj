(ns ring-http-exchange.async-lazy-req-map
  (:require
    [clj-http.client :as client]
    [clojure.test :refer [deftest is testing]]
    [ring-http-exchange.core :as server]
    [ring.core.protocols :as protocols])
  (:import (java.io ByteArrayInputStream File OutputStream)))


(defn- cwd [] (System/getProperty "user.dir"))

(defrecord Response [body headers status])

(deftype CustomStreamableType [])

(extend-protocol protocols/StreamableResponseBody
  CustomStreamableType
  (write-body-to-stream [_ _ ^OutputStream output-stream]
    (.write output-stream ^bytes (.getBytes "Hello world"))
    (.close output-stream)))

(defn- verify-response
  ([response expected-responses]
   (verify-response response {} expected-responses))
  ([response server-config expected-responses]
   (let [server (server/run-http-server (fn [_ req res]
                                          (req response))
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
              (dissoc "Connection")
              (dissoc "Date")
              (dissoc "Content-length")
              (dissoc "Transfer-encoding"))))
     (is (= (:body expected-responses) (:body response)))
     (server/stop-http-server server))))

(deftest sync-can-use-map-as-response
  (let [response-bodies (list
                          (CustomStreamableType.)
                          "Hello world"
                          (.getBytes "Hello world")
                          (ByteArrayInputStream. (.getBytes "Hello world"))
                          (File. (str (cwd) "/test/resources/helloworld")))]
    (doseq [response-body response-bodies]
      (let [server-response {:body response-body :status 201 :headers {"Content-type" "text/html; charset=utf-8"}}
            expected-response {:headers {"Content-type" "text/html; charset=utf-8"}
                               :status  201
                               :body    "Hello world"}]

        (testing (format "testing %s" (type response-body))
          (verify-response server-response {:lazy-request-map? true
                                            :async?            true}
                           expected-response))))))

(deftest sync-can-use-map-as-response-body-without-explicit-status
  (let [response-bodies (list
                          (CustomStreamableType.)
                          "Hello world"
                          (.getBytes "Hello world")
                          (ByteArrayInputStream. (.getBytes "Hello world"))
                          (File. (str (cwd) "/test/resources/helloworld")))]

    (doseq [response-body response-bodies]
      (let [server-response {:body response-body :headers {"Content-type" "text/html; charset=utf-8"}}
            expected-response {:headers {"Content-type" "text/html; charset=utf-8"}
                               :status  200
                               :body    "Hello world"}]

        (testing (format "testing %s" (type response-body))
          (verify-response server-response {:lazy-request-map? true
                                            :async?            true} expected-response))))))


(deftest sync-can-use-map-as-response-body-without-explicit-status-when-record-support-is-false
  (let [response-bodies (list
                          (CustomStreamableType.)
                          "Hello world"
                          (.getBytes "Hello world")
                          (ByteArrayInputStream. (.getBytes "Hello world"))
                          (File. (str (cwd) "/test/resources/helloworld")))]

    (doseq [response-body response-bodies]
      (let [server-response {:body response-body :headers {"Content-type" "text/html; charset=utf-8"}}
            expected-response {:headers {"Content-type" "text/html; charset=utf-8"}
                               :status  200
                               :body    "Hello world"}]

        (testing (format "testing %s" (type response-body))
          (verify-response server-response {:record-support?   false
                                            :lazy-request-map? true
                                            :async?            true} expected-response))))))


(deftest sync-can-use-record-as-response
  (doseq [response-body (list
                          (CustomStreamableType.)
                          "Hello world"
                          (.getBytes "Hello world")
                          (ByteArrayInputStream. (.getBytes "Hello world"))
                          (File. (str (cwd) "/test/resources/helloworld")))]

    (let [server-response (Response. response-body {"Content-type" "text/html; charset=utf-8"} 200)
          expected-response {:status  200
                             :headers {"Content-type" "text/html; charset=utf-8"}
                             :body    "Hello world"}]

      (verify-response server-response {:record-support?   true
                                        :async?            true
                                        :lazy-request-map? true} expected-response))))


(deftest sync-can-use-map-as-response-body-without-explicit-status-when-record-support-is-true
  (let [response-bodies (list
                          (CustomStreamableType.)
                          "Hello world"
                          (.getBytes "Hello world")
                          (ByteArrayInputStream. (.getBytes "Hello world"))
                          (File. (str (cwd) "/test/resources/helloworld")))]

    (doseq [response-body response-bodies]
      (let [server-response {:body response-body :headers {"Content-type" "text/html; charset=utf-8"}}
            expected-response {:headers {"Content-type" "text/html; charset=utf-8"}
                               :status  200
                               :body    "Hello world"}]

        (testing (format "testing %s" (type response-body))
          (verify-response server-response {:record-support?   true
                                            :async?            true
                                            :lazy-request-map? true} expected-response))))))


(defn verify-request-map [server-config expected-request-map]
  (let [
        server (server/run-http-server (fn [req res rej]
                                         (res {:status  200
                                               :headers {}
                                               :body    (str {:uri (:uri req)
                                                              :server-port (:server-port req)
                                                              :request-method (:request-method req)
                                                              :query-string (:query-string req)
                                                              :remote-addr (:remote-addr req)
                                                              :scheme (:scheme req)
                                                              :server-name (:server-name req)

                                                              :protocol (:protocol req)})}))
                                       server-config)
        response (client/get (format "http://localhost:%s/" (:port server-config)))]
    (is (= (:status response) 200))
    (is (= (clojure.edn/read-string (:body response)) expected-request-map))
    (server/stop-http-server server)))

(deftest test-request-map-get-request
  (let [server-config {:host "127.0.0.1"
                       :port 8085
                       :async?            true
                       :lazy-request-map? true}
        expected-request-map {:protocol       "HTTP/1.1"
                              :query-string   nil
                              :remote-addr    "127.0.0.1"
                              :request-method :get
                              :scheme         :http
                              :server-name    "localhost:8085"
                              :server-port    8085
                              :uri            "/"}]
    (verify-request-map server-config  expected-request-map)))