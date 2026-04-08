FROM mariadb:latest
COPY mariadb-entrypoint.sh /usr/local/bin/mariadb-entrypoint.sh
COPY init.sql /docker-entrypoint-initdb.d/init.sql
RUN chmod +x /usr/local/bin/mariadb-entrypoint.sh
