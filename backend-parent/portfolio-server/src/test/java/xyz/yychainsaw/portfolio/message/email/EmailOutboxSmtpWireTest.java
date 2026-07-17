package xyz.yychainsaw.portfolio.message.email;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import xyz.yychainsaw.portfolio.message.config.ContactProperties;
import xyz.yychainsaw.portfolio.message.config.EmailOutboxProperties;

class EmailOutboxSmtpWireTest {
    private static final String CONTACT_SECRET =
            "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
    private static final String OWNER = "owner@example.com";
    private static final String FROM = "notify@example.com";
    private static final Instant RECEIVED_AT = Instant.parse("2026-07-14T10:00:00Z");
    private static final UUID OUTBOX_ID =
            UUID.fromString("00000000-0000-4000-8000-000000000301");
    private static final String STABLE_MESSAGE_ID =
            "<portfolio-contact-" + OUTBOX_ID + "@yychainsaw.xyz>";

    @Test
    void realJavaMailTransportKeepsOneVerbatimStableMessageIdOnEveryRetry()
            throws Exception {
        try (LoopbackSmtpServer server = new LoopbackSmtpServer(2)) {
            JavaMailSenderImpl javaMail = new JavaMailSenderImpl();
            javaMail.setProtocol("smtp");
            javaMail.setHost(InetAddress.getLoopbackAddress().getHostAddress());
            javaMail.setPort(server.port());
            javaMail.setDefaultEncoding(StandardCharsets.UTF_8.name());
            javaMail.getJavaMailProperties().put("mail.smtp.auth", "false");
            javaMail.getJavaMailProperties().put("mail.smtp.connectiontimeout", "2000");
            javaMail.getJavaMailProperties().put("mail.smtp.timeout", "2000");
            javaMail.getJavaMailProperties().put("mail.smtp.writetimeout", "2000");
            SmtpEmailSender sender = new SmtpEmailSender(
                    javaMail,
                    new EmailOutboxProperties(
                            true,
                            FROM,
                            Duration.ofSeconds(10),
                            Duration.ofMinutes(2),
                            10),
                    new ContactProperties(CONTACT_SECRET, OWNER, "yychainsaw.xyz"),
                    secureMailProperties());
            ContactNotification notification = new ContactNotification(
                    OUTBOX_ID,
                    STABLE_MESSAGE_ID,
                    "tampered-recipient@example.net",
                    "visitor@example.net",
                    "易嘉轩",
                    "UE 合作 🚀",
                    "你好，我想讨论游戏开发。\nSecond line.",
                    RECEIVED_AT);

            sender.send(notification);
            sender.send(notification);

            List<byte[]> deliveries = server.awaitDeliveries();
            assertThat(deliveries).hasSize(2);
            for (byte[] raw : deliveries) {
                String wire = new String(raw, StandardCharsets.ISO_8859_1);
                String headers = wire.substring(0, wire.indexOf("\r\n\r\n"));
                assertThat(Arrays.stream(headers.split("\r\n"))
                                .filter(line -> line.regionMatches(
                                        true, 0, "Message-ID:", 0, "Message-ID:".length())))
                        .singleElement()
                        .isEqualTo("Message-ID: " + STABLE_MESSAGE_ID);

                MimeMessage parsed = new MimeMessage(
                        Session.getInstance(new Properties()),
                        new ByteArrayInputStream(raw));
                assertThat(parsed.getHeader("Message-ID"))
                        .containsExactly(STABLE_MESSAGE_ID);
                assertThat(parsed.getRecipients(Message.RecipientType.TO))
                        .extracting(Address::toString)
                        .containsExactly(OWNER);
                assertThat(parsed.getReplyTo())
                        .extracting(Address::toString)
                        .containsExactly("visitor@example.net");
                assertThat(parsed.getFrom())
                        .extracting(Address::toString)
                        .containsExactly(FROM);
                assertThat(parsed.isMimeType("text/plain")).isTrue();
                assertThat(parsed.getContentType()).containsIgnoringCase("charset=UTF-8");
                assertThat(parsed.getContent())
                        .isInstanceOf(String.class)
                        .asString()
                        .contains("易嘉轩", "UE 合作 🚀", "你好，我想讨论游戏开发。");
            }
        }
    }

