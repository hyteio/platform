HYTE DB
============
 
Contents
========

 * HYTE DB ${project.version}
 
 Components:
 * Apache Karaf ${hyte.karaf.version}
 * H2 Database ${hyte.h2.version}
 
Contact
=======

URL: https://hyte.io
Sales: <sales@hyte.io>
Technical Support: <support@hyte.io>

Distributions
=============

The HYTE DB is distributed in three ways:

 * hyte-db-${project.version}-unix.tgz - Stand-Alone distribution that is ready to start on Linux 64-bit systems.
 * hyte-db-${project.version}-win64.tgz - Stand-Alone distribution that is ready to start on Windows 64-bit systems.
 * hyte-db-${project.version}-docker.tgz - Docker-ready distribution that is prepared for inclusion in a Docker container build process.

All distributions are available for the current version of the product via Maven Central: http://central.maven.org/maven2/io/hyte/platform/hyte-db/

Quick Start
===========

+++ Stand-alone Quick Start Instructions (Linux)

 Starting: 

 1. Extract the 'hyte-db-${project,version}-unix.tgz' distribution file.
 2. Ensure additional instances of HYTE DB are shutdown to avoid port conflicts.
 3. Run ./bin/start
 4. Open a browser to http://localhost:8181 

 Stopping:

 * If the HYTE DB was started with "./bin/start", stop it with "./bin/stop".
 * If the HYTE DB was started with "./bin/karaf", enter the command "shutdown" or press ctrl+D within the shell prompt.

License
====================

HYTE DB is licensed according to the 'license.txt' file
