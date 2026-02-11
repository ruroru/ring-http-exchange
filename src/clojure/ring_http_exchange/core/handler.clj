(ns ring-http-exchange.core.handler
  (:import (com.sun.net.httpserver HttpHandler)))

(defrecord HandlerConfig [record-support ssl-context get-ssl-client-cert?] )

(deftype SyncHandler [host port handler send-response-fn get-request-map-fn get-exchange-response-fn]
  HttpHandler
  (handle [_ exchange]
    (send-response-fn
      exchange
      (get-exchange-response-fn
        handler
        (get-request-map-fn host port exchange)))))

(deftype AsyncHandler [host port handler response-fn request-map-fn create-response-fn]
  HttpHandler
  (handle [_ exchange]
    (response-fn
      handler
      (request-map-fn host port exchange)
      (create-response-fn  exchange))))