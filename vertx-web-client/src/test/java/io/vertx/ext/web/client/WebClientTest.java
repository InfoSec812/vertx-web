package io.vertx.ext.web.client;

import java.io.File;
import java.net.ConnectException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import io.vertx.core.http.HttpConnection;
import io.vertx.core.net.JksOptions;
import org.junit.Test;

import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.VertxException;
import io.vertx.core.VertxOptions;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.dns.AddressResolverOptions;
import io.vertx.core.file.AsyncFile;
import io.vertx.core.file.OpenOptions;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.ProxyOptions;
import io.vertx.core.net.ProxyType;
import io.vertx.core.streams.ReadStream;
import io.vertx.core.streams.WriteStream;
import io.vertx.ext.web.client.jackson.WineAndCheese;
import io.vertx.ext.web.codec.BodyCodec;
import io.vertx.test.core.HttpTestBase;
import io.vertx.test.core.TestUtils;
import io.vertx.test.core.tls.Cert;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
public class WebClientTest extends HttpTestBase {

  private WebClient client;

  @Override
  protected VertxOptions getOptions() {
    return super.getOptions().setAddressResolverOptions(new AddressResolverOptions().
      setHostsValue(Buffer.buffer(
        "127.0.0.1 somehost\n" +
        "127.0.0.1 localhost")));
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    super.client = vertx.createHttpClient(new HttpClientOptions().setDefaultPort(8080).setDefaultHost("localhost"));
    client = WebClient.wrap(super.client);
    server.close();
    server = vertx.createHttpServer(new HttpServerOptions().setPort(DEFAULT_HTTP_PORT).setHost(DEFAULT_HTTP_HOST));
  }

  @Test
  public void testDefaultHostAndPort() throws Exception {
    testRequest(client -> client.get("somepath"), req -> assertEquals("localhost:8080", req.host()));
  }

  @Test
  public void testDefaultPort() throws Exception {
    testRequest(client -> client.get("somehost", "somepath"), req -> assertEquals("somehost:8080", req.host()));
  }

  @Test
  public void testDefaultUserAgent() throws Exception {
    testRequest(client -> client.get("somehost", "somepath"), req -> {
      String ua = req.headers().get(HttpHeaders.USER_AGENT);
      assertTrue("Was expecting use agent header " + ua + " to start with Vert.x-WebClient/", ua.startsWith("Vert.x-WebClient/"));
    });
  }

  @Test
  public void testCustomUserAgent() throws Exception {
    client = WebClient.wrap(super.client, new WebClientOptions().setUserAgent("smith"));
    testRequest(client -> client.get("somehost", "somepath"), req -> assertEquals(Collections.singletonList("smith"), req.headers().getAll(HttpHeaders.USER_AGENT)));
  }

  @Test
  public void testUserAgentDisabled() throws Exception {
    client = WebClient.wrap(super.client, new WebClientOptions().setUserAgentEnabled(false));
    testRequest(client -> client.get("somehost", "somepath"), req -> assertEquals(Collections.emptyList(), req.headers().getAll(HttpHeaders.USER_AGENT)));
  }

  @Test
  public void testUserAgentHeaderOverride() throws Exception {
    testRequest(client -> client.get("somehost", "somepath").putHeader(HttpHeaders.USER_AGENT.toString(), "smith"), req -> assertEquals(Collections.singletonList("smith"), req.headers().getAll(HttpHeaders.USER_AGENT)));
  }

  @Test
  public void testUserAgentHeaderRemoved() throws Exception {
    testRequest(client -> {
      HttpRequest<Buffer> request = client.get("somehost", "somepath");
      request.headers().remove(HttpHeaders.USER_AGENT);
      return request;
    }, req -> assertEquals(Collections.emptyList(), req.headers().getAll(HttpHeaders.USER_AGENT)));
  }

  @Test
  public void testGet() throws Exception {
    testRequest(HttpMethod.GET);
  }

  @Test
  public void testHead() throws Exception {
    testRequest(HttpMethod.HEAD);
  }

  @Test
  public void testDelete() throws Exception {
    testRequest(HttpMethod.DELETE);
  }

