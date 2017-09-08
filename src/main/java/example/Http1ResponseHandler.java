package example;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.concurrent.Promise;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class Http1ResponseHandler extends SimpleChannelInboundHandler<HttpObject> {

    private final Channel channel;
    private final Consumer<HttpResponse> onResponse;
    private final BiConsumer<HttpHeaders, Boolean> onHeaders;
    private final BiConsumer<HttpContent, Boolean> onContent;
    private final Promise<FullHttpResponse> promise;

    Http1ResponseHandler(Channel channel, Consumer<HttpResponse> onResponse, BiConsumer<HttpHeaders, Boolean> onHeaders, BiConsumer<HttpContent, Boolean> onContent, Promise<FullHttpResponse> promise) {
        this.channel = channel;
        this.onResponse = onResponse;
        this.onHeaders = onHeaders;
        this.onContent = onContent;
        this.promise = promise;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, HttpObject msg) throws Exception {
        if (msg instanceof FullHttpResponse) {
            final FullHttpResponse fullHttpResponse = (FullHttpResponse) msg;
            channel.pipeline().remove(this);
            promise.setSuccess(fullHttpResponse); // retain?
        } else if (msg instanceof HttpResponse) {
            final HttpResponse httpResponse = (HttpResponse) msg;
            onResponse.accept(httpResponse);
        } else if (msg instanceof HttpHeaders) {
            final HttpHeaders httpHeaders = (HttpHeaders) msg;
            onHeaders.accept(httpHeaders, true);
        } else if (msg instanceof LastHttpContent) {
            final LastHttpContent lastHttpContent = (LastHttpContent) msg;
            onContent.accept(lastHttpContent, true);
        } else if (msg instanceof HttpContent) {
            final HttpContent httpContent = (HttpContent) msg;
            onContent.accept(httpContent, false);
        }
    }
}
