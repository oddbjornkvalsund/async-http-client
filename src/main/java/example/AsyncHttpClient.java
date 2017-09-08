package example;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
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
import io.netty.handler.codec.http.LastHttpContent;
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

import javax.net.ssl.SSLException;
import java.io.Closeable;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;

import static io.netty.channel.ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE;
import static io.netty.handler.logging.LogLevel.INFO;

public class AsyncHttpClient implements Closeable {

    private final Channel channel;
    private final boolean decompressBody;
    private final String host;
    private final int port;

    public static void main(String[] args) throws InterruptedException {
        final AsyncHttpClient client = new AsyncHttpClient("google.com", 443, true);
        client.run("GET", "/", Collections.emptyMap());
        client.run("GET", "/foo", Collections.emptyMap());
        Thread.sleep(10000);
    }

    AsyncHttpClient(String host, int port, boolean decompressBody) throws InterruptedException {
        this.host = host;
        this.port = port;
        this.decompressBody = decompressBody;

        final Bootstrap bootstrap = new Bootstrap()
                .group(new NioEventLoopGroup())
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

                        final ResponseHandler responseHandler = new ResponseHandler();

                        final ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast(createSslHandler(ch));
                        pipeline.addLast(connectionHandler);
                        pipeline.addLast(responseHandler);
                    }
                });

        final ChannelFuture channelFuture = bootstrap.connect(host, port);
        this.channel = channelFuture.sync().channel();
    }

    void run(String method, String path, Map<String, String> headerMap) throws InterruptedException {
        final DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.valueOf(method), path);
        final HttpHeaders headers = req.headers();
        headers.add(HttpHeaderNames.CONTENT_LENGTH, "0");
        headers.add(HttpHeaderNames.HOST, this.host);
        headers.add(HttpConversionUtil.ExtensionHeaderNames.SCHEME.text(), "https");

        for (Map.Entry<String, String> header : headerMap.entrySet()) {
            headers.add(header.getKey(), header.getValue());
        }

        try {
            // TODO: How do we get the streamid for the request?
            // See: http://unrestful.io/2015/10/10/http2-java-client-examples.html
            channel.writeAndFlush(req).addListener(FIRE_EXCEPTION_ON_FAILURE).sync();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() throws IOException {
        try {
            channel.close().sync();
        } catch (InterruptedException e) {
            throw new IOException("Interrupted while closing channel!", e);
        }
    }

    class ResponseHandler extends SimpleChannelInboundHandler<Object> {

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            super.channelInactive(ctx);
            System.out.println("Inactive");
            System.out.println("Is writable: " + ctx.channel().isWritable());
            System.out.println("Is open: " + ctx.channel().isOpen());
        }

        @Override
        public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
            super.channelUnregistered(ctx);
            System.out.println("Unregistered");
            System.out.println("Is writable: " + ctx.channel().isWritable());
            System.out.println("Is open: " + ctx.channel().isOpen());
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof FullHttpResponse) {
                System.out.println("Handler got FullHttpResponse: " + msg);
            } else if (msg instanceof HttpResponse) {
                System.out.println("Handler got HttpResponse: " + msg);
            } else if (msg instanceof HttpHeaders) {
                System.out.println("Handler got HttpHeaders: " + msg);
            } else if (msg instanceof LastHttpContent) {
                System.out.println("Handler got LastHttpContent: " + msg);
            } else if (msg instanceof HttpContent) {
                System.out.println("Handler got HttpContent: " + msg);
            } else {
                System.out.println("Handler got unknown: " + msg);
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