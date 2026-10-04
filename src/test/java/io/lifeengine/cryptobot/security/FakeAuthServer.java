package io.lifeengine.cryptobot.security;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * Un Auth falso que sólo sabe emitir tokens S2S, para los tests que arrancan el contexto
 * completo y necesitan que CryptoBot consiga un token antes de hablar con Runtime.
 *
 * <p>Responde {@code POST /api/auth/internal/service-token} con el contrato real
 * ({@code access_token, token_type, expires_in, kid, audience}). El token emitido lleva la
 * audiencia y un número de secuencia, así un test puede afirmar QUÉ token viajó y cuántas veces
 * se pidió. Cualquier otra ruta es 404: no es un Auth, es un emisor de tokens.
 */
public final class FakeAuthServer {

    public static final String SERVICE_TOKEN_PATH = "/api/auth/internal/service-token";

    private final MockWebServer server = new MockWebServer();
    private final AtomicInteger issued = new AtomicInteger();
    private final List<TokenRequest> tokenRequests = new CopyOnWriteArrayList<>();

    /** Lo que pidió CryptoBot: la request y su cuerpo ya leído (el Buffer se consume una sola vez). */
    public record TokenRequest(RecordedRequest request, String body) {
        public String audience() {
            return audienceOf(body);
        }
    }
    private volatile int expiresIn = 300;
    private volatile int failWithStatus = 0;

    public FakeAuthServer start() throws IOException {
        server.setDispatcher(
                new Dispatcher() {
                    @Override
                    public MockResponse dispatch(RecordedRequest request) {
                        if (!SERVICE_TOKEN_PATH.equals(request.getPath())
                                || !"POST".equals(request.getMethod())) {
                            return new MockResponse().setResponseCode(404);
                        }
                        String body = request.getBody().readUtf8();
                        tokenRequests.add(new TokenRequest(request, body));
                        if (failWithStatus != 0) {
                            return new MockResponse()
                                    .setResponseCode(failWithStatus)
                                    .setHeader("Content-Type", "application/json")
                                    .setBody("{\"error\":\"invalid_client\"}");
                        }
                        String audience = audienceOf(body);
                        String token = "s2s-" + audience + "-" + issued.incrementAndGet();
                        return new MockResponse()
                                .setResponseCode(200)
                                .setHeader("Content-Type", "application/json")
                                .setBody(
                                        "{\"access_token\":\"" + token + "\",\"token_type\":\"Bearer\","
                                                + "\"expires_in\":" + expiresIn + ",\"kid\":\"test-kid\","
                                                + "\"audience\":\"" + audience + "\"}");
                    }
                });
        server.start();
        return this;
    }

    public void shutdown() throws IOException {
        server.shutdown();
    }

    public String baseUrl() {
        return server.url("/").toString().replaceAll("/$", "");
    }

    /** TTL que informará en las próximas respuestas. */
    public void expiresIn(int seconds) {
        this.expiresIn = seconds;
    }

    /** Hace que los próximos pedidos fallen con ese status (0 = volver a emitir). */
    public void failWith(int status) {
        this.failWithStatus = status;
    }

    public int tokenRequestCount() {
        return tokenRequests.size();
    }

    public List<TokenRequest> tokenRequests() {
        return List.copyOf(tokenRequests);
    }

    public void reset() {
        tokenRequests.clear();
        issued.set(0);
        expiresIn = 300;
        failWithStatus = 0;
    }

    /** Extrae {@code audience} del cuerpo sin depender de Jackson: es un JSON plano de 3 campos. */
    public static String audienceOf(String body) {
        int i = body.indexOf("\"audience\"");
        if (i < 0) {
            return "unknown";
        }
        int start = body.indexOf('"', body.indexOf(':', i) + 1) + 1;
        int end = body.indexOf('"', start);
        return body.substring(start, end);
    }
}
