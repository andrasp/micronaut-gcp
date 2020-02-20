package io.micronaut.gcp.function.http;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.convert.value.MutableConvertibleValues;
import io.micronaut.core.convert.value.MutableConvertibleValuesMap;
import io.micronaut.core.type.Argument;
import io.micronaut.core.util.ArgumentUtils;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpParameters;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.cookie.Cookies;
import io.micronaut.http.simple.cookies.SimpleCookie;
import io.micronaut.http.simple.cookies.SimpleCookies;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.*;

/**
 * Implementation of the {@link HttpRequest} interface for Google Cloud Function.
 *
 * @author graemerocher
 * @since 1.2.0
 */
@Internal
final class GoogleFunctionHttpRequest<B> implements HttpRequest<B> {
    private static final String HEADER_KEY_VALUE_SEPARATOR = "=";
    private final com.google.cloud.functions.HttpRequest googleRequest;
    private final URI uri;
    private final HttpMethod method;
    private final GoogleFunctionHeaders headers;
    private final GoogleFunctionHttpResponse<?> googleResponse;
    private HttpParameters httpParameters;
    private MutableConvertibleValues<Object> attributes;

    GoogleFunctionHttpRequest(
            com.google.cloud.functions.HttpRequest googleRequest,
            GoogleFunctionHttpResponse<?> googleResponse) {
        this.googleRequest = googleRequest;
        this.googleResponse = googleResponse;
        this.uri = URI.create(googleRequest.getUri());
        HttpMethod method;
        try {
            method = HttpMethod.valueOf(googleRequest.getMethod());
        } catch (IllegalArgumentException e) {
            method = HttpMethod.CUSTOM;
        }
        this.method = method;
        this.headers = new GoogleFunctionHeaders();
    }

    /**
     * @return Gets the input stream
     * @throws IOException If an error occurs
     */
    InputStream getInputStream() throws IOException {
        return googleRequest.getInputStream();
    }

    /**
     * Reference to the response object.
     * @return The response.
     */
    GoogleFunctionHttpResponse<?> getGoogleResponse() {
        return googleResponse;
    }

    @Nonnull
    @Override
    public Cookies getCookies() {
        final SimpleCookies cookies = new SimpleCookies(ConversionService.SHARED);
        getHeaders().getAll(HttpHeaders.COOKIE).forEach(cookieValue -> {
            List<HeaderValue> parsedHeaders = parseHeaderValue(cookieValue, ";", ",");


            parsedHeaders.stream()
                    .filter(e -> e.getKey() != null)
                    .map(e -> new SimpleCookie(crlf(e.getKey()), crlf(e.getValue())))
                    .forEach(simpleCookie ->
                            cookies.put(simpleCookie.getName(), simpleCookie));
        });
        return cookies;
    }

    @Nonnull
    @Override
    public HttpParameters getParameters() {
        HttpParameters httpParameters = this.httpParameters;
        if (httpParameters == null) {
            synchronized (this) { // double check
                httpParameters = this.httpParameters;
                if (httpParameters == null) {
                    httpParameters = new GoogleFunctionParameters();
                    this.httpParameters = httpParameters;
                }
            }
        }
        return httpParameters;
    }

    @Nonnull
    @Override
    public HttpMethod getMethod() {
        return method;
    }

    @Nonnull
    @Override
    public URI getUri() {
        return this.uri;
    }

    @Nonnull
    @Override
    public HttpHeaders getHeaders() {
        return headers;
    }

    @Nonnull
    @Override
    public MutableConvertibleValues<Object> getAttributes() {
        MutableConvertibleValues<Object> attributes = this.attributes;
        if (attributes == null) {
            synchronized (this) { // double check
                attributes = this.attributes;
                if (attributes == null) {
                    attributes = new MutableConvertibleValuesMap<>();
                    this.attributes = attributes;
                }
            }
        }
        return attributes;
    }

    @Nonnull
    @Override
    public Optional<B> getBody() {
        return (Optional<B>) getBody(Argument.STRING);
    }

    @Nonnull
    @Override
    public <T> Optional<T> getBody(@Nonnull Argument<T> type) {
        return Optional.empty();
    }

