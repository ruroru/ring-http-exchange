(ns ^:no-doc ring-http-exchange.core.ring-handler
  (:require [clojure.tools.logging :as logger])
  (:import (clojure.lang IFn)
           (java.util.concurrent CountDownLatch)))


(def ^:private ^:const error-response {:status  500
                                       :body    "Internal Server Error"
                                       :headers {"Content-type" "text/html"}})
(deftype Raiser [res]
  IFn
  (invoke [_ t]
    (logger/error (.getMessage ^Throwable t))
    (res error-response)))

(defn get-exchange-response [handler request-map]
  (try (handler request-map)
       (catch Throwable t
         (logger/error (.getMessage ^Throwable t))
         error-response)))



(defn get-async-exchange-response [handler request-map res]
  (let [latch (CountDownLatch. 1)
        res-wrapper (fn [response]
                      (try
                        (res response)
                        (finally
                          (.countDown latch))))
        raise (Raiser. res-wrapper)]
    (try (handler request-map res-wrapper raise)
         (catch Throwable t
           (raise t)))
    (.await latch)))
