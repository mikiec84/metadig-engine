package edu.ucsb.nceas.mdqengine.scheduler;

import edu.ucsb.nceas.mdqengine.MDQconfig;
import edu.ucsb.nceas.mdqengine.exception.MetadigStoreException;
import edu.ucsb.nceas.mdqengine.model.Run;
import edu.ucsb.nceas.mdqengine.store.DatabaseStore;
import edu.ucsb.nceas.mdqengine.store.MDQStore;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.dataone.client.auth.AuthTokenSession;
import org.dataone.client.rest.DefaultHttpMultipartRestClient;
import org.dataone.client.rest.MultipartRestClient;
import org.dataone.client.v2.impl.MultipartCNode;
import org.dataone.client.v2.impl.MultipartMNode;
import org.dataone.mimemultipart.SimpleMultipartEntity;
import org.dataone.service.exceptions.NotAuthorized;
import org.dataone.service.types.v1.*;
import org.dataone.service.types.v2.SystemMetadata;
import org.dataone.service.util.TypeMarshaller;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.quartz.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Date;

/**
 * <p>
 * Run a MetaDIG Quality Engine Scheduler task, for example,
 * query a member node for new pids and request that a quality
 * report is created for each one.
 * </p>
 *
 * @author Peter Slaughter
 */
@PersistJobDataAfterExecution
@DisallowConcurrentExecution
public class RequestReportJob implements Job {

    private Log log = LogFactory.getLog(RequestReportJob.class);

    class ListResult {
        Integer resultCount;
        ArrayList<String> result = new ArrayList<>();

        void setResult(ArrayList result) {
            this.result = result;
        }

        ArrayList getResult() {
            return this.result;
        }

        void setResultCount(Integer count) {
            this.resultCount = count;
        }

        Integer getResultCount() {
            return this.resultCount;
        }
    }

    // Since Quartz will re-instantiate a class every time it
    // gets executed, members non-static member variables can
    // not be used to maintain state!

    /**
     * <p>
     * Empty constructor for job initialization
     * </p>
     * <p>
     * Quartz requires a public empty constructor so that the
     * scheduler can instantiate the class whenever it needs.
     * </p>
     */
    public RequestReportJob() {
    }

