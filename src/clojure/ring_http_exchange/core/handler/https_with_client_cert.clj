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
        (request/get-https-client-cert-exchange-request-map host port exchange)))))

(deftype RecordHandlerWithClientCert [host port handler]
  HttpHandler
  (handle [_ exchange]
    (response/send-record-exchange-response
      exchange
      (handler/get-exchange-response
        handler
        (request/get-https-client-cert-exchange-request-map host port exchange)))))

(deftype AsyncHandlerWithClientCert [host port handler]
  HttpHandler
  (handle [_ exchange]
    (handler/get-async-exchange-response
      handler (request/get-https-exchange-request-map host port exchange)
      (response/create-async-response exchange))))

(deftype AsyncRecordHandlerWithClientCert [host port handler]
  HttpHandler
  (handle [_ exchange]
    (handler/get-async-exchange-response
      handler (request/get-https-exchange-request-map host port exchange)
      (response/create-async-record-response exchange))))

(defn ->HandlerWithClientCert
  [host port handler]
  (HandlerWithClientCert. host port handler))

(defn ->RecordHandlerWithClientCert
  [host port handler]
  (RecordHandlerWithClientCert. host port handler))

(defn ->AsyncHandlerWithClientCert
  [host port handler]
  (AsyncHandlerWithClientCert. host port handler))

(defn ->AsyncRecordHandlerWithClientCert
  [host port handler]
  (AsyncRecordHandlerWithClientCert. host port handler))

