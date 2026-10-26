package com.fraternity.dues;

import java.time.LocalDate;
import java.util.List;

/**
 * Where Venmo payment emails come from. {@link ImapEmailSource} reads the chapter mailbox today; a server or webhook
 * that receives the emails could implement this later without changing the parser, matcher or storage.
 */
public interface EmailSource {

    /** One email exactly as received (RFC 822 bytes). {@code messageId} may be null when the source can't tell. */
    record RawEmail(String messageId, byte[] content) {}

    /** Venmo "paid you" candidates received on or after {@code since}, oldest first. */
    List<RawEmail> fetchSince(LocalDate since) throws EmailSourceException;

    /** Checks the connection and login; returns a short description of what was reached. */
    String testConnection() throws EmailSourceException;

    /** A failure with a message the treasurer can act on. */
    class EmailSourceException extends Exception {
        public EmailSourceException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
