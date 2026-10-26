package com.fraternity.dues;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.FetchProfile;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.search.AndTerm;
import jakarta.mail.search.ComparisonTerm;
import jakarta.mail.search.ReceivedDateTerm;
import jakarta.mail.search.SubjectTerm;
import org.eclipse.angus.mail.imap.IMAPFolder;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Properties;

/**
 * Reads Venmo "paid you" emails from the chapter mailbox over IMAPS (port 993). The mailbox is opened read-only, so
 * nothing is marked read, moved or deleted. On Gmail it reads All Mail, so emails still count after being archived.
 */
public class ImapEmailSource implements EmailSource {
    private static final int PORT = 993;
    private static final int MAX_EMAILS_PER_SYNC = 500;
    private static final int MAX_EMAIL_BYTES = 5 * 1024 * 1024;

    private final String host;
    private final String address;
    private final char[] password;

    /** Keeps its own copy of {@code password}; call {@link #wipe()} when done. */
    public ImapEmailSource(String host, String address, char[] password) {
        this.host = host.trim();
        this.address = address.trim();
        this.password = Arrays.copyOf(password, password.length);
    }

    public void wipe() {
        Arrays.fill(password, '\0');
    }

    @Override
    public List<RawEmail> fetchSince(LocalDate since) throws EmailSourceException {
        try (jakarta.mail.Store mailbox = connect()) {
            Folder folder = paymentFolder(mailbox);
            folder.open(Folder.READ_ONLY);
            try {
                Date from = Date.from(since.atStartOfDay(ZoneId.systemDefault()).toInstant());
                // "paid you" also matches "paid your $X request"; the parser does the real checks.
                Message[] found = folder.search(new AndTerm(new SubjectTerm("paid you"),
                        new ReceivedDateTerm(ComparisonTerm.GE, from)));
                FetchProfile profile = new FetchProfile();
                profile.add(FetchProfile.Item.ENVELOPE);
                profile.add(FetchProfile.Item.SIZE);
                folder.fetch(found, profile);
                int start = Math.max(0, found.length - MAX_EMAILS_PER_SYNC);
                List<RawEmail> emails = new ArrayList<>();
                for (Message message : Arrays.copyOfRange(found, start, found.length)) {
                    if (message.getSize() > MAX_EMAIL_BYTES || !(message instanceof MimeMessage mime)) continue;
                    ByteArrayOutputStream raw = new ByteArrayOutputStream();
                    mime.writeTo(raw);
                    emails.add(new RawEmail(mime.getMessageID(), raw.toByteArray()));
                }
                return emails;
            } finally {
                if (folder.isOpen()) folder.close(false);
            }
        } catch (MessagingException | java.io.IOException e) {
            throw describe(e);
        }
    }

    @Override
    public String testConnection() throws EmailSourceException {
        try (jakarta.mail.Store mailbox = connect()) {
            Folder folder = paymentFolder(mailbox);
            folder.open(Folder.READ_ONLY);
            try {
                return "Signed in to " + address + " and opened " + folder.getFullName() + ".";
            } finally {
                folder.close(false);
            }
        } catch (MessagingException e) {
            throw describe(e);
        }
    }

    private jakarta.mail.Store connect() throws MessagingException {
        Properties properties = new Properties();
        properties.put("mail.store.protocol", "imaps");
        properties.put("mail.imaps.host", host);
        properties.put("mail.imaps.port", String.valueOf(PORT));
        properties.put("mail.imaps.ssl.checkserveridentity", "true");
        properties.put("mail.imaps.connectiontimeout", "15000");
        properties.put("mail.imaps.timeout", "30000");
        properties.put("mail.imaps.writetimeout", "15000");
        jakarta.mail.Store mailbox = Session.getInstance(properties).getStore("imaps");
        mailbox.connect(host, PORT, address, new String(password));
        return mailbox;
    }

    /** Gmail's All Mail (found by its \All flag, whatever the language), otherwise the inbox. */
    private static Folder paymentFolder(jakarta.mail.Store mailbox) throws MessagingException {
        for (Folder folder : mailbox.getDefaultFolder().list("*")) {
            if (folder instanceof IMAPFolder imap && Arrays.asList(imap.getAttributes()).contains("\\All")) return folder;
        }
        return mailbox.getFolder("INBOX");
    }

    private EmailSourceException describe(Exception e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        String message;
        if (e instanceof AuthenticationFailedException) {
            message = "The mailbox rejected the email address or app password. For Gmail, use a 16-character app "
                    + "password (with 2-Step Verification on), not the normal Gmail password.";
        } else if (cause instanceof java.net.UnknownHostException) {
            message = "Couldn't find the mail server " + host + ". Check the IMAP host and the internet connection.";
        } else if (cause instanceof java.net.SocketTimeoutException || cause instanceof java.net.ConnectException
                || cause instanceof java.net.NoRouteToHostException) {
            message = "Couldn't reach " + host + ". Check the internet connection; DuesDesk will try again in 10 minutes.";
        } else if (cause instanceof javax.net.ssl.SSLException || cause instanceof java.security.cert.CertificateException) {
            message = "The secure connection to " + host + " failed. Check the IMAP host name.";
        } else {
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage().trim();
            if (detail.length() > 200) detail = detail.substring(0, 200);
            message = "The mailbox could not be read: " + detail;
        }
        return new EmailSourceException(message, e);
    }
}
