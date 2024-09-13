(ns ring-http-exchange.core
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as logger]
            [ring.core.protocols :as protocols])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer HttpsConfigurator HttpsExchange HttpsServer)
           (java.io ByteArrayOutputStream File)
           (java.net InetSocketAddress)
           (java.util.concurrent ArrayBlockingQueue ThreadPoolExecutor TimeUnit)))

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

(defn- handle-exchange [^HttpExchange exchange handler function]
  (let [output-buffer-size 32768]
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
          (logger/error (.getMessage t))
          (let [page (.getBytes "Internal Server Error" "UTF-8")]
            (.set (.getResponseHeaders exchange) "content-type" "text/html")
            (.sendResponseHeaders exchange 500 (alength page))
            (io/copy page (.getResponseBody exchange))))
        (finally
          (.flush (.getResponseBody exchange)))))))

(defn- get-handler [handler exchange-function]
  (proxy [HttpHandler] []
    (handle [exchange]
      (handle-exchange exchange handler exchange-function))))

(defn- get-server
  ([host port handler exchange-function]
   (let [server (HttpServer/create (InetSocketAddress. (str host) (int port)) 0)]
     (.createContext server "/" (get-handler handler exchange-function))
     server))
  ([host port ssl-context handler exchange-function]
   (let [^HttpsServer server (HttpsServer/create (InetSocketAddress. (str host) (int port)) 0)]
     (.setHttpsConfigurator server (HttpsConfigurator. ssl-context))
     (.createContext server "/" (get-handler handler exchange-function))
     server)))

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
            :or   {host                "127.0.0.1"
                   port                8080
                   min-threads         8
                   max-threads         50
                   max-queued-requests 1024
                   thread-idle-timeout 60000
                   ssl-context         nil
                   executor            nil}}]
  (let [^HttpServer server (if ssl-context
                             (get-server host port ssl-context handler https-exchange->request-map)
                             (get-server host port handler http-exchange->request-map))]
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
        (stop-http-server server)
        (throw t)))))
