# runtime
HYTE Runtime

HYBRID Microservices runtime 

 * Java 8 
 * Apache ActiveMQ (v5.15.2) for messaging and eventing
 * Apache Camel (v2.21.0) for integration and low-code process flows
 * Apache CXF (v3.2.3) for REST API (JAX-RS) 
 * Jackson (v2.9.1) for JSON data formats
 * Karaf (v4.1.5) lightweight runtime
 * LMAX disruptor (v3.4.1) for high-speed logging

Latest version: 4.1.5.hyte-4006 (DO NOT use 4.1.6.hyte-4006)

[Download latest](http://central.maven.org/maven2/io/hyte/runtime/hyte-runtime/4.1.5.hyte-4006/hyte-runtime-4.1.5.hyte-4006-unix.tar.gz)

Maven coordinates for Docker container or Maven Assembly builds:

```
<dependency>
    <groupId>io.hyte.runtime</groupId>
    <artifactId>hyte-runtime</artifactId>
    <version>4.1.5.hyte-4006</version>
    <type>tar.gz</type>
</dependency>
```
