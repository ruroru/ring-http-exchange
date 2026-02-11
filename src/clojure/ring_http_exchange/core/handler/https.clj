(ns ^:no-doc ring-http-exchange.core.handler.https
  (:require [clojure.tools.logging :as logger]
            [ring-http-exchange.core.request :as request]
            [ring-http-exchange.core.response :as response]
            [ring-http-exchange.core.ring-handler :as handler])
  (:import (com.sun.net.httpserver HttpHandler)))

(deftype SyncHandler [host port handler response-fn request-map-fn create-response-fn]
  HttpHandler
  (handle [_ exchange]
    (response-fn exchange (create-response-fn handler (request-map-fn host port exchange)))))

(deftype LazyReqSyncHandler [host port handler response-fn create-response-fn]
  HttpHandler
  (handle [_ exchange]
    (response-fn exchange (create-response-fn handler (request/->LazyHttpsRequest exchange)))))

(deftype AsyncHandler [host port handler request-map-fn create-response-fn]
  HttpHandler
  (handle [_ exchange]
    (handler/get-async-exchange-response handler (request-map-fn host port exchange) (create-response-fn exchange))))

(deftype LazyReqAsyncHandler [host port handler create-response-fn]
  HttpHandler
  (handle [_ exchange]
    (handler/get-async-exchange-response handler (request/->LazyHttpsRequest exchange) (create-response-fn exchange))))

(defn ->SecureHandler
  [host port handler record-support? lazy-request-map?]
  (case [record-support? lazy-request-map?]
    [true true] (LazyReqSyncHandler. host port handler response/send-record-exchange-response handler/get-exchange-response)
    [true false] (SyncHandler. host port handler response/send-record-exchange-response request/get-https-exchange-request-map handler/get-exchange-response)
    [false true] (LazyReqSyncHandler. host port handler response/send-exchange-response handler/get-exchange-response)
    [false false] (SyncHandler. host port handler response/send-exchange-response request/get-https-exchange-request-map handler/get-exchange-response)))

(defn ->AsyncSecureHandler
  [host port handler record-support? lazy-request-map?]
  (logger/error [record-support? lazy-request-map?])
  (case [record-support? lazy-request-map?]
    [true true] (LazyReqAsyncHandler. host port handler response/create-async-record-response)
    [true false] (AsyncHandler. host port handler request/get-https-exchange-request-map response/create-async-record-response)
    [false true] (LazyReqAsyncHandler. host port handler response/create-async-response)
    [false false] (AsyncHandler. host port handler request/get-https-exchange-request-map response/create-async-response)))