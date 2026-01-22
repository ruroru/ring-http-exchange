(ns ^:no-doc ring-http-exchange.core.request
  (:require [clojure.tools.logging :as logger])
  (:import (com.sun.net.httpserver Headers HttpExchange HttpsExchange)
           (java.net URI)
           (java.security.cert X509Certificate)
           (java.util List Map Map$Entry Set)
           (javax.net.ssl SSLSession)))

(def ^:const ^:private comma ",")
(def ^:private method-cache ^Map (Map/of
                                   "GET" :get
                                   "  POST" :post
                                   "PUT" :put
                                   "PATCH" :patch
                                   "DELETE" :delete
                                   "HEAD" :head
                                   "OPTIONS" :options
                                   "TRACE" :trace))


(defn- get-request-headers [^Set entry-set]
  (let [arr (.toArray entry-set)
        len (alength arr)]
    (loop [m (transient {})
           i 0]
      (if (< i len)
        (let [^Map$Entry map-entry (aget arr i)
              ^List header-list (.getValue map-entry)
              k (.getKey map-entry)
              v (if (= 1 (.size header-list))
                  (.get header-list 0)
                  (String/join ^String comma header-list))]
          (recur (assoc! m k v)
                 (inc i)))
        (persistent! m)))))


(defn- convert-certificate [certificate] (cast X509Certificate certificate))

(defn- get-certificate [^SSLSession session]
  (try
    (map convert-certificate (.getPeerCertificates session))
    (catch Exception t
      (when (logger/enabled? :debug)
        (logger/warnf "Unable to parse certificate due to: %s" (.getMessage ^Throwable t)))
      (make-array X509Certificate 0))))

(defn- ^:private get-request-method-val [^String method-string]
  (or (.get ^Map method-cache method-string)
      (keyword (.toLowerCase method-string))))

(defn- ^:private get-headers-map [^Headers headers]
  (get-request-headers (.entrySet headers)))


(defn get-https-client-cert-exchange-request-map [host port ^HttpsExchange exchange]
  {:body            (.getRequestBody exchange)
   :request-method  (get-request-method-val (.getRequestMethod exchange))
   :headers         (get-headers-map (.getRequestHeaders exchange))
   :uri             (.getPath (.getRequestURI exchange))
   :query-string    (.getQuery (.getRequestURI exchange))
   :server-port     port
   :scheme          :https
   :protocol        (.getProtocol exchange)
   :remote-addr     (.getHostString (.getRemoteAddress exchange))
   :server-name     host
   :ssl-client-cert (get-certificate ^SSLSession (.getSSLSession exchange))})


(defn get-https-exchange-request-map [host port ^HttpsExchange exchange]
  {:body           (.getRequestBody exchange)
   :request-method (get-request-method-val (.getRequestMethod exchange))
   :headers        (get-headers-map (.getRequestHeaders exchange))
   :uri            (.getPath ^URI (.getRequestURI exchange))
   :query-string   (.getQuery ^URI (.getRequestURI exchange))
   :server-port    port
   :scheme         :https
   :protocol       (.getProtocol exchange)
   :remote-addr    (.getHostString (.getRemoteAddress exchange))
   :server-name    host})

(defn get-http-exchange-request-map [host port ^HttpExchange exchange]
  {:body           (.getRequestBody exchange)
   :request-method (get-request-method-val (.getRequestMethod exchange))
   :headers        (get-headers-map (.getRequestHeaders exchange))
   :uri            (.getPath ^URI (.getRequestURI exchange))
   :query-string   (.getQuery ^URI (.getRequestURI exchange))
   :server-port    port
   :scheme         :http
   :protocol       (.getProtocol exchange)
   :remote-addr    (.getHostString (.getRemoteAddress exchange))
   :server-name    host})
