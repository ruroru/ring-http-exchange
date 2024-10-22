# ring-http-exchange

Clojure [ring](https://github.com/ring-clojure/ring) adapter for
[`com.sun.net.httpserver.HttpServer`](https://docs.oracle.com/javase/8/docs/jre/api/net/httpserver/spec/com/sun/net/httpserver/HttpServer.html)
which is included in the JDK.

The main motivation for this is to support starting a small HTTP
server inside an application which itself isn't necessary primarily a
web app, while avoiding adding any new major dependencies on the classpath.

## Usage

``` clojure
(:require [ring-http-exchange.core :as server])
```

``` clojure
(server/run-http-server
  (fn [_]
    {:status 200
     :headers {"Content-Type" "text/html; charset=utf-8"}
     :body "hello world"})
  {:port 8080
   :ssl-context (ssl/keystore->ssl-context 
                                      (io/resource "keystore.jks") "keystore-password"
                                      (io/resource "truststore.jks") "truststore-password")
   :executor (Executors/newVirtualThreadPerTaskExecutor)})
```

### Supported response body types

| Response Body Type                           | 
|----------------------------------------------|
| `java.lang.String`                           |
| `java.io.InputStream`                        |
| `java.io.File`                               |
| `byte[]`                                     |
| `ring.core.protocols/StreamableResponseBody` |
| `nil`                                        |

### Server configuration

| Property              | Description                                                                              | Default value |
|-----------------------|------------------------------------------------------------------------------------------|---------------|
| `host`                | Host name                                                                                | 127.0.0.1     | 
| `port`                | Application port                                                                         | 8080          |
| `executor`            | External Executor to be used, if none provided  ThreadPoolExecutor shall be used         | nil           |
| `max-threads`         | Max threads for ThreadPoolExecutor                                                       | 50            |
| `min-threads`         | Min threads for ThreadPoolExecutor                                                       | 8             |
| `max-queued-requests` | Max number of requests for ThreadPoolExecutor                                            | 1024          |                                           
| `thread-idle-timeout` | Thread idle timeout in milliseconds for ThreadPoolExecutor, after which thread will stop | 60000         |
| `ssl-context`         | Ssl context to be used in https configurator                                             | nil           |

### Limitations

* `Transfer-Encoding` header will be set to `chunked`, if it is not possible to get length of the body.


## License

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
