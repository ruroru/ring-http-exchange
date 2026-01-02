(ns ^:no-doc ring-http-exchange.core.handler.https-with-client-cert
  (:require [ring-http-exchange.core.request :as request]
            [ring-http-exchange.core.response :as response]
            [ring-http-exchange.core.ring-handler :as handler])
  (:import (com.sun.net.httpserver HttpHandler)))

(deftype HandlerWithClientCert [host port handler]
  HttpHandler
  (handle [_ exchange]
    (response/send-exchange-response
      exchange
      (handler/get-exchange-response
        handler
        (request/get-https-client-cert-exchange-request-map host port exchange)))
    (.close exchange)))

(deftype RecordHandlerWithClientCert [host port handler]
  HttpHandler
  (handle [_ exchange]
    (response/send-record-exchange-response
      exchange
      (handler/get-exchange-response
        handler
        (request/get-https-client-cert-exchange-request-map host port exchange)))
    (.close exchange)))
