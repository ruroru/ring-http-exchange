(ns ^:no-doc ring-http-exchange.core.ring-handler
  (:require [clojure.tools.logging :as logger]))

(defn get-exchange-response [handler request-map]
  (try (handler request-map)
       (catch Throwable t
         (logger/error (.getMessage ^Throwable t))
         {:status  500
          :body    "Internal Server Error"
          :headers {"Content-type" "text/html"}})))

(defn get-async-exchange-response [handler request-map res]
  (try (handler request-map res res)
       (catch Throwable t
         (logger/error (.getMessage ^Throwable t))
         (res {:status  500
               :body    "Internal Server Error"
               :headers {"Content-type" "text/html"}}))))
