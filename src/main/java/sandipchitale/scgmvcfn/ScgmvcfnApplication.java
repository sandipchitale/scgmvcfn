package sandipchitale.scgmvcfn;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.cloud.gateway.server.mvc.config.GatewayMvcProperties;
import org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions;
import org.springframework.cloud.gateway.server.mvc.handler.GatewayServerResponse;
import org.springframework.cloud.gateway.server.mvc.handler.ProxyExchange;
import org.springframework.cloud.gateway.server.mvc.handler.RestClientProxyExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.http.*;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.function.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpConnectTimeoutException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;

@SpringBootApplication
public class ScgmvcfnApplication {

	public static final String X_METHOD = "x-method";

	public static void main(String[] args) {
		SpringApplication.run(ScgmvcfnApplication.class, args);
	}

	@Controller
	public static class TestController {
		@GetMapping("/test")
		public String test() {
			return "test.html";
		}
	}

	static class ClientHttpResponseAdapter implements ProxyExchange.Response {

		private final ClientHttpResponse response;

		ClientHttpResponseAdapter(ClientHttpResponse response) {
			this.response = response;
		}

		@Override
		public HttpStatusCode getStatusCode() {
			try {
				return response.getStatusCode();
			}
			catch (IOException e) {
				throw new RuntimeException(e);
			}
		}

		@Override
		public HttpHeaders getHeaders() {
			return response.getHeaders();
		}

	}

	@Component
	public static class PerRequestTimeoutRestClientProxyExchange extends RestClientProxyExchange {
		private static final String X_TIMEOUT_MILLIS = "X-TIMEOUT-MILLIS";
		private final RestClient.Builder restClientBuilder;
		private final GatewayMvcProperties gatewayMvcProperties;
		private final SslBundles sslBundles;

		// Cache
		private final Map<Long, RestClient> xTimeoutMillisToRestClientMap = new HashMap<>();

		public PerRequestTimeoutRestClientProxyExchange(RestClient.Builder restClientBuilder,
														GatewayMvcProperties gatewayMvcProperties,
														SslBundles sslBundles) {
			super(restClientBuilder.build());
			this.restClientBuilder = restClientBuilder;
			this.gatewayMvcProperties = gatewayMvcProperties;
			this.sslBundles = sslBundles;
		}

		@Override
		public ServerResponse exchange(Request request) {
			String xTimeoutMillis = request.getHeaders().getFirst(X_TIMEOUT_MILLIS);
			if (xTimeoutMillis != null) {
				long xTimeoutMillisLong = Long.parseLong(xTimeoutMillis);
				if (xTimeoutMillisLong > 0) {
					RestClient restClient = xTimeoutMillisToRestClientMap.get(xTimeoutMillisLong);
					if (restClient == null) {
						restClient = restClientBuilder
								.requestFactory(gatewayClientHttpRequestFactory(gatewayMvcProperties,
										sslBundles,
										Duration.ofMillis(Long.parseLong(xTimeoutMillis)))) // Read timeout override
								.build();
						xTimeoutMillisToRestClientMap.put(xTimeoutMillisLong, restClient);
					}
					return restClient
							.method(request.getMethod())
							.uri(request.getUri())
							.headers(httpHeaders -> httpHeaders.putAll(request.getHeaders()))
							.body(outputStream -> copyBody(request, outputStream))
							.exchange((clientRequest, clientResponse) -> doExchange(request, clientResponse), false);
				}
			}
			return super.exchange(request);
		}

		// Streaming
		private static int copyBody(Request request, OutputStream outputStream) throws IOException {
			return StreamUtils.copy(request.getServerRequest().servletRequest().getInputStream(), outputStream);
		}

		private static ServerResponse doExchange(Request request, ClientHttpResponse clientResponse) throws IOException {
			ServerResponse serverResponse = GatewayServerResponse
					.status(clientResponse.getStatusCode())
					.build((HttpServletRequest req, HttpServletResponse httpServletResponse) -> {
						try (clientResponse) {
							// copy body from clientResponse to response
							StreamUtils.copy(clientResponse.getBody(), httpServletResponse.getOutputStream());
						}
						return null;
					});
			ClientHttpResponseAdapter proxyExchangeResponse = new ClientHttpResponseAdapter(clientResponse);
			request.getResponseConsumers()
					.forEach((ResponseConsumer responseConsumer) -> responseConsumer.accept(proxyExchangeResponse, serverResponse));
			return serverResponse;
		}

