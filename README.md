# async-http-client

Simple Netty based HTTP client that handles HTTP/1.1 (with or without SSL) and HTTP/2 (only over SSL).

Note that the client is non-blocking for HTTP/2, but inherently blocking per request for HTTP/1.1.
