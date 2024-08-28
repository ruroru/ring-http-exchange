(ns ring-http-exchange.core
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [ring-http-exchange.ssl :as ssl]
            [ring.core.protocols :as protocols])
  (:import [com.sun.net.httpserver HttpServer HttpHandler HttpExchange HttpsConfigurator HttpsExchange HttpsServer]
           [java.io ByteArrayOutputStream File PrintWriter]
           [java.util.concurrent ArrayBlockingQueue ThreadPoolExecutor TimeUnit]
           [java.net InetSocketAddress]))

(set! *warn-on-reflection* true)

(defn- http-exchange->request-map [^HttpExchange exchange]
  {:server-port     (.getPort (.getLocalAddress exchange))
   :server-name     (.getHostName (.getLocalAddress exchange))
   :remote-addr     (.getHostString (.getRemoteAddress exchange))
   :uri             (.getPath (.getRequestURI exchange))
   :query-string    (.getQuery (.getRequestURI exchange))
   :scheme          :http
   :request-method  (keyword (str/lower-case (.getRequestMethod exchange)))
   :protocol        (.getProtocol exchange)
   :headers         (->> (for [[k vs] (.getRequestHeaders exchange)]
                           [(str/lower-case k) (str/join "," vs)])
                         (into {}))
   :ssl-client-cert nil
   :body            (.getRequestBody exchange)})

(defn- https-exchange->request-map [^HttpsExchange exchange]
  {:server-port     (.getPort (.getLocalAddress exchange))
   :server-name     (.getHostName (.getLocalAddress exchange))
   :remote-addr     (.getHostString (.getRemoteAddress exchange))
   :uri             (.getPath (.getRequestURI exchange))
   :query-string    (.getQuery (.getRequestURI exchange))
   :scheme          :https
   :request-method  (keyword (str/lower-case (.getRequestMethod exchange)))
   :protocol        (.getProtocol exchange)
   :headers         (->> (for [[k vs] (.getRequestHeaders exchange)]
                           [(str/lower-case k) (str/join "," vs)])
                         (into {}))
   :ssl-client-cert nil
   :body            (.getRequestBody exchange)})

(defn- set-response-headers [^HttpExchange exchange headers]
  (doseq [:let [response-headers (.getResponseHeaders exchange)]
          [k v] headers
          v (cond-> v
                    (string? v) vector)]
    (.add response-headers (name k) v)))

(def ^{:private true} error-page-template
  (str "<html><head><title>%s</title></head>"
       "<body><h1>%s</h1><pre>%s</pre></body></html>"))

(defn- handle-exception [^HttpExchange exchange ^Throwable t]
  (let [title "Internal Server Error"
        trace (with-out-str
                (.printStackTrace t (PrintWriter. *out*)))
        page (-> (format error-page-template title title trace)
                 (.getBytes "UTF-8"))]
    (.set (.getResponseHeaders exchange) "content-type" "text/html")
    (.sendResponseHeaders exchange 500 (alength page))
    (io/copy page (.getResponseBody exchange))))

(defn- handle-exchange [^HttpExchange exchange handler
                        {:keys [output-buffer-size]
                         :or   {output-buffer-size 32768}}
                        function]
  (with-open [exchange exchange]
    (try
      (let [{:keys [status body headers]
             :as   response} (-> exchange function handler)
            out (.getResponseBody exchange)]
        (set-response-headers exchange headers)
        (cond
          (= "chunked" (get headers "transfer-encoding"))
          (do (.sendResponseHeaders exchange status 0)
              (protocols/write-body-to-stream body response out))

          (string? body)
          (let [bytes (.getBytes ^String body "UTF-8")]
            (.sendResponseHeaders exchange status (alength bytes))
            (io/copy bytes out))

          (instance? File body)
          (do (.sendResponseHeaders exchange status (.length ^File body))
              (protocols/write-body-to-stream body response out))
          :else
          (let [baos (ByteArrayOutputStream. output-buffer-size)]
            (protocols/write-body-to-stream body response baos)
            (.sendResponseHeaders exchange status (.size baos))
            (io/copy (.toByteArray baos) out))))
      (catch Throwable t
        (.printStackTrace t)
        (handle-exception exchange t))
      (finally
        (.flush (.getResponseBody exchange))))))