    private static MailProperties secureMailProperties() {
        MailProperties properties = new MailProperties();
        properties.setProtocol("smtp");
        properties.setHost("smtp.example.com");
        properties.setPort(587);
        properties.setUsername("smtp-user");
        properties.setPassword("smtp-password");
        properties.getProperties().put("mail.smtp.auth", "true");
        properties.getProperties().put("mail.smtp.starttls.enable", "true");
        properties.getProperties().put("mail.smtp.starttls.required", "true");
        properties.getProperties().put("mail.smtp.ssl.checkserveridentity", "true");
        properties.getProperties().put("mail.smtp.connectiontimeout", "10000");
        properties.getProperties().put("mail.smtp.timeout", "10000");
        properties.getProperties().put("mail.smtp.writetimeout", "10000");
        return properties;
    }

    private static final class LoopbackSmtpServer implements AutoCloseable {
        private static final int MAXIMUM_LINE_BYTES = 1_000_000;

        private final int expectedDeliveries;
        private final ServerSocket server;
        private final ExecutorService executor;
        private final Future<List<byte[]>> capture;

        LoopbackSmtpServer(int expectedDeliveries) throws IOException {
            this.expectedDeliveries = expectedDeliveries;
            this.server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
            this.executor = Executors.newSingleThreadExecutor();
            this.capture = executor.submit(this::serve);
        }

        int port() {
            return server.getLocalPort();
        }

        List<byte[]> awaitDeliveries() throws Exception {
            return capture.get(10, SECONDS);
        }

        @Override
        public void close() throws Exception {
            server.close();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, SECONDS)).isTrue();
        }

        private List<byte[]> serve() throws IOException {
            List<byte[]> deliveries = new ArrayList<>();
            while (deliveries.size() < expectedDeliveries) {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(5_000);
                    handle(socket, deliveries);
                }
            }
            return List.copyOf(deliveries);
        }

        private static void handle(Socket socket, List<byte[]> deliveries)
                throws IOException {
            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();
            reply(output, "220 localhost ESMTP ready");
            while (true) {
                byte[] lineBytes = readLine(input);
                if (lineBytes == null) {
                    return;
                }
                String line = new String(lineBytes, StandardCharsets.US_ASCII);
                String command = line.toUpperCase(java.util.Locale.ROOT);
                if (command.startsWith("EHLO ") || command.startsWith("HELO ")) {
                    output.write("250-localhost\r\n250 8BITMIME\r\n"
                            .getBytes(StandardCharsets.US_ASCII));
                    output.flush();
                } else if (command.startsWith("MAIL FROM:")
                        || command.startsWith("RCPT TO:")) {
                    reply(output, "250 accepted");
                } else if ("DATA".equals(command)) {
                    reply(output, "354 end data with <CRLF>.<CRLF>");
                    deliveries.add(readData(input));
                    reply(output, "250 queued");
                } else if ("QUIT".equals(command)) {
                    reply(output, "221 goodbye");
                    return;
                } else if ("RSET".equals(command) || "NOOP".equals(command)) {
                    reply(output, "250 ok");
                } else {
                    reply(output, "502 command not implemented");
                }
            }
        }

        private static byte[] readData(InputStream input) throws IOException {
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            while (true) {
                byte[] line = readLine(input);
                if (line == null) {
                    throw new IOException("SMTP client disconnected during DATA");
                }
                if (line.length == 1 && line[0] == '.') {
                    return data.toByteArray();
                }
                int offset = line.length > 1 && line[0] == '.' && line[1] == '.' ? 1 : 0;
                data.write(line, offset, line.length - offset);
                data.write('\r');
                data.write('\n');
            }
        }

        private static byte[] readLine(InputStream input) throws IOException {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            while (line.size() <= MAXIMUM_LINE_BYTES) {
                int octet = input.read();
                if (octet == -1) {
                    return line.size() == 0 ? null : line.toByteArray();
                }
                if (octet == '\n') {
                    byte[] value = line.toByteArray();
                    if (value.length > 0 && value[value.length - 1] == '\r') {
                        return Arrays.copyOf(value, value.length - 1);
                    }
                    return value;
                }
                line.write(octet);
            }
            throw new IOException("SMTP line exceeds test safety limit");
        }

        private static void reply(OutputStream output, String value) throws IOException {
            output.write((value + "\r\n").getBytes(StandardCharsets.US_ASCII));
            output.flush();
        }
    }
}
