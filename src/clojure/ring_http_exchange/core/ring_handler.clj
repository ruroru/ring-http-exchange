(ns ^:no-doc ring-http-exchange.core.ring-handler
  (:require [clojure.tools.logging :as logger])
  (:import (java.util.concurrent CompletableFuture Executor)))


(def ^:private ^:const error-response {:status  500
                                       :body    "Internal Server Error"
                                       :headers {"Content-type" "text/html"}})

(defn get-exchange-response [handler request-map]
  (try (handler request-map)
       (catch Throwable t
         (logger/error (.getMessage ^Throwable t))
         error-response)))


(defn get-async-exchange-response [handler executor request-map res-fn]
  (let [cf (CompletableFuture.)]
    (.execute ^Executor executor
              (fn []
                (try
                  (handler request-map
                           (fn [res] (.complete cf res))
                           (fn [_] (.complete cf :error)))
                  (catch Throwable _
                    (.complete cf :error)))))
    (let [result (.get cf)]
      (if (= result :error)
        (res-fn error-response)
        (res-fn result)))))
