package org.jahia.modules.formidable.jexperience.engine.profile;

/** jCustomer's profile schema could not be read: the module is absent, the site is not connected, or the call failed. */
public class ProfilePropertiesUnavailableException extends Exception {

    public ProfilePropertiesUnavailableException(String message) {
        super(message);
    }

    public ProfilePropertiesUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
