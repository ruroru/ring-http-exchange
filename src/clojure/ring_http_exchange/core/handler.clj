(ns ring-http-exchange.core.handler
  (:require [ring-http-exchange.core.request :as request]
            [ring-http-exchange.core.response :as response]
            [ring-http-exchange.core.ring-handler :as handler])
  (:import (com.sun.net.httpserver HttpHandler)))

(deftype SyncHandler [host port handler response-fn create-response-fn request-map-fn]
  HttpHandler
  (handle [_ exchange]
    (response-fn exchange (create-response-fn handler (request-map-fn host port exchange)))))

(deftype LazyReqSyncHandler [host port handler response-fn request-map-fn create-response-fn]
  HttpHandler
  (handle [_ exchange]
    (response-fn exchange (create-response-fn handler (request-map-fn exchange)))))

(deftype AsyncHandler [host port handler create-response-fn request-map-fn]
  HttpHandler
  (handle [_ exchange]
    (handler/get-async-exchange-response handler (request-map-fn host port exchange) (create-response-fn exchange))))

(deftype LazyReqAsyncHandler [host port handler create-response-fn request-map-fn]
  HttpHandler
  (handle [_ exchange]
    (handler/get-async-exchange-response handler (request-map-fn exchange) (create-response-fn exchange))))

(defn sync-not-secure-handler
  [host port handler record-support? lazy-request-map?]
  (case [record-support? lazy-request-map?]
    [true true] (LazyReqSyncHandler. host port handler response/send-record-exchange-response request/->LazyHttpRequest handler/get-exchange-response)
    [true false] (SyncHandler. host port handler response/send-record-exchange-response handler/get-exchange-response request/get-http-exchange-request-map)
    [false true] (LazyReqSyncHandler. host port handler response/send-exchange-response request/->LazyHttpRequest handler/get-exchange-response)
    [false false] (SyncHandler. host port handler response/send-exchange-response handler/get-exchange-response request/get-http-exchange-request-map)))

(defn async-not-secure-handler
  [host port handler record-support? lazy-request-map?]
  (case [record-support? lazy-request-map?]
    [true true] (LazyReqAsyncHandler. host port handler response/create-async-record-response request/->LazyHttpRequest)
    [true false] (AsyncHandler. host port handler response/create-async-record-response request/get-http-exchange-request-map)
    [false true] (LazyReqAsyncHandler. host port handler response/create-async-response request/->LazyHttpRequest)
    [false false] (AsyncHandler. host port handler response/create-async-response request/get-http-exchange-request-map)))

(defn sync-secure-handler
  [host port handler record-support? lazy-request-map?]
  (case [record-support? lazy-request-map?]
    [true true] (LazyReqSyncHandler. host port handler response/send-record-exchange-response request/->LazyHttpsRequest handler/get-exchange-response)
    [true false] (SyncHandler. host port handler response/send-record-exchange-response handler/get-exchange-response request/get-https-exchange-request-map)
    [false true] (LazyReqSyncHandler. host port handler response/send-exchange-response request/->LazyHttpsRequest handler/get-exchange-response)
    [false false] (SyncHandler. host port handler response/send-exchange-response handler/get-exchange-response request/get-https-exchange-request-map)))

(defn async-secure-handler
  [host port handler record-support? lazy-request-map?]
  (case [record-support? lazy-request-map?]
    [true true] (LazyReqAsyncHandler. host port handler response/create-async-record-response request/->LazyHttpsRequest)
    [true false] (AsyncHandler. host port handler response/create-async-record-response request/get-https-exchange-request-map)
    [false true] (LazyReqAsyncHandler. host port handler response/create-async-response request/->LazyHttpsRequest)
    [false false] (AsyncHandler. host port handler response/create-async-response request/get-https-exchange-request-map)))

(defn sync-secure-handler-with-certs
  [host port handler record-support? lazy-request-map?]
  (case [record-support? lazy-request-map?]
    [true true] (LazyReqSyncHandler. host port handler response/send-record-exchange-response request/->LazyHttpsClientCertRequest handler/get-exchange-response)
    [true false] (SyncHandler. host port handler response/send-record-exchange-response handler/get-exchange-response request/get-https-client-cert-exchange-request-map)
    [false true] (LazyReqSyncHandler. host port handler response/send-exchange-response request/->LazyHttpsClientCertRequest handler/get-exchange-response)
    [false false] (SyncHandler. host port handler response/send-exchange-response handler/get-exchange-response request/get-https-client-cert-exchange-request-map)))

(defn async-secure-handler-with-certs
  [host port handler record-support? lazy-request-map?]
  (case [record-support? lazy-request-map?]
    [true true] (LazyReqAsyncHandler. host port handler response/create-async-record-response request/->LazyHttpsClientCertRequest)
    [true false] (AsyncHandler. host port handler response/create-async-record-response request/get-https-client-cert-exchange-request-map)
    [false true] (LazyReqAsyncHandler. host port handler response/create-async-response request/->LazyHttpsClientCertRequest)
    [false false] (AsyncHandler. host port handler response/create-async-response request/get-https-client-cert-exchange-request-map)))