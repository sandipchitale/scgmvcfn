package sandipchitale.scgmvcfn;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.cloud.gateway.server.mvc.common.MvcUtils;
import org.springframework.cloud.gateway.server.mvc.config.GatewayMvcProperties;
import org.springframework.cloud.gateway.server.mvc.handler.GatewayServerResponse;
import org.springframework.cloud.gateway.server.mvc.handler.ProxyExchange;
import org.springframework.cloud.gateway.server.mvc.handler.RestClientProxyExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpConnectTimeoutException;
import java.time.Duration;
import java.util.function.Function;

import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;

@SpringBootApplication
public class ScgmvcfnApplication {

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
		private final ClientHttpRequestFactory clientHttpRequestFactory;
		private final GatewayMvcProperties gatewayMvcProperties;
		private final SslBundles sslBundles;

		public PerRequestTimeoutRestClientProxyExchange(RestClient.Builder restClientBuilder,
														ClientHttpRequestFactory clientHttpRequestFactory,
														GatewayMvcProperties gatewayMvcProperties,
														SslBundles sslBundles) {
			super(restClientBuilder.build());
			this.restClientBuilder = restClientBuilder;
			this.clientHttpRequestFactory = clientHttpRequestFactory;
			this.gatewayMvcProperties = gatewayMvcProperties;
			this.sslBundles = sslBundles;
		}

		@Override
		public ServerResponse exchange(Request request) {
			if (request.getHeaders().getFirst(X_TIMEOUT_MILLIS) != null) {
				RestClient restClient = restClientBuilder.requestFactory(gatewayClientHttpRequestFactory(gatewayMvcProperties,
								sslBundles,
								Duration.ofMillis(Long.parseLong(request.getHeaders().getFirst(X_TIMEOUT_MILLIS)))))
						.build();

				return restClient.method(request.getMethod()).uri(request.getUri())
						.headers(httpHeaders -> httpHeaders.putAll(request.getHeaders()))
						.body(outputStream -> copyBody(request, outputStream))
						.exchange((clientRequest, clientResponse) -> doExchange(request, clientResponse), false);
			}
			return super.exchange(request);
		}

		private static int copyBody(Request request, OutputStream outputStream) throws IOException {
			return StreamUtils.copy(request.getServerRequest().servletRequest().getInputStream(), outputStream);
		}

		private static ServerResponse doExchange(Request request, ClientHttpResponse clientResponse) throws IOException {
			ServerResponse serverResponse = GatewayServerResponse.status(clientResponse.getStatusCode())
					.build((req, httpServletResponse) -> {
						try (clientResponse) {
							// copy body from request to clientHttpRequest
							StreamUtils.copy(clientResponse.getBody(), httpServletResponse.getOutputStream());
						}
						return null;
					});
			ClientHttpResponseAdapter proxyExchangeResponse = new ClientHttpResponseAdapter(clientResponse);
			request.getResponseConsumers()
					.forEach(responseConsumer -> responseConsumer.accept(proxyExchangeResponse, serverResponse));
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

	public static void main(String[] args) {
		SpringApplication.run(ScgmvcfnApplication.class, args);
	}

	public static class PostmanechoUriResolver implements Function<ServerRequest, ServerRequest> {

		@Override
		public ServerRequest apply(ServerRequest request) {
			URI uri = URI.create("https://postman-echo.com/");
			MvcUtils.setRequestUrl(request, uri);
			return request;
		}
	}

	@Bean
	public RouterFunction<ServerResponse> postmanEchoRoute() {
		return route("postman-echo")
				.GET("/", http())
				.before(new PostmanechoUriResolver())
				.before((ServerRequest request) -> {
					URI uri = UriComponentsBuilder
							.fromUri(request.uri())
							.replacePath(request.method().name().toLowerCase())
							.build()
							.toUri();
					return ServerRequest.from(request).uri(uri).build();
				})
				.filter((request, next) -> {
					return next.handle(request);
				})
				.onError(e -> e.getCause() instanceof SocketTimeoutException || e.getCause() instanceof HttpConnectTimeoutException,
						(e, request) -> {
							ProblemDetail problemDetail = ProblemDetail.forStatus(HttpStatus.GATEWAY_TIMEOUT);
							problemDetail.setType(request.uri());
							problemDetail.setDetail(e.getCause().getMessage());
							return ServerResponse
								.status(HttpStatus.GATEWAY_TIMEOUT)
								.body(problemDetail);}
				)
				.build();
	}
}