  private void testRequest(HttpMethod method) throws Exception {
    testRequest(client -> {
      switch (method) {
        case GET:
          return client.get(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
        case HEAD:
          return client.head(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
        case DELETE:
          return client.delete(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
        default:
          fail("Invalid HTTP method");
          return null;
      }
    }, req -> assertEquals(method, req.method()));
  }

  private void testRequest(Function<WebClient, HttpRequest<Buffer>> reqFactory, Consumer<HttpServerRequest> reqChecker) throws Exception {
    waitFor(4);
    server.requestHandler(req -> {
      try {
        reqChecker.accept(req);
        complete();
      } finally {
        req.response().end();
      }
    });
    startServer();
    HttpRequest<Buffer> builder = reqFactory.apply(client);
    builder.send(onSuccess(resp -> complete()));
    builder.send(onSuccess(resp -> complete()));
    await();
  }

  @Test
  public void testPost() throws Exception {
    testRequestWithBody(HttpMethod.POST, false);
  }

  @Test
  public void testPostChunked() throws Exception {
    testRequestWithBody(HttpMethod.POST, true);
  }

  @Test
  public void testPut() throws Exception {
    testRequestWithBody(HttpMethod.PUT, false);
  }

  @Test
  public void testPutChunked() throws Exception {
    testRequestWithBody(HttpMethod.PUT, true);
  }

  @Test
  public void testPatch() throws Exception {
    testRequestWithBody(HttpMethod.PATCH, false);
  }

  private void testRequestWithBody(HttpMethod method, boolean chunked) throws Exception {
    String expected = TestUtils.randomAlphaString(1024 * 1024);
    File f = File.createTempFile("vertx", ".data");
    f.deleteOnExit();
    Files.write(f.toPath(), expected.getBytes());
    waitFor(2);
    server.requestHandler(req -> req.bodyHandler(buff -> {
      assertEquals(method, req.method());
      assertEquals(Buffer.buffer(expected), buff);
      complete();
      req.response().end();
    }));
    startServer();
    vertx.runOnContext(v -> {
      AsyncFile asyncFile = vertx.fileSystem().openBlocking(f.getAbsolutePath(), new OpenOptions());

      HttpRequest<Buffer> builder = null;

      switch (method) {
        case POST:
          builder = client.post(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
          break;
        case PUT:
          builder = client.put(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
          break;
        case PATCH:
          builder = client.patch(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
          break;
        default:
          fail("Invalid HTTP method");
      }

      if (!chunked) {
        builder = builder.putHeader("Content-Length", "" + expected.length());
      }
      builder.sendStream(asyncFile, onSuccess(resp -> {
            assertEquals(200, resp.statusCode());
            complete();
          }));
    });
    await();
  }

  @Test
  public void testSendJsonObjectBody() throws Exception {
    JsonObject body = new JsonObject().put("wine", "Chateauneuf Du Pape").put("cheese", "roquefort");
    testSendBody(body, (contentType, buff) -> {
      assertEquals("application/json", contentType);
      assertEquals(body, buff.toJsonObject());
    });
  }

  @Test
  public void testSendJsonPojoBody() throws Exception {
    testSendBody(new WineAndCheese().setCheese("roquefort").setWine("Chateauneuf Du Pape"),
        (contentType, buff) -> {
          assertEquals("application/json", contentType);
          assertEquals(new JsonObject().put("wine", "Chateauneuf Du Pape").put("cheese", "roquefort"), buff.toJsonObject());
        });
  }

  @Test
  public void testSendJsonArrayBody() throws Exception {
    JsonArray body = new JsonArray().add(0).add(1).add(2);
    testSendBody(body, (contentType, buff) -> {
      assertEquals("application/json", contentType);
      assertEquals(body, buff.toJsonArray());
    });
  }

  @Test
  public void testSendBufferBody() throws Exception {
    Buffer body = TestUtils.randomBuffer(2048);
    testSendBody(body, (contentType, buff) -> assertEquals(body, buff));
  }

  private void testSendBody(Object body, BiConsumer<String, Buffer> checker) throws Exception {
    waitFor(2);
    server.requestHandler(req -> req.bodyHandler(buff -> {
      checker.accept(req.getHeader("content-type"), buff);
      complete();
      req.response().end();
    }));
    startServer();
    HttpRequest<Buffer> post = client.post(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
    if (body instanceof Buffer) {
      post.sendBuffer((Buffer) body, onSuccess(resp -> complete()));
    } else if (body instanceof JsonObject) {
      post.sendJsonObject((JsonObject) body, onSuccess(resp -> complete()));
    } else {
      post.sendJson(body, onSuccess(resp -> complete()));
    }
    await();
  }

  @Test
  public void testConnectError() throws Exception {
    HttpRequest<Buffer> get = client.get(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
    get.send(onFailure(err -> {
      assertTrue(err instanceof ConnectException);
      complete();
    }));
    await();
  }

  @Test
  public void testRequestSendError() throws Exception {
    HttpRequest<Buffer> post = client.post(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<HttpConnection> conn = new AtomicReference<>();
    server.requestHandler(req -> {
      conn.set(req.connection());
      req.pause();
      latch.countDown();
    });
    startServer();
    AtomicReference<Handler<Buffer>> dataHandler = new AtomicReference<>();
    AtomicReference<Handler<Void>> endHandler = new AtomicReference<>();
    AtomicBoolean paused = new AtomicBoolean();
    post.sendStream(new ReadStream<Buffer>() {
          @Override
          public ReadStream<Buffer> exceptionHandler(Handler<Throwable> handler) {
            return this;
          }
          @Override
          public ReadStream<Buffer> handler(Handler<Buffer> handler) {
            dataHandler.set(handler);
            return this;
          }
          @Override
          public ReadStream<Buffer> pause() {
            paused.set(true);
            return this;
          }
          @Override
          public ReadStream<Buffer> resume() {
            paused.set(false);
            return this;
          }
          @Override
          public ReadStream<Buffer> endHandler(Handler<Void> handler) {
            endHandler.set(handler);
            return this;
          }
        }, onFailure(err -> {
          // Should be a connection reset by peer or closed
          assertNull(endHandler.get());
          assertNull(dataHandler.get());
          assertFalse(paused.get());
          complete();
        }));
    assertWaitUntil(() -> dataHandler.get() != null);
    dataHandler.get().handle(TestUtils.randomBuffer(1024));
    awaitLatch(latch);
    while (!paused.get()) {
      dataHandler.get().handle(TestUtils.randomBuffer(1024));
    }
    conn.get().close();
    await();
  }

  @Test
  public void testRequestPumpError() throws Exception {
    waitFor(2);
    HttpRequest<Buffer> post = client.post(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
    CompletableFuture<Void> done = new CompletableFuture<>();
    server.requestHandler(req -> {
      req.response().closeHandler(v -> complete());
      req.handler(buff -> done.complete(null));
    });
    Throwable cause = new Throwable();
    startServer();
    post.sendStream(new ReadStream<Buffer>() {
          @Override
          public ReadStream<Buffer> exceptionHandler(Handler<Throwable> handler) {
            if (handler != null) {
              done.thenAccept(v -> handler.handle(cause));
            }
            return this;
          }
          @Override
          public ReadStream<Buffer> handler(Handler<Buffer> handler) {
            if (handler != null) {
              handler.handle(TestUtils.randomBuffer(1024));
            }
            return this;
          }
          @Override
          public ReadStream<Buffer> pause() {
            return this;
          }
          @Override
          public ReadStream<Buffer> resume() {
            return this;
          }
          @Override
          public ReadStream<Buffer> endHandler(Handler<Void> endHandler) {
            return this;
          }
        }, onFailure(err -> {
          assertSame(cause, err);
          complete();
        }));
    await();
  }

  @Test
  public void testRequestPumpErrorNotYetConnected() throws Exception {
    HttpRequest<Buffer> post = client.post(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
    server.requestHandler(req -> fail());
    Throwable cause = new Throwable();
    startServer();
    post.sendStream(new ReadStream<Buffer>() {
      Handler<Throwable> exceptionHandler;
      @Override
      public ReadStream<Buffer> exceptionHandler(Handler<Throwable> handler) {
        exceptionHandler = handler;
        return this;
      }
      @Override
      public ReadStream<Buffer> handler(Handler<Buffer> handler) {
        if (handler != null) {
          handler.handle(TestUtils.randomBuffer(1024));
          vertx.runOnContext(v -> exceptionHandler.handle(cause));
        }
        return this;
      }
      @Override
      public ReadStream<Buffer> pause() {
        return this;
      }
      @Override
      public ReadStream<Buffer> resume() {
        return this;
      }
      @Override
      public ReadStream<Buffer> endHandler(Handler<Void> endHandler) {
        return this;
      }
    }, onFailure(err -> {
      assertSame(cause, err);
      testComplete();
    }));
    await();
  }

  @Test
  public void testResponseBodyAsBuffer() throws Exception {
    Buffer expected = TestUtils.randomBuffer(2000);
    server.requestHandler(req -> req.response().end(expected));
    startServer();
    HttpRequest<Buffer> get = client.get(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
    get.send(onSuccess(resp -> {
      assertEquals(200, resp.statusCode());
      assertEquals(expected, resp.body());
      testComplete();
    }));
    await();
  }

  @Test
  public void testResponseBodyAsAsJsonObject() throws Exception {
    JsonObject expected = new JsonObject().put("cheese", "Goat Cheese").put("wine", "Condrieu");
    server.requestHandler(req -> req.response().end(expected.encode()));
    startServer();
    HttpRequest<Buffer> get = client.get(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
    get
      .as(BodyCodec.jsonObject())
      .send(onSuccess(resp -> {
      assertEquals(200, resp.statusCode());
      assertEquals(expected, resp.body());
      testComplete();
    }));
    await();
  }

  @Test
  public void testResponseBodyAsAsJsonMapped() throws Exception {
    JsonObject expected = new JsonObject().put("cheese", "Goat Cheese").put("wine", "Condrieu");
    server.requestHandler(req -> req.response().end(expected.encode()));
    startServer();
    HttpRequest<Buffer> get = client.get(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
    get
      .as(BodyCodec.json(WineAndCheese.class))
      .send(onSuccess(resp -> {
      assertEquals(200, resp.statusCode());
      assertEquals(new WineAndCheese().setCheese("Goat Cheese").setWine("Condrieu"), resp.body());
      testComplete();
    }));
    await();
  }

  @Test
  public void testResponseBodyAsAsJsonArray() throws Exception {
    JsonArray expected = new JsonArray().add("cheese").add("wine");
    server.requestHandler(req -> req.response().end(expected.encode()));
    startServer();
    HttpRequest<Buffer> get = client.get(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
    get
      .as(BodyCodec.jsonArray())
      .send(onSuccess(resp -> {
        assertEquals(200, resp.statusCode());
        assertEquals(expected, resp.body());
        testComplete();
      }));
    await();
  }

  @Test
  public void testResponseBodyAsAsJsonArrayMapped() throws Exception {
    JsonArray expected = new JsonArray().add("cheese").add("wine");
    server.requestHandler(req -> req.response().end(expected.encode()));
    startServer();
    HttpRequest<Buffer> get = client.get(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
    get
      .as(BodyCodec.json(List.class))
      .send(onSuccess(resp -> {
        assertEquals(200, resp.statusCode());
        assertEquals(expected.getList(), resp.body());
        testComplete();
      }));
    await();
  }

  @Test
  public void testResponseBodyDiscarded() throws Exception {
    server.requestHandler(req -> req.response().end(TestUtils.randomAlphaString(1024)));
    startServer();
    HttpRequest<Buffer> get = client.get(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
    get
      .as(BodyCodec.none())
      .send(onSuccess(resp -> {
      assertEquals(200, resp.statusCode());
      assertEquals(null, resp.body());
      testComplete();
    }));
    await();
  }

  @Test
  public void testResponseUnknownContentTypeBodyAsJsonObject() throws Exception {
    JsonObject expected = new JsonObject().put("cheese", "Goat Cheese").put("wine", "Condrieu");
    server.requestHandler(req -> req.response().end(expected.encode()));
    startServer();
    HttpRequest<Buffer> get = client.get(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
    get.send(onSuccess(resp -> {
      assertEquals(200, resp.statusCode());
      assertEquals(expected, resp.bodyAsJsonObject());
      testComplete();
    }));
    await();
  }

  @Test
  public void testResponseUnknownContentTypeBodyAsJsonArray() throws Exception {
    JsonArray expected = new JsonArray().add("cheese").add("wine");
    server.requestHandler(req -> req.response().end(expected.encode()));
    startServer();
    HttpRequest<Buffer> get = client.get(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
    get.send(onSuccess(resp -> {
      assertEquals(200, resp.statusCode());
      assertEquals(expected, resp.bodyAsJsonArray());
      testComplete();
    }));
    await();
  }

  @Test
  public void testResponseUnknownContentTypeBodyAsJsonMapped() throws Exception {
    JsonObject expected = new JsonObject().put("cheese", "Goat Cheese").put("wine", "Condrieu");
    server.requestHandler(req -> req.response().end(expected.encode()));
    startServer();
    HttpRequest<Buffer> get = client.get(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
    get.send(onSuccess(resp -> {
      assertEquals(200, resp.statusCode());
      assertEquals(new WineAndCheese().setCheese("Goat Cheese").setWine("Condrieu"), resp.bodyAsJson(WineAndCheese.class));
      testComplete();
    }));
    await();
  }

  @Test
  public void testResponseBodyUnmarshallingError() throws Exception {
    server.requestHandler(req -> req.response().end("not-json-object"));
    startServer();
    HttpRequest<Buffer> get = client.get(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
    get
      .as(BodyCodec.jsonObject())
      .send(onFailure(err -> {
      assertTrue(err instanceof DecodeException);
      testComplete();
    }));
    await();
  }

  @Test
  public void testResponseBodyStream() throws Exception {
    AtomicBoolean paused = new AtomicBoolean();
    server.requestHandler(req -> {
      HttpServerResponse resp = req.response();
      resp.setChunked(true);
      vertx.setPeriodic(1, id -> {
        if (!resp.writeQueueFull()) {
          resp.write(TestUtils.randomAlphaString(1024));
        } else {
          resp.drainHandler(v -> resp.end());
          paused.set(true);
          vertx.cancelTimer(id);
        }
      });
    });
    startServer();
    CompletableFuture<Void> resume = new CompletableFuture<>();
    AtomicInteger size = new AtomicInteger();
    AtomicBoolean ended = new AtomicBoolean();
    WriteStream<Buffer> stream = new WriteStream<Buffer>() {
      boolean paused = true;
      Handler<Void> drainHandler;
      {
        resume.thenAccept(v -> {
          paused = false;
          if (drainHandler != null) {
            drainHandler.handle(null);
          }
        });
      }
      @Override
      public WriteStream<Buffer> exceptionHandler(Handler<Throwable> handler) {
        return this;
      }
      @Override
      public WriteStream<Buffer> write(Buffer data) {
        size.addAndGet(data.length());
        return this;
      }
      @Override
      public void end() {
        ended.set(true);
      }
      @Override
      public WriteStream<Buffer> setWriteQueueMaxSize(int maxSize) {
        return this;
      }
      @Override
      public boolean writeQueueFull() {
        return paused;
      }
      @Override
      public WriteStream<Buffer> drainHandler(Handler<Void> handler) {
        drainHandler = handler;
        return this;
      }
    };
    HttpRequest<Buffer> get = client.get(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
    get
      .as(BodyCodec.pipe(stream))
      .send(onSuccess(resp -> {
      assertTrue(ended.get());
      assertEquals(200, resp.statusCode());
      assertEquals(null, resp.body());
      testComplete();
    }));
    assertWaitUntil(paused::get);
    resume.complete(null);
    await();
  }

  @Test
  public void testResponseBodyStreamError() throws Exception {
    CompletableFuture<Void> fail = new CompletableFuture<>();
    server.requestHandler(req -> {
      HttpServerResponse resp = req.response();
      resp.setChunked(true);
      resp.write(TestUtils.randomBuffer(2048));
      fail.thenAccept(v -> resp.close());
    });
    startServer();
    AtomicInteger received = new AtomicInteger();
    WriteStream<Buffer> stream = new WriteStream<Buffer>() {
      @Override
      public WriteStream<Buffer> exceptionHandler(Handler<Throwable> handler) {
        return this;
      }
      @Override
      public WriteStream<Buffer> write(Buffer data) {
        received.addAndGet(data.length());
        return this;
      }
      @Override
      public void end() {
      }
      @Override
      public WriteStream<Buffer> setWriteQueueMaxSize(int maxSize) {
        return this;
      }
      @Override
      public boolean writeQueueFull() {
        return false;
      }
      @Override
      public WriteStream<Buffer> drainHandler(Handler<Void> handler) {
        return this;
      }
    };
    HttpRequest<Buffer> get = client.get(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
    get
      .as(BodyCodec.pipe(stream))
      .send(onFailure(err -> testComplete()));
    assertWaitUntil(() -> received.get() == 2048);
    fail.complete(null);
    await();
  }

  @Test
  public void testResponseBodyCodecError() throws Exception {
    server.requestHandler(req -> {
      HttpServerResponse resp = req.response();
      resp.setChunked(true);
      resp.end(TestUtils.randomBuffer(2048));
    });
    startServer();
    RuntimeException cause = new RuntimeException();
    WriteStream<Buffer> stream = new WriteStream<Buffer>() {
      Handler<Throwable> exceptionHandler;
      @Override
      public WriteStream<Buffer> exceptionHandler(Handler<Throwable> handler) {
        exceptionHandler = handler;
        return this;
      }
      @Override
      public WriteStream<Buffer> write(Buffer data) {
        exceptionHandler.handle(cause);
        return this;
      }
      @Override
      public void end() {
      }
      @Override
      public WriteStream<Buffer> setWriteQueueMaxSize(int maxSize) {
        return this;
      }
      @Override
      public boolean writeQueueFull() {
        return false;
      }
      @Override
      public WriteStream<Buffer> drainHandler(Handler<Void> handler) {
        return this;
      }
    };
    HttpRequest<Buffer> get = client.get(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
    get
      .as(BodyCodec.pipe(stream))
      .send(onFailure(err -> {
      assertSame(cause, err);
      testComplete();
    }));
    await();
  }

  @Test
  public void testResponseJsonObjectMissingBody() throws Exception {
    testResponseMissingBody(BodyCodec.jsonObject());
  }

  @Test
  public void testResponseJsonMissingBody() throws Exception {
    testResponseMissingBody(BodyCodec.json(WineAndCheese.class));
  }

  @Test
  public void testResponseWriteStreamMissingBody() throws Exception {
    AtomicInteger length = new AtomicInteger();
    AtomicBoolean ended = new AtomicBoolean();
    WriteStream<Buffer> stream = new WriteStream<Buffer>() {
      @Override
      public WriteStream<Buffer> exceptionHandler(Handler<Throwable> handler) {
        return this;
      }
      @Override
      public WriteStream<Buffer> write(Buffer data) {
        length.addAndGet(data.length());
        return this;
      }
      @Override
      public void end() {
        ended.set(true);
      }
      @Override
      public WriteStream<Buffer> setWriteQueueMaxSize(int maxSize) {
        return this;
      }
      @Override
      public boolean writeQueueFull() {
        return false;
      }
      @Override
      public WriteStream<Buffer> drainHandler(Handler<Void> handler) {
        return this;
      }
    };
    testResponseMissingBody(BodyCodec.pipe(stream));
    assertTrue(ended.get());
    assertEquals(0, length.get());
  }

  private <R> void testResponseMissingBody(BodyCodec<R> codec) throws Exception {
    server.requestHandler(req -> req.response().setStatusCode(403).end());
    startServer();
    HttpRequest<Buffer> get = client.get(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
    get
      .as(codec)
      .send(onSuccess(resp -> {
      assertEquals(403, resp.statusCode());
      assertNull(resp.body());
      testComplete();
    }));
    await();
  }

  @Test
  public void testHttpResponseError() throws Exception {
    server.requestHandler(req -> req.response().setChunked(true).write(Buffer.buffer("some-data")).close());
    startServer();
    HttpRequest<Buffer> get = client.get(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
    get
      .as(BodyCodec.jsonObject())
      .send(onFailure(err -> {
      assertTrue(err instanceof VertxException);
      testComplete();
    }));
    await();
  }

  @Test
  public void testTimeout() throws Exception {
    AtomicInteger count = new AtomicInteger();
    server.requestHandler(req -> count.incrementAndGet());
    startServer();
    HttpRequest<Buffer> get = client.get(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/somepath");
    get.timeout(50).send(onFailure(err -> {
      assertEquals(err.getClass(), TimeoutException.class);
      testComplete();
    }));
    await();
  }

  @Test
  public void testQueryParam() throws Exception {
    testRequest(client -> client.get(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/").addQueryParam("param", "param_value"), req -> {
      assertEquals("param=param_value", req.query());
      assertEquals("param_value", req.getParam("param"));
    });
  }

  @Test
  public void testQueryParamMulti() throws Exception {
    testRequest(client -> client.get(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/").addQueryParam("param", "param_value1").addQueryParam("param", "param_value2"), req -> {
      assertEquals("param=param_value1&param=param_value2", req.query());
      assertEquals(Arrays.asList("param_value1", "param_value2"), req.params().getAll("param"));
    });
  }

  @Test
  public void testQueryParamAppend() throws Exception {
    testRequest(client -> client.get(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/?param1=param1_value1").addQueryParam("param1", "param1_value2").addQueryParam("param2", "param2_value"), req -> {
      assertEquals("param1=param1_value1&param1=param1_value2&param2=param2_value", req.query());
      assertEquals("param1_value2", req.getParam("param1"));
      assertEquals("param2_value", req.getParam("param2"));
    });
  }

  @Test
  public void testOverwriteQueryParams() throws Exception {
    testRequest(client -> client.get(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/?param=param_value1").setQueryParam("param", "param_value2"), req -> {
      assertEquals("param=param_value2", req.query());
      assertEquals("param_value2", req.getParam("param"));
    });
  }

  @Test
  public void testQueryParamEncoding() throws Exception {
    testRequest(client -> client
      .get(DEFAULT_HTTP_PORT, DEFAULT_HTTP_HOST, "/")
      .addQueryParam("param1", " ")
      .addQueryParam("param2", "\u20AC"), req -> {
      assertEquals("param1=%20&param2=%E2%82%AC", req.query());
      assertEquals(" ", req.getParam("param1"));
      assertEquals("\u20AC", req.getParam("param2"));
    });
  }

  @Test
  public void testFormUrlEncoded() throws Exception {
    server.requestHandler(req -> {
      req.setExpectMultipart(true);
      req.endHandler(v -> {
        assertEquals("param1_value", req.getFormAttribute("param1"));
        req.response().end();
      });
    });
    startServer();
    MultiMap form = MultiMap.caseInsensitiveMultiMap();
    form.add("param1", "param1_value");
    HttpRequest<Buffer> builder = client.post("/somepath");
    builder.sendForm(form, onSuccess(resp -> complete()));
    await();
  }

  @Test
  public void testFormMultipart() throws Exception {
    server.requestHandler(req -> {
      req.setExpectMultipart(true);
      req.endHandler(v -> {
        assertEquals("param1_value", req.getFormAttribute("param1"));
        req.response().end();
      });
    });
    startServer();
    MultiMap form = MultiMap.caseInsensitiveMultiMap();
    form.add("param1", "param1_value");
    HttpRequest<Buffer> builder = client.post("/somepath");
    builder.putHeader("content-type", "multipart/form-data");
    builder.sendForm(form, onSuccess(resp -> complete()));
    await();
  }

  @Test
  public void testDefaultFollowRedirects() throws Exception {
    testFollowRedirects(null, true);
  }

  @Test
  public void testFollowRedirects() throws Exception {
    testFollowRedirects(true, true);
  }

  @Test
  public void testDoNotFollowRedirects() throws Exception {
    testFollowRedirects(false, false);
  }

  private void testFollowRedirects(Boolean set, boolean expect) throws Exception {
    waitFor(2);
    String location = "http://" + DEFAULT_HTTP_HOST + ":" + DEFAULT_HTTP_PORT + "/ok";
    server.requestHandler(req -> {
      if (req.path().equals("/redirect")) {
        req.response().setStatusCode(301).putHeader("Location", location).end();
        if (!expect) {
          complete();
        }
      } else {
        req.response().end(req.path());
        if (expect) {
          complete();
        }
      }
    });
    startServer();
    HttpRequest<Buffer> builder = client.get("/redirect");
    if (set != null) {
      builder = builder.followRedirects(set);
    }
    builder.send(onSuccess(resp -> {
      if (expect) {
        assertEquals(200, resp.statusCode());
        assertEquals("/ok", resp.body().toString());
      } else {
        assertEquals(301, resp.statusCode());
        assertEquals(location, resp.getHeader("location"));
      }
      complete();
    }));
    await();
  }

  @Test
  public void testVirtualHost() throws Exception {
    server.requestHandler(req -> {
      assertEquals("another-host:8080", req.host());
      req.response().end();
    });
    startServer();
    HttpRequest<Buffer> req = client.get("/test").virtualHost("another-host");
    req.send(onSuccess(resp -> {
      testComplete();
    }));
    await();
  }

  @Test
  public void testTLSEnabled() throws Exception {
    testTLS(true, true, client -> client.get("/"));
  }

  @Test
  public void testTLSEnabledDisableRequestTLS() throws Exception {
    testTLS(true, false, client -> client.get("/").ssl(false));
  }

  @Test
  public void testTLSEnabledEnableRequestTLS() throws Exception {
    testTLS(true, true, client -> client.get("/").ssl(true));
  }

  @Test
  public void testTLSDisabledDisableRequestTLS() throws Exception {
    testTLS(false, false, client -> client.get("/").ssl(false));
  }

  @Test
  public void testTLSDisabledEnableRequestTLS() throws Exception {
    testTLS(false, true, client -> client.get("/").ssl(true));
  }

  @Test
  public void testTLSEnabledDisableRequestTLSAbsURI() throws Exception {
    testTLS(true, false, client -> client.getAbs("http://" + DEFAULT_HTTPS_HOST + ":" + DEFAULT_HTTPS_PORT));
  }

  @Test
  public void testTLSEnabledEnableRequestTLSAbsURI() throws Exception {
    testTLS(true, true, client -> client.getAbs("https://" + DEFAULT_HTTPS_HOST + ":" + DEFAULT_HTTPS_PORT));
  }

  @Test
  public void testTLSDisabledDisableRequestTLSAbsURI() throws Exception {
    testTLS(false, false, client -> client.getAbs("http://" + DEFAULT_HTTPS_HOST + ":" + DEFAULT_HTTPS_PORT));
  }

  @Test
  public void testTLSDisabledEnableRequestTLSAbsURI() throws Exception {
    testTLS(false, true, client -> client.getAbs("https://" + DEFAULT_HTTPS_HOST + ":" + DEFAULT_HTTPS_PORT));
  }

  /**
   * Regression test for issue #563 (https://github.com/vert-x3/vertx-web/issues/563)
   * <p>
   * Only occurred when {@link WebClientOptions#isSsl()} was false for an SSL request.
   */
  @Test
  public void testTLSQueryParametersIssue563() throws Exception {
    testTLS(false, true,
      client -> client.getAbs("https://" + DEFAULT_HTTPS_HOST + ":" + DEFAULT_HTTPS_PORT)
        .addQueryParam("query1", "value1")
        .addQueryParam("query2", "value2"),
      serverRequest -> assertEquals("query1=value1&query2=value2", serverRequest.query()));
  }

  private void testTLS(boolean clientSSL, boolean serverSSL, Function<WebClient, HttpRequest<Buffer>> requestProvider) throws Exception {
    testTLS(clientSSL, serverSSL, requestProvider, null);
  }

  private void testTLS(boolean clientSSL, boolean serverSSL, Function<WebClient, HttpRequest<Buffer>> requestProvider, Consumer<HttpServerRequest> serverAssertions) throws Exception {
    WebClientOptions clientOptions = new WebClientOptions()
      .setSsl(clientSSL)
      .setTrustAll(true)
      .setDefaultHost(DEFAULT_HTTPS_HOST)
      .setDefaultPort(DEFAULT_HTTPS_PORT);
    HttpServerOptions serverOptions = new HttpServerOptions()
      .setSsl(serverSSL)
      .setKeyStoreOptions(Cert.SERVER_JKS.get())
      .setPort(DEFAULT_HTTPS_PORT)
      .setHost(DEFAULT_HTTPS_HOST);
    testTLS(clientOptions, serverOptions, requestProvider, serverAssertions);
  }

  @Test
  public void testVirtualHostSNI() throws Exception {
    WebClientOptions clientOptions = new WebClientOptions()
      .setTrustAll(true)
      .setDefaultHost(DEFAULT_HTTPS_HOST)
      .setDefaultPort(DEFAULT_HTTPS_PORT);
    HttpServerOptions serverOptions = new HttpServerOptions()
      .setSsl(true)
      .setSni(true)
      .setKeyStoreOptions(Cert.SNI_JKS.get())
      .setPort(DEFAULT_HTTPS_PORT)
      .setHost(DEFAULT_HTTPS_HOST);
     testTLS(clientOptions, serverOptions, req -> req.get("/").virtualHost("host2.com").ssl(true), req -> {
       assertEquals("host2.com", req.connection().indicatedServerName());
      System.out.println(req.host());
    });
  }

  private void testTLS(WebClientOptions clientOptions, HttpServerOptions serverOptions, Function<WebClient, HttpRequest<Buffer>> requestProvider, Consumer<HttpServerRequest> serverAssertions) throws Exception {
    WebClient sslClient = WebClient.create(vertx, clientOptions);
    HttpServer sslServer = vertx.createHttpServer(serverOptions);
    sslServer.requestHandler(req -> {
      assertEquals(serverOptions.isSsl(), req.isSSL());
      if (serverAssertions != null) {
        serverAssertions.accept(req);
      }
      req.response().end();
    });
    try {
      startServer(sslServer);
      HttpRequest<Buffer> builder = requestProvider.apply(sslClient);
      builder.send(onSuccess(resp -> testComplete()));
      await();
    } finally {
      sslClient.close();
      sslServer.close();
    }
  }

  @Test
  public void testHttpProxyFtpRequest() throws Exception {
    startProxy(null, ProxyType.HTTP);
    proxy.setForceUri("http://" + DEFAULT_HTTP_HOST + ":" + DEFAULT_HTTP_PORT);
    server.requestHandler(req -> req.response().setStatusCode(200).end());
    startServer();

    WebClientOptions options = new WebClientOptions();
    options.setProxyOptions(new ProxyOptions().setPort(proxy.getPort()));
    WebClient client = WebClient.create(vertx, options);
    client
    .getAbs("ftp://ftp.gnu.org/gnu/")
    .send(ar -> {
      if (ar.succeeded()) {
        // Obtain response
        HttpResponse<Buffer> response = ar.result();
        assertEquals(200, response.statusCode());
        assertEquals("ftp://ftp.gnu.org/gnu/", proxy.getLastUri());
        testComplete();
      } else {
        fail(ar.cause());
      }
    });
    await();
  }

  @Test
  public void testStreamHttpServerRequest() throws Exception {
    Buffer expected = TestUtils.randomBuffer(10000);
    HttpServer server2 = vertx.createHttpServer(new HttpServerOptions().setPort(8081)).requestHandler(req -> req.bodyHandler(body -> {
      assertEquals(body, expected);
      req.response().end();
    }));
    startServer(server2);
    WebClient webClient = WebClient.create(vertx);
    try {
      server.requestHandler(req -> webClient.postAbs("http://localhost:8081/")
        .sendStream(req, onSuccess(resp -> req.response().end("ok"))));
      startServer();
      webClient.post(8080, "localhost", "/").sendBuffer(expected, onSuccess(resp -> {
        assertEquals("ok", resp.bodyAsString());
        complete();
      }));
      await();
    } finally {
      server2.close();
    }
  }
}
