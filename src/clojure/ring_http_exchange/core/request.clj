(ns ^:no-doc ring-http-exchange.core.request
  (:require [clojure.tools.logging :as logger])
  (:import (clojure.lang Associative Counted IFn IHashEq ILookup IMapEntry
                         IObj IPersistentCollection IPersistentMap MapEntry
                         Seqable)
           (com.sun.net.httpserver Headers HttpExchange HttpsExchange)
           (java.net URI)
           (java.security.cert X509Certificate)
           (java.util Iterator List Map Map$Entry Set)
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

(defn- force-val [v]
  (if (delay? v) @v v))

(defn- force-and-cache! [^IPersistentMap m k raw]
  (if (delay? raw)
    (let [v @raw
          updated (.assoc m k v)]
      [updated v])
    [m raw]))

(deftype LazyMap [^:volatile-mutable ^IPersistentMap delay-map _meta]
  IPersistentMap
  (assoc [_ k v]
    (LazyMap. (.assoc delay-map k v) _meta))
  (assocEx [_ k v]
    (LazyMap. (.assocEx delay-map k v) _meta))
  (without [_ k]
    (LazyMap. (.without delay-map k) _meta))

  IPersistentCollection
  (count [_] (.count delay-map))
  (cons [_ o]
    (LazyMap. (.cons delay-map o) _meta))
  (empty [_] (LazyMap. (.empty delay-map) _meta))
  (equiv [_ o]
    (if (instance? LazyMap o)
      (.equiv delay-map (.-delay-map ^LazyMap o))
      (and (map? o)
           (= (count o) (.count delay-map))
           (every? (fn [^IMapEntry e]
                     (let [k (.key e)]
                       (and (contains? o k)
                            (= (force-val (.val e)) (get o k)))))
                   (.seq delay-map)))))

  Associative
  (containsKey [_ k] (.containsKey delay-map k))
  (entryAt [_ k]
    (when-let [^IMapEntry e (.entryAt delay-map k)]
      (let [[m v] (force-and-cache! delay-map (.key e) (.val e))]
        (set! delay-map m)
        (MapEntry/create (.key e) v))))

  ILookup
  (valAt [_ k]
    (let [raw (.valAt delay-map k)]
      (let [[m v] (force-and-cache! delay-map k raw)]
        (set! delay-map m)
        v)))
  (valAt [_ k not-found]
    (if (.containsKey delay-map k)
      (let [raw (.valAt delay-map k)
            [m v] (force-and-cache! delay-map k raw)]
        (set! delay-map m)
        v)
      not-found))

  Seqable
  (seq [_]
    (when-let [s (.seq delay-map)]
      (map (fn [^IMapEntry e]
             (MapEntry/create (.key e) (force-val (.val e))))
           s)))

  Counted

  IFn
  (invoke [this k] (.valAt this k))
  (invoke [this k not-found] (.valAt this k not-found))

  IObj
  (meta [_] _meta)
  (withMeta [_ m] (LazyMap. delay-map m))

  IHashEq
  (hasheq [this]
    (hash (into {} this)))

  Iterable
  (iterator [this]
    (let [s (atom (.seq this))]
      (reify Iterator
        (hasNext [_] (boolean (seq @s)))
        (next [_]
          (let [e (first @s)]
            (swap! s rest)
            e))))))

(defn- build-lazy-map [m]
  (LazyMap. m nil))

(defn ->LazyHttpRequest [^HttpExchange exchange]
  (build-lazy-map
    {:body           (.getRequestBody exchange)
     :request-method (delay (get-request-method-val (.getRequestMethod exchange)))
     :headers        (delay (get-headers-map (.getRequestHeaders exchange)))
     :uri            (delay (.getPath ^URI (.getRequestURI exchange)))
     :query-string   (delay (.getQuery ^URI (.getRequestURI exchange)))
     :server-port    (delay (.getPort (.getLocalAddress exchange)))
     :scheme         :http
     :protocol       (delay (.getProtocol exchange))
     :remote-addr    (delay (.getHostString (.getRemoteAddress exchange)))
     :server-name    (delay (or (.getFirst (.getRequestHeaders exchange) "Host")
                                (.getHostString (.getLocalAddress exchange))))}))

(defn ->LazyHttpsRequest [^HttpsExchange exchange]
  (build-lazy-map
    {:body           (.getRequestBody exchange)
     :request-method (delay (get-request-method-val (.getRequestMethod exchange)))
     :headers        (delay (get-headers-map (.getRequestHeaders exchange)))
     :uri            (delay (.getPath ^URI (.getRequestURI exchange)))
     :query-string   (delay (.getQuery ^URI (.getRequestURI exchange)))
     :server-port    (delay (.getPort (.getLocalAddress exchange)))
     :scheme         :https
     :protocol       (delay (.getProtocol exchange))
     :remote-addr    (delay (.getHostString (.getRemoteAddress exchange)))
     :server-name    (delay (or (.getFirst (.getRequestHeaders exchange) "Host")
                                (.getHostString (.getLocalAddress exchange))))}))

(defn ->LazyHttpsClientCertRequest [^HttpsExchange exchange]
  (build-lazy-map
    {:body             (.getRequestBody exchange)
     :request-method   (delay (get-request-method-val (.getRequestMethod exchange)))
     :headers          (delay (get-headers-map (.getRequestHeaders exchange)))
     :uri              (delay (.getPath ^URI (.getRequestURI exchange)))
     :query-string     (delay (.getQuery ^URI (.getRequestURI exchange)))
     :server-port      (delay (.getPort (.getLocalAddress exchange)))
     :scheme           :https
     :protocol         (delay (.getProtocol exchange))
     :remote-addr      (delay (.getHostString (.getRemoteAddress exchange)))
     :server-name      (delay (or (.getFirst (.getRequestHeaders exchange) "Host")
                                  (.getHostString (.getLocalAddress exchange))))
     :ssl-client-cert  (delay (get-certificate ^SSLSession (.getSSLSession exchange)))}))

