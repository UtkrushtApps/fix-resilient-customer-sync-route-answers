# Solution Steps

1. Create a new route class `CustomerSyncRouteBuilder` under `src/main/java/com/example/customersync/route` and annotate it with `@Component` so that Camel Spring Boot will discover it.

2. Make `CustomerSyncRouteBuilder` extend `RouteBuilder` and override the `configure()` method to call two private methods: one for global error handling and one for the main customer sync route.

3. In `configureGlobalErrorHandling()`, configure a base `errorHandler(defaultErrorHandler())` with zero automatic redeliveries and `LoggingLevel.ERROR` to keep control of retries explicit and to avoid stopping the consumer on unhandled exceptions.

4. Still in `configureGlobalErrorHandling()`, add an `onException` clause for HTTP-related failures (`HttpOperationFailedException`, `ConnectException`, `SocketTimeoutException`, `IOException`) with `.handled(true)`, `.maximumRedeliveries(3)`, and `.redeliveryDelay(2000)` so each failed HTTP call is retried three times with a 2-second delay and then treated as a handled failure.

5. In the same HTTP `onException` block, configure `.retryAttemptedLogLevel(LoggingLevel.WARN)` and `.logExhaustedMessageHistory(true)` and add a `.log(LoggingLevel.ERROR, ...)` statement that logs the `customerId`, retry counter, exception type, and message once retries are exhausted.

6. Add a second `onException(Exception.class)` with `.handled(true)` and `maximumRedeliveries(0)` to serve as a catch-all safety net so that any unexpected error is logged and does not stop the timer-based route.

7. Inside `configureCustomerSyncRoute()`, define the timer-based entry point with `from("timer:customerSyncTimer?period={{customer.sync.period:60000}}")` and set a `syncRunId` header (using `${uuid}`) plus an INFO log statement to mark the start of each sync run for observability.

8. Before calling JDBC, set `JdbcConstants.JDBC_QUERY` header with a SQL query such as `SELECT id, first_name, last_name, email FROM customers WHERE synced = false`, then send the exchange to `jdbc:customerDataSource` (assuming a DataSource bean named `customerDataSource`).

9. After the JDBC call, use a `choice()` block to handle the case where no rows are returned: when the body is null or has size 0, log that there are no customers to sync and end the route; otherwise, log how many customers were loaded and continue.

10. Within the `otherwise()` branch, add a `split(body())` block to iterate over the list of customer rows, enabling `.streaming()` and `.stopOnException(false)` so each row is handled independently and failures for one row do not halt processing of the remaining rows.

11. Assign a routeId for the per-customer processing (e.g., `customer-sync-per-customer-route`), set a `customerId` header from `body[id]` for logging/error handling, and add a DEBUG log indicating preparation of the CRM request for that customer.

12. Create a processor class `CustomerRowToCrmRequestProcessor` under `src/main/java/com/example/customersync/processor` implementing `Processor`, annotated with `@Component`, and inject a Jackson `ObjectMapper` via constructor injection.

13. In `CustomerRowToCrmRequestProcessor`, cast the incoming body to `Map<String, Object>` (JDBC row), extract `id`, `first_name`, `last_name`, and `email`, and convert them into appropriate Java types via helper methods (`toLong`, `toString`), throwing an `IllegalArgumentException` for invalid numeric IDs so the error handler can manage the failure.

14. Ensure `CustomerRowToCrmRequestProcessor` always sets the `customerId` header when an `id` is available and then constructs a `CustomerPayload` DTO populated with the extracted values.

15. Serialize the `CustomerPayload` to JSON using `objectMapper.writeValueAsString`, set the HTTP method header to POST (`Exchange.HTTP_METHOD` with `HttpMethods.POST.name()`), the `Content-Type` header to `application/json`, and set the JSON string as the message body.

16. Create a simple DTO class `CustomerPayload` under `src/main/java/com/example/customersync/model` with fields `id`, `firstName`, `lastName`, and `email`, along with getters, setters, constructors, and a `toString()` implementation for better logging/debugging.

17. Back in the route, after `.process(customerRowToCrmRequestProcessor)`, call the CRM endpoint using `.toD("http://{{crm.service.base-url:localhost:8080/api}}/customers?httpMethod=POST&throwExceptionOnFailure=true&connectTimeout={{crm.http.connect-timeout:5000}}&socketTimeout={{crm.http.read-timeout:5000}}")` so the HTTP component throws exceptions on non-2xx responses and the timeouts are configurable by properties.

18. After the CRM call, add an INFO log statement like `Successfully synced customerId=${header.customerId} during runId=${header.syncRunId}` to confirm success for each customer record.

19. End the `split` and `choice` blocks and add a final INFO log `Finished customer sync runId=${header.syncRunId}` at the end of the route so every run has clear start and end markers in the logs, verifying that the timer continues to trigger regularly even when some CRM calls fail.

