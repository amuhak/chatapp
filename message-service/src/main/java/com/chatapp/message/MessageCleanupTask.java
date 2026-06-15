package com.chatapp.message;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.logging.Logger;

@ApplicationScoped
public class MessageCleanupTask {

    private static final Logger LOGGER = Logger.getLogger(MessageCleanupTask.class.getName());
    private static final long SEVEN_DAYS_IN_MS = 7L * 24 * 60 * 60 * 1000;

    @Scheduled(every = "1h")
    @Transactional
    public void cleanupOldMessages() {
        long cutoff = System.currentTimeMillis() - SEVEN_DAYS_IN_MS;
        LOGGER.info("Starting expired messages cleanup. Cutoff: " + cutoff);

        // Delete all message records older than 7 days
        long deletedMessages = Message.delete("timestamp < ?1", cutoff);
        LOGGER.info("Deleted " + deletedMessages + " expired message records.");

        if (deletedMessages > 0) {
            // Delete parent MessageData payloads that no longer have any Message pointing to them
            long deletedData = MessageData.delete("id not in (select m.messageData.id from Message m)");
            LOGGER.info("Deleted " + deletedData + " orphaned message data payloads.");
        }
    }
}
