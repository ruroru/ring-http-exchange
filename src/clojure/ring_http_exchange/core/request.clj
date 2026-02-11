(ns ^:no-doc ring-http-exchange.core.request
  (:require [clojure.tools.logging :as logger])
  (:import (clojure.lang IFn ILookup)
           (com.sun.net.httpserver Headers HttpExchange HttpsExchange)
           (java.net URI)
           (java.security.cert X509Certificate)
           (java.util List Map Map$Entry Set)
           (javax.net.ssl SSLSession)))

(def ^:private get-method "GET")
(def ^:const ^:private comma ",")
(def ^:private method-cache ^Map (Map/of
                                   "POST" :post
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

(defn- get-method-or-default [ ^String method-string]
  (or (.get ^Map method-cache method-string)
      (keyword (.toLowerCase ^String method-string))))

(defn-  get-request-method-val [^String method-string]
  (if (.equals ^String get-method method-string )
    :get
    (get-method-or-default method-string)))

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

(deftype LazyHttpRequest [^HttpExchange exchange]
  ILookup
  (valAt [this k]
    (.valAt this k nil))
  (valAt [this k not-found]
    (case k
      :body (.getRequestBody exchange)
      :request-method (get-request-method-val (.getRequestMethod exchange))
      :headers (get-headers-map (.getRequestHeaders exchange))
      :uri (.getPath ^URI (.getRequestURI exchange))
      :query-string (.getQuery ^URI (.getRequestURI exchange))
      :server-port (.getPort (.getLocalAddress exchange))
      :scheme :http
      :protocol (.getProtocol exchange)
      :remote-addr (.getHostString (.getRemoteAddress exchange))
      :server-name (or (.getFirst (.getRequestHeaders exchange) "Host")
                       (.getHostString (.getLocalAddress exchange)))
      not-found))

  IFn
  (invoke [this k]
    (.valAt this k))
  (invoke [this k not-found]
    (.valAt this k not-found)))

(deftype LazyHttpsRequest [^HttpsExchange exchange]
  ILookup
  (valAt [this k]
    (.valAt this k nil))
  (valAt [this k not-found]
    (case k
      :body (.getRequestBody exchange)
      :request-method (get-request-method-val (.getRequestMethod exchange))
      :headers (get-headers-map (.getRequestHeaders exchange))
      :uri (.getPath ^URI (.getRequestURI exchange))
      :query-string (.getQuery ^URI (.getRequestURI exchange))
      :server-port (.getPort (.getLocalAddress exchange))
      :scheme :https
      :protocol (.getProtocol exchange)
      :remote-addr (.getHostString (.getRemoteAddress exchange))
      :server-name (or (.getFirst (.getRequestHeaders exchange) "Host")
                       (.getHostString (.getLocalAddress exchange)))
      not-found))

  IFn
  (invoke [this k]
    (.valAt this k))
  (invoke [this k not-found]
    (.valAt this k not-found)))


(deftype LazyHttpsClientCertRequest [^HttpsExchange exchange]
  ILookup
  (valAt [this k]
    (.valAt this k nil))
  (valAt [this k not-found]
    (case k
      :body (.getRequestBody exchange)
      :request-method (get-request-method-val (.getRequestMethod exchange))
      :headers (get-headers-map (.getRequestHeaders exchange))
      :uri (.getPath ^URI (.getRequestURI exchange))
      :query-string (.getQuery ^URI (.getRequestURI exchange))
      :server-port (.getPort (.getLocalAddress exchange))
      :scheme :https
      :protocol (.getProtocol exchange)
      :remote-addr (.getHostString (.getRemoteAddress exchange))
      :server-name (or (.getFirst (.getRequestHeaders exchange) "Host")
                       (.getHostString (.getLocalAddress exchange)))
      :ssl-client-cert (get-certificate ^SSLSession (.getSSLSession exchange))
      not-found))

  IFn
  (invoke [this k]
    (.valAt this k))
  (invoke [this k not-found]
    (.valAt this k not-found)))

(defn ->LazyHttpRequest [exchange]
  (LazyHttpRequest. exchange))

(defn ->LazyHttpsRequest [exchange]
  (LazyHttpsRequest. exchange))

(defn ->LazyHttpsClientCertRequest [exchange]
  (LazyHttpsClientCertRequest. exchange))

