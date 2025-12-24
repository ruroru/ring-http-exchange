(ns ring-http-exchange.core
  (:require [clojure.spec.alpha :as s]
            [clojure.tools.logging :as logger]
            [ring.core.protocols :as protocols])
  (:import (com.sun.net.httpserver Headers HttpExchange HttpHandler HttpServer HttpsConfigurator HttpsExchange HttpsServer)
           (java.io File FileInputStream InputStream OutputStream)
           (java.net InetSocketAddress URI)
           (java.nio.charset StandardCharsets)
           (java.security.cert X509Certificate)
           (java.util Collections$UnmodifiableMap$UnmodifiableEntrySet$UnmodifiableEntry List Map Set)
           (java.util.concurrent Executors)
           (javax.net.ssl SSLSession)))

(s/def ::port (s/int-in 1 65536))

(def ^:const ^:private comma ",")
(def ^:private method-cache ^Map (Map/of
                                   "GET" :get
                                   "POST" :post
                                   "PUT" :put
                                   "PATCH" :patch
                                   "DELETE" :delete
                                   "HEAD" :head
                                   "OPTIONS" :options
                                   "TRACE" :trace))

(defn- get-header-value [^List header-list]
  (if (= 1 (.size ^List header-list))
    (.get header-list 0)
    (String/join ^String comma header-list)))


(defn- get-header-map [m ^Collections$UnmodifiableMap$UnmodifiableEntrySet$UnmodifiableEntry header]
  (let [key (.getKey header)
        value (get-header-value (.getValue header))]
    (assoc! m key value)))

(defn- get-request-headers [^Set entry-set]
  (persistent!
    (reduce get-header-map (transient {})
            entry-set)))


(defn- set-response-headers [^Headers response-headers resp-headers]
  (doseq [[k v] resp-headers]
    (.add response-headers (name k)
          (if (string? v) v (str v)))))


(defn- get-request-method [^String method]
  (or (.get ^Map method-cache method)
      (keyword (.toLowerCase method))))

(defn- convert-certificate [certificate] (cast X509Certificate certificate))

(defn- get-certificate [^SSLSession session]
  (try
    (map convert-certificate (.getPeerCertificates session))
    (catch Exception t
      (when (logger/enabled? :debug)
        (logger/warnf "Unable to parse certificate due to: %s" (.getMessage ^Throwable t)))
      (make-array X509Certificate 0))))

(defn- ^:private get-request-method-val [^String method-string]
  (get-request-method method-string))

(defn- ^:private get-headers-map [^Headers headers]
  (get-request-headers (.entrySet headers)))

