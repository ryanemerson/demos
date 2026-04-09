FROM gvenzl/oracle-free:23-full
USER root
RUN microdnf install -y openssl && microdnf clean all
COPY setup-tls.sh /container-entrypoint-startdb.d/01-setup-tls.sh
RUN chmod +x /container-entrypoint-startdb.d/01-setup-tls.sh
USER oracle
