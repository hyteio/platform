# HYTE Platform #

## HYBRID Microservices Runtime ##

HYTE Platform provides a standards-based runtime for running modular applications. Support for using REST APIs, standardized enterprise messaging and low-code integration process flows.

## Components ##

 * Java 11
 * Apache ActiveMQ (v5.17.5) for messaging and eventing
 * Apache Camel (v3.14.2) for integration and low-code process flows
 * Apache CXF (v3.4.8) for REST API (JAX-RS) 
 * Apache Karaf (v4.3.7) lightweight runtime
 * Jackson (v2.15.2) for JSON data formats
 * LMAX disruptor (v3.4.4) for high-speed async logging with log4j2

Latest version: 4.3.7.hyte-4307d

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

## Downloads:
 * UNIX 64-bit tar.gz: [Download latest](https://repo1.maven.org/maven2/io/hyte/platform/hyte-runtime/4.3.7.hyte-4307d/hyte-runtime-4.3.7.hyte-4307d-unix.tar.gz)
 * Kubernetes pre-image tar.gz: [Download latest](https://repo1.maven.org/maven2/io/hyte/platform/hyte-runtime/4.3.7.hyte-4307d/hyte-runtime-4.3.7.hyte-4307d-kubernetes.tar.gz)
 * Windows 64-bit zip: [Download latest](https://repo1.maven.org/maven2/io/hyte/platform/hyte-runtime/4.3.7.hyte-4307d/hyte-runtime-4.3.7.hyte-4307d-win64.zip)

### Maven coordinates for UNIX: ###
```
<dependency>
    <groupId>io.hyte.platform</groupId>
    <artifactId>hyte-runtime</artifactId>
    <version>4.3.7.hyte-4307d</version>
    <classifier>unix</classifier>
    <type>tar.gz</type>
</dependency>
```

### Maven coordinates for Kubernetes image composition: ###
```
<dependency>
    <groupId>io.hyte.platform</groupId>
    <artifactId>hyte-runtime</artifactId>
    <version>4.3.7.hyte-4307d</version>
    <classifier>kubernetes</classififer>
    <type>tar.gz</type>
</dependency>
```

### Maven coordinates for Windows: ###
```
<dependency>
    <groupId>io.hyte.platform</groupId>
    <artifactId>hyte-runtime</artifactId>
    <version>4.3.7.hyte-4307d</version>
    <classifier>win64</classififer>
    <type>zip</type>
</dependency>
```

## HYTE MQ ##

HYTE MQ is a packaged build of Apache ActiveMQ that applies enterprise grade best practices

## Downloads:
 * UNIX 64-bit tar.gz: [Download latest](https://repo1.maven.org/maven2/io/hyte/platform/hyte-mq/4.3.7.hyte-4307d/hyte-mq-4.3.7.hyte-4307d-unix.tar.gz)
 * Kubernetes pre-image tar.gz: [Download latest](https://repo1.maven.org/maven2/io/hyte/platform/hyte-mq/4.3.7.hyte-4307d/hyte-mq-4.3.7.hyte-4307d-kubernetes.tar.gz)
 * Windows 64-bit zip: [Download latest](https://repo1.maven.org/maven2/io/hyte/platform/hyte-mq/4.3.7.hyte-4307d/hyte-mq-4.3.7.hyte-4307d-win64.zip)

### Maven coordinates for UNIX: ###
```
<dependency>
    <groupId>io.hyte.platform</groupId>
    <artifactId>hyte-mq</artifactId>
    <version>4.3.7.hyte-4307d</version>
    <classifier>unix</classifier>
    <type>tar.gz</type>
</dependency>
```

### Maven coordinates for Kubernetes image composition: ###
```
<dependency>
    <groupId>io.hyte.platform</groupId>
    <artifactId>hyte-mq</artifactId>
    <version>4.3.7.hyte-4307d</version>
    <classifier>kubernetes</classififer>
    <type>tar.gz</type>
</dependency>
```

### Maven coordinates for Windows: ###
```
<dependency>
    <groupId>io.hyte.platform</groupId>
    <artifactId>hyte-mq</artifactId>
    <version>4.3.7.hyte-4307d</version>
    <classifier>win64</classififer>
    <type>zip</type>
</dependency>
```

## HYTE DB ##

HYTE DB is a packaged build of H2 Database

### Downloads:
 * UNIX 64-bit tar.gz: [Download latest](https://repo1.maven.org/maven2/io/hyte/platform/hyte-db/4.3.7.hyte-4307d/hyte-db-4.3.7.hyte-4307d-unix.tar.gz)
 * Kubernetes pre-image tar.gz: [Download latest](https://repo1.maven.org/maven2/io/hyte/platform/hyte-db/4.3.7.hyte-4307d/hyte-db-4.3.7.hyte-4307d-kubernetes.tar.gz)
 * Windows 64-bit zip: [Download latest](https://repo1.maven.org/maven2/io/hyte/platform/hyte-db/4.3.7.hyte-4307d/hyte-db-4.3.7.hyte-4307d-win64.zip)

### Maven coordinates for UNIX: ###
```
<dependency>
    <groupId>io.hyte.platform</groupId>
    <artifactId>hyte-db</artifactId>
    <version>4.3.7.hyte-4307d</version>
    <classifier>unix</classifier>
    <type>tar.gz</type>
</dependency>
```

### Maven coordinates for Kubernetes image composition: ###
```
<dependency>
    <groupId>io.hyte.platform</groupId>
    <artifactId>hyte-db</artifactId>
    <version>4.3.7.hyte-4307d</version>
    <classifier>kubernetes</classififer>
    <type>tar.gz</type>
</dependency>
```

### Maven coordinates for Windows: ###
```
<dependency>
    <groupId>io.hyte.platform</groupId>
    <artifactId>hyte-db</artifactId>
    <version>4.3.7.hyte-4307d</version>
    <classifier>win64</classififer>
    <type>zip</type>
</dependency>
```

[![Codacy Badge](https://api.codacy.com/project/badge/Grade/32c2b2ab5c3e4646bda106ee65e9a6d1)](https://www.codacy.com/app/mattrpav_2/runtime?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=hyteio/runtime&amp;utm_campaign=Badge_Grade)
