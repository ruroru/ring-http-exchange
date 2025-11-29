(ns ring-http-exchange.core
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.tools.logging :as logger]
            [ring.core.protocols :as protocols])
  (:import (com.sun.net.httpserver Headers HttpExchange HttpHandler HttpServer HttpsConfigurator HttpsExchange HttpsServer)
           (java.io File FileInputStream InputStream OutputStream)
           (java.net InetSocketAddress)
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
    (str/join comma header-list)))


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
        (logger/debugf "Unable to parse certificate due to: %s" (.getMessage ^Throwable t)))
      (make-array X509Certificate 0))))

(defn- get-https-mtls-exchange-request-map [host port ^HttpsExchange exchange]
  (let [uri (.getRequestURI exchange)
        session ^SSLSession (.getSSLSession exchange)
        certificates (get-certificate session)]

    (array-map
      :body (.getRequestBody exchange)
      :request-method (get-request-method (.getRequestMethod exchange))
      :headers (get-request-headers (.entrySet (.getRequestHeaders exchange)))
      :uri (.getPath uri)
      :query-string (.getQuery uri)
      :server-port port
      :scheme :https
      :protocol (.getProtocol exchange)
      :remote-addr (.getHostString (.getRemoteAddress exchange))
      :server-name host
      :ssl-client-cert certificates)))


(defn- get-https-exchange-request-map [host port ^HttpsExchange exchange]
  (let [uri (.getRequestURI exchange)]
    (array-map
      :body (.getRequestBody exchange)
      :request-method (get-request-method (.getRequestMethod exchange))
      :headers (get-request-headers (.entrySet (.getRequestHeaders exchange)))
      :uri (.getPath uri)
      :query-string (.getQuery uri)
      :server-port port
      :scheme :https
      :protocol (.getProtocol exchange)
      :remote-addr (.getHostString (.getRemoteAddress exchange))
      :server-name host)))


(defn- get-http-exchange-request-map [host port ^HttpExchange exchange]
  (let [uri (.getRequestURI exchange)]
    (array-map
      :body (.getRequestBody exchange)
      :request-method (get-request-method (.getRequestMethod exchange))
      :headers (get-request-headers (.entrySet (.getRequestHeaders exchange)))
      :uri (.getPath uri)
      :query-string (.getQuery uri)
      :server-port port
      :scheme :http
      :protocol (.getProtocol exchange)
      :remote-addr (.getHostString (.getRemoteAddress exchange))
      :server-name host)))


(defn- get-exchange-response [handler request-map]
  (try (handler request-map)
       (catch Throwable t
         (logger/error (.getMessage ^Throwable t))
         {:status  500
          :body    "Internal Server Error"
          :headers {"Content-type" "text/html"}})))


(defn- send-record-file [^HttpExchange exchange response]
  (set-response-headers (.getResponseHeaders exchange) (:headers response))
  (let [body ^File (:body response)
        content-length (.length body)]
    (.sendResponseHeaders exchange (:status response 200) content-length)
    (with-open [in ^InputStream (FileInputStream. body)
                out ^OutputStream (.getResponseBody exchange)]
      (.transferTo ^FileInputStream in out))))

(defn- send-file [^HttpExchange exchange response]
  (set-response-headers (.getResponseHeaders exchange) (response :headers ))
  (let [body ^File (response :body )
        content-length (.length body)]
    (.sendResponseHeaders exchange (response :status  200) content-length)
    (with-open [in ^InputStream (FileInputStream. body)
                out ^OutputStream (.getResponseBody exchange)]
      (.transferTo ^FileInputStream in out))))


(defn- send-input-stream [^HttpExchange exchange response]
  (set-response-headers (.getResponseHeaders exchange) (response :headers ))
  (.sendResponseHeaders exchange (response :status  200) 0)
  (let [in ^InputStream (response :body )
        out ^OutputStream (.getResponseBody exchange)]
    (.transferTo ^InputStream in out)
    (.close in)
    (.flush out)
    (.close out)))

(defn- send-record-input-stream [^HttpExchange exchange response]
  (set-response-headers (.getResponseHeaders exchange) (:headers response))
  (.sendResponseHeaders exchange (:status response 200) 0)
  (let [in ^InputStream (:body response)
        out ^OutputStream (.getResponseBody exchange)]
    (.transferTo ^InputStream in out)
    (.close in)
    (.flush out)
    (.close out)))


(defn- send-string [^HttpExchange exchange response]
  (set-response-headers (.getResponseHeaders exchange) (response :headers ))
  (let [body (response :body )
        bytes (.getBytes ^String body "UTF-8")
        content-length (alength bytes)]
    (.sendResponseHeaders exchange (response :status  200) content-length)
    (let [out ^OutputStream (.getResponseBody exchange)]
      (.write out bytes)
      (.flush out)
      (.close out))))

(defn- send-record-string [^HttpExchange exchange response]
  (set-response-headers (.getResponseHeaders exchange) (:headers response))
  (let [body (:body response)
        bytes (.getBytes ^String body "UTF-8")
        content-length (alength bytes)]
    (.sendResponseHeaders exchange (:status response 200) content-length)
    (let [out ^OutputStream (.getResponseBody exchange)]
      (.write out bytes)
      (.flush out)
      (.close out))))


