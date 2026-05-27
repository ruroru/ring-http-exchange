(ns ring-http-exchange.request-map-fields-test
  (:require
    [clj-http.client :as client]
    [clojure.edn :as edn]
    [clojure.test :refer [deftest is testing]]
    [ring-http-exchange.core :as server]))

(defn- request-map-keys
  "Starts a server with the given config, makes a GET request, and returns
   the set of keys present in the request map."
  [server-config]
  (let [server (server/run-http-server
                 (fn [req]
                   {:status  200
                    :headers {}
                    :body    (str (keys req))})
                 server-config)
        response (client/get (format "http://localhost:%s/" (:port server-config)))
        ks (edn/read-string (:body response))]
    (server/stop-http-server server)
    (set ks)))

(def ^:private all-fields
  #{:body :request-method :headers :uri :query-string
    :server-port :scheme :protocol :remote-addr :server-name})

(deftest nil-request-map-fields-returns-all-fields
  (let [ks (request-map-keys {:port 8086
                              :request-map-fields nil})]
    (is (= all-fields ks))))

(deftest default-returns-all-fields
  (let [ks (request-map-keys {:port 8086})]
    (is (= all-fields ks))))

(deftest request-map-fields-filters-to-specified-keys
  (let [fields #{:body :headers :uri}
        ks (request-map-keys {:port              8086
                              :request-map-fields fields})]
    (is (= fields ks))))

(deftest request-map-fields-single-field
  (let [ks (request-map-keys {:port              8086
                              :request-map-fields #{:uri}})]
    (is (= #{:uri} ks))))

(deftest request-map-fields-values-are-correct
  (let [fields #{:uri :scheme :request-method}
        server (server/run-http-server
                 (fn [req]
                   {:status  200
                    :headers {}
                    :body    (str req)})
                 {:host               "127.0.0.1"
                  :port               8086
                  :request-map-fields fields})
        response (client/get "http://localhost:8086/")
        result (edn/read-string (:body response))]
    (is (= {:uri            "/"
            :scheme         :http
            :request-method :get}
           result))
    (server/stop-http-server server)))

(deftest request-map-fields-with-record-support-disabled
  (let [ks (request-map-keys {:port              8086
                              :record-support?    false
                              :request-map-fields #{:body :uri :scheme}})]
    (is (= #{:body :uri :scheme} ks))))

(deftest request-map-fields-with-async
  (let [fields #{:body :headers :uri :request-method}
        server (server/run-http-server
                 (fn [req respond _raise]
                   (respond {:status  200
                             :headers {}
                             :body    (str (keys req))}))
                 {:port               8086
                  :async?             true
                  :request-map-fields fields})
        response (client/get "http://localhost:8086/")
        ks (set (edn/read-string (:body response)))]
    (is (= fields ks))
    (server/stop-http-server server)))
