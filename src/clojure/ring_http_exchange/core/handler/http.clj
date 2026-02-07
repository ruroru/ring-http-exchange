(ns ^:no-doc ring-http-exchange.core.handler.http
  (:require [ring-http-exchange.core.request :as request]
            [ring-http-exchange.core.response :as response]
            [ring-http-exchange.core.ring-handler :as handler])
  (:import (com.sun.net.httpserver HttpHandler)))

(deftype UnsecureHandler [host port handler]
  HttpHandler
  (handle [_ exchange]
    (response/send-exchange-response
      exchange
      (handler/get-exchange-response
        handler
        (request/get-http-exchange-request-map host port exchange)))))

(deftype UnsecureRecordHandler [host port handler]
  HttpHandler
  (handle [_ exchange]
    (response/send-record-exchange-response
      exchange
      (handler/get-exchange-response
        handler
        (request/get-http-exchange-request-map host port exchange)))))

(deftype AsyncUnsecureHandler [host port handler]
  HttpHandler
  (handle [_ exchange]
    (handler/get-async-exchange-response
      handler (request/get-http-exchange-request-map host port exchange)
      (response/create-async-response exchange))))

(deftype AsyncUnsecureRecordHandler [host port handler]
  HttpHandler
  (handle [_ exchange]
    (handler/get-async-exchange-response
      handler (request/get-http-exchange-request-map host port exchange)
      (response/create-async-record-response exchange))))

(defn ->UnsecureHandler
  [host port handler]
  (UnsecureHandler. host port handler))

(defn ->UnsecureRecordHandler
  [host port handler]
  (UnsecureRecordHandler. host port handler))

(defn ->AsyncUnsecureHandler
  [host port handler]
  (AsyncUnsecureHandler. host port handler))

(defn ->AsyncUnsecureRecordHandler
  [host port handler]
  (AsyncUnsecureRecordHandler. host port handler))

