I want to right a demo application that show cases remote MCP client -> server communication, with Keycloak 26.6.1 used as the authorization server that authorizes communication between the client and server.

Write a CLI ChatBot application using Quarkus that relies on an Anthropic LLM.

On starting the CLI, users should be redirected to their web browser so they can log in using an IdP that is configurable via application.properties. Once a user has logged in, a client access token should be obtained for the application and this JWT should be used as a Bearer token for all subsequent MCP client -> server communication.

Once an access token has been obtained, users should be able to interact with the LLM and make text requests.

Assume the API TOKEN required by the anthropic Quarku 1langchain4j extension is set before the CLI is launched. Add a README.md file with usage instructions.

Implement a MCP server that exposes two resources. The first resource is called "who am i" and should return the username of the user the MCP client is acting on behalf of. The second resource is called "server-secret" and should only be available to a client if the Bearer token contains the "admin" role, it should return the hostname of the node
running the server.

Make sure that the ChatBot application always utilises OIDC to communicate with the MCP server. The MCP server should
reject all unauthenticated requests.

# Summary and versions of technologies to use:
- Java 21
- Quarkus 3.34.6 and relevant extensions
- Quarkus Langchain4j and extensions for MCP/LLM integration
- Keycloak 26.6.1
- docker compose to deploy everything
