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
        (request/get-http-exchange-request-map host port exchange)))
    (.close exchange)))

(deftype UnsecureRecordHandler [host port handler]
  HttpHandler
  (handle [_ exchange]
    (response/send-record-exchange-response
      exchange
      (handler/get-exchange-response
        handler
        (request/get-http-exchange-request-map host port exchange)))
    (.close exchange)))
