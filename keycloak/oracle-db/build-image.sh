#!/bin/bash

mkdir drivers
curl https://repo1.maven.org/maven2/com/oracle/database/jdbc/ojdbc17/23.6.0.24.10/ojdbc17-23.6.0.24.10.jar --output drivers/ojdbc17.jar
curl https://repo1.maven.org/maven2/com/oracle/database/nls/orai18n/23.6.0.24.10/orai18n-23.6.0.24.10.jar --output drivers/orai18n.jar

docker build . --tag my-keycloak