    /**
     * <p>
     * Called by the <code>{@link org.quartz.Scheduler}</code> when a
     * <code>{@link org.quartz.Trigger}</code> fires that is associated with
     * the <code>Job</code>.
     * </p>
     *
     * @throws JobExecutionException if there is an exception while executing the job.
     */
    public void execute(JobExecutionContext context)
            throws JobExecutionException {

        String qualityServiceUrl = null;
        try {
            MDQconfig cfg = new MDQconfig();
            qualityServiceUrl = cfg.getString("quality.serviceUrl");
        } catch (ConfigurationException | IOException ce) {
            JobExecutionException jee = new JobExecutionException("Error executing task.");
            jee.initCause(ce);
            throw jee;
        }

        //Log log = LogFactory.getLog(RequestReportJob.class);
        JobKey key = context.getJobDetail().getKey();
        JobDataMap dataMap = context.getJobDetail().getJobDataMap();

        String authToken = dataMap.getString("authToken");
        String pidFilter = dataMap.getString("pidFilter");
        String suiteId = dataMap.getString("suiteId");
        String nodeId = dataMap.getString("nodeId");
        String nodeServiceUrl = dataMap.getString("nodeServiceUrl");
        String startHarvestDatetimeStr = dataMap.getString("startHarvestDatetime");
        int harvestDatetimeInc = dataMap.getInt("harvestDatetimeInc");
        String solrLocation = dataMap.getString("solrLocation");
        int countRequested = dataMap.getInt("countRequested");
        MultipartRestClient mrc = null;
        MultipartMNode mnNode = null;
        MultipartCNode cnNode = null;
        Boolean isCN = false;

        log.debug("Executing task for node: " + nodeId + ", suiteId: " + suiteId);

        try {
            mrc = new DefaultHttpMultipartRestClient();
        } catch (Exception e) {
            log.error("Error creating rest client: " + e.getMessage());
            JobExecutionException jee = new JobExecutionException(e);
            jee.setRefireImmediately(false);
            throw jee;
        }

        Subject subject = new Subject();
        subject.setValue("public");
        Session session = null;
        if(authToken == null || authToken.equals("")) {
            session = new Session();
            //session.setSubject(subject);
        } else {
            session = new AuthTokenSession(authToken);
        }

        //log.info("Created session with subject: " + session.getSubject().getValue().toString());

        // Don't know node type yet from the id, so have to manually check if it's a CN
        if(nodeId.equalsIgnoreCase("urn:node:CN")) {
            cnNode = new MultipartCNode(mrc, nodeServiceUrl, session);
            isCN = true;
        } else {
            mnNode = new MultipartMNode(mrc, nodeServiceUrl, session);
        }

        MDQStore store = null;

        try {
            store = new DatabaseStore();
        } catch (Exception e) {
            e.printStackTrace();
            throw new JobExecutionException("Cannot create store, unable to schedule job", e);
        }

        if(!store.isAvailable()) {
            try {
                store.renew();
            } catch (MetadigStoreException e) {
                e.printStackTrace();
                throw new JobExecutionException("Cannot renew store, unable to schedule job", e);
            }
        }

        // Set UTC as the default time zone for all DateTime operations.
        // Get current datetime, which may be used for start time range.
        DateTimeZone.setDefault(DateTimeZone.UTC);
        DateTime currentDT = new DateTime(DateTimeZone.UTC);
        DateTimeFormatter dtfOut = DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss.SS'Z'");
        String currentDatetimeStr = dtfOut.print(currentDT);

        DateTime startDateTimeRange = null;
        DateTime endDateTimeRange = null;

        String lastHarvestDateStr = null;
        edu.ucsb.nceas.mdqengine.model.Node node;
        node = store.getNode(nodeId);

        // If a 'node' entry has not beeen saved for this nodeId yet, then a 'lastHarvested'
        // DataTime will not be available, in which case the 'startHarvestDataTime' from the
        // config file will be used.
        if(node.getNodeId() == null) {
            node = new edu.ucsb.nceas.mdqengine.model.Node();
            node.setNodeId(nodeId);
            lastHarvestDateStr = startHarvestDatetimeStr;
            node.setLastHarvestDatetime(lastHarvestDateStr);
            if(!solrLocation.equalsIgnoreCase("")) {
                node.setSolrLocation(solrLocation);
            } else {
                node.setSolrLocation("");
            }
        } else {
            lastHarvestDateStr = node.getLastHarvestDatetime();
            // Set the solr location for the node specified in the taskList, unless it was
            // left blank. Note that this is the only route to enter the solr location for a node
            // into the Database store.
            // First check the solr location from the task list
            if (!solrLocation.equalsIgnoreCase("")) {
                node.setSolrLocation(solrLocation);
            } else {
                node.setSolrLocation("");
            }
        }

        DateTime lastHarvestDate = new DateTime(lastHarvestDateStr);
        // Set the search start datetime to the last harvest datetime, unless it is in the
        // future. (This can happen when the previous time range end was for the current day,
        // as the end datetime range for the previous task run will have been stored as the
        // new lastharvestDateTime.
        DateTime startDTR = null;
        if(lastHarvestDate.isAfter(currentDT.toInstant())) {
            startDTR = currentDT;
        } else {
            startDTR = new DateTime(lastHarvestDate);
        }

        DateTime endDTR = new DateTime(startDTR);
        endDTR = endDTR.plusDays(harvestDatetimeInc);
        if(endDTR.isAfter(currentDT.toInstant())) {
            endDTR = currentDT;
        }

        // If the start and end harvest dates are the same (happends for a new node), then
        // tweek the start so that DataONE listObjects doesn't complain.
        if(startDTR == endDTR ) {
            startDTR = startDTR.minusMinutes(1);
        }

        String startDTRstr = dtfOut.print(startDTR);
        String endDTRstr = dtfOut.print(endDTR);

        Integer startCount = new Integer(0);
        ListResult result = null;
        Integer resultCount = null;

        boolean morePids = true;
        while(morePids) {
            ArrayList<String> pidsToProcess = null;
            log.debug("Getting list of pids to process.");
            log.debug("    harvest start time: " + startDTRstr);
            log.debug("    harvest end time: " + endDTRstr);

            log.debug("startCount: " + startCount);
            log.debug("countRequested: " + countRequested);

            try {
                result = getPidsToProcess(cnNode, mnNode, isCN, session, suiteId, nodeId, pidFilter, startDTRstr, endDTRstr, store, startCount, countRequested);
                pidsToProcess = result.getResult();
                resultCount = result.getResultCount();
            } catch (Exception e) {
                JobExecutionException jee = new JobExecutionException("Unable to get pids to process", e);
                jee.setRefireImmediately(false);
                throw jee;
            }

            for (String pidStr : pidsToProcess) {
                try {
                    log.info("submitting pid: " + pidStr);
                    submitReportRequest(cnNode, mnNode, isCN, session, qualityServiceUrl, pidStr, suiteId);
                } catch (Exception e) {
                    JobExecutionException jee = new JobExecutionException("Unable to submit request to create new quality reports", e);
                    jee.setRefireImmediately(false);
                    throw jee;
                }
            }

            node.setLastHarvestDatetime(endDTRstr);
            log.debug("nodeid: " + node.getNodeId());
            log.debug("lastharvestdate: " + node.getLastHarvestDatetime());
            log.debug("solrLocation: " + node.getSolrLocation());

            try {
                store.saveNode(node);
            } catch (MetadigStoreException mse) {
                log.error("error saving node: " + node.getNodeId());
                JobExecutionException jee = new JobExecutionException("Unable to save new harvest date", mse);
                jee.setRefireImmediately(false);
                throw jee;
            }
            // Check if DataONE returned the max number of results. If so, we have to request more by paging through
            // the results.
            if(resultCount >= countRequested) {
                morePids = true;
                startCount = startCount + resultCount;
                log.info("Paging through more results, current start is " + startCount);
            } else {
                morePids = false;
            }
        }
    }

