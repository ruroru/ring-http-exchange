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

(deftype FutureHandler [host port handler create-response-fn request-map-fn]
  HttpHandler
  (handle [_ exchange]
    (handler/get-future-exchange-response
      handler
      (request-map-fn host port exchange)
      (create-response-fn exchange))))

(defn- filter-request-map-fn [request-map-fn request-map-fields]
  (if request-map-fields
    (fn [host port exchange]
      (request-map-fn host port exchange request-map-fields))
    request-map-fn))

(defn sync-not-secure-handler
  [host port handler record-support? request-map-fields]
  (let [req-fn (filter-request-map-fn request/get-http-exchange-request-map request-map-fields)]
    (if record-support?
      (SyncHandler. host port handler response/send-record-exchange-response handler/get-exchange-response req-fn)
      (SyncHandler. host port handler response/send-exchange-response handler/get-exchange-response req-fn))))

(defn async-not-secure-handler
  [host port handler executor record-support? request-map-fields]
  (let [req-fn (filter-request-map-fn request/get-http-exchange-request-map request-map-fields)]
    (if record-support?
      (AsyncHandler. host port handler executor response/create-async-record-response req-fn)
      (AsyncHandler. host port handler executor response/create-async-response req-fn))))

(defn sync-secure-handler
  [host port handler record-support? request-map-fields]
  (let [req-fn (filter-request-map-fn request/get-https-exchange-request-map request-map-fields)]
    (if record-support?
      (SyncHandler. host port handler response/send-record-exchange-response handler/get-exchange-response req-fn)
      (SyncHandler. host port handler response/send-exchange-response handler/get-exchange-response req-fn))))

(defn async-secure-handler
  [host port handler executor record-support? request-map-fields]
  (let [req-fn (filter-request-map-fn request/get-https-exchange-request-map request-map-fields)]
    (if record-support?
      (AsyncHandler. host port handler executor response/create-async-record-response req-fn)
      (AsyncHandler. host port handler executor response/create-async-response req-fn))))

(defn sync-secure-handler-with-certs
  [host port handler record-support? request-map-fields]
  (let [req-fn (filter-request-map-fn request/get-https-client-cert-exchange-request-map request-map-fields)]
    (if record-support?
      (SyncHandler. host port handler response/send-record-exchange-response handler/get-exchange-response req-fn)
      (SyncHandler. host port handler response/send-exchange-response handler/get-exchange-response req-fn))))

(defn async-secure-handler-with-certs
  [host port handler executor record-support? request-map-fields]
  (let [req-fn (filter-request-map-fn request/get-https-client-cert-exchange-request-map request-map-fields)]
    (if record-support?
      (AsyncHandler. host port handler executor response/create-async-record-response req-fn)
      (AsyncHandler. host port handler executor response/create-async-response req-fn))))

(defn future-not-secure-handler
  [host port handler record-support? request-map-fields]
  (let [req-fn (filter-request-map-fn request/get-http-exchange-request-map request-map-fields)]
    (if record-support?
      (FutureHandler. host port handler response/create-async-record-response req-fn)
      (FutureHandler. host port handler response/create-async-response req-fn))))

(defn future-secure-handler
  [host port handler record-support? request-map-fields]
  (let [req-fn (filter-request-map-fn request/get-https-exchange-request-map request-map-fields)]
    (if record-support?
      (FutureHandler. host port handler response/create-async-record-response req-fn)
      (FutureHandler. host port handler response/create-async-response req-fn))))

(defn future-secure-handler-with-certs
  [host port handler record-support? request-map-fields]
  (let [req-fn (filter-request-map-fn request/get-https-client-cert-exchange-request-map request-map-fields)]
    (if record-support?
      (FutureHandler. host port handler response/create-async-record-response req-fn)
      (FutureHandler. host port handler response/create-async-response req-fn))))
