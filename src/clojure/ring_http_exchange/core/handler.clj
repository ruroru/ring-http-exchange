(ns ring-http-exchange.core.handler
  (:require [ring-http-exchange.core.request :as request]
            [ring-http-exchange.core.response :as response]
            [ring-http-exchange.core.ring-handler :as handler])
  (:import (com.sun.net.httpserver HttpHandler)
           ))

(deftype SyncHandler [host port handler response-fn create-response-fn request-map-fn]
  HttpHandler
  (handle [_ exchange]
    (response-fn exchange (create-response-fn handler (request-map-fn host port exchange)))))

(deftype AsyncHandler [host port handler executor create-response-fn request-map-fn]
  HttpHandler
  (handle [_ exchange]
    (handler/get-async-exchange-response
      handler
      executor
      (request-map-fn host port exchange)
      (create-response-fn exchange))))

(defn sync-not-secure-handler
  [host port handler record-support?]
  (if record-support?
    (SyncHandler. host port handler response/send-record-exchange-response handler/get-exchange-response request/get-http-exchange-request-map)
    (SyncHandler. host port handler response/send-exchange-response handler/get-exchange-response request/get-http-exchange-request-map)))

(defn async-not-secure-handler
  [host port handler executor record-support?]
  (if record-support?
    (AsyncHandler. host port handler executor response/create-async-record-response request/get-http-exchange-request-map)
    (AsyncHandler. host port handler executor response/create-async-response request/get-http-exchange-request-map)))

(defn sync-secure-handler
  [host port handler record-support?]
  (if record-support?
    (SyncHandler. host port handler response/send-record-exchange-response handler/get-exchange-response request/get-https-exchange-request-map)
    (SyncHandler. host port handler response/send-exchange-response handler/get-exchange-response request/get-https-exchange-request-map)))

(defn async-secure-handler
  [host port handler executor record-support?]
  (if record-support?
    (AsyncHandler. host port handler executor response/create-async-record-response request/get-https-exchange-request-map)
    (AsyncHandler. host port handler executor response/create-async-response request/get-https-exchange-request-map)))

(defn sync-secure-handler-with-certs
  [host port handler record-support?]
  (if record-support?
    (SyncHandler. host port handler response/send-record-exchange-response handler/get-exchange-response request/get-https-client-cert-exchange-request-map)
    (SyncHandler. host port handler response/send-exchange-response handler/get-exchange-response request/get-https-client-cert-exchange-request-map)))

(defn async-secure-handler-with-certs
  [host port handler executor record-support?]
  (if record-support?
    (AsyncHandler. host port handler executor response/create-async-record-response request/get-https-client-cert-exchange-request-map)
    (AsyncHandler. host port handler executor response/create-async-response request/get-https-client-cert-exchange-request-map)))