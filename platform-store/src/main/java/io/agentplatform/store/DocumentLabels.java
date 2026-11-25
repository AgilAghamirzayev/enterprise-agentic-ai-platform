package io.agentplatform.store;

import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.UUID;

record DocumentLabels(String sourceUri,String classification) {
    static DocumentLabels from(String source,String classification,List<String> roles) {
        if(roles==null) throw new StoreException(400,"Missing document roles");
        if(source!=null) {
            Database.requireText(source,"source URI",1024);
            URI uri;
            try {uri=URI.create(source);}catch(IllegalArgumentException error){throw new StoreException(400,"Invalid document source URI");}
            if(uri.getScheme()==null || !Set.of("https","internal","s3").contains(uri.getScheme()) || uri.getHost()==null
                    ||uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null) throw new StoreException(400,"Invalid document source URI");
        }
        String level=classification==null?(roles.isEmpty()?"INTERNAL":"CONFIDENTIAL"):classification;
        if(!Set.of("INTERNAL","PUBLIC","CONFIDENTIAL","RESTRICTED").contains(level) ||
               Set.of("CONFIDENTIAL","RESTRICTED").contains(level)&&roles.isEmpty()) throw new StoreException(400,"Protected documents require an access role");
        return new DocumentLabels(source,level);
    }

    void insert(Database db,String tenant,UUID id){
        db.jdbc.update("INSERT INTO document_labels(tenant,id,source_uri,classification) VALUES (?,?,?,?)",tenant,id,sourceUri,classification);
    }
}
