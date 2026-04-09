FROM mysql:8.4
COPY mysql-entrypoint.sh /usr/local/bin/mysql-entrypoint.sh
COPY init.sql /docker-entrypoint-initdb.d/init.sql
RUN chmod +x /usr/local/bin/mysql-entrypoint.sh
