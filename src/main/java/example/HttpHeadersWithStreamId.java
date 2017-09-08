package example;

import io.netty.handler.codec.http.HttpHeaders;

public class HttpHeadersWithStreamId {
    public final HttpHeaders httpHeaders;
    public final int streamId;
    public final boolean noMoreContent;

    public HttpHeadersWithStreamId(HttpHeaders httpHeaders, int streamId, boolean noMoreContent) {
        this.httpHeaders = httpHeaders;
        this.streamId = streamId;
        this.noMoreContent = noMoreContent;
    }

    @Override
    public String toString() {
        return String.format("HttpHeadersWithStreamId{streamId=%d, httpHeaders=%s, noMoreContent=%s}", streamId, httpHeaders, noMoreContent);
    }
}
