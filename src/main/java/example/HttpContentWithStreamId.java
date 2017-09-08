package example;

import io.netty.handler.codec.http.HttpContent;

public class HttpContentWithStreamId {
    public final HttpContent httpContent;
    public final int streamId;
    public final boolean noMoreContent;

    public HttpContentWithStreamId(HttpContent httpContent, int streamId, boolean noMoreContent) {
        this.httpContent = httpContent;
        this.streamId = streamId;
        this.noMoreContent = noMoreContent;
    }

    @Override
    public String toString() {
        return String.format("HttpContentWithStreamId{streamId=%d, httpContent=%s, noMoreContent=%s}", streamId, httpContent, noMoreContent);
    }
}
