FROM postgres:18
COPY pg-entrypoint.sh /usr/local/bin/pg-entrypoint.sh
COPY pg_hba.conf /etc/postgresql/pg_hba.conf
RUN chmod +x /usr/local/bin/pg-entrypoint.sh
