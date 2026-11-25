package io.agentplatform.server;

import io.agentplatform.store.PlatformStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
final class RetentionMaintenance {
    private static final Logger LOG=LoggerFactory.getLogger(RetentionMaintenance.class);
    private final PlatformStore store;

    RetentionMaintenance(PlatformStore store){this.store=store;}

    @Scheduled(fixedDelayString="${platform.retention.interval:1m}")
    void compact(){
        for(String tenant:store.dueArchiveScopes()){
            try {store.archiveDue(tenant,"retention-worker",100);}
            catch(RuntimeException error){LOG.warn("Retention batch will retry after its lease",error);}
        }
    }
}
