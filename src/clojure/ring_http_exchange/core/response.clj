(ns ^:no-doc ring-http-exchange.core.response
  (:require [ring.core.protocols :as protocols])
  (:import (com.sun.net.httpserver Headers HttpExchange)
           (java.io File FileInputStream InputStream OutputStream)
           (java.nio.charset StandardCharsets)))

(defn- set-response-headers [^Headers response-headers resp-headers]
  (doseq [[k v] resp-headers]
    (.add response-headers (name k)
          (if (string? v) v (str v)))))

(defn- send-file [^HttpExchange exchange ^OutputStream out body headers status]
  (with-open [in ^InputStream (FileInputStream. ^File body)
              out1 ^OutputStream out]
    (set-response-headers (.getResponseHeaders exchange) headers)
    (.sendResponseHeaders exchange status (.length ^File body))

    (.transferTo ^FileInputStream in out1)
    (.flush ^OutputStream out1)))


(defn- send-input-stream [^HttpExchange exchange ^OutputStream out body headers status]
  (with-open [in ^InputStream body
              out1 out]
    (set-response-headers (.getResponseHeaders exchange) headers)
    (.sendResponseHeaders exchange status 0)

    (.transferTo ^InputStream in out1)
    (.flush ^OutputStream out1)))

(defn- send-byte-array [^HttpExchange exchange ^OutputStream out body headers status]
  (with-open [out out]
    (set-response-headers (.getResponseHeaders exchange) headers)
    (.sendResponseHeaders exchange status (alength ^"[B" body))

    (.write out ^"[B" body)
    (.flush ^OutputStream out)))

(defn- send-string [^HttpExchange exchange ^OutputStream out ^String body headers status]
  (send-byte-array exchange out (.getBytes ^String body StandardCharsets/UTF_8) headers status))

(defn- maybe-streamable [^HttpExchange exchange ^OutputStream out body headers status]
  (with-open [out out]
    (set-response-headers (.getResponseHeaders exchange) headers)
    (.sendResponseHeaders exchange status 0)

    (protocols/write-body-to-stream body {:body    body
                                          :status  status
                                          :headers headers}
                                    out)))


(defn- send-error [^HttpExchange exchange]
  (send-string exchange
               (.getResponseBody exchange)
               "Internal Server Error"
               {"Content-type" "text/html"}
               500))

(defn- send-file-not-found [^HttpExchange exchange]
  (send-string exchange
               (.getResponseBody exchange)
               "File Not Found"
               {"Content-type" "text/html"}
               404))

(defn- if-not-file [exchange ^OutputStream out body headers status]
  (if (satisfies? protocols/StreamableResponseBody body)
    (maybe-streamable exchange out body headers status)
    (send-error exchange)))

(defn- if-not-byte-array [exchange ^OutputStream out body headers status]
  (if (instance? File body)
    (if (.exists ^File body)
      (send-file exchange out body headers status)
      (send-file-not-found exchange))
    (if-not-file exchange out body headers status)))

(defn- maybe-byte-array [exchange ^OutputStream out body headers status]
  (if (bytes? body)
    (send-byte-array exchange out body headers status)
    (if-not-byte-array exchange out body headers status)))

(defn- maybe-inputs-stream [exchange ^OutputStream out body headers status]
  (if (instance? InputStream body)
    (send-input-stream exchange out body headers status)
    (maybe-byte-array exchange out body headers status)))

(defn- send-response [exchange out body headers status]
  (if (instance? String body)
    (send-string exchange out body headers status)
    (maybe-inputs-stream exchange out body headers status)))

(defn send-exchange-response [^HttpExchange exchange response]
  (if response
    (send-response exchange (.getResponseBody exchange) (response :body) (response :headers) (response :status 200))
    (send-error exchange)))

(defn send-record-exchange-response [^HttpExchange exchange response]
  (if response
    (send-response exchange (.getResponseBody exchange) (:body response) (:headers response) (:status response 200))
    (send-error exchange)))
