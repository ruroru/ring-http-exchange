(ns ring-http-exchange.async-core-test
  (:require [clj-http.client :as client]
            [clojure.test :refer :all]
            [ring.core.protocols :as protocols]
            [ring-http-exchange.core :as server])
  (:import (java.io ByteArrayInputStream File OutputStream)
           (java.util.concurrent Executors)))


(deftype CustomStreamableType [])
(defn- cwd [] (System/getProperty "user.dir"))

(extend-protocol protocols/StreamableResponseBody
  CustomStreamableType
  (write-body-to-stream [_ _ ^OutputStream output-stream]
    (.write output-stream ^bytes (.getBytes "Hello world"))
    (.close output-stream)))


(defn- verify-response
  ([response expected-responses]
   (verify-response response {} expected-responses))
  ([response server-config expected-responses]
   (let [server (server/run-http-server (fn [req res rej] (res response)) server-config)
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
(defrecord Response [body status headers])


(deftest async-map-support
  (let [response-bodies (list
                          (CustomStreamableType.)
                          "Hello world"
                          (.getBytes "Hello world")
                          (ByteArrayInputStream. (.getBytes "Hello world"))
                          (File. (str (cwd) "/test/resources/helloworld")))]

    (doseq [response-body response-bodies]
      (let [server-response {:body    response-body
                             :headers {"Content-type" "text/html; charset=utf-8"}}
            expected-response {:headers {"Content-type" "text/html; charset=utf-8"}
                               :status  200
                               :body    "Hello world"}]

        (testing (format "testing %s" (type response-body))
          (verify-response server-response {:async?          true
                                            :record-support? false} expected-response))))))

(deftest async-record-support
  (let [response-bodies (list
                          (CustomStreamableType.)
                          "Hello world"
                          (.getBytes "Hello world")
                          (ByteArrayInputStream. (.getBytes "Hello world"))
                          (File. (str (cwd) "/test/resources/helloworld")))]

    (doseq [response-body response-bodies]
      (let [server-response (Response.
                              response-body
                              200
                              {"Content-type" "text/html; charset=utf-8"})
            expected-response {:headers {"Content-type" "text/html; charset=utf-8"}
                               :status  200
                               :body    "Hello world"}]

        (testing (format "testing %s" (type response-body))
          (verify-response server-response {:async?          true
                                            :record-support? true} expected-response))))))

(deftest can-survive-exceptions-in-handler
  (let [port 8083
        server (server/run-http-server (fn [req res rej]
                                         (if (= (:uri req) "/error")
                                           (throw (Exception. "Internal exception"))
                                           (res {:status  200
                                                 :headers {}
                                                 :body    "hello world"})))

                                       {:executor (Executors/newVirtualThreadPerTaskExecutor)
                                        :async?   true
                                        :port     port})
        response1 (client/get (format "http://localhost:%s/error" port)
                              {:throw-exceptions false})
        response2 (client/get (format "http://localhost:%s/" port))]
    (is (= 500 (:status response1)))
    (is (= "Internal Server Error" (:body response1)))
    (is (= 200 (:status response2)))
    (is (= "hello world" (:body response2)))
    (server/stop-http-server server)))