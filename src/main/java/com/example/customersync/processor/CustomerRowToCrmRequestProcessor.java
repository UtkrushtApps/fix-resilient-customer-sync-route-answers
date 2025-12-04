package com.example.customersync.processor;

import java.util.Map;

import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;
import org.apache.camel.http.common.HttpMethods;
import org.springframework.stereotype.Component;

import com.example.customersync.model.CustomerPayload;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Processor that converts a JDBC result row into a JSON HTTP request
 * suitable for the external CRM service.
 */
@Component
public class CustomerRowToCrmRequestProcessor implements Processor {

    private final ObjectMapper objectMapper;

    public CustomerRowToCrmRequestProcessor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) throws Exception {
        Message in = exchange.getIn();

        Map<String, Object> row = in.getBody(Map.class);
        if (row == null) {
            throw new IllegalStateException("Expected JDBC row as Map but body was null");
        }

        // Extract fields from the JDBC row. Column names must match the
        // SELECT clause used in the JDBC query in the route.
        Long id = toLong(row.get("id"));
        String firstName = toString(row.get("first_name"));
        String lastName = toString(row.get("last_name"));
        String email = toString(row.get("email"));

        // Ensure customerId header is always set for error logging.
        if (id != null) {
            in.setHeader("customerId", id);
        }

        CustomerPayload payload = new CustomerPayload();
        payload.setId(id);
        payload.setFirstName(firstName);
        payload.setLastName(lastName);
        payload.setEmail(email);

        String jsonBody = objectMapper.writeValueAsString(payload);

        // Prepare HTTP request.
        in.setHeader(Exchange.HTTP_METHOD, HttpMethods.POST.name());
        in.setHeader(Exchange.CONTENT_TYPE, "application/json");
        in.setBody(jsonBody);
    }

    private String toString(Object value) {
        return value != null ? String.valueOf(value) : null;
    }

    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            // Let the route fail for this particular record; the global
            // error handler will deal with logging and continuation.
            throw new IllegalArgumentException("Invalid numeric value for id: " + value, ex);
        }
    }
}
