(ns ring-http-exchange.sync-lazy-req-map
  (:require
    [clj-http.client :as client]
    [clojure.test :refer [deftest is testing]]
    [ring-http-exchange.core :as server]
    [ring.core.protocols :as protocols])
  (:import (java.io ByteArrayInputStream File OutputStream)
           (org.apache.http NoHttpResponseException)))


(defn- cwd [] (System/getProperty "user.dir"))

(defrecord Response [body headers status])

(deftype CustomStreamableType [])

(extend-protocol protocols/StreamableResponseBody
  CustomStreamableType
  (write-body-to-stream [_ _ ^OutputStream output-stream]
    (.write output-stream ^bytes (.getBytes "Hello world"))
    (.close output-stream)))

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
          (verify-response server-response {:lazy-request-map? true}
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
          (verify-response server-response {:lazy-request-map? true} expected-response))))))


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
                                            } expected-response))))))


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
                                        :lazy-request-map? true} expected-response))))

(deftest sync-cant-use-record-as-response-when-record-support-is-disabled
  (doseq [response-body (list
                          (CustomStreamableType.)
                          "Hello world"
                          (.getBytes "Hello world")
                          (ByteArrayInputStream. (.getBytes "Hello world"))
                          (File. (str (cwd) "/test/resources/helloworld")))]

    (let [server-config {:record-support?   false
                         :lazy-request-map? true}
          server-response (Response. response-body {"Content-type" "text/html; charset=utf-8"} 200)]

      (let [server (server/run-http-server (fn [_]
                                             server-response)
                                           server-config)]
        (try
          (client/get (format "http%s://localhost:%s" (if (:ssl-context server-config)
                                                        "s"
                                                        "")
                              (if (:port server-config)
                                (:port server-config)
                                8080))
                      {:insecure?        true
                       :throw-exceptions true})
          (is (= -1 -2) " Should fail")
          (catch Exception e
            (is (instance? NoHttpResponseException e))))
        (server/stop-http-server server)))))



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
                                            :lazy-request-map? true} expected-response))))))


