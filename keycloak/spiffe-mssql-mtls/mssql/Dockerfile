FROM mcr.microsoft.com/mssql/server:2022-latest
USER root
COPY mssql-entrypoint.sh /usr/local/bin/mssql-entrypoint.sh
RUN chmod +x /usr/local/bin/mssql-entrypoint.sh
ENTRYPOINT ["/usr/local/bin/mssql-entrypoint.sh"]
