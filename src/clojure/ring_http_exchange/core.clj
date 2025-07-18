(ns ring-http-exchange.core
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.tools.logging :as logger]
            [ring.core.protocols :as protocols])
  (:import (com.sun.net.httpserver Headers HttpExchange HttpHandler HttpServer HttpsConfigurator HttpsExchange HttpsServer)
           (java.io File FileInputStream InputStream OutputStream)
           (java.net InetSocketAddress)
           (java.security.cert X509Certificate)
           (java.util Collections$UnmodifiableMap$UnmodifiableEntrySet$UnmodifiableEntry List Set)
           (java.util.concurrent Executors)
           (javax.net.ssl SSLSession)))

(s/def ::port (s/int-in 1 65536))

(def ^:const ^:private comma ",")
(def ^:const ^:private content-type "Content-type")
(def ^:const ^:private index-path "/")
(def ^:const ^:private internal-server-error "Internal Server Error")
(def ^:const ^:private default-host "0.0.0.0")
(def ^:const ^:private text-html "text/html")
(def ^:const ^:private method-cache (into {}
                                          (for [method ["get" "post" "put" "patch" "delete" "head" "options" "trace"]]
                                            (let [uppercase-method (clojure.string/upper-case method)
                                                  keyword-method (keyword method)]
                                              [uppercase-method keyword-method]))))

(defn- get-header-value [^List header-list]
  (if (= 1 (.size ^List header-list))
    (.get header-list 0)
    (str/join comma header-list)))


(defn- get-request-headers [^Set entry-set]
  (persistent!
    (reduce
      (fn [m ^Collections$UnmodifiableMap$UnmodifiableEntrySet$UnmodifiableEntry header]
        (let [key (.getKey header)
              value (get-header-value (.getValue header))]
          (assoc! m key value)))
      (transient {})
      entry-set)))


(defn- set-response-headers [^Headers response-headers resp-headers]
  (doseq [[k v] resp-headers]
    (.add response-headers (name k)
          (if (string? v) v (str v)))))


(defn- get-request-method [^String method]
  (method-cache method (keyword (.toLowerCase method))))


(defn- get-certificate [^SSLSession session]
  (try
    (map
      (fn [certificate]
        (cast X509Certificate certificate))
      (.getPeerCertificates session))
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
          :body    internal-server-error
          :headers {content-type text-html}})))


(defn- send-file [^HttpExchange exchange response ^File body]
  (set-response-headers (.getResponseHeaders exchange) (response :headers))
  (let [content-length (.length body)]
    (.sendResponseHeaders exchange (response :status 200) content-length))
  (with-open [in ^InputStream (FileInputStream. body)
              out ^OutputStream (.getResponseBody exchange)]
    (.transferTo ^FileInputStream in out)))


(defn- send-input-stream [^HttpExchange exchange response ^InputStream in]
  (set-response-headers (.getResponseHeaders exchange) (response :headers))
  (.sendResponseHeaders exchange (response :status 200) 0)
  (let [out ^OutputStream (.getResponseBody exchange)]
    (.transferTo ^InputStream in out)
    (.close in)
    (.flush out)
    (.close out)))


(defn- send-byte-array [^HttpExchange exchange response body]
  (set-response-headers (.getResponseHeaders exchange) (response :headers))
  (let [content-length (alength ^"[B" body)]
    (.sendResponseHeaders exchange (response :status 200) content-length))
  (let [out ^OutputStream (.getResponseBody exchange)]
    (.write ^OutputStream out ^"[B" body)
    (.flush out)
    (.close out)))


(defn- send-string [^HttpExchange exchange response body]
  (send-byte-array exchange response (.getBytes ^String body)))


(defn- send-streamable [^HttpExchange exchange response body]
  (set-response-headers (.getResponseHeaders exchange) (response :headers))
  (.sendResponseHeaders exchange (response :status 200) 0)
  (with-open [out ^OutputStream (.getResponseBody exchange)]
    (protocols/write-body-to-stream body response out)))


(defn- send-error [^HttpExchange exchange]
  (send-string exchange {:status  500
                         :body    internal-server-error
                         :headers {content-type text-html}} internal-server-error))


(defn- send-exchange-response [^HttpExchange exchange response]
  (if response
    (let [body (response :body)]
      (cond
        (instance? String body) (send-string exchange response body)
        (instance? File body) (if
                                (.exists ^File body)
                                (send-file exchange response body)
                                (send-error exchange))
        (instance? InputStream body) (send-input-stream exchange response body)
        (bytes? body) (send-byte-array exchange response body)
        (satisfies? protocols/StreamableResponseBody body) (send-streamable exchange response body)
        :else (send-error exchange)))
    (send-error exchange)))


(defmulti get-server (fn [_ _ _ _ ssl-context get-ssl-client-cert?]
                       (case [(nil? ssl-context) get-ssl-client-cert?]
                         [false true] :with-client-cert
                         [false false] :without-client-cert
                         :no-tls)))

(deftype HandlerWithClientCert [host port handler]
  HttpHandler
  (handle [_ exchange]
    (->> (get-https-mtls-exchange-request-map host port exchange)
         (get-exchange-response handler)
         (send-exchange-response exchange))
    (.close exchange)))

(defmethod ^:private get-server :with-client-cert [host port backlog _ handler ssl-context & _]
  (let [^HttpsServer server (HttpsServer/create (InetSocketAddress. (str host) (int port)) (int backlog))]
    (.setHttpsConfigurator server (HttpsConfigurator. ssl-context))
    (.createContext server index-path (HandlerWithClientCert. host port handler))
    server))

(deftype HandlerWithoutClientCert [host port handler]
  HttpHandler
  (handle [_ exchange]
    (->> (get-https-exchange-request-map host port exchange)
         (get-exchange-response handler)
         (send-exchange-response exchange))
    (.close exchange)
    ))

(defmethod ^:private get-server :without-client-cert [host port backlog handler ssl-context & _]
  (let [^HttpsServer server (HttpsServer/create (InetSocketAddress. (str host) (int port)) (int backlog))]
    (.setHttpsConfigurator server (HttpsConfigurator. ssl-context))
    (.createContext server index-path (HandlerWithoutClientCert. host port handler))
    server))

(deftype UnsecureHandler [host port handler]
  HttpHandler
  (handle [_ exchange]
    (->> (get-http-exchange-request-map host port exchange)
         (get-exchange-response handler)
         (send-exchange-response exchange))
    (.close exchange)
    ))

(defmethod ^:private get-server :no-tls [host port backlog handler & _]
  (let [server (HttpServer/create (InetSocketAddress. (str host) (int port)) (int backlog))]
    (.createContext server index-path (UnsecureHandler. host port handler))
    server))


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
  :executor             - executor to use in HttpServer, will default to ThreadPoolExecutor
  :get-ssl-client-cert? - a boolean value indicating whether to enable mutual TLS (mTLS), will default to false.
  :backlog              - size of a backlog, defaults to 8192"

  [handler {:keys [host
                   port
                   ssl-context
                   executor
                   get-ssl-client-cert?
                   backlog]
            :or   {host                 default-host
                   port                 8080
                   ssl-context          nil
                   executor             (Executors/newCachedThreadPool)
                   get-ssl-client-cert? false
                   backlog              (* 1024 8)}}]
  (when (s/valid? ::port port)
    (let [^HttpServer server (get-server host port backlog handler ssl-context get-ssl-client-cert?)]
      (try
        (doto server
          (.setExecutor executor)
          (.start))
        (catch Throwable t
          (logger/error (.getMessage t))
          (throw t))))))