(defn- send-byte-array [^HttpExchange exchange response]
  (set-response-headers (.getResponseHeaders exchange) (response :headers ))
  (let [body (response :body )
        content-length (alength ^"[B" body)]
    (.sendResponseHeaders exchange (response :status  200) content-length)
    (let [out ^OutputStream (.getResponseBody exchange)]
      (.write out ^"[B" body)
      (.flush out)
      (.close out))))

(defn- send-record-byte-array [^HttpExchange exchange response]
  (set-response-headers (.getResponseHeaders exchange) (:headers response))
  (let [body (:body response)
        content-length (alength ^"[B" body)]
    (.sendResponseHeaders exchange (:status response 200) content-length)
    (let [out ^OutputStream (.getResponseBody exchange)]
      (.write out ^"[B" body)
      (.flush out)
      (.close out))))


(defn- send-streamable [^HttpExchange exchange response]
  (set-response-headers (.getResponseHeaders exchange) (:headers response))
  (.sendResponseHeaders exchange (:status response 200) 0)
  (with-open [out ^OutputStream (.getResponseBody exchange)]
    (let [body (:body response)]
      (protocols/write-body-to-stream body response out))))


(defn- send-error [^HttpExchange exchange]
  (send-string exchange {:status  500
                         :body    "Internal Server Error"
                         :headers {"Content-type" "text/html"}}))


(defn- send-exchange-response [^HttpExchange exchange response]
  (if response
    (let [body (response :body)]
      (if (instance? String body)
        (send-string exchange response)
        (if (instance? InputStream body)
          (send-input-stream exchange response)
          (if (instance? File body)
            (if (.exists ^File body)
              (send-file exchange response)
              (send-error exchange))
            (if (bytes? body)
              (send-byte-array exchange response)
              (if (satisfies? protocols/StreamableResponseBody body)
                (send-streamable exchange response)
                (send-error exchange)))))))
    (send-error exchange)))

(defn- send-record-exchange-response [^HttpExchange exchange response]
  (if response
    (let [body (:body response)]
      (if (instance? String body)
        (send-record-string exchange response)
        (if (instance? InputStream body)
          (send-record-input-stream exchange response)
          (if (instance? File body)
            (if (.exists ^File body)
              (send-record-file exchange response)
              (send-error exchange))
            (if (bytes? body)
              (send-record-byte-array exchange response)
              (if (satisfies? protocols/StreamableResponseBody body)
                (send-streamable exchange response)
                (send-error exchange)))))))
    (send-error exchange)))

(deftype HandlerWithClientCert [host port handler]
  HttpHandler
  (handle [_ exchange]
    (let [request-map (get-https-mtls-exchange-request-map host port exchange)]
      (send-exchange-response exchange (get-exchange-response handler request-map)))
    (.close exchange)))

(deftype RecordHandlerWithClientCert [host port handler]
  HttpHandler
  (handle [_ exchange]
    (let [request-map (get-https-mtls-exchange-request-map host port exchange)]
      (send-record-exchange-response exchange (get-exchange-response handler request-map)))
    (.close exchange)))

(deftype HandlerWithoutClientCert [host port handler]
  HttpHandler
  (handle [_ exchange]
    (let [request-map (get-https-exchange-request-map host port exchange)]
      (send-exchange-response exchange (get-exchange-response handler request-map)))
    (.close exchange)))

(deftype RecordHandlerWithoutClientCert [host port handler]
  HttpHandler
  (handle [_ exchange]
    (let [request-map (get-https-exchange-request-map host port exchange)]
      (send-record-exchange-response exchange (get-exchange-response handler request-map)))
    (.close exchange)))

(deftype UnsecureHandler [host port handler]
  HttpHandler
  (handle [_ exchange]
    (let [request-map (get-http-exchange-request-map host port exchange)]
      (send-exchange-response exchange (get-exchange-response handler request-map)))
    (.close exchange)))

(deftype UnsecureRecordHandler [host port handler]
  HttpHandler
  (handle [_ exchange]
    (let [request-map (get-http-exchange-request-map host port exchange)]
      (send-record-exchange-response exchange (get-exchange-response handler request-map)))
    (.close exchange)))

(defn- create-server [host port backlog handler ssl-context get-ssl-client-cert? record-support?]
  (let [index-route "/"]
    (if ssl-context
      (let [^HttpsServer server (HttpsServer/create (InetSocketAddress. (str host) (int port)) (int backlog))]
        (.setHttpsConfigurator server (HttpsConfigurator. ssl-context))
        (if get-ssl-client-cert?
          (if record-support?
            (.createContext server index-route (HandlerWithClientCert. host port handler))
            (.createContext server index-route (RecordHandlerWithClientCert. host port handler)))
          (if record-support?
            (.createContext server index-route (HandlerWithoutClientCert. host port handler))
            (.createContext server index-route (RecordHandlerWithoutClientCert. host port handler))))
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
  :host                 - the hostname to listen on (defaults to 127.0.0.1)
  :ssl-context          - the ssl context, that is used in https server
  :executor             - executor to use in HttpServer, will default to ThreadPerTaskExecutor
  :get-ssl-client-cert? - a boolean value indicating whether to enable mutual TLS (mTLS), will default to false.
  :backlog              - size of a backlog, defaults to 8192"

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
                   record-support?      false}
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