    private final class GoogleFunctionParameters extends GoogleMultiValueMap implements HttpParameters {
        GoogleFunctionParameters() {
            super(googleRequest.getQueryParameters());
        }

        @Override
        public Optional<String> getFirst(CharSequence name) {
            ArgumentUtils.requireNonNull("name", name);
            return googleRequest.getFirstQueryParameter(name.toString());
        }

        @Nullable
        @Override
        public String get(CharSequence name) {
            return getFirst(name).orElse(null);
        }
    }

    private final class GoogleFunctionHeaders extends GoogleMultiValueMap implements HttpHeaders {
        GoogleFunctionHeaders() {
            super(googleRequest.getHeaders());
        }
    }

    /*
     * TODO: Copied from Micronaut AWS. Find a way to share this code
     */
    private static String crlf(String s) {
        if (s == null) {
            return null;
        }
        return s.replaceAll("[\r\n]", "");
    }

    private static List<HeaderValue> parseHeaderValue(
            String headerValue, String valueSeparator, String qualifierSeparator) {
        // Accept: text/html, application/xhtml+xml, application/xml;q=0.9, */*;q=0.8
        // Accept-Language: fr-CH, fr;q=0.9, en;q=0.8, de;q=0.7, *;q=0.5
        // Cookie: name=value; name2=value2; name3=value3
        // X-Custom-Header: YQ==

        List<HeaderValue> values = new ArrayList<>();
        if (headerValue == null) {
            return values;
        }

        for (String v : headerValue.split(valueSeparator)) {
            String curValue = v;
            float curPreference = 1.0f;
            HeaderValue newValue = new HeaderValue();
            newValue.setRawValue(v);

            for (String q : curValue.split(qualifierSeparator)) {

                String[] kv = q.split(HEADER_KEY_VALUE_SEPARATOR, 2);
                String key = null;
                String val = null;
                // no separator, set the value only
                if (kv.length == 1) {
                    val = q.trim();
                }
                // we have a separator
                if (kv.length == 2) {
                    // if the length of the value is 0 we assume that we are looking at a
                    // base64 encoded value with padding so we just set the value. This is because
                    // we assume that empty values in a key/value pair will contain at least a white space
                    if (kv[1].length() == 0) {
                        val = q.trim();
                    }
                    // this was a base64 string with an additional = for padding, set the value only
                    if ("=".equals(kv[1].trim())) {
                        val = q.trim();
                    } else { // it's a proper key/value set both
                        key = kv[0].trim();
                        val = ("".equals(kv[1].trim()) ? null : kv[1].trim());
                    }
                }

                if (newValue.getValue() == null) {
                    newValue.setKey(key);
                    newValue.setValue(val);
                } else {
                    // special case for quality q=
                    if ("q".equals(key)) {
                        curPreference = Float.parseFloat(val);
                    } else {
                        newValue.addAttribute(key, val);
                    }
                }
            }
            newValue.setPriority(curPreference);
            values.add(newValue);
        }

        // sort list by preference
        values.sort((HeaderValue first, HeaderValue second) -> {
            if ((first.getPriority() - second.getPriority()) < .001f) {
                return 0;
            }
            if (first.getPriority() < second.getPriority()) {
                return 1;
            }
            return -1;
        });
        return values;
    }

    /**
     * Class that represents a header value.
     */
    private static class HeaderValue {
        private String key;
        private String value;
        private String rawValue;
        private float priority;
        private Map<String, String> attributes;

        public HeaderValue() {
            attributes = new HashMap<>();
        }

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }

        public String getRawValue() {
            return rawValue;
        }

        public void setRawValue(String rawValue) {
            this.rawValue = rawValue;
        }

        public float getPriority() {
            return priority;
        }

        public void setPriority(float priority) {
            this.priority = priority;
        }

        public Map<String, String> getAttributes() {
            return attributes;
        }

        public void setAttributes(Map<String, String> attributes) {
            this.attributes = attributes;
        }

        public void addAttribute(String key, String value) {
            attributes.put(key, value);
        }

        public String getAttribute(String key) {
            return attributes.get(key);
        }
    }
}