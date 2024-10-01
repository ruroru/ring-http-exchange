# ring-http-exchange

Clojure [ring](https://github.com/ring-clojure/ring) adapter for
[
`com.sun.net.httpserver.HttpServer`](https://docs.oracle.com/javase/8/docs/jre/api/net/httpserver/spec/com/sun/net/httpserver/HttpServer.html)
which is included in the JDK.

The main motivation for this is to support starting a small HTTP
server inside an application which itself isn't necessary primarily a
web app, while avoiding adding any new dependencies on the classpath
(apart from ring-core). It could also be used for tests.

## Usage

``` clojure
(require 'ring-http-exchange.core)

(ring-http-exchange.core/run-http-server
  (fn [_]
    {:status 200
     :headers {"Content-Type" "text/html; charset=utf-8"}
     :body "hello world"})
  {:port 8080
   :executor (Executors/newVirtualThreadPerTaskExecutor)})
```

### Supported response body types

| Response Body Type    | 
|-----------------------|
| `java.lang.String`    |
| `java.io.InputStream` |
| `java.io.File`        |
| `byte[]`              |
| `nil`                 |

### Server configuration

| Response Body Type    | Default value | Description                                                                              |
|-----------------------|---------------|------------------------------------------------------------------------------------------|
| `host`                | 127.0.0.1     | host name                                                                                |
| `port`                | 8080          | application port                                                                         |
| `executor`            | nil           | External Executor to be used, if none provided  ThreadPoolExecutor shall be used         |
| `max-threads`         | 50            | Max threads for ThreadPoolExecutor                                                       |
| `min-threads`         | 8             | Min threads for ThreadPoolExecutor                                                       |
| `max-queued-requests` | 1024          | Max number of requests for ThreadPoolExecutor                                            |                                           
| `thread-idle-timeout` | 60000         | Thread idle timeout in milliseconds for ThreadPoolExecutor, after which thread will stop |
| `ssl-context`         | nil           | Ssl context to be used in https configurator                                             |

### Limitations

* The `Content-Length` header must be explicitly set when using `java.io.InputStream`, if the `Content-length` is
  required. Otherwise, the `Transfer-Encoding` header will be set to `chunked`
* When the response body is `nil`, the `Transfer-Encoding` header will be automatically set to `chunked`

## License

Copyright © 2017 Håkan Råberg

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
