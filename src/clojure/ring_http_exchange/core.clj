(ns ring-http-exchange.core
  (:require [clojure.string :as str]
            [clojure.tools.logging :as logger]
            [ring.core.protocols :as protocols])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer HttpsConfigurator HttpsServer)
           (java.io File InputStream)
           (java.net InetSocketAddress)
           (java.util List)
           (java.util.concurrent ArrayBlockingQueue ThreadPoolExecutor TimeUnit)))

(set! *warn-on-reflection* true)

(def ^:private byte-array-class (Class/forName "[B"))
(def ^:private comma ",")
(def ^:private content-length "Content-length")
(def ^:private content-type "Content-type")
(def ^:private http-schema :http)
(def ^:private https-schema :https)
(def ^:private index-path "/")
(def ^:private internal-server-error "Internal Server Error")
(def ^:private localhost "127.0.0.1")
(def ^:private text-html "text/html")

(defn- get-header-value [^List header-list]
  (if (= 1 (.size ^List header-list))
    (first header-list)
    (str/join comma header-list)))

(defmacro get-headers [request-headers]
  `(reduce
     (fn [ring-headers# header#]
       (assoc ring-headers#
         (.toLowerCase ^String (first header#))
         (get-header-value (second header#))))
     {}
     ~request-headers))

(defmacro set-response-headers [exchange headers]
  `(let [response-headers# (.getResponseHeaders ~exchange)]
     (doseq [[k# v#] ~headers]
       (.add response-headers#
             (name k#)
             (if (instance? String v#)
               v#
               (str v#))))))

(defn- http-exchange->request-map [^HttpExchange exchange schema host port]
  {:server-port     port
   :server-name     host
   :remote-addr     (.getHostString (.getRemoteAddress exchange))
   :uri             (.getPath (.getRequestURI exchange))
   :query-string    (.getQuery (.getRequestURI exchange))
   :scheme          schema
   :request-method  (keyword (str/lower-case (.getRequestMethod exchange)))
   :protocol        (.getProtocol exchange)
   :headers         (get-headers (into {} (.getRequestHeaders exchange)))
   :ssl-client-cert nil
   :body            (.getRequestBody exchange)})

(defn- get-content-length [body headers]
  (cond
    (string? body) (.length ^String body)
    (instance? InputStream body) (get headers content-length 0)
    (instance? File body) (.length ^File body)
    (instance? byte-array-class body) (alength body)
    (nil? body) 0))

(defn- handle-exchange [^HttpExchange exchange handler schema host port]
  (with-open [exchange exchange]
    (let [{:keys [status body headers] :as response}
          (try
            (handler (http-exchange->request-map exchange schema host port))
            (catch Throwable t
              (logger/error (.getMessage ^Throwable t))
              {:status  500
               :body    internal-server-error
               :headers {content-type text-html}}))]

      (try
        (let [out (.getResponseBody exchange)]
          (set-response-headers exchange headers)
          (.sendResponseHeaders exchange status (get-content-length body headers))
          (protocols/write-body-to-stream body response out))

        (catch Throwable t
          (logger/error (.getMessage t)))

        (finally
          (.flush (.getResponseBody exchange)))))))

(defn- get-handler [handler schema host server-port]
  (reify HttpHandler
    (handle [_ exchange]
      (handle-exchange exchange handler schema host server-port))))

(defn- get-server
  ([host port handler]
   (let [server (HttpServer/create (InetSocketAddress. (str host) (int port)) 0)]
     (.createContext server index-path (get-handler handler http-schema host port))
     server))
  ([host port handler ssl-context]
   (if ssl-context
     (let [^HttpsServer server (HttpsServer/create (InetSocketAddress. (str host) (int port)) 0)]
       (.setHttpsConfigurator server (HttpsConfigurator. ssl-context))
       (.createContext server index-path (get-handler handler https-schema host port))
       server)
     (get-server host port handler))))

(defn stop-http-server
  "Stops a com.sun.net.httpserver.HttpServer with an optional
  delay (in seconds) to allow active request to finish."
  ([server]
   (stop-http-server server 0))
  ([^HttpServer server delay]
   (doto server (.stop delay))))

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
  :ssl-context          - the ssl context, that is used in https server
  :executor             - executor to use in HttpServer, will default to ThreadPoolExecutor"

  [handler {:keys [
                   host
                   port
                   min-threads
                   max-threads
                   max-queued-requests
                   thread-idle-timeout
                   ssl-context
                   executor
                   ]
            :or   {host                localhost
                   port                8080
                   min-threads         8
                   max-threads         50
                   max-queued-requests 1024
                   thread-idle-timeout 60000
                   ssl-context         nil
                   executor            nil
                   }}]
  (let [^HttpServer server (if ssl-context
                             (get-server host port handler ssl-context)
                             (get-server host port handler))]
    (if executor
      (.setExecutor server executor)
      (.setExecutor server (ThreadPoolExecutor. min-threads
                                                max-threads
                                                thread-idle-timeout
                                                TimeUnit/MILLISECONDS
                                                (ArrayBlockingQueue. max-queued-requests))))
    (try
      (doto server .start)
      (catch Throwable t
        (logger/error (.getMessage t))
        (throw t)))))
