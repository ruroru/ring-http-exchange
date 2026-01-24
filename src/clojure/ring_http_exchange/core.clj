(ns ring-http-exchange.core
  (:require [clojure.spec.alpha :as s]
            [ring_http_exchange.core.handler.http]
            [ring_http_exchange.core.handler.https]
            [ring-http-exchange.core.handler.https-with-client-cert]
            [clojure.tools.logging :as logger])
  (:import (com.sun.net.httpserver HttpServer HttpsConfigurator HttpsServer)
           (java.net InetSocketAddress)
           (java.util.concurrent Executors)
           (ring_http_exchange.core.handler.http UnsecureHandler UnsecureRecordHandler)
           (ring_http_exchange.core.handler.https HandlerWithoutClientCert RecordHandlerWithoutClientCert)
           (ring_http_exchange.core.handler.https_with_client_cert HandlerWithClientCert RecordHandlerWithClientCert)))

(s/def ::port (s/int-in 1 65536))

(defn- create-server [host port backlog handler ssl-context get-ssl-client-cert? record-support?]
  (let [index-route "/"]
    (if ssl-context
      (let [^HttpsServer server (HttpsServer/create (InetSocketAddress. (str host) (int port)) (int backlog))]
        (.setHttpsConfigurator server (HttpsConfigurator. ssl-context))
        (if get-ssl-client-cert?
          (if record-support?
            (.createContext server index-route (RecordHandlerWithClientCert. host port handler))
            (.createContext server index-route (HandlerWithClientCert. host port handler)))
          (if record-support?
            (.createContext server index-route (RecordHandlerWithoutClientCert. host port handler))
            (.createContext server index-route (HandlerWithoutClientCert. host port handler))))
        server)
      (let [server (HttpServer/create (InetSocketAddress. (str host) (int port)) (int backlog))]
        (if record-support?
          (.createContext server index-route (UnsecureRecordHandler. host port handler))
          (.createContext server index-route (UnsecureHandler. host port handler)))
        server))))
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
  :record-support?      - option to disable record support, defaults to true"

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
                   record-support?      true}
            }]
  (set-httpserver-nodelay)

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
