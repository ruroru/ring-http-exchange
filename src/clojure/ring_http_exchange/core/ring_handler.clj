(ns ^:no-doc ring-http-exchange.core.ring-handler
  (:require [clojure.tools.logging :as logger])
  (:import (clojure.lang IFn)))


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
  (let [raise (Raiser. res)]
    (try (handler request-map res raise)
         (catch Throwable t
           (raise t)))))
