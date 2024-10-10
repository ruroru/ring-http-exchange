(ns ring-http-exchange.ssl
  (:require [clojure.java.io :as io])
  (:import (java.security KeyStore)
           (javax.net.ssl KeyManagerFactory SSLContext TrustManagerFactory)))


(defn- load-keystore [keystore key-password]
  (with-open [in (io/input-stream keystore)]
    (doto (KeyStore/getInstance (KeyStore/getDefaultType))
      (.load in (.toCharArray key-password)))))

(defn- keystore->key-managers [^KeyStore keystore ^String key-password]
  (.getKeyManagers
    (doto (KeyManagerFactory/getInstance (KeyManagerFactory/getDefaultAlgorithm))
      (.init keystore (.toCharArray key-password)))))

(defn- truststore->trust-managers [^KeyStore trust-store]
  (.getTrustManagers
    (doto
      (TrustManagerFactory/getInstance (TrustManagerFactory/getDefaultAlgorithm))
      (.init trust-store))))

(defn keystore->ssl-context
  ([keystore key-password truststore trust-password instance-protocol]
   (when keystore
     (let [keystore (load-keystore keystore key-password)
           truststore (if truststore
                        (load-keystore truststore trust-password)
                        keystore)]
       (doto (SSLContext/getInstance instance-protocol)
         (.init
           (keystore->key-managers keystore key-password)
           (truststore->trust-managers truststore)
           nil)))))
  ([keystore key-password truststore trust-password]
   (keystore->ssl-context keystore key-password truststore trust-password "TLS")))