		private ClientHttpRequestFactory gatewayClientHttpRequestFactory(GatewayMvcProperties gatewayMvcProperties,
																		 SslBundles sslBundles,
																		 Duration readTimeout) {
			GatewayMvcProperties.HttpClient properties = gatewayMvcProperties.getHttpClient();

			SslBundle sslBundle = null;
			if (StringUtils.hasText(properties.getSslBundle())) {
				sslBundle = sslBundles.getBundle(properties.getSslBundle());
			}
			ClientHttpRequestFactorySettings settings = new ClientHttpRequestFactorySettings(properties.getConnectTimeout(),
					readTimeout, sslBundle);

			if (properties.getType() == GatewayMvcProperties.HttpClientType.JDK) {
				// TODO: customize restricted headers
				String restrictedHeaders = System.getProperty("jdk.httpclient.allowRestrictedHeaders");
				if (!StringUtils.hasText(restrictedHeaders)) {
					System.setProperty("jdk.httpclient.allowRestrictedHeaders", "host");
				}
				else if (StringUtils.hasText(restrictedHeaders) && !restrictedHeaders.contains("host")) {
					System.setProperty("jdk.httpclient.allowRestrictedHeaders", restrictedHeaders + ",host");
				}

				return ClientHttpRequestFactories.get(JdkClientHttpRequestFactory.class, settings);
			}

			// Autodetect
			return ClientHttpRequestFactories.get(settings);
		}
	}

	private Function<ServerRequest, ServerRequest> methodToRequestHeader() {
		return (ServerRequest serverRequest) -> {
			return ServerRequest.from(serverRequest)
					.headers(httpHeaders -> {
						httpHeaders.add(X_METHOD, serverRequest.method().name());
					}).build();
		};
	}

	private static Function<ServerRequest, ServerRequest> pathFromRequestMethodName() {
		return (ServerRequest request) -> {
			URI uri = UriComponentsBuilder
					.fromUri(request.uri())
					.replacePath(request.method().name().toLowerCase())
					.build()
					.toUri();
			return ServerRequest.from(request).uri(uri).build();
		};
	}

	private static Predicate<Throwable> timeoutExceptionPredicate() {
		return (Throwable throwable) -> {
            return throwable.getCause() instanceof SocketTimeoutException || throwable.getCause() instanceof HttpConnectTimeoutException;
        };
	}

	private static BiFunction<Throwable, ServerRequest, ServerResponse> timeoutExceptionServerResponse() {
		return (Throwable throwable, ServerRequest request) -> {
			ProblemDetail problemDetail = ProblemDetail.forStatus(HttpStatus.GATEWAY_TIMEOUT);
			problemDetail.setType(request.uri());
			problemDetail.setDetail(throwable.getCause().getMessage());
			return ServerResponse
					.status(HttpStatus.GATEWAY_TIMEOUT)
					.header(X_METHOD, request.method().name())
					.body(problemDetail);
		};
	}

	private static BiFunction<ServerRequest, ServerResponse, ServerResponse> methodToResponseHeader() {
		return (ServerRequest serverRequest, ServerResponse serverResponse) -> {
			if (!serverResponse.statusCode().isError()) {
				serverResponse.headers().add(X_METHOD, serverRequest.method().name());
			}
			return serverResponse;
		};
	}

	@Bean
	public RouterFunction<ServerResponse> postmanEchoRoute() {
		return RouterFunctions.route()
				.before(BeforeFilterFunctions.routeId("postman-echo"))
				.before(methodToRequestHeader())
				.before(pathFromRequestMethodName())
				.route(RequestPredicates.path("/")
								.and(RequestPredicates.methods(
										HttpMethod.GET,
										HttpMethod.POST,
										HttpMethod.PUT,
										HttpMethod.DELETE)),
						http(URI.create("https://postman-echo.com/"))) // This is where the proxying to the external service happens
				.after(methodToResponseHeader())
				.onError(timeoutExceptionPredicate(), timeoutExceptionServerResponse())
				.build();
	}

}