    public ListResult getPidsToProcess(MultipartCNode cnNode, MultipartMNode mnNode, Boolean isCN, Session session,
                                  String suiteId, String nodeId, String pidFilter, String startHarvestDatetimeStr,
                                  String endHarvestDatetimeStr, MDQStore store, int startCount,
                                  int countRequested) throws Exception {

        ArrayList<String> pids = new ArrayList<String>();
        InputStream qis = null;
        ObjectList objList = null;

        ObjectFormatIdentifier formatId = null;
        NodeReference nodeRef = null;
        //nodeRef.setValue(nodeId);
        Identifier identifier = null;
        Boolean replicaStatus = false;

        // Do some back-flips to convert the start and end date to the ancient Java 'Date' type that is
        // used by DataONE 'listObjects()'.
        ZonedDateTime zdt = ZonedDateTime.parse(startHarvestDatetimeStr);
        // start date milliseconds since the epoch date "midnight, January 1, 1970 UTC"
        long msSinceEpoch = zdt.toInstant().toEpochMilli();
        Date startDate = new Date(msSinceEpoch);

        zdt = ZonedDateTime.parse(endHarvestDatetimeStr);
        msSinceEpoch = zdt.toInstant().toEpochMilli();
        Date endDate = new Date(msSinceEpoch);

        try {
            // Even though MultipartMNode and MultipartCNode have the same parent class, their interfaces are differnt, so polymorphism
            // isn't happening here.
            if(isCN) {
                objList = cnNode.listObjects(session=session, startDate, endDate, formatId, nodeRef, identifier, startCount, countRequested);
            } else {
                objList = mnNode.listObjects(session=session, startDate, endDate, formatId, identifier, replicaStatus, startCount, countRequested);
            }
            //log.info("Got " + objList.getCount() + " pids for format: " + formatId.getValue() + " pids.");
        } catch (Exception e) {
            log.error("Error retrieving pids: " + e.getMessage());
            throw e;
        }

        String thisFormatId = null;
        String thisPid = null;

        if (objList.getCount() > 0) {
            for(ObjectInfo oi: objList.getObjectInfoList()) {
                thisFormatId = oi.getFormatId().getValue();
                thisPid = oi.getIdentifier().getValue();

                // Check all pid filters. There could be multiple wildcard filters, which are separated
                // by ','.
                String [] filters = pidFilter.split("\\|");
                Boolean found = false;
                for(String thisFilter:filters) {
                    if(thisFormatId.matches(thisFilter)) {
                        found = true;
                        continue;
                    }
                }

                // Always re-create a report, even if it exists for a pid, as the sysmeta could have
                // been updated (i.e. obsoletedBy, access) and the quality report and index contain
                // sysmeta fields.
                if(found) {
                //    if (!runExists(thisPid, suiteId, store)) {
                    pids.add(thisPid);
                    log.info("adding pid " + thisPid + ", formatId: " + thisFormatId);
                //    }
                }
            }
        }

        ListResult result = new ListResult();
        result.setResultCount(objList.getCount());
        result.setResult(pids);

        return result;
    }

