# HYTE Runtime #

## HYBRID Microservices runtime ##

HYTE Runtime provides a standards-based runtime for running HYBRID microservies using REST APIs, standardized enterprise messaging and low-code integration process flows.

## Components ##

 * Java 8 
 * Apache ActiveMQ (v5.15.3) for messaging and eventing
 * Apache Camel (v2.21.0) for integration and low-code process flows
 * Apache CXF (v3.2.3) for REST API (JAX-RS) 
 * Apache Karaf (v4.1.5) lightweight runtime
 * Jackson (v2.9.3) for JSON data formats
 * LMAX disruptor (v3.4.1) for high-speed async logging with log4j2

Latest version: 4.1.5.hyte-4007 (**_DO NOT use 4.1.6.hyte-4006_**)

 * UNIX 64-bit tar.gz: [Download latest](http://central.maven.org/maven2/io/hyte/runtime/hyte-runtime/4.1.5.hyte-4007/hyte-runtime-4.1.5.hyte-4007-unix.tar.gz)
 * Docker pre-image tar.gz: [Download latest](http://central.maven.org/maven2/io/hyte/runtime/hyte-runtime/4.1.5.hyte-4007/hyte-runtime-4.1.5.hyte-4007-docker.tar.gz)
 * Windows 64-bit zip: [Download latest](http://central.maven.org/maven2/io/hyte/runtime/hyte-runtime/4.1.5.hyte-4007/hyte-runtime-4.1.5.hyte-4007-win64.zip)

### Maven coordinates for UNIX: ###
```
<dependency>
    <groupId>io.hyte.runtime</groupId>
    <artifactId>hyte-runtime</artifactId>
    <version>4.1.5.hyte-4007</version>
    <classifier>unix</classifier>
    <type>tar.gz</type>
</dependency>
```

### Maven coordinates for Docker image composition: ###
```
<dependency>
    <groupId>io.hyte.runtime</groupId>
    <artifactId>hyte-runtime</artifactId>
    <version>4.1.5.hyte-4007</version>
    <classifier>docker</classififer>
    <type>tar.gz</type>
</dependency>
```

### Maven coordinates for Windows: ###
```
<dependency>
    <groupId>io.hyte.runtime</groupId>
    <artifactId>hyte-runtime</artifactId>
    <version>4.1.5.hyte-4007</version>
    <classifier>win64</classififer>
    <type>zip</type>
</dependency>
```


