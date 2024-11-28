(ns ring-http-exchange.core
  (:require [clojure.string :as str]
            [clojure.tools.logging :as logger]
            [ring.core.protocols :as protocols])
  (:import (com.sun.net.httpserver Headers HttpExchange HttpHandler HttpServer HttpsConfigurator HttpsServer)
           (java.io File FileInputStream InputStream OutputStream)
           (java.net InetSocketAddress)
           (java.util Collections$UnmodifiableMap$UnmodifiableEntrySet$UnmodifiableEntry List Set)
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


(defn- get-request-headers [^Set entry-set]
  (reduce
    (fn [m ^Collections$UnmodifiableMap$UnmodifiableEntrySet$UnmodifiableEntry header]
      (let [key (.getKey  header)
            value (get-header-value (.getValue header))]
        (assoc m key value)))
    {}
    entry-set))

(defn- ^:private set-response-headers [^Headers response-headers resp-headers]
  (doseq [key-value resp-headers]
    (.add response-headers (name (first key-value))
          (let [value (second key-value)]
            (if
              (instance? String value)
              value
              (str value))))))

(defn- get-request-method [request-method]
  (keyword (.toLowerCase ^String request-method)))

(defn- get-exchange-request-map [scheme host port ^HttpExchange exchange]
  {:server-port     port
   :server-name     host
   :remote-addr     (.getHostString (.getRemoteAddress exchange))
   :uri             (.getPath (.getRequestURI exchange))
   :query-string    (.getQuery (.getRequestURI exchange))
   :scheme          scheme
   :request-method  (get-request-method (.getRequestMethod exchange))
   :protocol        (.getProtocol exchange)
   :headers         (get-request-headers (.entrySet (.getRequestHeaders exchange)))
   :ssl-client-cert nil
   :body            (.getRequestBody exchange)})

(defn- get-exchange-response [handler request-map]
  (try (handler request-map)
       (catch Throwable t
         (logger/error (.getMessage ^Throwable t))
         {:status  500
          :body    internal-server-error
          :headers {content-type text-html}})))

(defn- send-string [^HttpExchange exchange response body]
  (set-response-headers (.getResponseHeaders exchange) (:headers response))
  (let [content-length (.length ^String body)]
    (.sendResponseHeaders exchange (:status response 200) content-length))
  (let [body-bytes (.getBytes ^String body)]
    (with-open [out ^OutputStream (.getResponseBody exchange)]
      (.write ^OutputStream out body-bytes))))

(defn- send-file [^HttpExchange exchange response body]
  (set-response-headers (.getResponseHeaders exchange) (:headers response))
  (let [content-length (.length ^File body)]
    (.sendResponseHeaders exchange (:status response 200) content-length))
  (with-open [file-input-stream (FileInputStream. ^File body)
              out ^OutputStream (.getResponseBody exchange)]
    (.transferTo ^FileInputStream file-input-stream out)))

(defn- send-input-stream [^HttpExchange exchange response body]
  (set-response-headers (.getResponseHeaders exchange) (:headers response))
  (.sendResponseHeaders exchange (:status response 200) 0)
  (with-open [out ^OutputStream (.getResponseBody exchange)]
    (.transferTo ^InputStream body out))
  (.close ^InputStream body))

(defn- send-byte-array [^HttpExchange exchange response body]
  (set-response-headers (.getResponseHeaders exchange) (:headers response))
  (let [content-length (alength ^"[B" body)]
    (.sendResponseHeaders exchange (:status response 200) content-length))
  (with-open [out ^OutputStream (.getResponseBody exchange)]
    (.write ^OutputStream out ^"[B" body)))

(defn- send-streamable [^HttpExchange exchange response body]
  (set-response-headers (.getResponseHeaders exchange) (:headers response))
  (.sendResponseHeaders exchange (:status response 200) 0)
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
  (let [body (:body response)]
    (cond
      (string? body) (send-string exchange response body)
      (instance? File body) (send-file exchange response body)
      (instance? InputStream body) (send-input-stream exchange response body)
      (instance? byte-array-class body) (send-byte-array exchange response body)
      (satisfies? protocols/StreamableResponseBody body) (send-streamable exchange response body)
      :else (send-error exchange))))

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
