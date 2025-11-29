# ring-http-exchange

Clojure [ring](https://github.com/ring-clojure/ring) adapter for
[
`com.sun.net.httpserver.HttpServer`](https://docs.oracle.com/javase/8/docs/jre/api/net/httpserver/spec/com/sun/net/httpserver/HttpServer.html)
which is included in the JDK.

The main motivation for this is to support starting a small HTTP
server inside an application which itself isn't necessary primarily a
web app, while avoiding adding any new major dependencies on the classpath.

[![Clojars Project](https://img.shields.io/clojars/v/org.clojars.jj/ring-http-exchange.svg)](https://clojars.org/org.clojars.jj/ring-http-exchange)

## Installation

Add ring-http-exchange to dependency list

```clojure
[org.clojars.jj/ring-http-exchange "1.2.6"]
```

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

### Server configuration

| Property          | Description                                  | Default value         |
|-------------------|----------------------------------------------|-----------------------|
| `host`            | Host name                                    | 0.0.0.0               | 
| `port`            | Application port                             | 8080                  |
| `executor`        | Executor to be used                          | ThreadPerTaskExecutor |
| `ssl-context`     | Ssl context to be used in https configurator | nil                   |
| `record-support?` | Can use records as a response                | false                 |

## Performance Tips

### Executor

To increase performance you can pick executor depending on the use case

| Use case   | Executor                                                                         |
|------------|----------------------------------------------------------------------------------|
| Throughput | `Executors/newVirtualThreadPerTaskExecutor`                                      |
| Latency    | [NioEventLoopGroup](https://mvnrepository.com/artifact/io.netty/netty-transport) |

### Robaho httpserver

[Robaho httpserver](https://github.com/robaho/httpserver) can be added to the dependency list,
which is a drop-in replacement for a ``com.sun.net.httpserver``

```clojure
[io.github.robaho/httpserver "1.0.29"]
```

## License

Distributed under the Eclipse Public License either version 1.0 or (at your option) any later version.
