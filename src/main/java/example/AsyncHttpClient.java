package example;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http2.DefaultHttp2Connection;
import io.netty.handler.codec.http2.DelegatingDecompressorFrameListener;
import io.netty.handler.codec.http2.Http2FrameLogger;
import io.netty.handler.codec.http2.Http2SecurityUtil;
import io.netty.handler.codec.http2.HttpConversionUtil;
import io.netty.handler.codec.http2.HttpToHttp2ConnectionHandler;
import io.netty.handler.codec.http2.HttpToHttp2ConnectionHandlerBuilder;
import io.netty.handler.codec.http2.InboundHttp2ToHttpAdapter;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.AsciiString;
import io.netty.util.concurrent.Promise;

import javax.net.ssl.SSLException;
import java.io.Closeable;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static io.netty.channel.ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE;
import static io.netty.handler.logging.LogLevel.INFO;
import static java.util.Collections.emptyMap;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class AsyncHttpClient implements Closeable {

    private final static AsciiString streamIdHeaderName = HttpConversionUtil.ExtensionHeaderNames.STREAM_ID.text();

    private final NioEventLoopGroup group;
    private final Channel channel;
    private final String host;

    private AtomicInteger streamIdCounter = new AtomicInteger(3);

    public static void main(String[] args) throws InterruptedException, IOException {
        final byte[] emptyBody = new byte[0];

        try (final AsyncHttpClient client = new AsyncHttpClient("google.com", 443, true)) {
            final Promise<FullHttpResponse> future1 = client.run("GET", "/", emptyMap(), emptyBody,
                    response -> {
                        System.out.println("Got response for GET /: " + response);
                    },
                    (headers, isLast) -> {
                        System.out.println("Got headers for GET /: " + headers);
                        System.out.println("Got headers.isLast for GET /: " + isLast);
                    },
                    (content, isLast) -> {
                        System.out.println("Got content for GET /: " + content);
                        System.out.println("Got content.isLast for GET /: " + isLast);
                    }
            );
            final Promise<FullHttpResponse> future2 = client.run("GET", "/foo", emptyMap(), emptyBody,
                    response -> {
                        System.out.println("Got response for GET /foo: " + response);
                    },
                    (headers, isLast) -> {
                        System.out.println("Got headers for GET /foo: " + headers);
                        System.out.println("Got headers.isLast for GET /foo: " + isLast);
                    },
                    (content, isLast) -> {
                        System.out.println("Got content for GET /foo: " + content);
                        System.out.println("Got content.isLast for GET /foo: " + isLast);
                    }
            );

            System.out.println("Waiting for future 1 sync...");
            final Promise<FullHttpResponse> result1 = future1.sync();
            System.out.println("Got future 1 sync!");
            if (result1.isSuccess()) {
                System.out.println("Hurray for future 1!");
            } else {
                System.out.println("Boo for future 1!");
            }

            System.out.println("Waiting for future 2 sync...");
            final Promise<FullHttpResponse> result2 = future2.sync();
            System.out.println("Got future 2 sync!");
            if (result2.isSuccess()) {
                System.out.println("Hurray for future 2!");
            } else {
                System.out.println("Boo for future 2!");
            }
        }
    }

    public AsyncHttpClient(String host, int port, boolean decompressBody) {
        this.host = host;
        this.group = new NioEventLoopGroup();

        final Bootstrap bootstrap = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        final Http2FrameLogger frameLogger = new Http2FrameLogger(INFO, AsyncHttpClient.class);
                        final DefaultHttp2Connection connection = new DefaultHttp2Connection(false);
                        final InboundHttp2ToHttpAdapter adapter = new DetailedInboundHttp2ToHttpAdapter(connection);
                        final HttpToHttp2ConnectionHandler connectionHandler = new HttpToHttp2ConnectionHandlerBuilder()
                                .frameListener(decompressBody ? new DelegatingDecompressorFrameListener(connection, adapter) : adapter)
                                .frameLogger(frameLogger)
                                .connection(connection)
                                .build();

                        final ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast(createSslHandler(ch));
                        pipeline.addLast(connectionHandler);
                    }
                });

        final ChannelFuture channelFuture = bootstrap.connect(host, port);
        try {
            this.channel = channelFuture.sync().channel();
        } catch (InterruptedException ie) {
            throw new RuntimeException(ie);
        }
    }

    private int getNextStreamId() {
        return streamIdCounter.getAndAdd(2);
    }

    private Promise<FullHttpResponse> run(String method, String path, Map<String, String> headerMap, byte[] body) {
        return run(method, path, headerMap, body,
                r -> {
                },
                (h, b) -> {
                },
                (c, b) -> {
                }
        );
    }

    private Promise<FullHttpResponse> run(String method, String path, Map<String, String> headerMap, byte[] body, Consumer<HttpResponse> onResponse, BiConsumer<HttpHeaders, Boolean> onHeaders, BiConsumer<HttpContent, Boolean> onContent) {
        final DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.valueOf(method), path, Unpooled.wrappedBuffer(body));
        final HttpHeaders headers = req.headers();
        headers.add(HttpHeaderNames.HOST, this.host);
        headers.add(HttpHeaderNames.CONTENT_LENGTH, body.length);
        headers.add(HttpConversionUtil.ExtensionHeaderNames.SCHEME.text(), "https");

        for (Map.Entry<String, String> header : headerMap.entrySet()) {
            headers.add(header.getKey(), header.getValue());
        }

        final int nextStreamId = getNextStreamId();
        final Promise<FullHttpResponse> promise = channel.pipeline().firstContext().executor().newPromise();
        channel.pipeline().addLast(new ResponseHandler(nextStreamId, onResponse, onHeaders, onContent, promise));
        channel.writeAndFlush(req).addListener(FIRE_EXCEPTION_ON_FAILURE);
        return promise;
    }

    @Override
    public void close() throws IOException {
        try {
            channel.close().sync();
            group.shutdownGracefully(0, 100, MILLISECONDS);
        } catch (InterruptedException e) {
            throw new IOException("Interrupted while closing channel!", e);
        }
    }

    class ResponseHandler extends SimpleChannelInboundHandler<Object> {

        private final int streamId;
        private final Consumer<HttpResponse> onResponse;
        private final BiConsumer<HttpHeaders, Boolean> onHeaders;
        private final BiConsumer<HttpContent, Boolean> onContent;
        private final Promise<FullHttpResponse> promise;

        ResponseHandler(int streamId, Consumer<HttpResponse> onResponse, BiConsumer<HttpHeaders, Boolean> onHeaders, BiConsumer<HttpContent, Boolean> onContent, Promise<FullHttpResponse> promise) {
            this.streamId = streamId;
            this.onResponse = onResponse;
            this.onHeaders = onHeaders;
            this.onContent = onContent;
            this.promise = promise;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof HttpResponseWithStreamId) {
                final HttpResponseWithStreamId objectWithStreamId = (HttpResponseWithStreamId) msg;

                if (objectWithStreamId.streamId == streamId) {
                    onResponse.accept(objectWithStreamId.httpResponse);
                } else {
                    ctx.fireChannelRead(msg);
                }
            } else if (msg instanceof HttpHeadersWithStreamId) {
                final HttpHeadersWithStreamId objectWithStreamId = (HttpHeadersWithStreamId) msg;

                if (objectWithStreamId.streamId == streamId) {
                    onHeaders.accept(objectWithStreamId.httpHeaders, objectWithStreamId.noMoreContent);
                } else {
                    ctx.fireChannelRead(msg);
                }
            } else if (msg instanceof HttpContentWithStreamId) {
                final HttpContentWithStreamId objectWithStreamId = (HttpContentWithStreamId) msg;

                if (objectWithStreamId.streamId == streamId) {
                    onContent.accept(objectWithStreamId.httpContent, objectWithStreamId.noMoreContent);
                } else {
                    ctx.fireChannelRead(msg);
                }
            } else if (msg instanceof FullHttpResponse) {
                final FullHttpResponse fullHttpResponse = (FullHttpResponse) msg;

                final HttpHeaders headers = fullHttpResponse.headers();
                final int responseStreamId = headers.getInt(streamIdHeaderName);
                if (responseStreamId == streamId) {
                    promise.setSuccess(fullHttpResponse);
                } else {
                    ctx.fireChannelRead(fullHttpResponse.retain());
                }
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            if (!promise.isDone()) {
                promise.setFailure(cause);
            }
        }
    }

    private SslHandler createSslHandler(SocketChannel channel) {
        try {
            final SslProvider provider = OpenSsl.isAlpnSupported() ? SslProvider.OPENSSL : SslProvider.JDK;
            final SslContext sslCtx = SslContextBuilder.forClient().sslProvider(provider)
            /* NOTE: the cipher filter may not include all ciphers required by the HTTP/2 specification.
            * Please refer to the HTTP/2 specification for cipher requirements. */
                    .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
                    .trustManager(InsecureTrustManagerFactory.INSTANCE)
                    .applicationProtocolConfig(new ApplicationProtocolConfig(
                            ApplicationProtocolConfig.Protocol.ALPN,
                            // NO_ADVERTISE is currently the only mode supported by both OpenSsl and JDK providers.
                            ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                            // ACCEPT is currently the only mode supported by both OpenSsl and JDK providers.
                            ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                            ApplicationProtocolNames.HTTP_2))
                    .build();
            return sslCtx.newHandler(channel.alloc());
        } catch (SSLException e) {
            throw new RuntimeException(e);
        }
    }
}