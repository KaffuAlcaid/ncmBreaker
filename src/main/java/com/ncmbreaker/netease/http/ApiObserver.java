package com.ncmbreaker.netease.http;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;

/** Optional in-process observer. The application does not install a persistent recorder. */
public interface ApiObserver {
    void onExchange(Exchange exchange);

    record Exchange(
            String path,
            String protocol,
            JsonObject request,
            Map<String, List<String>> requestHeaders,
            int httpStatus,
            Map<String, List<String>> responseHeaders,
            JsonElement response,
            long elapsedMillis,
            String failureType
    ) {
    }
}
