(ns ring-http-exchange.core
  (:require [clojure.spec.alpha :as s]
            [clojure.tools.logging :as logger]
            [ring-http-exchange.core.handler :as handler])
  (:import (com.sun.net.httpserver HttpServer HttpsConfigurator HttpsServer)
           (java.net InetSocketAddress)
           (java.util.concurrent Executors)))

(s/def ::port (s/int-in 1 65536))


(def ^:private valid-request-map-fields
     #{:body :request-method :headers :uri :query-string :server-port :scheme :protocol :remote-addr :server-name :ssl-client-cert})
  
(defn- validate-request-map-fields [fields]
  (when fields
    (let [invalid (remove valid-request-map-fields fields)]
      (when (seq invalid)
        (throw (ex-info (str "Invalid request-map-fields: " (vec invalid)
                        ". Valid fields are: " valid-request-map-fields)
                        {:invalid-fields (set invalid) :valid-fields valid-request-map-fields}))))))

(defn- create-server [host port backlog handler executor ssl-context get-ssl-client-cert? record-support? async? request-map-fields]
  (let [index-route "/"
        server (if ssl-context
                 (HttpsServer/create (InetSocketAddress. (str host) (int port)) (int backlog))
                 (HttpServer/create (InetSocketAddress. (str host) (int port)) (int backlog)))]

    (when ssl-context
      (.setHttpsConfigurator ^HttpsServer server (HttpsConfigurator. ssl-context)))

    (let [handler-instance (cond
                             (and ssl-context get-ssl-client-cert? async?)
                             (handler/async-secure-handler-with-certs host port handler executor record-support? request-map-fields)

                             (and ssl-context get-ssl-client-cert?)
                             (handler/sync-secure-handler-with-certs host port handler record-support? request-map-fields)

                             (and ssl-context async?)
                             (handler/async-secure-handler host port handler executor record-support? request-map-fields)

                             ssl-context
                             (handler/sync-secure-handler host port handler record-support? request-map-fields)

                             async?
                             (handler/async-not-secure-handler host port handler executor record-support? request-map-fields)

                             :else
                             (handler/sync-not-secure-handler host port handler record-support? request-map-fields))]
      (.createContext ^HttpServer server index-route handler-instance))

    server))

(defn- set-httpserver-nodelay
  []
  (let [property-key (if (try
                           (Class/forName "robaho.net.httpserver.ServerImpl")
                           true
                           (catch ClassNotFoundException _
                             false))
                       "robaho.net.httpserver.nodelay"
                       "sun.net.httpserver.nodelay")]
    (System/setProperty property-key "true")
    property-key))

(defn stop-http-server
  "Stops a com.sun.net.httpserver.HttpServer with an optional
  delay (in seconds) to allow active request to finish."
  ([^HttpServer server]
   (stop-http-server server 0))
  ([^HttpServer server delay]
   (doto server (.stop delay))))

(defn- virtual-thread-per-task? [executor]
  (and executor
       (= "java.util.concurrent.ThreadPerTaskExecutor"
          (.getName (class executor)))))

(defn run-http-server
  "Start a com.sun.net.httpserver.HttpServer to serve the given
  handler according to the supplied options:

  :port                 - the port to listen on (defaults to 8080)
  :host                 - the hostname to listen on (defaults to 0.0.0.0)
  :ssl-context          - the ssl context, that is used in https server
  :executor             - executor to use in HttpServer, will default to ThreadPerTaskExecutor
  :get-ssl-client-cert? - a boolean value indicating whether retrieve client certs, will default to false.
  :backlog              - size of a backlog, defaults to 8192
  :record-support?      - option to disable record support, defaults to true
  :async?               - Async-over-sync support, defaults to false
  :request-map-fields   - set of keys to include in request map (e.g. #{:body :headers :uri}), defaults to nil (all fields)
  "

  [handler {:keys [host
                   port
                   ssl-context
                   executor
                   get-ssl-client-cert?
                   backlog
                   record-support?
                   async?
                   request-map-fields]
            :or   {host                 "0.0.0.0"
                   port                 8080
                   ssl-context          nil
                   executor             (Executors/newVirtualThreadPerTaskExecutor)
                   get-ssl-client-cert? false
                   backlog              (* 1024 8)
                   record-support?      true
                   async?               false
                   request-map-fields   nil}
            }]
  (set-httpserver-nodelay)
  (validate-request-map-fields request-map-fields)

  (when (s/valid? ::port port)
    (let [async-executor (if (virtual-thread-per-task? executor) nil executor)

          ^HttpServer server (create-server host port backlog handler async-executor ssl-context get-ssl-client-cert? record-support? async? request-map-fields)]
      (try
        (if async?
          (doto server
            (.setExecutor (Executors/newVirtualThreadPerTaskExecutor))
            (.start))
          (doto server
            (.setExecutor executor)
            (.start)))
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
