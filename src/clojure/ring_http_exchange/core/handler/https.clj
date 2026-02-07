(ns ^:no-doc ring-http-exchange.core.handler.https
  (:require [ring-http-exchange.core.request :as request]
            [ring-http-exchange.core.response :as response]
            [ring-http-exchange.core.ring-handler :as handler])
  (:import (com.sun.net.httpserver HttpHandler)))

(deftype HandlerWithoutClientCert [host port handler]
  HttpHandler
  (handle [_ exchange]
    (handler/get-exchange-response
      handler
      (request/get-https-exchange-request-map host port exchange))))

(deftype RecordHandlerWithoutClientCert [host port handler]
  HttpHandler
  (handle [_ exchange]
    (response/send-record-exchange-response
      exchange
      (handler/get-exchange-response
        handler
        (request/get-https-exchange-request-map host port exchange)))))

(deftype AsyncHandlerWithoutClientCert [host port handler]
  HttpHandler
  (handle [_ exchange]
    (handler/get-async-exchange-response
      handler
      (request/get-http-exchange-request-map host port exchange)
      (response/create-async-response exchange))))

(deftype AsyncRecordHandlerWithoutClientCert [host port handler]
  HttpHandler
  (handle [_ exchange]
    (handler/get-async-exchange-response
      handler
      (request/get-http-exchange-request-map host port exchange)
      (response/create-async-record-response exchange))))

(defn ->HandlerWithoutClientCert
  [host port handler]
  (HandlerWithoutClientCert. host port handler))

(defn ->RecordHandlerWithoutClientCert
  [host port handler]
  (RecordHandlerWithoutClientCert. host port handler))

(defn ->AsyncHandlerWithoutClientCert
  [host port handler]
  (AsyncHandlerWithoutClientCert. host port handler))

(defn ->AsyncRecordHandlerWithoutClientCert
  [host port handler]
  (AsyncRecordHandlerWithoutClientCert. host port handler))