(defn- get-server
  ([host port]
   (HttpServer/create (InetSocketAddress. (str host) (int port)) 0))
  ([host port ssl-context]
   (let [^HttpsServer server (HttpsServer/create (InetSocketAddress. (str host) (int port)) 0)]
     (.setHttpsConfigurator server (HttpsConfigurator. ssl-context))
     server))
  ([host port keystore key-password truststore trust-password]
   (let [^HttpsServer server (HttpsServer/create (InetSocketAddress. (str host) (int port)) 0)
         ssl-context (ssl/keystore->ssl-context keystore key-password truststore trust-password)]
     (.setHttpsConfigurator server (HttpsConfigurator. ssl-context))
     server)))

(defn stop-http-server
  "Stops a com.sun.net.httpserver.HttpServer with an optional
  delay (in seconds) to allow active request to finish."
  ([server]
   (stop-http-server server 0))
  ([^HttpServer server delay]
   (doto server
     (.stop delay))))

(defn run-http-server
  "Start a com.sun.net.httpserver.HttpServer to serve the given
  handler according to the supplied options:

  :port                 - the port to listen on (defaults to 8080)
  :host                 - the hostname to listen on (defaults to 127.0.0.1)
  :max-threads          - the maximum number of threads to use (default 50)
  :min-threads          - the minimum number of threads to use (default 8)
  :max-queued-requests  - the maximum number of requests to be queued (default 1024)
  :thread-idle-timeout  - Set the maximum thread idle time. Threads that are idle
                          for longer than this period may be stopped (default 60000)
  :keystore             - keystore used in creation of ssl context
  :key-password         - keystore password, that is used in ssl context
  :truststore           - truststore used in creation of ssl context
  :trust-password       - trust store password, that is used to create ssl contxt
  :ssl?                 - flag to check if http or https server should be used
  :ssl-port             - the https port to listen on (defaults to 8443)
  :ssl-context          - the ssl context, that is used in https server"
  [handler {:keys [
                   host
                   port
                   min-threads
                   max-threads
                   max-queued-requests
                   thread-idle-timeout
                   keystore
                   key-password
                   truststore
                   trust-password
                   ssl?
                   ssl-port
                   ssl-context
                   ]
            :as   options
            :or   {host                "127.0.0.1" port 8080
                   min-threads         8 max-threads 50
                   max-queued-requests 1024 thread-idle-timeout 60000
                   keystore            (io/resource "keystore.js")
                   key-password        ""
                   truststore          (io/resource "truststore.jks")
                   trust-password      ""
                   ssl?                false
                   ssl-port            8443
                   ssl-context         nil}}]
  (let [^HttpServer server (if ssl?
                             (if ssl-context
                               (get-server host ssl-port ssl-context)
                               (get-server host ssl-port keystore key-password truststore trust-password))
                             (get-server host port))]

    (if ssl?
      (.createContext server "/" (proxy [HttpHandler] []
                                   (handle [exchange]
                                     (handle-exchange exchange handler options https-exchange->request-map))))
      (.createContext server "/" (proxy [HttpHandler] []
                                   (handle [exchange]
                                     (handle-exchange exchange handler options http-exchange->request-map)))))
    (try
      (doto server
        (.setExecutor (ThreadPoolExecutor. min-threads
                                           max-threads
                                           thread-idle-timeout
                                           TimeUnit/MILLISECONDS
                                           (ArrayBlockingQueue. max-queued-requests)))
        .start)
      (catch Throwable t
        (stop-http-server server)
        (throw t)))))
