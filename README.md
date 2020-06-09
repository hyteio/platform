# HYTE Platform #

## HYBRID Microservices Runtime ##

HYTE Platform provides a standards-based runtime for running modular applications. Support for using REST APIs, standardized enterprise messaging and low-code integration process flows.

## Components ##

 * Java 11
 * Apache ActiveMQ (v5.15.13) for messaging and eventing
 * Apache Camel (v2.25.1) for integration and low-code process flows
 * Apache CXF (v3.3.6) for REST API (JAX-RS) 
 * Apache Karaf (v4.2.9) lightweight runtime
 * Jackson (v2.11.0) for JSON data formats
 * LMAX disruptor (v3.4.2) for high-speed async logging with log4j2

Latest version: 4.2.9.hyte-4290

 * UNIX 64-bit tar.gz: [Download latest](https://repo1.maven.org/maven2/io/hyte/platform/hyte-runtime/4.2.9.hyte-4290/hyte-runtime-4.2.9.hyte-4290-unix.tar.gz)
 * Docker pre-image tar.gz: [Download latest](https://repo1.maven.org/maven2/io/hyte/platform/hyte-runtime/4.2.9.hyte-4290/hyte-runtime-4.2.9.hyte-4290-docker.tar.gz)
 * Windows 64-bit zip: [Download latest](https://repo1.maven.org/maven2/io/hyte/platform/hyte-runtime/4.2.9.hyte-4290/hyte-runtime-4.2.9.hyte-4290-win64.zip)

### Default admin account and ports ###

Default administrator account name: admin
Default password: admin

| **Service** | **Port** | **URL** | **Example usage** |
|---------|------|-----|---------|
| HTTP    | 8181 | http://localhost:8181 | wget http://localhost:8181 |
| API base url | 8181 | http://localhost:8181/api | wget http://localhost:8181/api |
| SSH     | 8101 | ssh://localhost:8101 | ssh -p 8101 admin@localhost |
| JMXMP   | 9999 | service:jmx:jmxmp://localhost:9999 | |
| JMX     | 44444/1099 | service:jmx:rmi://localhost:44444/jndi/rmi://localhost:1099/karaf-root | |

### Maven coordinates for UNIX: ###
```
<dependency>
    <groupId>io.hyte.platform</groupId>
    <artifactId>hyte-runtime</artifactId>
    <version>4.2.9.hyte-4290</version>
    <classifier>unix</classifier>
    <type>tar.gz</type>
</dependency>
```

### Maven coordinates for Docker image composition: ###
```
<dependency>
    <groupId>io.hyte.platform</groupId>
    <artifactId>hyte-runtime</artifactId>
    <version>4.2.9.hyte-4290</version>
    <classifier>docker</classififer>
    <type>tar.gz</type>
</dependency>
```

### Maven coordinates for Windows: ###
```
<dependency>
    <groupId>io.hyte.platform</groupId>
    <artifactId>hyte-runtime</artifactId>
    <version>4.2.9.hyte-4290</version>
    <classifier>win64</classififer>
    <type>zip</type>
</dependency>
```

## HYTE MQ ##

HYTE MQ is a packaged build of Apache ActiveMQ that applies enterprise grade best practices

### Maven coordinates for UNIX: ###
```
<dependency>
    <groupId>io.hyte.platform</groupId>
    <artifactId>hyte-mq</artifactId>
    <version>4.2.9.hyte-4290</version>
    <classifier>unix</classifier>
    <type>tar.gz</type>
</dependency>
```

### Maven coordinates for Docker image composition: ###
```
<dependency>
    <groupId>io.hyte.platform</groupId>
    <artifactId>hyte-mq</artifactId>
    <version>4.2.9.hyte-4290</version>
    <classifier>docker</classififer>
    <type>tar.gz</type>
</dependency>
```

### Maven coordinates for Windows: ###
```
<dependency>
    <groupId>io.hyte.platform</groupId>
    <artifactId>hyte-mq</artifactId>
    <version>4.2.9.hyte-4290</version>
    <classifier>win64</classififer>
    <type>zip</type>
</dependency>
```

## HYTE DB ##

HYTE DB is a packaged build of H2 Database

### Maven coordinates for UNIX: ###
```
<dependency>
    <groupId>io.hyte.platform</groupId>
    <artifactId>hyte-db</artifactId>
    <version>4.2.9.hyte-4290</version>
    <classifier>unix</classifier>
    <type>tar.gz</type>
</dependency>
```

### Maven coordinates for Docker image composition: ###
```
<dependency>
    <groupId>io.hyte.platform</groupId>
    <artifactId>hyte-db</artifactId>
    <version>4.2.9.hyte-4290</version>
    <classifier>docker</classififer>
    <type>tar.gz</type>
</dependency>
```

### Maven coordinates for Windows: ###
```
<dependency>
    <groupId>io.hyte.platform</groupId>
    <artifactId>hyte-db</artifactId>
    <version>4.2.9.hyte-4290</version>
    <classifier>win64</classififer>
    <type>zip</type>
</dependency>
```

[![Codacy Badge](https://api.codacy.com/project/badge/Grade/32c2b2ab5c3e4646bda106ee65e9a6d1)](https://www.codacy.com/app/mattrpav_2/runtime?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=hyteio/runtime&amp;utm_campaign=Badge_Grade)