    public boolean runExists(String pid, String suiteId, MDQStore store) throws MetadigStoreException {

        boolean found = false;

        if(!store.isAvailable()) {
            try {
                store.renew();
            } catch (MetadigStoreException e) {
                e.printStackTrace();
                throw e;
            }
        }

        Run run = store.getRun(pid, suiteId);
        if(run != null) {
            found = true;
        } else {
            found = false;
        }

        return found;
    }

    public void submitReportRequest(MultipartCNode cnNode, MultipartMNode mnNode, Boolean isCN,  Session session, String qualityServiceUrl, String pidStr, String suiteId) throws Exception {

        SystemMetadata sysmeta = null;
        InputStream objectIS = null;
        InputStream runResultIS = null;

        Identifier pid = new Identifier();
        pid.setValue(pidStr);

        try {
            if (isCN) {
                sysmeta = cnNode.getSystemMetadata(session, pid);
            } else {
                sysmeta = mnNode.getSystemMetadata(session, pid);
            }
        } catch (NotAuthorized na) {
            log.error("Not authorized to read sysmeta for pid: " + pid.getValue() + ", continuing with next pid...");
            return;
        } catch (Exception e) {
            throw(e);
        }

        try {
            if(isCN) {
                objectIS = cnNode.get(session, pid);
            } else  {
                objectIS = mnNode.get(session, pid);
            }
            log.debug("Retrieved metadata object for pid: " + pidStr);
        } catch (NotAuthorized na) {
            log.error("Not authorized to read pid: " + pid + ", continuing with next pid...");
            return;
        } catch (Exception e) {
            throw(e);
        }

        // quality suite service url, i.e. "http://docke-ucsb-1.dataone.org:30433/quality/suites/knb.suite.1/run
        qualityServiceUrl = qualityServiceUrl + "/suites/" + suiteId + "/run";
        HttpPost post = new HttpPost(qualityServiceUrl);

        try {
            // add document
            SimpleMultipartEntity entity = new SimpleMultipartEntity();
            entity.addFilePart("document", objectIS);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            TypeMarshaller.marshalTypeToOutputStream(sysmeta, baos);
            entity.addFilePart("systemMetadata", new ByteArrayInputStream(baos.toByteArray()));

            // make sure we get XML back
            post.addHeader("Accept", "application/xml");

            // send to service
            log.trace("submitting: " + qualityServiceUrl);
            post.setEntity(entity);
            CloseableHttpClient client = HttpClients.createDefault();
            CloseableHttpResponse response = client.execute(post);

            // retrieve results
            HttpEntity reponseEntity = response.getEntity();
            if (reponseEntity != null) {
                runResultIS = reponseEntity.getContent();
            }
        } catch (Exception e) {
            throw(e);
        }
    }
}
