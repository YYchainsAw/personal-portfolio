package xyz.yychainsaw.portfolio.message.email;

@FunctionalInterface
public interface EmailSenderPort {
    void send(ContactNotification notification);
}
