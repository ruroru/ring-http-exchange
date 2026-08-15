(ns ring-http-exchange.test-client
  "Shared potoroo clients for the test suite.

  The servers under test speak HTTP/1.1 only, so both clients are pinned to it
  to keep the JDK client from sending h2c upgrade headers, which would show up
  in the request maps the tests assert on."
  (:require [jj.potoroo.httpclient :as http])
  (:import (java.security SecureRandom)
           (java.security.cert X509Certificate)
           (java.net.http HttpClient HttpClient$Version)
           (javax.net.ssl SSLContext TrustManager X509ExtendedTrustManager)))

(def client
  (http/client {:version :http-1.1}))

(def ^:private trust-all-manager
  (proxy [X509ExtendedTrustManager] []
    (getAcceptedIssuers [] (make-array X509Certificate 0))
    (checkClientTrusted
      ([_ _])
      ([_ _ _]))
    (checkServerTrusted
      ([_ _])
      ([_ _ _]))))

(def ^:private trust-all-context
  (doto (SSLContext/getInstance "TLS")
    (.init nil (into-array TrustManager [trust-all-manager]) (SecureRandom.))))

(def insecure-client
  "Client that accepts the self-signed certificate used by the ssl tests.
  potoroo's `client` cannot take an SSLContext, so this is built straight from
  the JDK builder and passed through as `:client`."
  (-> (HttpClient/newBuilder)
      (.version HttpClient$Version/HTTP_1_1)
      (.sslContext trust-all-context)
      (.build)))
