package com.example.customersync.route;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;

import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.jdbc.JdbcConstants;
import org.apache.camel.http.common.HttpOperationFailedException;
import org.springframework.stereotype.Component;

import com.example.customersync.processor.CustomerRowToCrmRequestProcessor;

/**
 * Main Camel route for periodically synchronizing customers to an external CRM.
 *
 * <p>Key responsibilities:</p>
 * <ul>
 *   <li>Trigger every 60 seconds using a Camel timer (configurable by property).</li>
 *   <li>Load new/unsynced customers from a database via Camel JDBC.</li>
 *   <li>Split the result set and process each customer individually.</li>
 *   <li>Invoke an external CRM HTTP endpoint for each customer.</li>
 *   <li>Apply robust error handling and retries for HTTP failures without
 *       stopping the overall job or timer.</li>
 * </ul>
 */
@Component
public class CustomerSyncRouteBuilder extends RouteBuilder {

    private final CustomerRowToCrmRequestProcessor customerRowToCrmRequestProcessor;

    public CustomerSyncRouteBuilder(CustomerRowToCrmRequestProcessor customerRowToCrmRequestProcessor) {
        this.customerRowToCrmRequestProcessor = customerRowToCrmRequestProcessor;
    }

    @Override
    public void configure() {
        configureGlobalErrorHandling();
        configureCustomerSyncRoute();
    }

    /**
     * Configure global error handling so that any exception raised by the
     * timer/JDBC/HTTP flow is handled with retries and logging, but never
     * crashes the Camel application or stops the timer.
     */
    private void configureGlobalErrorHandling() {
        // Base error handler configuration: no implicit redeliveries here.
        // We control retries explicitly via onException policies below.
        errorHandler(defaultErrorHandler()
                .maximumRedeliveries(0)
                .logStackTrace(true)
                .loggingLevel(LoggingLevel.ERROR));

        // HTTP-related failures (500s, timeouts, connection issues, etc.)
        // are retried a small, fixed number of times with a delay between
        // attempts. After retries are exhausted, the failure is logged with
        // the customer identifier and the exception message, and the
        // exchange is marked as handled so that the route and timer continue.
        onException(HttpOperationFailedException.class,
                    ConnectException.class,
                    SocketTimeoutException.class,
                    IOException.class)
                .handled(true)
                .maximumRedeliveries(3)
                .redeliveryDelay(2_000L)
                .retryAttemptedLogLevel(LoggingLevel.WARN)
                .logExhaustedMessageHistory(true)
                .log(LoggingLevel.ERROR,
                        "CRM sync failed for customerId=${header.customerId} " +
                        "after ${header.CamelRedeliveryCounter} attempts. " +
                        "Exception=${exception.class.simpleName}, message=${exception.message}");

        // Catch-all for any other unexpected exception to prevent the
        // consumer from stopping. We do not retry here (maximumRedeliveries=0)
        // but we log clearly that the sync run failed.
        onException(Exception.class)
                .handled(true)
                .maximumRedeliveries(0)
                .logExhaustedMessageHistory(true)
                .log(LoggingLevel.ERROR,
                        "Unhandled exception during customer sync runId=${header.syncRunId}. " +
                        "Exception=${exception.class.simpleName}, message=${exception.message}");
    }

    /**
     * Main customer synchronization route.
     */
    private void configureCustomerSyncRoute() {
        // Timer triggers every 60 seconds by default. This can be overridden
        // with the property `customer.sync.period` (in milliseconds).
        from("timer:customerSyncTimer?period={{customer.sync.period:60000}}")
                .routeId("customer-sync-timer-route")

                // Assign a unique identifier for observability for each sync run.
                .setHeader("syncRunId", simple("${uuid}"))
                .log(LoggingLevel.INFO,
                        "Starting customer sync runId=${header.syncRunId}")

                // Build the JDBC query used to fetch customers to sync.
                // In a real system this should be an efficient, parameterized
                // query (e.g. based on an updated_since column or a status flag).
                .setHeader(JdbcConstants.JDBC_QUERY, constant(
                        "SELECT id, first_name, last_name, email " +
                        "FROM customers WHERE synced = false"))

                // Execute the query against the configured DataSource.
                // The endpoint name `customerDataSource` must match the
                // DataSource bean name defined in Spring configuration.
                .to("jdbc:customerDataSource")

                // Body is now a List<Map<String, Object>> from the JDBC
                // component. If there are no rows, stop gracefully.
                .choice()
                    .when(simple("${body} == null || ${body.size} == 0"))
                        .log(LoggingLevel.INFO,
                                "No customers to sync for runId=${header.syncRunId}")
                    .otherwise()
                        .log(LoggingLevel.INFO,
                                "Loaded ${body.size} customers to sync for runId=${header.syncRunId}")

                        // Split the result set so that each customer is
                        // processed independently. A failure for one customer
                        // will not prevent the others from being attempted.
                        .split(body())
                            .streaming()
                            .stopOnException(false)
                            .parallelProcessing(false)
                            .routeId("customer-sync-per-customer-route")

                            // Extract a readable identifier early for
                            // logging and error handling.
                            .setHeader("customerId", simple("${body[id]}"))

                            .log(LoggingLevel.DEBUG,
                                    "Preparing CRM request for customerId=${header.customerId}")

                            // Transform the JDBC row into a CRM HTTP request
                            // (JSON body + appropriate headers).
                            .process(customerRowToCrmRequestProcessor)

                            // Perform the HTTP call to the CRM service.
                            // The base URL and timeouts are externalized to
                            // properties so they can be tuned per environment.
                            //
                            // Example properties:
                            //   crm.service.base-url = http://crm:8080/api
                            //   crm.http.connect-timeout = 5000
                            //   crm.http.read-timeout = 5000
                            .toD("http://{{crm.service.base-url:localhost:8080/api}}/customers" +
                                    "?httpMethod=POST" +
                                    "&throwExceptionOnFailure=true" +
                                    "&connectTimeout={{crm.http.connect-timeout:5000}}" +
                                    "&socketTimeout={{crm.http.read-timeout:5000}}")

                            .log(LoggingLevel.INFO,
                                    "Successfully synced customerId=${header.customerId} " +
                                    "during runId=${header.syncRunId}")
                        .end() // end split
                .end() // end choice

                .log(LoggingLevel.INFO,
                        "Finished customer sync runId=${header.syncRunId}");
    }
}
