package com.example.customersync.model;

/**
 * Simple DTO representing the customer payload sent to the CRM service.
 *
 * <p>This class is intentionally minimal and does not depend on the
 * internal database representation. It models the contract between the
 * sync service and the external CRM.</p>
 */
public class CustomerPayload {

    private Long id;
    private String firstName;
    private String lastName;
    private String email;

    public CustomerPayload() {
    }

    public CustomerPayload(Long id, String firstName, String lastName, String email) {
        this.id = id;
        this.firstName = firstName;
        this.lastName = lastName;
        this.email = email;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    @Override
    public String toString() {
        return "CustomerPayload{" +
                "id=" + id +
                ", firstName='" + firstName + '\'' +
                ", lastName='" + lastName + '\'' +
                ", email='" + email + '\'' +
                '}';
    }
}
