(ns ring-http-exchange.core
  (:require [clojure.spec.alpha :as s]
            [ring-http-exchange.core.handler.http :as http-handlers]
            [ring-http-exchange.core.handler.https :as https-handlers]
            [ring-http-exchange.core.handler.https-with-client-cert :as https-client-cert-handlers]
            [clojure.tools.logging :as logger])
  (:import (com.sun.net.httpserver HttpServer HttpsConfigurator HttpsServer)
           (java.net InetSocketAddress)
           (java.util.concurrent Executors)))

(s/def ::port (s/int-in 1 65536))

(defn- create-server [host port backlog handler ssl-context get-ssl-client-cert? record-support? async?]
  (let [index-route "/"]
    (if async?
      (if ssl-context
        (let [^HttpsServer server (HttpsServer/create (InetSocketAddress. (str host) (int port)) (int backlog))]
          (.setHttpsConfigurator server (HttpsConfigurator. ssl-context))
          (if get-ssl-client-cert?
            (if record-support?
              (.createContext server index-route (https-client-cert-handlers/->AsyncRecordHandlerWithClientCert host port handler))
              (.createContext server index-route (https-client-cert-handlers/->AsyncHandlerWithClientCert host port handler)))
            (if record-support?
              (.createContext server index-route (https-handlers/->AsyncRecordHandlerWithoutClientCert host port handler))
              (.createContext server index-route (https-handlers/->AsyncHandlerWithoutClientCert host port handler))))
          server)
        (let [server (HttpServer/create (InetSocketAddress. (str host) (int port)) (int backlog))]
          (if record-support?
            (.createContext server index-route (http-handlers/->AsyncUnsecureRecordHandler host port handler))
            (.createContext server index-route (http-handlers/->AsyncUnsecureHandler host port handler)))
          server))
      (if ssl-context
        (let [^HttpsServer server (HttpsServer/create (InetSocketAddress. (str host) (int port)) (int backlog))]
          (.setHttpsConfigurator server (HttpsConfigurator. ssl-context))
          (if get-ssl-client-cert?
            (if record-support?
              (.createContext server index-route (https-client-cert-handlers/->RecordHandlerWithClientCert host port handler))
              (.createContext server index-route (https-client-cert-handlers/->HandlerWithClientCert host port handler)))
            (if record-support?
              (.createContext server index-route (https-handlers/->RecordHandlerWithoutClientCert host port handler))
              (.createContext server index-route (https-handlers/->HandlerWithoutClientCert host port handler))))
          server)
        (let [server (HttpServer/create (InetSocketAddress. (str host) (int port)) (int backlog))]
          (if record-support?
            (.createContext server index-route (http-handlers/->UnsecureRecordHandler host port handler))
            (.createContext server index-route (http-handlers/->UnsecureHandler host port handler)))
          server)))))

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
  :async?               - Async-over-sync support, defaults to false"

  [handler {:keys [host
                   port
                   ssl-context
                   executor
                   get-ssl-client-cert?
                   backlog
                   record-support?
                   async?]
            :or   {host                 "0.0.0.0"
                   port                 8080
                   ssl-context          nil
                   executor             (Executors/newVirtualThreadPerTaskExecutor)
                   get-ssl-client-cert? false
                   backlog              (* 1024 8)
                   record-support?      true
                   async?               false}
            }]
  (set-httpserver-nodelay)

  (when (s/valid? ::port port)
    (let [^HttpServer server (create-server host port backlog handler ssl-context get-ssl-client-cert? record-support? async?)]
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
