(ns ring-http-exchange.core
  (:require [clojure.string :as str]
            [clojure.tools.logging :as logger]
            [ring.core.protocols :as protocols])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer HttpsConfigurator HttpsServer)
           (java.io File FileInputStream InputStream OutputStream)
           (java.net InetSocketAddress)
           (java.util List)
           (java.util.concurrent Executors)))

(def ^:const ^:private byte-array-class (Class/forName "[B"))
(def ^:const ^:private comma ",")
(def ^:const ^:private content-type "Content-type")
(def ^:const ^:private http-scheme :http)
(def ^:const ^:private https-scheme :https)
(def ^:const ^:private index-path "/")
(def ^:const ^:private internal-server-error "Internal Server Error")
(def ^:const ^:private localhost "127.0.0.1")
(def ^:const ^:private text-html "text/html")

(defn- get-header-value [^List header-list]
  (if (= 1 (.size ^List header-list))
    (.get header-list 0)
    (str/join comma header-list)))

(defmacro ^:private get-request-headers [request-headers]
  `(reduce
     (fn [ring-headers# header#]
       (assoc ring-headers#
         (.toLowerCase ^String (first header#))
         (get-header-value (second header#))))
     {}
     ~request-headers))

(defmacro ^:private set-response-headers [exchange headers]
  `(let [response-headers# (.getResponseHeaders ~exchange)]
     (doseq [[k# v#] ~headers]
       (.add response-headers#
             (name k#)
             (if (instance? String v#)
               v#
               (str v#))))))

(def ^:private get-request-method
  (memoize (fn [request-method]
             (keyword (.toLowerCase ^String request-method)))))

(defn- get-exchange-request-map [scheme host port ^HttpExchange exchange]
  {:server-port     port
   :server-name     host
   :remote-addr     (.getHostString (.getRemoteAddress exchange))
   :uri             (.getPath (.getRequestURI exchange))
   :query-string    (.getQuery (.getRequestURI exchange))
   :scheme          scheme
   :request-method  (get-request-method (.getRequestMethod exchange))
   :protocol        (.getProtocol exchange)
   :headers         (get-request-headers (into {} (.getRequestHeaders exchange)))
   :ssl-client-cert nil
   :body            (.getRequestBody exchange)})

(defn- get-exchange-response [handler request-map]
  (try (handler request-map)
       (catch Throwable t
         (logger/error (.getMessage ^Throwable t))
         {:status  500
          :body    internal-server-error
          :headers {content-type text-html}})))

(defn- send-exchange-response [^HttpExchange exchange {:keys [headers status body] :as response}]
  (cond
    (string? body)
    (do
      (set-response-headers exchange headers)
      (let [content-length (.length ^String body)]
        (.sendResponseHeaders exchange status content-length))
      (let [body-bytes (.getBytes ^String body)]
        (with-open [out ^OutputStream (.getResponseBody exchange)]
          (.write ^OutputStream out body-bytes))))

    (instance? File body)
    (do
      (set-response-headers exchange headers)
      (let [content-length (.length ^File body)]
        (.sendResponseHeaders exchange status content-length))
      (with-open [file-input-stream (FileInputStream. ^File body)
                  out ^OutputStream (.getResponseBody exchange)]
        (.transferTo ^FileInputStream file-input-stream out)))

    (instance? InputStream body)
    (do
      (set-response-headers exchange headers)
      (.sendResponseHeaders exchange status 0)
      (with-open [out ^OutputStream (.getResponseBody exchange)]
        (.transferTo ^InputStream body out))
      (.close ^InputStream body))

    (instance? byte-array-class body)
    (do
      (set-response-headers exchange headers)
      (let [content-length (alength ^"[B" body)]
        (.sendResponseHeaders exchange status content-length))
      (with-open [out ^OutputStream (.getResponseBody exchange)]
        (.write ^OutputStream out ^"[B" body)))

    (satisfies? protocols/StreamableResponseBody body)
    (do
      (set-response-headers exchange headers)
      (.sendResponseHeaders exchange status 0)
      (with-open [out ^OutputStream (.getResponseBody exchange)]
        (protocols/write-body-to-stream body response out)))

    :else
    (do
      (set-response-headers exchange {content-type text-html})
      (let [content-length (.length ^String internal-server-error)]
        (.sendResponseHeaders exchange 500 content-length))
      (let [body-bytes (.getBytes ^String internal-server-error)]
        (with-open [out ^OutputStream (.getResponseBody exchange)]
          (.write ^OutputStream out body-bytes))))))

(defn- get-handler [handler scheme host port]
  (reify HttpHandler
    (handle [_ exchange]
      (with-open [exchange exchange]
        (->> (get-exchange-request-map scheme host port exchange)
             (get-exchange-response handler)
             (send-exchange-response exchange))))))

(defn- get-server
  ([host port handler]
   (let [server (HttpServer/create (InetSocketAddress. (str host) (int port)) 0)]
     (.createContext server index-path (get-handler handler http-scheme host port))
     server))
  ([host port handler ssl-context]
   (if ssl-context
     (let [^HttpsServer server (HttpsServer/create (InetSocketAddress. (str host) (int port)) 0)]
       (.setHttpsConfigurator server (HttpsConfigurator. ssl-context))
       (.createContext server index-path (get-handler handler https-scheme host port))
       server)
     (get-server host port handler))))

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
  :executor             - executor to use in HttpServer, will default to ThreadPoolExecutor"

  [handler {:keys [host
                   port
                   ssl-context
                   executor]
            :or   {host        localhost
                   port        8080
                   ssl-context nil
                   executor    (Executors/newCachedThreadPool)}}]
  (let [^HttpServer server (if ssl-context
                             (get-server host port handler ssl-context)
                             (get-server host port handler))]
    (try
      (doto server
        (.setExecutor executor)
        (.start))
      (catch Throwable t
        (logger/error (.getMessage t))
        (throw t)))))
