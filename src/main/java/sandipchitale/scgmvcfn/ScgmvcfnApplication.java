package sandipchitale.scgmvcfn;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.cloud.gateway.server.mvc.GatewayServerMvcAutoConfiguration;
import org.springframework.cloud.gateway.server.mvc.config.GatewayMvcProperties;
import org.springframework.cloud.gateway.server.mvc.handler.RestClientProxyExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.function.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpConnectTimeoutException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.routeId;
import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.setPath;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;

@SpringBootApplication
public class ScgmvcfnApplication {

	public static final String X_METHOD = "x-method";

	public static void main(String[] args) {
		System.out.println("java.version = " + System.getProperty("java.version"));
		SpringApplication.run(ScgmvcfnApplication.class, args);
	}

	@Controller
	public static class TestController {
		@GetMapping("/test")
		public String test() {
			return "test.html";
		}
	}

	@Component
	public static class PerRequestTimeoutRestClientProxyExchange extends RestClientProxyExchange {
		private static final String X_TIMEOUT_MILLIS = "X-TIMEOUT-MILLIS";
		private final RestClient.Builder restClientBuilder;
		private final GatewayServerMvcAutoConfiguration gatewayServerMvcAutoConfiguration;
		private final GatewayMvcProperties gatewayMvcProperties;
		private final SslBundles sslBundles;

		// Cache
		private final Map<Long, RestClient> xTimeoutMillisToRestClientMap = new HashMap<>();
		private final Method superCopyBody;
		private final Method superDoExchange;

		public PerRequestTimeoutRestClientProxyExchange(RestClient.Builder restClientBuilder,
														GatewayServerMvcAutoConfiguration gatewayServerMvcAutoConfiguration,
														GatewayMvcProperties gatewayMvcProperties,
														SslBundles sslBundles) {
			super(restClientBuilder.build());
			this.restClientBuilder = restClientBuilder;
			this.gatewayServerMvcAutoConfiguration = gatewayServerMvcAutoConfiguration;
			this.gatewayMvcProperties = gatewayMvcProperties;
			this.sslBundles = sslBundles;

			superCopyBody = ReflectionUtils.findMethod(RestClientProxyExchange.class, "copyBody", Request.class, OutputStream.class);
			if (superCopyBody != null) {
				ReflectionUtils.makeAccessible(superCopyBody);
			}

			superDoExchange = ReflectionUtils.findMethod(RestClientProxyExchange.class, "doExchange", Request.class, ClientHttpResponse.class);
			if (superDoExchange != null) {
				ReflectionUtils.makeAccessible(superDoExchange);
			}
		}

		@Override
		public ServerResponse exchange(Request request) {
			String xTimeoutMillis = request.getHeaders().getFirst(X_TIMEOUT_MILLIS);
//			xTimeoutMillis = "30";
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
							.body(outputStream -> copyBody(superCopyBody, request, outputStream))
							.exchange((clientRequest, clientResponse) -> doExchange(superDoExchange, request, clientResponse), false);
				}
			}
			return super.exchange(request);
		}

		// Try to use original implementation as much as possible
		private static void copyBody(Method superCopyBody, Request request, OutputStream outputStream) {
			ReflectionUtils.invokeMethod(superCopyBody,
					null,
					request,
					outputStream);
		}

		private static ServerResponse doExchange(Method superDoExchange, Request request, ClientHttpResponse clientResponse) {
			return (ServerResponse) ReflectionUtils.invokeMethod(superDoExchange,
					null,
					request,
					clientResponse);
		}

		private ClientHttpRequestFactory gatewayClientHttpRequestFactory(GatewayMvcProperties gatewayMvcProperties,
																		 SslBundles sslBundles,
																		 Duration readTimeout) {
			Duration originalReadTimeout = gatewayMvcProperties.getHttpClient().getReadTimeout();
			try {
				gatewayMvcProperties.getHttpClient().setReadTimeout(readTimeout);
				return gatewayServerMvcAutoConfiguration.gatewayClientHttpRequestFactory(gatewayMvcProperties,
						sslBundles);
			} finally {
				gatewayMvcProperties.getHttpClient().setReadTimeout(originalReadTimeout);
			}
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
					// need to clear query string, because query params
					// are also captured by ServerRequest.from(request)
					.replaceQuery("")
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
//				.before(BeforeFilterFunctions.routeId("echo"))
				.before(routeId("postman-echo"))
				.before(methodToRequestHeader())
				.before(pathFromRequestMethodName())
				.route(RequestPredicates.path("/")
								.and(RequestPredicates.methods(
										HttpMethod.GET,
										HttpMethod.POST,
										HttpMethod.PUT,
										HttpMethod.DELETE)),
//						http(URI.create("http://localhost:9090/"))) // This is where the proxying to the external, local echo service happens
						http(URI.create("https://postman-echo.com/"))) // This is where the proxying to the external postman-echo service happens
				.after(methodToResponseHeader())
				.onError(timeoutExceptionPredicate(), timeoutExceptionServerResponse())
				.build();
	}

}