(defn- get-https-mtls-exchange-request-map [host port ^HttpsExchange exchange]
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


(defn- get-https-exchange-request-map [host port ^HttpsExchange exchange]
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



(defn- get-http-exchange-request-map [host port ^HttpExchange exchange]
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


(defn- get-exchange-response [handler request-map]
  (try (handler request-map)
       (catch Throwable t
         (logger/error (.getMessage ^Throwable t))
         {:status  500
          :body    "Internal Server Error"
          :headers {"Content-type" "text/html"}})))


(defn- send-file [^HttpExchange exchange ^OutputStream out body headers status]
  (with-open [in ^InputStream (FileInputStream. ^File body)
              out1 ^OutputStream out]
    (set-response-headers (.getResponseHeaders exchange) headers)
    (.sendResponseHeaders exchange status (.length ^File body))

    (.transferTo ^FileInputStream in out1)))


(defn- send-input-stream [^HttpExchange exchange ^OutputStream out body headers status]
  (with-open [in ^InputStream body
              out1 out]
    (set-response-headers (.getResponseHeaders exchange) headers)
    (.sendResponseHeaders exchange status 0)

    (.transferTo ^InputStream in out1)))

(defn- send-byte-array [^HttpExchange exchange ^OutputStream out body headers status]
  (with-open [out out]
    (set-response-headers (.getResponseHeaders exchange) headers)
    (.sendResponseHeaders exchange status (alength ^"[B" body))

    (.write out ^"[B" body)))

(defn- send-string [^HttpExchange exchange ^OutputStream out ^String body headers status]
  (send-byte-array  exchange  out (.getBytes ^String body StandardCharsets/UTF_8) headers status))

(defn- maybe-streamable [^HttpExchange exchange ^OutputStream out body headers status]
  (with-open [out out]
    (set-response-headers (.getResponseHeaders exchange) headers)
    (.sendResponseHeaders exchange status 0)

    (protocols/write-body-to-stream body {:body    body
                                          :status  status
                                          :headers headers}
                                    out)))

(defn- send-error [^HttpExchange exchange]
  (send-string exchange
               (.getResponseBody exchange)
               "Internal Server Error"
               {"Content-type" "text/html"}
               500))

(defn- send-file-not-found [^HttpExchange exchange]
  (send-string exchange
               (.getResponseBody exchange)
               "File Not Found"
               {"Content-type" "text/html"}
               404))

(defn- if-not-file [exchange ^OutputStream out body headers status]
  (if (satisfies? protocols/StreamableResponseBody body)
    (maybe-streamable exchange out body headers status)
    (send-error exchange)))

(defn- if-not-byte-array [exchange ^OutputStream out body headers status]
  (if (instance? File body)
    (if (.exists ^File body)
      (send-file exchange out body headers status)
      (send-file-not-found exchange))
    (if-not-file exchange out body headers status)))

(defn- maybe-byte-array [exchange ^OutputStream out body headers status]
  (if (bytes? body)
    (send-byte-array exchange out body headers status)
    (if-not-byte-array exchange out body headers status)))

(defn- maybe-inputs-stream [exchange ^OutputStream out body headers status]
  (if (instance? InputStream body)
    (send-input-stream exchange out body headers status)
    (maybe-byte-array exchange out body headers status)))

(defn- send-response [exchange out body headers status]
  (if (instance? String body)
    (send-string exchange out body headers status)
    (maybe-inputs-stream exchange out body headers status)))

(defn- send-exchange-response [^HttpExchange exchange response]
  (if response
    (send-response exchange (.getResponseBody exchange) (response :body) (response :headers) (response :status 200))
    (send-error exchange)))

(defn- send-record-exchange-response [^HttpExchange exchange response]
  (if response
    (send-response exchange (.getResponseBody exchange) (:body response) (:headers response) (:status response 200))
    (send-error exchange)))

(deftype HandlerWithClientCert [host port handler]
  HttpHandler
  (handle [_ exchange]
    (send-exchange-response exchange
                            (get-exchange-response handler
                                                   (get-https-mtls-exchange-request-map host port exchange)))
    (.close exchange)))

(deftype RecordHandlerWithClientCert [host port handler]
  HttpHandler
  (handle [_ exchange]
    (send-record-exchange-response exchange
                                   (get-exchange-response handler
                                                          (get-https-mtls-exchange-request-map host
                                                                                               port
                                                                                               exchange)))
    (.close exchange)))

(deftype HandlerWithoutClientCert [host port handler]
  HttpHandler
  (handle [_ exchange]
    (send-exchange-response exchange
                            (get-exchange-response handler
                                                   (get-https-exchange-request-map host
                                                                                   port
                                                                                   exchange)))
    (.close exchange)))

(deftype RecordHandlerWithoutClientCert [host port handler]
  HttpHandler
  (handle [_ exchange]
    (send-record-exchange-response exchange
                                   (get-exchange-response handler (get-https-exchange-request-map host port exchange)))
    (.close exchange)))

(deftype UnsecureHandler [host port handler]
  HttpHandler
  (handle [_ exchange]
    (send-exchange-response exchange (get-exchange-response handler (get-http-exchange-request-map host port exchange)))
    (.close exchange)))

(deftype UnsecureRecordHandler [host port handler]
  HttpHandler
  (handle [_ exchange]
    (send-record-exchange-response exchange (get-exchange-response handler (get-http-exchange-request-map host port exchange)))
    (.close exchange)))

(defn- create-server [host port backlog handler ssl-context get-ssl-client-cert? record-support?]
  (let [index-route "/"]
    (if ssl-context
      (let [^HttpsServer server (HttpsServer/create (InetSocketAddress. (str host) (int port)) (int backlog))]
        (.setHttpsConfigurator server (HttpsConfigurator. ssl-context))
        (if get-ssl-client-cert?
          (if record-support?
            (.createContext server index-route (RecordHandlerWithClientCert. host port handler))
            (.createContext server index-route (HandlerWithClientCert. host port handler)))
          (if record-support?
            (.createContext server index-route (RecordHandlerWithoutClientCert. host port handler))
            (.createContext server index-route (HandlerWithoutClientCert. host port handler))))
        server)
      (let [server (HttpServer/create (InetSocketAddress. (str host) (int port)) (int backlog))]
        (if record-support?
          (.createContext server index-route (UnsecureRecordHandler. host port handler))
          (.createContext server index-route (UnsecureHandler. host port handler)))
        server))))


(defn stop-http-server
  "Stops a com.sun.net.httpserver.HttpServer with an optional
  delay (in seconds) to allow active request to finish."
  ([^HttpServer server]
   (stop-http-server server 0))
  ([^HttpServer server delay]
   (doto server (.stop delay))))

(defn run-http-server
  "Start a com.sun.net.httpserver.HttpServer to serve the given
  handler according to the supplied options:

  :port                 - the port to listen on (defaults to 8080)
  :host                 - the hostname to listen on (defaults to 0.0.0.0)
  :ssl-context          - the ssl context, that is used in https server
  :executor             - executor to use in HttpServer, will default to ThreadPerTaskExecutor
  :get-ssl-client-cert? - a boolean value indicating whether to enable mutual TLS (mTLS), will default to false.
  :backlog              - size of a backlog, defaults to 8192
  :record-support?      - option to disable record support, defaults to true"

  [handler {:keys [host
                   port
                   ssl-context
                   executor
                   get-ssl-client-cert?
                   backlog
                   record-support?]
            :or   {host                 "0.0.0.0"
                   port                 8080
                   ssl-context          nil
                   executor             (Executors/newVirtualThreadPerTaskExecutor)
                   get-ssl-client-cert? false
                   backlog              (* 1024 8)
                   record-support?      true}
            }]
  (when (s/valid? ::port port)
    (let [^HttpServer server (create-server host port backlog handler ssl-context get-ssl-client-cert? record-support?)]
      (try
        (doto server
          (.setExecutor executor)
          (.start))
        (catch Throwable t
          (logger/error (.getMessage t))
          (throw t))))))

(defn restart-http-server
  "restarts HttpServer with an optional delay (in seconds) to allow active request to finish."
  ([^HttpServer server handler server-config]
   (restart-http-server server handler server-config 0))
  ([^HttpServer server handler server-config delay]
   (doto server (.stop delay))
   (run-http-server handler server-config)))