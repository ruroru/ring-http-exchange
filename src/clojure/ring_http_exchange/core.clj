(ns ring-http-exchange.core
  (:require [clojure.string :as str]
            [clojure.tools.logging :as logger]
            [ring.core.protocols :as protocols])
  (:import (com.sun.net.httpserver Headers HttpExchange HttpHandler HttpServer HttpsConfigurator HttpsExchange HttpsServer)
           (java.io File FileInputStream InputStream OutputStream)
           (java.net InetSocketAddress)
           (java.security.cert X509Certificate)
           (java.util Collections$UnmodifiableMap$UnmodifiableEntrySet$UnmodifiableEntry List Set)
           (java.util.concurrent Executors)
           (javax.net.ssl SSLSession)))

(def ^:const ^:private comma ",")
(def ^:const ^:private content-type "Content-type")
(def ^:const ^:private index-path "/")
(def ^:const ^:private internal-server-error "Internal Server Error")
(def ^:const ^:private localhost "127.0.0.1")
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

(defn- get-keywordized-request-headers [^Set entry-set]
  (persistent!
    (reduce
      (fn [m ^Collections$UnmodifiableMap$UnmodifiableEntrySet$UnmodifiableEntry header]
        (let [key (keyword (.toLowerCase ^String (.getKey header)))
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

(defn- https-request-map-with-ssl-client-cert [host port ^HttpsExchange exchange]
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

(defn- https-request-map-with-ssl-client-cert-and-keywordized-request-headers [host port ^HttpsExchange exchange]
  (let [uri (.getRequestURI exchange)
        session ^SSLSession (.getSSLSession exchange)
        certificates (get-certificate session)]
    (array-map
      :body (.getRequestBody exchange)
      :request-method (get-request-method (.getRequestMethod exchange))
      :headers (get-keywordized-request-headers (.entrySet (.getRequestHeaders exchange)))
      :uri (.getPath uri)
      :query-string (.getQuery uri)
      :server-port port
      :scheme :https
      :protocol (.getProtocol exchange)
      :remote-addr (.getHostString (.getRemoteAddress exchange))
      :server-name host
      :ssl-client-cert certificates)))


(defn- https-request-map [host port ^HttpsExchange exchange]
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

(defn- https-request-map-with-keywordized-request-headers [host port ^HttpsExchange exchange]
  (let [uri (.getRequestURI exchange)]
    (array-map
      :body (.getRequestBody exchange)
      :request-method (get-request-method (.getRequestMethod exchange))
      :headers (get-keywordized-request-headers (.entrySet (.getRequestHeaders exchange)))
      :uri (.getPath uri)
      :query-string (.getQuery uri)
      :server-port port
      :scheme :https
      :protocol (.getProtocol exchange)
      :remote-addr (.getHostString (.getRemoteAddress exchange))
      :server-name host)))


(defn- http-request-map [host port ^HttpExchange exchange]
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

(defn- http-request-map-with-keywordized-request-headers [host port ^HttpExchange exchange]
  (let [uri (.getRequestURI exchange)]
    (array-map
      :body (.getRequestBody exchange)
      :request-method (get-request-method (.getRequestMethod exchange))
      :headers (get-keywordized-request-headers (.entrySet (.getRequestHeaders exchange)))
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


(defn- send-string [^HttpExchange exchange response body]
  (set-response-headers (.getResponseHeaders exchange) (response :headers))
  (let [content-length (.length ^String body)]
    (.sendResponseHeaders exchange (response :status 200) content-length))
  (let [body-bytes (.getBytes ^String body)]
    (with-open [out ^OutputStream (.getResponseBody exchange)]
      (.write ^OutputStream out body-bytes))))


(defn- send-file [^HttpExchange exchange response body]
  (set-response-headers (.getResponseHeaders exchange) (response :headers))
  (let [content-length (.length ^File body)]
    (.sendResponseHeaders exchange (response :status 200) content-length))
  (with-open [file-input-stream (FileInputStream. ^File body)
              out ^OutputStream (.getResponseBody exchange)]
    (.transferTo ^FileInputStream file-input-stream out)))


(defn- send-input-stream [^HttpExchange exchange response body]
  (set-response-headers (.getResponseHeaders exchange) (response :headers))
  (.sendResponseHeaders exchange (response :status 200) 0)
  (with-open [out ^OutputStream (.getResponseBody exchange)]
    (.transferTo ^InputStream body out))
  (.close ^InputStream body))


(defn- send-byte-array [^HttpExchange exchange response body]
  (set-response-headers (.getResponseHeaders exchange) (response :headers))
  (let [content-length (alength ^"[B" body)]
    (.sendResponseHeaders exchange (response :status 200) content-length))
  (with-open [out ^OutputStream (.getResponseBody exchange)]
    (.write ^OutputStream out ^"[B" body)))


(defn- send-streamable [^HttpExchange exchange response body]
  (set-response-headers (.getResponseHeaders exchange) (response :headers))
  (.sendResponseHeaders exchange (response :status 200) 0)
  (with-open [out ^OutputStream (.getResponseBody exchange)]
    (protocols/write-body-to-stream body response out)))


(defn- send-error [^HttpExchange exchange]
  (set-response-headers (.getResponseHeaders exchange) {content-type text-html})
  (let [content-length (.length ^String internal-server-error)]
    (.sendResponseHeaders exchange 500 content-length))
  (let [body-bytes (.getBytes ^String internal-server-error)]
    (with-open [out ^OutputStream (.getResponseBody exchange)]
      (.write ^OutputStream out body-bytes))))


(defn- send-exchange-response [^HttpExchange exchange response]
  (let [body (response :body)]
    (cond
      (string? body) (send-string exchange response body)
      (instance? File body) (send-file exchange response body)
      (instance? InputStream body) (send-input-stream exchange response body)
      (bytes? body) (send-byte-array exchange response body)
      (satisfies? protocols/StreamableResponseBody body) (send-streamable exchange response body)
      :else (send-error exchange))))


(defmulti get-server (fn [_ _ _ ssl-context _ keyword-headers]
                       (case [(nil? ssl-context) keyword-headers]
                         [false true] :tls-with-client-certs
                         [false false] :tls
                         :no-tls)))

(defmethod ^:private get-server :tls-with-client-certs [host port handler  ssl-context keyword-headers? & _]
  (let [^HttpsServer server (HttpsServer/create (InetSocketAddress. (str host) (int port)) 0)
        handler (if keyword-headers?
                  (reify HttpHandler
                    (handle [_ exchange]
                      (with-open [exchange exchange]
                        (->> (https-request-map-with-ssl-client-cert-and-keywordized-request-headers host port exchange)
                             (get-exchange-response handler)
                             (send-exchange-response exchange)))))
                  (reify HttpHandler
                    (handle [_ exchange]
                      (with-open [exchange exchange]
                        (->> (https-request-map-with-ssl-client-cert host port exchange)
                             (get-exchange-response handler)
                             (send-exchange-response exchange))))))]
    (.setHttpsConfigurator server (HttpsConfigurator. ssl-context))
    (.createContext server index-path handler)
    server))


(defmethod ^:private get-server :tls [host port handler  ssl-context keyword-headers?  & _]

  (when (logger/enabled? :debug)
    (logger/debug "Starting server with ssl-context and keyword headers"))
  (let [^HttpsServer server (HttpsServer/create (InetSocketAddress. (str host) (int port)) 0)
        handler (if keyword-headers?
                  (reify HttpHandler
                    (handle [_ exchange]
                      (with-open [exchange exchange]
                        (->> (https-request-map-with-keywordized-request-headers host port exchange)
                             (get-exchange-response handler)
                             (send-exchange-response exchange)))))
                  (reify HttpHandler
                    (handle [_ exchange]
                      (with-open [exchange exchange]
                        (->> (https-request-map host port exchange)
                             (get-exchange-response handler)
                             (send-exchange-response exchange))))))]
    (.setHttpsConfigurator server (HttpsConfigurator. ssl-context))
    (.createContext server index-path handler)
    server))


(defmethod ^:private get-server :no-tls [host port handler _ keyword-headers?  & _]
  (let [server (HttpServer/create (InetSocketAddress. (str host) (int port)) 0)
        handler (if keyword-headers?
                  (do
                    (reify HttpHandler
                      (handle [_ exchange]
                        (with-open [exchange exchange]
                          (->> (http-request-map-with-keywordized-request-headers host port exchange)
                               (get-exchange-response handler)
                               (send-exchange-response exchange))))))
                  (reify HttpHandler
                    (handle [_ exchange]
                      (with-open [exchange exchange]
                        (->> (http-request-map host port exchange)
                             (get-exchange-response handler)
                             (send-exchange-response exchange))))))]
    (.createContext server index-path handler)
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
  :host                 - the hostname to listen on (defaults to 0.0.0.0)
  :ssl-context          - the ssl context, that is used in https server
  :executor             - executor to use in HttpServer, will default to ThreadPoolExecutor
  :client-certs?        - a boolean value indicating if :ssl-client-cert should be added, defaults to false
  :keyword-headers?     - convert request header  keys to keywords, defaults to false.
  "

  [handler {:keys [host
                   port
                   ssl-context
                   executor
                   client-certs?
                   keyword-headers?]
            :or   {host             localhost
                   port             8080
                   ssl-context      nil
                   executor         (Executors/newCachedThreadPool)
                   client-certs?    false
                   keyword-headers? false}}]
  (let [^HttpServer server (get-server host port handler ssl-context keyword-headers? client-certs?)]
    (try
      (doto server
        (.setExecutor executor)
        (.start))
      (catch Throwable t
        (logger/error (.getMessage t))
        (throw t)))))
