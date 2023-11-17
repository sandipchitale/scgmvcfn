# Spring Cloud Gateway MVC (fn) to Postman Echo service

- A simple Spring Cloud Gateway application that routes requests to the Postman Echo service. It automatically appends
  the `method` of the request to the Postman Echo URL `https://postman-echo.com/{method}`.

- Supports per request timeout by using  `X-TIMEOUT-MILLIS` header. If the header is not present, the default timeout is used.
- Converts `SocketTimeoutException` or `HttpConnectTimeoutException` into `HttpStatus.GATEWAY_TIMEOUT` response.
- Sets a request header `x-method`
- Sets a response header `x-method`

Use [Test page](http://localhost:8080/test.html) to test the application.

## Blog post

[Blog](https://sandipchitale.medium.com/spring-cloud-gateway-server-mvc-fn-6c8821395f22)

### Reference Documentation
For further reference, please consider the following sections:

* [spring-cloud-gateway-mvc-sample](https://github.com/spencergibb/spring-cloud-gateway-mvc-sample)
* [How to Include Spring Cloud Gateway Server MVC](https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-mvc/starter.html)
* [Official Gradle documentation](https://docs.gradle.org)
* [Spring Boot Gradle Plugin Reference Guide](https://docs.spring.io/spring-boot/docs/3.2.0-RC1/gradle-plugin/reference/html/)
* [Create an OCI image](https://docs.spring.io/spring-boot/docs/3.2.0-RC1/gradle-plugin/reference/html/#build-image)
* [Spring Web](https://docs.spring.io/spring-boot/docs/3.2.0-RC1/reference/htmlsingle/index.html#web)

### Guides
The following guides illustrate how to use some features concretely:

* [Building a RESTful Web Service](https://spring.io/guides/gs/rest-service/)
* [Serving Web Content with Spring MVC](https://spring.io/guides/gs/serving-web-content/)
* [Building REST services with Spring](https://spring.io/guides/tutorials/rest/)

### Additional Links
These additional references should also help you:

* [Gradle Build Scans – insights for your project's build](https://scans.gradle.com#gradle)

